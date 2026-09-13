"""消息构造：严格遵循 docs/mqtt-protocol.md 的 JSON 信封。"""
from __future__ import annotations

import time
import uuid
from typing import Any, Dict, List, Optional


def now_ms() -> int:
    return int(time.time() * 1000)


def telemetry(gateway_sn: str, records: List[Dict[str, Any]], ts: Optional[int] = None) -> Dict[str, Any]:
    return {"type": "telemetry", "gatewaySn": gateway_sn, "ts": ts or now_ms(), "records": records}


def soil_record(device_code: str, values: Dict[str, float], ts: int, quality: str = "GOOD") -> Dict[str, Any]:
    return {"deviceCode": device_code, "kind": "soil", "ts": ts, "values": values, "quality": quality}


def weather_record(device_code: str, values: Dict[str, float], ts: int, quality: str = "GOOD") -> Dict[str, Any]:
    return {"deviceCode": device_code, "kind": "weather", "ts": ts, "values": values, "quality": quality}


def flow_record(device_code: str, values: Dict[str, float], ts: int, quality: str = "GOOD") -> Dict[str, Any]:
    return {"deviceCode": device_code, "kind": "flow", "ts": ts, "values": values, "quality": quality}


def valve_status(gateway_sn: str, valve_code: str, state: str, job_id: Optional[str],
                 command_id: Optional[str], instant_flow: float, applied_volume: float,
                 total_flow: float, stop_reason: Optional[str] = None,
                 ts: Optional[int] = None) -> Dict[str, Any]:
    return {
        "type": "valveStatus", "gatewaySn": gateway_sn, "ts": ts or now_ms(),
        "valveCode": valve_code, "jobId": job_id, "commandId": command_id,
        "state": state, "instantFlow": round(instant_flow, 3),
        "totalFlow": round(total_flow, 3), "appliedVolume": round(applied_volume, 3),
        "stopReason": stop_reason,
    }


def event(gateway_sn: str, level: str, code: str, message: str,
          valve_code: Optional[str] = None, context: Optional[Dict[str, Any]] = None,
          ts: Optional[int] = None) -> Dict[str, Any]:
    return {
        "type": "event", "gatewaySn": gateway_sn, "ts": ts or now_ms(),
        "level": level, "code": code, "valveCode": valve_code,
        "message": message, "context": context or {},
    }


def device_status(gateway_sn: str, device_code: str, state: str,
                  command_id: Optional[str], job_id: Optional[str] = None,
                  opening: Optional[int] = None, motor_current: Optional[float] = None,
                  pressure: Optional[float] = None, overload: bool = False,
                  ts: Optional[int] = None) -> Dict[str, Any]:
    """施肥泵/调节阀等通用执行器状态回报（farm/{gw}/device/status）。"""
    m: Dict[str, Any] = {
        "type": "deviceStatus", "gatewaySn": gateway_sn, "ts": ts or now_ms(),
        "deviceCode": device_code, "state": state,
        "commandId": command_id, "jobId": job_id, "overload": overload,
    }
    if opening is not None:
        m["opening"] = int(opening)
    if motor_current is not None:
        m["motorCurrent"] = round(motor_current, 2)
    if pressure is not None:
        m["pressure"] = round(pressure, 1)
    return m


def health(gateway_sn: str, uptime_sec: int, queue_depth: int, fw: str = "1.0.0") -> Dict[str, Any]:
    return {"type": "health", "gatewaySn": gateway_sn, "ts": now_ms(),
            "uptimeSec": uptime_sec, "queueDepth": queue_depth, "fw": fw}


def new_command_id() -> str:
    return str(uuid.uuid4())
