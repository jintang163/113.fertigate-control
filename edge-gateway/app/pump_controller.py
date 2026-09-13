"""施肥泵（比例注肥泵）控制器 + 泵侧安全联锁（边缘侧）。

职责：
- 幂等执行云端 device/command（START / STOP / SET_OPENING，commandId 去重）
- 开度 0-100 驱动（Modbus AO 或仿真器），回报 device/status
- 运行中电机过载（motorCurrent > pumpOverloadA）立即停泵并上报告警
- 安全停车接口 safety_stop_all：缺水/通信中断/阀门联锁时由主程序调用
- 重启后默认停泵（不记忆“运行”）
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

from .config import GatewayConfig
from .messages import device_status, event, new_command_id

log = logging.getLogger("pump-ctl")


@dataclass
class PumpRun:
    command_id: str
    job_id: Optional[str]
    opening: int                  # 0-100
    inject_ratio_pct: float       # 注肥比例 %（云端下发，台账参考）
    started_at: float
    fertilizer_l: float = 0.0     # 累计注肥液量 L


@dataclass
class PumpRuntime:
    code: str
    running: bool = False
    opening: int = 0
    run: Optional[PumpRun] = None
    last_command_id: Optional[str] = None
    rejected: Dict[str, str] = field(default_factory=dict)
    last_status_at: float = 0.0


class PumpController:
    def __init__(self, cfg: GatewayConfig):
        self.cfg = cfg
        self.interlocks = cfg.interlocks
        self._pumps: Dict[str, PumpRuntime] = {
            d.code: PumpRuntime(code=d.code) for d in cfg.by_kind("pump")
        }
        # pump → 主管道压力传感器（缺水判定）
        self._pressure_of: Dict[str, str] = {}
        self._capacity_lph: Dict[str, float] = {}
        for d in cfg.devices:
            if d.kind == "pump":
                self._pressure_of[d.code] = d.linkedPressure or ""
                self._capacity_lph[d.code] = float(d.sim.get("capacityLph", 120.0))
        self._driver: Optional[Callable[[str, int], None]] = None
        self._last_frame: Dict[str, Dict[str, Any]] = {}
        self._last_tick = time.time()

    def attach_driver(self, driver: Callable[[str, int], None]) -> None:
        """挂载驱动回调 driver(pump_code, opening 0-100)，启动即停泵。"""
        self._driver = driver
        for code in self._pumps:
            try:
                driver(code, 0)
            except Exception as e:
                log.error("初始化停泵 %s 失败: %s", code, e)

    # ------------------------------------------------------------------ #
    # 云端命令（MQTT 线程）
    # ------------------------------------------------------------------ #
    def handle_command(self, msg: Dict[str, Any]) -> List[Dict[str, Any]]:
        out: List[Dict[str, Any]] = []
        code = msg.get("deviceCode")
        rt = self._pumps.get(code)
        if rt is None:
            log.warning("收到未知施肥泵命令: %s", code)
            return out
        cid = msg.get("commandId") or new_command_id()

        if cid and cid == rt.last_command_id:
            log.info("重复泵命令 %s 忽略，重放泵态 %s", cid, rt.running)
            return out
        rt.last_command_id = cid

        action = (msg.get("action") or "").upper()
        opening = int(msg.get("opening", 100)) if action == "START" else int(msg.get("opening", 0))
        opening = max(0, min(100, opening))
        job_id = msg.get("jobId")

        if action == "STOP":
            self._stop(rt, cid, job_id, "REMOTE_STOP", out)
            return out

        if action not in ("START", "SET_OPENING"):
            log.warning("未知泵命令动作: %s", action)
            return out

        if action == "SET_OPENING" and not rt.running:
            # 未运行时的开度设置：仅记录目标开度，不启动
            rt.opening = opening
            return out

        # 安全前置：主管道压力不足禁止注肥
        block = self._blocked_by_pressure(code)
        if block:
            block_code, block_msg = block
            rt.rejected[cid] = block_code
            out.append(event(self.cfg.gatewaySn, "CRITICAL", "COMMAND_REJECTED",
                             f"注肥泵启动被联锁拒绝: {block_msg}",
                             valve_code=code,
                             context={"commandId": cid, "interlock": block_code}))
            out.append(device_status(self.cfg.gatewaySn, code, "STOPPED", cid, job_id,
                                     opening=0, overload=False))
            return out

        ratio = float(msg.get("injectRatioPct") or 0.0)
        rt.run = PumpRun(command_id=cid, job_id=job_id, opening=opening,
                         inject_ratio_pct=ratio, started_at=time.time())
        rt.running = True
        rt.opening = opening
        self._drive(rt, opening)
        out.append(device_status(self.cfg.gatewaySn, code, "RUNNING", cid, job_id,
                                 opening=opening, motor_current=self._current_of(code, opening),
                                 pressure=self._pressure_now(code)))
        log.info("施肥泵 %s START opening=%s%% job=%s 注肥比=%s%%", code, opening, job_id, ratio)
        return out

    # ------------------------------------------------------------------ #
    # 每采集周期：过载联锁 + 肥液量累计 + 周期回报
    # ------------------------------------------------------------------ #
    def evaluate(self, frame: Dict[str, Dict[str, Any]]) -> List[Dict[str, Any]]:
        out: List[Dict[str, Any]] = []
        now = time.time()
        dt = max(0.0, now - self._last_tick)
        self._last_tick = now
        self._last_frame = frame

        for code, rt in self._pumps.items():
            if not rt.running or rt.run is None:
                continue
            reading = frame.get(code) or {}
            values = reading.get("values") or {}
            current = values.get("motorCurrent")
            if current is None:
                current = self._current_of(code, rt.opening)
            overload = bool(values.get("overload", False)) or \
                (current is not None and current > self.interlocks.pumpOverloadA)

            # 肥液量累计 L = 额定 L/h × 开度 × dt
            rt.run.fertilizer_l += self._capacity_lph.get(code, 0.0) \
                * (rt.opening / 100.0) * dt / 3600.0

            if overload:
                out.append(event(
                    self.cfg.gatewaySn, "CRITICAL", "INTERLOCK_PUMP_OVERLOAD",
                    f"施肥泵 {code} 电机过载（电流 {current}A > {self.interlocks.pumpOverloadA}A），已紧急停泵",
                    valve_code=code, context={"motorCurrent": current,
                                              "thresholdA": self.interlocks.pumpOverloadA}))
                self._stop(rt, rt.run.command_id, rt.run.job_id,
                           "INTERLOCK_PUMP_OVERLOAD", out, overload=True)
                continue

            # 周期回报（每 60s 或开度变化）
            if now - rt.last_status_at >= 60:
                out.append(device_status(
                    self.cfg.gatewaySn, code, "RUNNING", None, rt.run.job_id,
                    opening=rt.opening, motor_current=current,
                    pressure=self._pressure_now(code)))
                rt.last_status_at = now
        return out

    # ------------------------------------------------------------------ #
    # 安全停车（缺水/通信中断/阀门联锁联动）
    # ------------------------------------------------------------------ #
    def safety_stop_all(self, reason: str) -> List[Dict[str, Any]]:
        out: List[Dict[str, Any]] = []
        for code, rt in self._pumps.items():
            if rt.running:
                log.warning("安全停泵 %s reason=%s", code, reason)
                out.append(event(self.cfg.gatewaySn, "CRITICAL", reason,
                                 f"安全联锁动作，施肥泵 {code} 已停止", valve_code=code,
                                 context={"fertilizerL": round(
                                     rt.run.fertilizer_l if rt.run else 0.0, 3)}))
                self._stop(rt, rt.run.command_id if rt.run else None,
                           rt.run.job_id if rt.run else None, reason, out)
        return out

    # ------------------------------------------------------------------ #
    def _stop(self, rt: PumpRuntime, command_id: Optional[str], job_id: Optional[str],
              reason: str, out: List[Dict[str, Any]], overload: bool = False) -> None:
        fert_l = rt.run.fertilizer_l if rt.run else 0.0
        if rt.running:
            self._drive(rt, 0)
            log.info("施肥泵 %s 停止 reason=%s 累计肥液=%.2fL", rt.code, reason, fert_l)
        rt.running = False
        rt.opening = 0
        rt.run = None
        out.append(device_status(self.cfg.gatewaySn, rt.code, "STOPPED", command_id, job_id,
                                 opening=0, overload=overload))

    def _drive(self, rt: PumpRuntime, opening: int) -> None:
        if self._driver is None:
            raise RuntimeError("施肥泵驱动未挂载")
        self._driver(rt.code, opening)
        rt.last_status_at = time.time()

    def _blocked_by_pressure(self, pump_code: str) -> Optional[tuple[str, str]]:
        p = self._pressure_now(pump_code)
        pc = self._pressure_of.get(pump_code)
        if pc and p is not None and p < self.interlocks.pressureMinKpa:
            return ("INTERLOCK_WATER_LOST",
                    f"主管道水压 {p}kPa < {self.interlocks.pressureMinKpa}kPa")
        return None

    def _pressure_now(self, pump_code: str) -> Optional[float]:
        pc = self._pressure_of.get(pump_code)
        if not pc:
            return None
        v = (self._last_frame.get(pc, {}).get("values") or {}).get("pressure")
        return float(v) if v is not None else None

    def _current_of(self, code: str, opening: int) -> float:
        """无实测电流时按开度估算（额定电流取设备 sim.ratedCurrent，默认 4A）。"""
        rated = 4.0
        d = next((d for d in self.cfg.devices if d.code == code), None)
        if d is not None:
            rated = float(d.sim.get("ratedCurrent", 4.0))
        return round(rated * opening / 100.0, 2)

    def is_any_running(self) -> bool:
        return any(rt.running for rt in self._pumps.values())

    # ------------------------------------------------------------------ #
    # 云端 config 下发
    # ------------------------------------------------------------------ #
    def apply_config(self, msg: Dict[str, Any]) -> None:
        il = msg.get("interlocks") or {}
        for k, v in il.items():
            if k in ("pressureMinKpa", "pumpOverloadA", "commLostSec") and v is not None:
                setattr(self.interlocks, k, float(v))
        log.info("泵控联锁参数已更新: pressureMin=%s overloadA=%s",
                 self.interlocks.pressureMinKpa, self.interlocks.pumpOverloadA)
