"""Modbus-RTU 采集层（真实硬件）。

寄存器映射（对接常见农业传感器约定，可在设备 protocol_config 中调整）：
土壤三合一（功能码 03 保持寄存器，地址从 0 起，1 寄存器=2字节，整型10倍）：
  0 湿度 0.1%  1 EC 0.01mS/cm  2 pH 0.01  3 地温 0.1℃
气象站（03）：
  0 气温0.1℃ 1 空气湿度0.1% 2 光照 1W/m² 3 雨量 0.1mm 4 风速0.1m/s
流量计（03）：
  0 瞬时流量 0.001m³/h（uint32, 占2寄存器） 2 累计流量 0.001m³（uint32, 占2寄存器）
电磁阀（DO 线圈，功能码 05/01）：
  线圈0：开闭；离散输入0：到位反馈（可选）

无硬件时上层使用 simulator.FieldSimulator（poll/set_valve 同构）。
"""
from __future__ import annotations

import logging
import time
from typing import Any, Dict, List

from pymodbus.client import ModbusSerialClient

from .config import DeviceConfig

log = logging.getLogger("modbus")


class ModbusBackend:
    def __init__(self, devices: List[DeviceConfig], serial_cfg: Dict[str, Any]):
        self._devices = {d.code: d for d in devices}
        self._client = ModbusSerialClient(
            port=serial_cfg.get("serialPort", "/dev/ttyS4"),
            baudrate=int(serial_cfg.get("baudrate", 9600)),
            bytesize=int(serial_cfg.get("bytesize", 8)),
            parity=serial_cfg.get("parity", "N"),
            stopbits=int(serial_cfg.get("stopbits", 1)),
            timeout=float(serial_cfg.get("timeoutSec", 1)),
        )
        self._valve_addr = {d.code: d.modbusAddr for d in devices if d.kind == "valve"}

    def connect(self) -> bool:
        return self._client.connect()

    def close(self) -> None:
        self._client.close()

    def _read_hr(self, addr: int, count: int) -> List[int]:
        rr = self._client.read_holding_registers(address=0, count=count, slave=addr)
        if rr.isError():
            raise IOError(f"modbus read error: {rr}")
        return list(rr.registers)

    def poll(self) -> Dict[str, Dict[str, Any]]:
        frame: Dict[str, Dict[str, Any]] = {}
        for d in self._devices.values():
            try:
                if d.kind == "soil":
                    regs = self._read_hr(d.modbusAddr, 4)
                    frame[d.code] = {"kind": "soil", "quality": "GOOD", "values": {
                        "soilMoist": regs[0] / 10.0,
                        "ec": regs[1] / 100.0,
                        "ph": regs[2] / 100.0,
                        "soilTemp": regs[3] / 10.0,
                    }}
                elif d.kind == "weather":
                    regs = self._read_hr(d.modbusAddr, 5)
                    frame[d.code] = {"kind": "weather", "quality": "GOOD", "values": {
                        "airTemp": regs[0] / 10.0,
                        "airHumidity": regs[1] / 10.0,
                        "solarRadiation": float(regs[2]),
                        "rainfall": regs[3] / 10.0,
                        "windSpeed": regs[4] / 10.0,
                    }}
                elif d.kind == "flow":
                    regs = self._read_hr(d.modbusAddr, 4)
                    instant = ((regs[0] << 16) | regs[1]) / 1000.0
                    total = ((regs[2] << 16) | regs[3]) / 1000.0
                    frame[d.code] = {"kind": "flow", "quality": "GOOD",
                                     "values": {"instantFlow": instant, "totalFlow": total}}
                elif d.kind == "valve":
                    rr = self._client.read_coils(address=0, count=1, slave=d.modbusAddr)
                    state = "OPEN" if (not rr.isError() and rr.bits[0]) else "CLOSED"
                    frame[d.code] = {"kind": "valve", "quality": "GOOD",
                                     "values": {"state": state}}
            except Exception as e:  # 单点故障不拖垮整轮采集
                log.warning("读取 %s 失败: %s", d.code, e)
                frame[d.code] = {"kind": d.kind, "quality": "SENSOR_FAULT", "values": {}}
        return frame

    # ---- 阀门驱动 ----
    def set_valve(self, code: str, open_: bool) -> None:
        addr = self._valve_addr.get(code)
        if addr is None:
            raise KeyError(f"未知阀门: {code}")
        rw = self._client.write_coil(address=0, value=bool(open_), slave=addr)
        if rw.isError():
            raise IOError(f"阀门 {code} 写入失败: {rw}")
        log.info("阀门 %s → %s", code, "OPEN" if open_ else "CLOSED")

    def valve_state(self, code: str) -> str:
        addr = self._valve_addr[code]
        rr = self._client.read_coils(address=0, count=1, slave=addr)
        return "OPEN" if (not rr.isError() and rr.bits[0]) else "CLOSED"
