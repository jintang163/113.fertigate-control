"""阀控器 + 本地安全联锁（边缘侧最高优先级，独立于云端）。

职责：
- 幂等执行云端 valve/command（commandId 去重，重复命令重放当前状态）
- OPEN 前安全前置；OPEN 期间每个采集周期检查停止条件并自动关阀
- 传感器失联 / 湿度硬上限 / EC / pH / 达量 / 超时 联锁
- 重启后默认所有阀 CLOSED，不记忆“开”

联锁实现不依赖网络：即便与云端长时间失联，OPEN 命令自带的
plannedVolume/maxDurationSec/hardMaxMoisture 也能保证自行关断。
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from .config import GatewayConfig, InterlockConfig
from .messages import event, new_command_id, now_ms, valve_status

log = logging.getLogger("valve-ctl")


@dataclass
class ValveRun:
    command_id: str
    job_id: Optional[str]
    planned_volume: float          # m³
    max_duration_sec: float
    hard_max_moisture: float
    sensor_code: str
    started_at: float
    flow_total_start: float = 0.0
    applied_volume: float = 0.0
    closing: bool = False


@dataclass
class ValveRuntime:
    code: str
    is_open: bool = False
    run: Optional[ValveRun] = None
    last_command_id: Optional[str] = None
    # 最近一次拒绝执行的命令，重放时给出同样结果
    rejected: Dict[str, str] = field(default_factory=dict)


class ValveController:
    def __init__(self, cfg: GatewayConfig):
        self.cfg = cfg
        self.interlocks = cfg.interlocks
        self._valves: Dict[str, ValveRuntime] = {
            d.code: ValveRuntime(code=d.code) for d in cfg.by_kind("valve")
        }
        # valve → 取数传感器 / 流量计 / 压力传感器
        self._sensor_of: Dict[str, str] = {}
        self._flow_of: Dict[str, str] = {}
        self._pressure_of: Dict[str, str] = {}
        for d in cfg.devices:
            if d.kind == "valve" and d.sensorCode:
                self._sensor_of[d.code] = d.sensorCode
            if d.kind == "flow" and d.linkedValve:
                self._flow_of[d.linkedValve] = d.code
            if d.kind == "pressure" and d.linkedValve:
                self._pressure_of[d.linkedValve] = d.code
        # 传感器最近有效数据时间
        self._last_seen: Dict[str, float] = {}
        self._driver: Any = None
        self._last_frame: Dict[str, Dict[str, Any]] = {}
        self._ec_high_since: Dict[str, float] = {}
        # 缺水联锁去抖：首次低于阈值的时间
        self._pressure_low_since: Dict[str, float] = {}
        self._flow_low_since: Dict[str, float] = {}

    def attach_driver(self, driver: Any) -> None:
        """挂载阀门驱动回调 driver(valve_code, open: bool)。

        网关主程序在此回调内加总线锁，避免采集线程与 MQTT 线程抢占 RS485。
        启动即确保所有阀门关闭（继电器常闭，重启不记忆“开”）。
        """
        self._driver = driver
        for code in self._valves:
            try:
                driver(code, False)
            except Exception as e:
                log.error("初始化关阀 %s 失败: %s", code, e)

    # ------------------------------------------------------------------ #
    # 云端命令处理（MQTT 线程调用，需快速返回）
    # ------------------------------------------------------------------ #
    def handle_command(self, msg: Dict[str, Any]) -> List[Dict[str, Any]]:
        """返回需要上报的消息列表（valveStatus / event）。"""
        out: List[Dict[str, Any]] = []
        code = msg.get("valveCode")
        rt = self._valves.get(code)
        if rt is None:
            log.warning("收到未知阀门命令: %s", code)
            return out
        cid = msg.get("commandId") or new_command_id()

        # 幂等：重复 commandId 不重复执行，重放当前状态
        if cid and cid == rt.last_command_id:
            log.info("重复命令 %s 忽略，重放阀态 %s", cid, rt.is_open)
            return out
        rt.last_command_id = cid

        action = (msg.get("command") or "").upper()
        if action == "CLOSE":
            reason = msg.get("stopReason") or "REMOTE_CLOSE"
            self._close(rt, reason=reason, command_id=cid,
                        job_id=msg.get("jobId"), out=out)
            return out

        if action != "OPEN":
            log.warning("未知命令动作: %s", action)
            return out

        # --- OPEN 安全前置 ---
        sensor_code = msg.get("sensorCode") or self._sensor_of.get(code)
        block = self._open_blocked(code, sensor_code)
        if block:
            block_code, block_msg = block
            rt.rejected[cid] = block_code
            out.append(event(self.cfg.gatewaySn, "CRITICAL", "COMMAND_REJECTED",
                             f"开阀命令被本地联锁拒绝: {block_msg}",
                             valve_code=code,
                             context={"commandId": cid, "interlock": block_code}))
            out.append(self._status(rt, "CLOSED", cid, msg.get("jobId"),
                                    stop_reason=block_code))
            log.warning("OPEN %s 被拒: %s", code, block_code)
            return out

        run = ValveRun(
            command_id=cid, job_id=msg.get("jobId"),
            planned_volume=float(msg.get("plannedVolume") or 0.0),
            max_duration_sec=(float(msg["maxDurationSec"])
                              if msg.get("maxDurationSec") is not None else 900.0),
            hard_max_moisture=float(
                msg.get("hardMaxMoisture")
                if msg.get("hardMaxMoisture") is not None
                else self.interlocks.moistureHardMax),
            sensor_code=sensor_code or "",
            started_at=time.time(),
        )
        # 流量基线在开阀瞬间锁定（累计式流量计），后续实灌 = 当前累计 − 基线
        flow_code = self._flow_of.get(code)
        fv = (self._last_frame.get(flow_code, {}) if flow_code else {}) \
            .get("values") or {}
        run.flow_total_start = float(fv.get("totalFlow", 0.0))
        rt.run = run
        self._drive(rt, True)
        out.append(self._status(rt, "OPEN", cid, run.job_id))
        log.info("阀门 %s OPEN job=%s 计划=%sm3 最长=%ss",
                 code, run.job_id, run.planned_volume, run.max_duration_sec)
        return out

    # ------------------------------------------------------------------ #
    # 每采集周期联锁评估（采集主线程调用）
    # ------------------------------------------------------------------ #
    def evaluate(self, frame: Dict[str, Dict[str, Any]]) -> List[Dict[str, Any]]:
        out: List[Dict[str, Any]] = []
        now = time.time()
        self._last_frame = frame

        # 刷新传感器存活时间
        for code, reading in frame.items():
            if reading.get("quality") == "GOOD" and reading.get("values"):
                self._last_seen[code] = now

        for code, rt in self._valves.items():
            if not rt.is_open or rt.run is None:
                continue
            run = rt.run
            reading = frame.get(run.sensor_code) if run.sensor_code else None
            values = (reading or {}).get("values") or {}
            quality = (reading or {}).get("quality")

            # 1) 湿度硬上限
            moist = values.get("soilMoist")
            if moist is not None and moist >= run.hard_max_moisture:
                self._interlock_close(
                    rt, "INTERLOCK_MOISTURE_HARD_MAX",
                    f"土壤湿度 {moist}% 超过硬上限 {run.hard_max_moisture}%，阀门已强制关闭",
                    {"moisture": moist, "hardMax": run.hard_max_moisture}, out)
                continue

            # 2) 传感器失联
            last = self._last_seen.get(run.sensor_code)
            if quality == "SENSOR_FAULT" or not last or \
                    now - last > self.interlocks.sensorLostSec:
                self._interlock_close(
                    rt, "INTERLOCK_SENSOR_LOST",
                    f"联锁传感器 {run.sensor_code} 超过 "
                    f"{self.interlocks.sensorLostSec:.0f}s 无有效数据，阀门已关闭",
                    {"sensorCode": run.sensor_code}, out)
                continue

            # 3) 累计流量达量（基线在 OPEN 时锁定）
            flow_code = self._flow_of.get(code)
            if flow_code and flow_code in frame:
                fv = frame[flow_code]["values"]
                run.applied_volume = max(
                    0.0, float(fv.get("totalFlow", 0.0)) - run.flow_total_start)
            if run.planned_volume > 0 and run.applied_volume >= run.planned_volume - 1e-6:
                self._close(rt, "VOLUME_REACHED",
                            command_id=run.command_id, job_id=run.job_id, out=out)
                continue

            # 4) 最大时长
            elapsed = now - run.started_at
            if elapsed >= run.max_duration_sec:
                self._close(rt, "DURATION_LIMIT",
                            command_id=run.command_id, job_id=run.job_id, out=out)
                continue

            # 4b) 主管道水压低（缺水保护，持续 waterLostDelaySec 去抖）
            pressure = self._pressure_of_valve(code, frame)
            if pressure is not None and pressure < self.interlocks.pressureMinKpa:
                since = self._pressure_low_since.get(code, now)
                self._pressure_low_since[code] = since
                if now - since >= self.interlocks.waterLostDelaySec:
                    self._interlock_close(
                        rt, "INTERLOCK_WATER_LOST",
                        f"主管道水压 {pressure}kPa 持续低于 "
                        f"{self.interlocks.pressureMinKpa}kPa（缺水），阀门已关闭",
                        {"pressure": pressure, "thresholdKpa": self.interlocks.pressureMinKpa}, out)
                    continue
            else:
                self._pressure_low_since.pop(code, None)

            # 4c) 阀开但瞬时流量过低（爆管/堵塞/缺水，启动 30s 宽限 + 延时去抖）
            instant = None
            if flow_code and flow_code in frame:
                instant = frame[flow_code]["values"].get("instantFlow")
            if instant is not None and elapsed > 30 \
                    and instant < self.interlocks.flowMinM3h:
                since = self._flow_low_since.get(code, now)
                self._flow_low_since[code] = since
                if now - since >= self.interlocks.waterLostDelaySec:
                    self._interlock_close(
                        rt, "INTERLOCK_FLOW_LOW",
                        f"阀开但瞬时流量 {instant}m³/h 持续低于 "
                        f"{self.interlocks.flowMinM3h}m³/h（缺水/爆管），阀门已关闭",
                        {"instantFlow": instant, "thresholdM3h": self.interlocks.flowMinM3h}, out)
                    continue
            else:
                self._flow_low_since.pop(code, None)

            # 5) EC / pH 肥害联锁（持续 2 个周期超限才关，防抖动）
            ec, ph = values.get("ec"), values.get("ph")
            if ec is not None and ec > self.interlocks.ecHigh:
                since = self._ec_high_since.get(code, now)
                self._ec_high_since[code] = since
                if now - since >= 2 * self.cfg.pollIntervalSec:
                    self._interlock_close(
                        rt, "INTERLOCK_EC_HIGH",
                        f"EC {ec} mS/cm 持续高于 {self.interlocks.ecHigh}，防盐害关阀停肥",
                        {"ec": ec, "ecHigh": self.interlocks.ecHigh}, out)
                    continue
            else:
                self._ec_high_since.pop(code, None)

            if ph is not None and not (self.interlocks.phLow <= ph <= self.interlocks.phHigh):
                code_ev = "INTERLOCK_PH_LOW" if ph < self.interlocks.phLow else "INTERLOCK_PH_HIGH"
                self._interlock_close(
                    rt, code_ev,
                    f"pH {ph} 超出窗口 [{self.interlocks.phLow}, {self.interlocks.phHigh}]，关阀",
                    {"ph": ph}, out)
                continue

        return out

    # ------------------------------------------------------------------ #
    # 通信中断紧急关阀（主程序在与云端连接持续丢失时调用）
    # ------------------------------------------------------------------ #
    def emergency_close_all(self, reason: str = "INTERLOCK_COMM_LOST") -> List[Dict[str, Any]]:
        out: List[Dict[str, Any]] = []
        for code, rt in self._valves.items():
            if rt.is_open and rt.run is not None:
                out.append(event(
                    self.cfg.gatewaySn, "CRITICAL", reason,
                    f"与云端通信中断超限，阀门 {code} 紧急关闭（本地安全策略）",
                    valve_code=code, context={"jobId": rt.run.job_id}))
                self._close(rt, reason, command_id=rt.run.command_id,
                            job_id=rt.run.job_id, out=out)
        return out

    # ------------------------------------------------------------------ #
    # 内部
    # ------------------------------------------------------------------ #
    def _pressure_of_valve(self, valve_code: str,
                           frame: Dict[str, Dict[str, Any]]) -> Optional[float]:
        code = self._pressure_of.get(valve_code)
        if not code:
            return None
        v = (frame.get(code, {}).get("values") or {}).get("pressure")
        try:
            return float(v) if v is not None else None
        except (TypeError, ValueError):
            return None

    def _open_blocked(self, valve_code: str, sensor_code: Optional[str]) \
            -> Optional[tuple[str, str]]:
        last = self._last_seen.get(sensor_code or "")
        if not sensor_code or not last or \
                time.time() - last > self.interlocks.sensorLostSec:
            return ("INTERLOCK_SENSOR_LOST",
                    f"传感器 {sensor_code} 无有效数据")
        return None

    def _interlock_close(self, rt: ValveRuntime, ev_code: str, msg: str,
                         ctx: Dict[str, Any], out: List[Dict[str, Any]]) -> None:
        out.append(event(self.cfg.gatewaySn, "CRITICAL", ev_code, msg,
                         valve_code=rt.code, context=ctx))
        self._close(rt, ev_code, command_id=rt.run.command_id if rt.run else None,
                    job_id=rt.run.job_id if rt.run else None, out=out)

    def _close(self, rt: ValveRuntime, reason: str, command_id: Optional[str],
               job_id: Optional[str], out: List[Dict[str, Any]]) -> None:
        if not rt.is_open:
            # 已关：仍回报当前状态，便于云端结算
            out.append(self._status(rt, "CLOSED", command_id, job_id, stop_reason=reason))
            rt.run = None
            return
        self._drive(rt, False)
        out.append(self._status(rt, "CLOSED", command_id, job_id, stop_reason=reason))
        log.info("阀门 %s 关闭 reason=%s 实灌=%.3fm3",
                 rt.code, reason, rt.run.applied_volume if rt.run else 0.0)
        rt.run = None

    def _drive(self, rt: ValveRuntime, open_: bool) -> None:
        if self._driver is None:
            raise RuntimeError("阀门驱动未挂载")
        self._driver(rt.code, open_)
        rt.is_open = open_

    def _status(self, rt: ValveRuntime, state: str, command_id: Optional[str],
                job_id: Optional[str], stop_reason: Optional[str] = None) -> Dict[str, Any]:
        flow_code = self._flow_of.get(rt.code)
        instant = total = 0.0
        fv = (self._last_frame.get(flow_code, {}) if flow_code else {}) \
            .get("values") or {}
        instant = float(fv.get("instantFlow", 0.0))
        total = float(fv.get("totalFlow", 0.0))
        applied = rt.run.applied_volume if rt.run else 0.0
        return valve_status(
            self.cfg.gatewaySn, rt.code, state, job_id, command_id,
            instant_flow=instant, applied_volume=applied, total_flow=total,
            stop_reason=stop_reason)

    # ------------------------------------------------------------------ #
    # 云端 config 下发
    # ------------------------------------------------------------------ #
    def apply_config(self, msg: Dict[str, Any]) -> None:
        il = msg.get("interlocks") or {}
        for k, v in il.items():
            if k in InterlockConfig.__dataclass_fields__ and v is not None:
                setattr(self.interlocks, k, float(v))
        log.info("联锁参数已更新: %s", self.interlocks)
