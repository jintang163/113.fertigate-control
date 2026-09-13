"""日需水 / 日需肥曲线：按品种 + 生育期逐日输出。

需水：逐日 Kc 在阶段内线性插值，ETc = Kc × ET0；
     无气象数据（ET0 缺省）时回退到品种库阶段基准 waterMmDay。
需肥：逐日 N/P/K 取所在阶段日需量（kg/ha/day，农艺上按阶段恒定给肥）。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional

from .growth_models import FertCurvePoint, GrowthStage, Variety, WaterCurvePoint


@dataclass
class GrowthStageParams:
    """某一天落在的生育期及其当日参数。"""
    stage: GrowthStage
    kc: float
    p: float
    water_need_mm_day: float        # 当日需水（有 ET0 时 = Kc×ET0，否则阶段基准）


def stage_at(stages: List[GrowthStage], day: int) -> GrowthStage:
    """播后 day 天所在生育期；区间外取最近阶段（与 crop_model.stage_at 同规则）。"""
    if not stages:
        raise ValueError("variety stages 不能为空")
    ordered = sorted(stages, key=lambda s: s.startDay)
    cur = ordered[-1]
    for s in ordered:
        if s.startDay <= day <= s.endDay:
            cur = s
            break
        if day < s.startDay:
            cur = s
            break
    return cur


def _interp(day: int, s: GrowthStage, start: float, end: float) -> float:
    if s.endDay <= s.startDay:
        return start
    f = (day - s.startDay) / (s.endDay - s.startDay)
    f = min(1.0, max(0.0, f))
    return start + f * (end - start)


def params_at(variety: Variety, day: int,
              et0_mm_day: Optional[float] = None) -> GrowthStageParams:
    """当日生育期参数：Kc/p 插值 + 当日需水 mm/day。"""
    s = stage_at(variety.stages, day)
    kc = _interp(day, s, s.startKc, s.endKc)
    p = _interp(day, s, s.startP, s.endP)
    water = round(et0_mm_day * kc, 2) if et0_mm_day is not None else s.waterMmDay
    return GrowthStageParams(stage=s, kc=round(kc, 4), p=round(p, 4),
                             water_need_mm_day=water)


def water_curve(variety: Variety,
                et0_mm_day: Optional[float] = None,
                days: Optional[int] = None) -> List[WaterCurvePoint]:
    """全生育期逐日需水曲线（mm/day）。"""
    if not variety.stages:
        raise ValueError("variety stages 不能为空")
    total = days or max(s.endDay for s in variety.stages)
    out: List[WaterCurvePoint] = []
    for d in range(0, total + 1):
        p = params_at(variety, d, et0_mm_day)
        out.append(WaterCurvePoint(day=d, stage=p.stage.name,
                                   kc=p.kc, etcMmDay=p.water_need_mm_day))
    return out


def fert_curve(variety: Variety, days: Optional[int] = None) -> List[FertCurvePoint]:
    """全生育期逐日需肥曲线（N/P/K kg/ha/day）。"""
    if not variety.stages:
        raise ValueError("variety stages 不能为空")
    total = days or max(s.endDay for s in variety.stages)
    out: List[FertCurvePoint] = []
    for d in range(0, total + 1):
        s = stage_at(variety.stages, d)
        out.append(FertCurvePoint(day=d, stage=s.name,
                                  nKgHaDay=s.nKgHaDay,
                                  pKgHaDay=s.pKgHaDay,
                                  kKgHaDay=s.kKgHaDay))
    return out
