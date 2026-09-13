"""无硬件田间仿真器：实现与 Modbus 采集层相同的读数接口。

包含一个极简水文模型，用于端到端演示控制闭环：
- 蒸散耗水：dθ = −ETc[mm/d] / 湿润层深[mm] × 100 （按天比例折算到步长）
- 灌溉增墒：灌水 V 均匀铺在湿润面积 A·p_wet 上 → 水深 = V/(A·p_wet)·1000 mm
  dθ = 水深/湿润层深 × 100；超过田持 θfc 的部分形成深层渗漏（削顶到 θfc）
- 气象站按日周期合成气温/湿度/光照；默认无降雨
- 流量计：阀开时 instantFlow = 系统总流量，totalFlow 累计
"""
from __future__ import annotations

import math
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass
class SoilState:
    code: str
    moisture: float
    theta_fc: float
    theta_wp: float
    etc_mm_day: float
    ec: float
    ph: float
    soil_temp: float = 22.0
    fault: bool = False


@dataclass
class WeatherState:
    code: str
    t_max: float = 32.0
    t_min: float = 21.0
    rh_max: float = 75.0
    rh_min: float = 40.0
    latitude: float = 34.5


@dataclass
class PressureState:
    code: str
    pressure: float = 250.0     # kPa
    fault: bool = False         # True 时模拟缺水（压力骤降）


@dataclass
class PumpState:
    code: str
    opening: int = 0            # 0-100
    rated_current: float = 4.0  # A
    fault: bool = False         # True 时模拟电机过载


class FieldSimulator:
    """同步式仿真后端：poll() 返回一帧读数，valve set 立刻生效。"""

    def __init__(self, devices: List[Dict[str, Any]], sim_cfg: Dict[str, Any]):
        self._t = time.time()
        self.area = float(sim_cfg.get("areaM2", 2000))
        self.wet_ratio = float(sim_cfg.get("wetRatio", 0.8))
        self.wetted_depth = float(sim_cfg.get("wettedDepthMm", 300))
        self.total_lph = float(sim_cfg.get("emitterTotalLph", 2400))
        self.latitude = float((sim_cfg.get("weather") or {}).get("latitude", 34.5))

        self.soils: Dict[str, SoilState] = {}
        self.weather: Optional[WeatherState] = None
        self.valves: Dict[str, bool] = {}
        self.fault: Dict[str, bool] = {}
        self.pressures: Dict[str, PressureState] = {}
        self.pumps: Dict[str, PumpState] = {}
        self._flow_total = 0.0
        self._flow_code: Optional[str] = None
        self._valve_flow_codes: List[str] = []

        for d in devices:
            kind = d["kind"]
            if kind == "soil":
                s = d.get("sim") or {}
                self.soils[d["code"]] = SoilState(
                    code=d["code"],
                    moisture=float(s.get("initialMoisture", 20.0)),
                    theta_fc=float(s.get("thetaFc", 30.0)),
                    theta_wp=float(s.get("thetaWp", 12.0)),
                    etc_mm_day=float(s.get("etcMmDay", 5.0)),
                    ec=float(s.get("ec", 1.6)),
                    ph=float(s.get("ph", 6.5)),
                )
            elif kind == "weather":
                self.weather = WeatherState(code=d["code"], latitude=self.latitude)
            elif kind == "valve":
                self.valves[d["code"]] = False
                self._valve_flow_codes.append(d["code"])
            elif kind == "flow":
                self._flow_code = d["code"]
            elif kind == "pressure":
                s = d.get("sim") or {}
                self.pressures[d["code"]] = PressureState(
                    code=d["code"], pressure=float(s.get("initialKpa", 250.0)))
            elif kind == "pump":
                s = d.get("sim") or {}
                self.pumps[d["code"]] = PumpState(
                    code=d["code"], rated_current=float(s.get("ratedCurrent", 4.0)))

        # 多土壤点共用同一阀门时，总流量按点数均分
        self._soil_share: Dict[str, float] = {}

    # ---- 阀门驱动接口（与 Modbus 驱动一致） ----
    def set_valve(self, code: str, open_: bool) -> None:
        self.valves[code] = open_

    def valve_state(self, code: str) -> str:
        return "OPEN" if self.valves.get(code) else "CLOSED"

    # ---- 施肥泵驱动（开度 0-100） ----
    def set_pump(self, code: str, opening: int) -> None:
        p = self.pumps.get(code)
        if p is not None:
            p.opening = max(0, min(100, int(opening)))

    def pump_opening(self, code: str) -> int:
        p = self.pumps.get(code)
        return p.opening if p else 0

    def any_open(self) -> bool:
        return any(self.valves.values())

    # ---- 故障注入（测试/演示） ----
    def set_pressure_fault(self, code: str, fault: bool) -> None:
        p = self.pressures.get(code)
        if p is not None:
            p.fault = fault

    def set_pump_fault(self, code: str, fault: bool) -> None:
        p = self.pumps.get(code)
        if p is not None:
            p.fault = fault

    # ---- 水文推进 ----
    def step(self, dt_sec: float) -> None:
        days = dt_sec / 86400.0
        open_count = sum(1 for v in self.valves.values() if v)
        # 阀开流量（m³/h）；多阀并联时均分总流量
        per_valve_m3h = (self.total_lph / 1000.0 / open_count) if open_count else 0.0

        # 统计每个开阀关联的土壤点
        for soil in self.soils.values():
            # 蒸散耗水（%/d = ETc / 湿润层深 × 100）
            soil.moisture -= soil.etc_mm_day / self.wetted_depth * 100.0 * days
            soil.soil_temp += (self._air_temp() - soil.soil_temp) * min(1.0, dt_sec / 600.0)

        if open_count:
            # 简化：所有土壤传感器都位于被灌区域，总流量按土壤点数均分
            codes = list(self.soils.keys())
            share_m3h = self.total_lph / 1000.0 / max(1, len(codes))
            v_m3 = share_m3h * dt_sec / 3600.0
            wetted_area = self.area * self.wet_ratio / max(1, len(codes))
            depth_added_mm = v_m3 / wetted_area * 1000.0
            for code in codes:
                soil = self.soils[code]
                soil.moisture += depth_added_mm / self.wetted_depth * 100.0
                # 深层渗漏：超过田持的水排走
                soil.moisture = min(soil.theta_fc, soil.moisture)

        for soil in self.soils.values():
            soil.moisture = max(soil.theta_wp - 2.0, soil.moisture)

        # 流量计
        instant = per_valve_m3h * open_count
        self._flow_total += instant * dt_sec / 3600.0
        self._t += dt_sec

        # 主管道水压：阀开正常工作约 250kPa；故障注入时骤降至 40kPa（缺水）
        for p in self.pressures.values():
            target = 40.0 if p.fault else (250.0 if open_count else 280.0)
            p.pressure += (target - p.pressure) * min(1.0, dt_sec / 5.0)

    def _air_temp(self) -> float:
        if not self.weather:
            return 25.0
        hour = (self._t % 86400) / 3600.0
        # 14 时最高、5 时最低的正弦曲线
        x = math.sin((hour - 9) / 24.0 * 2 * math.pi)
        return (self.weather.t_max + self.weather.t_min) / 2 + \
            (self.weather.t_max - self.weather.t_min) / 2 * x

    # ---- 采集帧（与 ModbusPoller.poll 同构） ----
    def poll(self) -> Dict[str, Dict[str, Any]]:
        frame: Dict[str, Dict[str, Any]] = {}
        for code, s in self.soils.items():
            if s.fault:
                frame[code] = {"kind": "soil", "quality": "SENSOR_FAULT", "values": {}}
            else:
                frame[code] = {"kind": "soil", "quality": "GOOD", "values": {
                    "soilMoist": round(s.moisture, 2),
                    "ec": round(s.ec, 2),
                    "ph": round(s.ph, 2),
                    "soilTemp": round(s.soil_temp, 1),
                }}
        if self.weather:
            hour = (self._t % 86400) / 3600.0
            temp = self._air_temp()
            x_t = (math.sin((hour - 9) / 24 * 2 * math.pi) + 1) / 2
            rh = self.weather.rh_max - (self.weather.rh_max - self.weather.rh_min) * x_t
            solar = max(0.0, math.sin((hour - 6) / 12 * math.pi)) * 850.0 if 6 <= hour <= 18 else 0.0
            frame[self.weather.code] = {"kind": "weather", "quality": "GOOD", "values": {
                "airTemp": round(temp, 1),
                # 日最高/最低气温（Hargreaves ET0 所需；真实气象站无此项时由云端以辐射法替代）
                "tMax": self.weather.t_max,
                "tMin": self.weather.t_min,
                "airHumidity": round(rh, 1),
                "solarRadiation": round(solar, 0),
                "rainfall": 0.0,
                "windSpeed": round(1.5 + 0.8 * math.sin(hour), 1),
            }}
        for code in self.valves:
            frame[code] = {"kind": "valve", "quality": "GOOD",
                           "values": {"state": self.valve_state(code)}}
        # 主管道压力
        for code, p in self.pressures.items():
            frame[code] = {"kind": "pressure", "quality": "GOOD",
                           "values": {"pressure": round(p.pressure, 1)}}
        # 施肥泵：开度/电流（过载故障时电流超限）
        for code, p in self.pumps.items():
            opening = p.opening
            current = p.rated_current * opening / 100.0
            if p.fault and opening > 0:
                current = p.rated_current * 2.2
            frame[code] = {"kind": "pump", "quality": "GOOD", "values": {
                "opening": opening,
                "motorCurrent": round(current, 2),
                "overload": bool(p.fault and opening > 0),
            }}
        if self._flow_code:
            instant = (self.total_lph / 1000.0) if self.any_open() else 0.0
            frame[self._flow_code] = {"kind": "flow", "quality": "GOOD", "values": {
                "instantFlow": round(instant, 3),
                "totalFlow": round(self._flow_total, 3),
            }}
        return frame

    # ---- 测试/演示用故障注入 ----
    def set_soil_fault(self, code: str, fault: bool) -> None:
        self.soils[code].fault = fault
