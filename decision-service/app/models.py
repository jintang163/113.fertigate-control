"""Pydantic 入参/出参模型 —— 与 docs/api-contract.md 的 /decide 严格对应。"""
from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, Field


class CropStage(BaseModel):
    name: str
    startDay: int
    endDay: int
    # 线性插值表达生育期渐变；固定值时 start==end 取值相同
    startKc: float
    endKc: float
    startP: float
    endP: float
    zrMm: float


class Crop(BaseModel):
    code: str
    stages: List[CropStage]
    daysAfterSowing: int = 0
    # 日需水曲线值 mm/day（品种库/曲线服务给出）；缺省时不做日需水钳制
    dailyWaterNeedMm: Optional[float] = None


class SoilZone(BaseModel):
    depth: int          # cm，传感器埋深
    moist: float        # 体积含水率 %


class Soil(BaseModel):
    thetaFc: float                                  # 田间持水量 %
    thetaWp: float                                  # 萎蔫点 %
    moisture: Optional[float] = None                # 根区平均湿度 %（缺省时由 zones 加权）
    zones: Optional[List[SoilZone]] = None


class Weather(BaseModel):
    airTemp: Optional[float] = None
    tMax: Optional[float] = None
    tMin: Optional[float] = None
    rainfallToday: float = 0.0
    rainForecastMm: List[float] = Field(default_factory=list)
    irrigatedTodayMm: float = 0.0                   # 当日已灌水量 mm（日需水钳制用）
    latitude: float = 34.5
    dayOfYear: Optional[int] = None                 # 缺省取服务当天


class FieldInfo(BaseModel):
    areaM2: float
    wetRatio: float = 1.0                           # 湿润比 p_wet
    efficiency: float = 0.9                         # 灌溉水利用系数 η
    emitterTotalLph: float                          # 滴头/系统总流量 L/h
    irrigationMode: str = "DRIP"
    rainSkipMm: float = 5.0
    targetOffsetPct: float = 1.0                    # 滴灌灌至 θfc-offset，防深层渗漏
    dailyNeedCapFactor: float = 1.2                 # 日需水钳制安全系数（单日不超 需水×系数）


class Limits(BaseModel):
    hardMax: float
    hardMin: float = 0.0
    ec: Optional[float] = None
    ecMin: Optional[float] = None
    ecMax: Optional[float] = None
    ph: Optional[float] = None
    phMin: Optional[float] = None
    phMax: Optional[float] = None
    maxDurationSec: int = 900
    minIntervalH: float = 0.5
    lastIrrigAgoH: Optional[float] = None


class DecideRequest(BaseModel):
    crop: Crop
    soil: Soil
    weather: Weather = Field(default_factory=Weather)
    field: FieldInfo
    limits: Limits


class Decision(BaseModel):
    decision: str                   # IRRIGATE / HOLD / SKIP / FORBID
    stage: str
    kc: float
    p: float
    zrMm: float
    thetaStart: float
    thetaTarget: float
    moisture: float
    deficitMm: float = 0.0
    volumeM3: float = 0.0
    durationSec: int = 0
    clampReason: Optional[str] = None
    et0MmDay: Optional[float] = None
    etcMmDay: Optional[float] = None
    reasons: List[str] = Field(default_factory=list)
