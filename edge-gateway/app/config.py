"""网关配置加载：YAML + ${ENV:default} 占位符展开。"""
from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from typing import Any, Dict, List

import yaml

_PLACEHOLDER = re.compile(r"\$\{([A-Z0-9_]+)(?::([^}]*))?\}")


def _expand(value: Any) -> Any:
    if isinstance(value, str):
        def repl(m: re.Match) -> str:
            return os.environ.get(m.group(1), m.group(2) if m.group(2) is not None else "")
        return _PLACEHOLDER.sub(repl, value)
    if isinstance(value, dict):
        return {k: _expand(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_expand(v) for v in value]
    return value


@dataclass
class InterlockConfig:
    moistureHardMax: float = 33.0
    moistureHardMin: float = 8.0
    sensorLostSec: float = 180.0
    ecHigh: float = 3.0
    phLow: float = 5.0
    phHigh: float = 8.0
    # 缺水联锁：主管道水压下限 kPa / 阀开最低瞬时流量 m³h，持续 waterLostDelaySec 判定
    pressureMinKpa: float = 80.0
    flowMinM3h: float = 0.5
    waterLostDelaySec: float = 15.0
    # 施肥泵过载电流 A
    pumpOverloadA: float = 8.0
    # 与云端通信中断超过该秒数时，本地紧急关停所有执行器
    commLostSec: float = 300.0


@dataclass
class DeviceConfig:
    code: str
    kind: str                       # soil | weather | valve | flow | pressure | pump
    modbusAddr: int = 0
    linkedValve: str | None = None
    sensorCode: str | None = None   # valve 设备联锁取数点
    linkedPressure: str | None = None  # pump 关联的主管道压力传感器
    sim: Dict[str, Any] = field(default_factory=dict)


@dataclass
class GatewayConfig:
    gatewaySn: str
    mqtt: Dict[str, Any]
    devices: List[DeviceConfig]
    interlocks: InterlockConfig
    mode: str = "simulate"
    pollIntervalSec: float = 10.0
    healthIntervalSec: float = 60.0
    outboxBatch: int = 50
    modbus: Dict[str, Any] = field(default_factory=dict)
    simulate: Dict[str, Any] = field(default_factory=dict)
    sqlitePath: str = "data/gateway-outbox.db"
    raw: Dict[str, Any] = field(default_factory=dict)

    def device(self, code: str) -> DeviceConfig | None:
        return next((d for d in self.devices if d.code == code), None)

    def by_kind(self, kind: str) -> List[DeviceConfig]:
        return [d for d in self.devices if d.kind == kind]


def load_config(path: str) -> GatewayConfig:
    with open(path, "r", encoding="utf-8") as f:
        raw = _expand(yaml.safe_load(f))

    il = raw.get("interlocks") or {}
    devices = [DeviceConfig(
        code=d["code"], kind=d["kind"], modbusAddr=d.get("modbusAddr", 0),
        linkedValve=d.get("linkedValve"), sensorCode=d.get("sensorCode"),
        linkedPressure=d.get("linkedPressure"),
        sim=d.get("sim") or {},
    ) for d in raw.get("devices", [])]

    return GatewayConfig(
        gatewaySn=raw["gatewaySn"],
        mqtt=raw.get("mqtt") or {},
        devices=devices,
        interlocks=InterlockConfig(**{
            k: v for k, v in il.items() if k in InterlockConfig.__dataclass_fields__}),
        mode=raw.get("mode", "simulate"),
        pollIntervalSec=float(raw.get("pollIntervalSec", 10)),
        healthIntervalSec=float(raw.get("healthIntervalSec", 60)),
        outboxBatch=int(raw.get("outboxBatch", 50)),
        modbus=raw.get("modbus") or {},
        simulate=raw.get("simulate") or {},
        sqlitePath=(raw.get("sqlite") or {}).get("path", "data/gateway-outbox.db"),
        raw=raw,
    )
