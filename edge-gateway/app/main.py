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

        def drive(code: str, open_: bool) -> None:
            with self._bus_lock:
                self.backend.set_valve(code, open_)

        self.controller.attach_driver(drive)

        self.mqtt = MqttClient(cfg.gatewaySn, cfg.mqtt, self.outbox, cfg.outboxBatch)
        self.mqtt.on_command = self._on_command
        self.mqtt.on_config = self._on_config

    # ---------------------------------------------------------------- #
    def _on_command(self, msg: Dict[str, Any]) -> None:
        try:
            with self._bus_lock:
                replies = self.controller.handle_command(msg)
            for m in replies:
                kind = "valveStatus" if m.get("type") == "valveStatus" else "events"
                self.mqtt.publish_nowait(kind, m)
        except Exception:
            log.exception("处理阀控命令失败")

    def _on_config(self, msg: Dict[str, Any]) -> None:
        self.controller.apply_config(msg)
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

        # 本地联锁（最高优先级，离线也生效）
        events_out = self.controller.evaluate(frame)
        for m in events_out:
            kind = "valveStatus" if m.get("type") == "valveStatus" else "events"
            self.mqtt.publish_nowait(kind, m)

        # 遥测：soil / weather / flow（阀位以 valve/status 为准）
        records = []
        ts = int(time.time() * 1000)
        for d in self.cfg.devices:
            reading = frame.get(d.code)
            if not reading or reading["kind"] == "valve":
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
