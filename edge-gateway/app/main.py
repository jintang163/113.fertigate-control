"""边缘网关主程序。

采集线程（10s 周期）：
  poll 总线 → 模拟器水文推进(仅 simulate) → 本地联锁评估 → 遥测/状态/事件入 outbox
MQTT 线程：
  接收 valve/command、config；出站由独立 flush 线程保证 PUBACK 后删除缓存
"""
from __future__ import annotations

import argparse
import logging
import signal
import threading
import time
from typing import Any, Dict, List

from .config import GatewayConfig, load_config
from .messages import health, telemetry
from .modbus_io import ModbusBackend
from .mqtt_client import MqttClient
from .outbox import Outbox
from .pump_controller import PumpController
from .simulator import FieldSimulator
from .valve_controller import ValveController

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("gateway")


class Gateway:
    def __init__(self, cfg: GatewayConfig):
        self.cfg = cfg
        self.outbox = Outbox(cfg.sqlitePath)
        self._stop = threading.Event()
        self._started = time.time()
        # 可重入：MQTT 命令线程持锁执行 handle_command 时，内部驱动回调会再次取锁
        self._bus_lock = threading.RLock()

        mode = cfg.mode
        if mode == "simulate":
            self.sim = FieldSimulator(
                [d.__dict__ for d in cfg.devices], cfg.simulate)
            self.backend = self.sim
        else:
            self.sim = None
            self.backend = ModbusBackend(cfg.devices, cfg.modbus)
            if not self.backend.connect():
                raise RuntimeError(f"无法打开串口 {cfg.modbus.get('serialPort')}")

        self.controller = ValveController(cfg)
        self.pump_controller = PumpController(cfg)

        def drive_valve(code: str, open_: bool) -> None:
            with self._bus_lock:
                self.backend.set_valve(code, open_)

        def drive_pump(code: str, opening: int) -> None:
            with self._bus_lock:
                self.backend.set_pump(code, opening)

        self.controller.attach_driver(drive_valve)
        self.pump_controller.attach_driver(drive_pump)

        self.mqtt = MqttClient(cfg.gatewaySn, cfg.mqtt, self.outbox, cfg.outboxBatch)
        self.mqtt.on_command = self._on_command
        self.mqtt.on_device_command = self._on_device_command
        self.mqtt.on_config = self._on_config
        # 通信中断联锁计时
        self._comm_ok_since = time.time()
        self._comm_ever_connected = False
        self._comm_tripped = False

    # ---------------------------------------------------------------- #
    def _publish_replies(self, replies) -> None:
        for m in replies:
            t = m.get("type")
            kind = "valveStatus" if t == "valveStatus" else \
                   "deviceStatus" if t == "deviceStatus" else "events"
            self.mqtt.publish_nowait(kind, m)

    def _on_command(self, msg: Dict[str, Any]) -> None:
        try:
            with self._bus_lock:
                replies = self.controller.handle_command(msg)
            self._publish_replies(replies)
        except Exception:
            log.exception("处理阀控命令失败")

    def _on_device_command(self, msg: Dict[str, Any]) -> None:
        try:
            with self._bus_lock:
                replies = self.pump_controller.handle_command(msg)
            self._publish_replies(replies)
        except Exception:
            log.exception("处理设备命令失败")

    def _on_config(self, msg: Dict[str, Any]) -> None:
        self.controller.apply_config(msg)
        self.pump_controller.apply_config(msg)
        if msg.get("pollIntervalSec"):
            self.cfg.pollIntervalSec = float(msg["pollIntervalSec"])

    # ---------------------------------------------------------------- #
    def _poll_once(self, dt_sec: float) -> None:
        with self._bus_lock:
            if self.sim is not None:
                # 仿真时间倍速（无硬件演示加速蒸散/灌溉响应）
                scale = float(self.cfg.simulate.get("timeScale", 1))
                self.sim.step(dt_sec * scale)
            frame = self.backend.poll()

        # 本地联锁（最高优先级，离线也生效）：阀门 + 施肥泵
        events_out = self.controller.evaluate(frame)
        # 阀门侧 CRITICAL 联锁（缺水/超湿/EC/pH…）联动全停注肥泵
        if any(m.get("type") == "event" and m.get("level") == "CRITICAL"
               and str(m.get("code", "")).startswith("INTERLOCK_")
               for m in events_out):
            events_out += self.pump_controller.safety_stop_all(
                next((m["code"] for m in events_out
                      if m.get("type") == "event" and m.get("level") == "CRITICAL"),
                     "INTERLOCK_VALVE"))
        events_out += self.pump_controller.evaluate(frame)
        self._publish_replies(events_out)

        # 与云端通信中断超限 → 紧急关阀停泵（本地安全策略）
        self._check_comm_lost()

        # 遥测：soil / weather / flow / pressure（阀位以 valve/status 为准，泵以 device/status 为准）
        records = []
        ts = int(time.time() * 1000)
        for d in self.cfg.devices:
            reading = frame.get(d.code)
            if not reading or reading["kind"] in ("valve", "pump"):
                continue
            records.append({
                "deviceCode": d.code,
                "kind": reading["kind"],
                "ts": ts,
                "values": reading["values"],
                "quality": reading.get("quality", "GOOD"),
            })
        if records:
            self.mqtt.publish_nowait("telemetry",
                                     telemetry(self.cfg.gatewaySn, records, ts))

    def _check_comm_lost(self) -> None:
        limit = float(self.cfg.interlocks.commLostSec)
        if self.mqtt.connected.is_set():
            self._comm_ok_since = time.time()
            self._comm_ever_connected = True
            self._comm_tripped = False
            return
        if not self._comm_ever_connected:
            return  # 启动后从未连上，不判中断（本地联锁仍生效）
        offline_for = time.time() - self._comm_ok_since
        if not self._comm_tripped and offline_for >= limit:
            log.error("与云端通信中断 %.0fs ≥ %.0fs，紧急关阀停泵", offline_for, limit)
            replies = self.controller.emergency_close_all("INTERLOCK_COMM_LOST")
            replies += self.pump_controller.safety_stop_all("INTERLOCK_COMM_LOST")
            self._publish_replies(replies)
            self._comm_tripped = True

    def _health_loop(self) -> None:
        while not self._stop.is_set():
            self.mqtt.publish_nowait("health", health(
                self.cfg.gatewaySn,
                int(time.time() - self._started), self.outbox.depth()))
            self._stop.wait(self.cfg.healthIntervalSec)

    def run(self) -> None:
        log.info("网关 %s 启动（mode=%s, 设备 %d 个, outbox=%d）",
                 self.cfg.gatewaySn, self.cfg.mode, len(self.cfg.devices),
                 self.outbox.depth())
        self.mqtt.start()
        threading.Thread(target=self._health_loop, daemon=True,
                         name="health").start()

        last = time.time()
        while not self._stop.is_set():
            now = time.time()
            try:
                self._poll_once(now - last)
            except Exception:
                log.exception("采集周期异常")
            last = now
            self._stop.wait(self.cfg.pollIntervalSec)

    def stop(self, *_: Any) -> None:
        log.info("网关停止中…")
        self._stop.set()
        try:
            self.mqtt.stop()
        finally:
            self.outbox.close()
            if isinstance(self.backend, ModbusBackend):
                self.backend.close()


def main() -> None:
    ap = argparse.ArgumentParser(description="水肥一体化边缘网关")
    ap.add_argument("-c", "--config",
                    default="config/gateway.example.yaml", help="配置文件路径")
    ap.add_argument("--simulate", action="store_true",
                    help="强制使用内置田间仿真器（无硬件调试）")
    args = ap.parse_args()

    cfg = load_config(args.config)
    if args.simulate:
        cfg.mode = "simulate"
    gw = Gateway(cfg)
    signal.signal(signal.SIGINT, gw.stop)
    signal.signal(signal.SIGTERM, gw.stop)
    gw.run()


if __name__ == "__main__":
    main()
