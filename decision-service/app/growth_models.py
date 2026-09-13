"""生长模型 Pydantic 模型：品种 / 生育期 / 曲线 / 施肥决策 / 回调。"""
from __future__ import annotations

from typing import Dict, List, Optional

from pydantic import BaseModel, Field


# ---------------------------------------------------------------- 品种与生育期

class GrowthStage(BaseModel):
    """生育期阶段：灌溉模型参数 + 水肥需求参数。"""
    name: str                       # seedling 苗期 / flowering 花期 / fruiting 结果期 / maturity 成熟期
    label: str = ""                 # 中文名（苗期/花期/…）
    startDay: int                   # 播后天数区间
    endDay: int
    startKc: float                  # 作物系数（阶段内线性插值）
    endKc: float
    startP: float                   # 允许亏缺比例 p
    endP: float
    zrMm: float                     # 根深 mm
    waterMmDay: float = 0.0         # 日需水基准 mm/day（无气象数据时的曲线值）
    nKgHaDay: float = 0.0           # 日需氮 kg/ha/day
    pKgHaDay: float = 0.0           # 日需磷 kg/ha/day
    kKgHaDay: float = 0.0           # 日需钾 kg/ha/day
    ecMin: Optional[float] = None   # EC 目标带 mS/cm
    ecMax: Optional[float] = None
    phMin: Optional[float] = None   # pH 目标带
    phMax: Optional[float] = None


class Variety(BaseModel):
    code: str
    name: str
    cropCode: str = ""
    stages: List[GrowthStage] = Field(default_factory=list)
    updatedAt: Optional[str] = None


# ---------------------------------------------------------------- 曲线

class WaterCurvePoint(BaseModel):
    day: int
    stage: str
    kc: float
    etcMmDay: float                 # 日需水 mm/day


class FertCurvePoint(BaseModel):
    day: int
    stage: str
    nKgHaDay: float
    pKgHaDay: float
    kKgHaDay: float


# ---------------------------------------------------------------- 施肥融合决策

class FertigationRequest(BaseModel):
    varietyCode: str
    daysAfterSowing: int = 0
    areaM2: float
    ec: Optional[float] = None              # 实测 EC mS/cm
    ph: Optional[float] = None              # 实测 pH
    daysSinceLastFert: float = 1.0          # 距上次施肥天数
    solutionNutrientKgPerL: float = 0.3     # 肥液总养分浓度 kg/L
    irrigationWaterM3: Optional[float] = None   # 本轮灌溉水量（用于折算注肥比）
    maxInjectRatioPct: float = 2.0          # 注肥比上限 %（防浓度过高烧根）


class FertigationDecision(BaseModel):
    decision: str                   # FERTIGATE / HOLD / FORBID
    stage: str
    varietyCode: str
    ec: Optional[float] = None
    ph: Optional[float] = None
    ecTarget: Optional[List[float]] = None
    phTarget: Optional[List[float]] = None
    npkKg: Dict[str, float] = Field(default_factory=dict)   # {"n":..,"p":..,"k":..}
    fertilizerL: float = 0.0
    injectRatioPct: Optional[float] = None
    reasons: List[str] = Field(default_factory=list)


# ---------------------------------------------------------------- 回调载荷

class IrrigationAction(BaseModel):
    """灌溉执行指令：开启/关闭 + 持续时间。"""
    action: str                     # OPEN / CLOSED
    volumeM3: float = 0.0
    durationSec: int = 0
    reasons: List[str] = Field(default_factory=list)


class FertigationAction(BaseModel):
    shouldFertilize: bool = False
    fertilizerL: float = 0.0
    npkKg: Dict[str, float] = Field(default_factory=dict)
    injectRatioPct: Optional[float] = None
    reasons: List[str] = Field(default_factory=list)


class GrowthCallback(BaseModel):
    """POST 到 Spring Boot /api/growth/callback 的载荷。"""
    decisionId: str
    fieldId: int
    decidedAt: str
    irrigation: IrrigationAction
    fertigation: FertigationAction
