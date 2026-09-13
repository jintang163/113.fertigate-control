"""灌溉融合决策（生长模型版）：实测墒情 + 品种日需水曲线 + 阈值上下限 → 开/关/时长。

复用核心 decide()（FAO-56 动态阈值 + 联锁 + 灌量/时长），
在此之上把品种库的日需水曲线（mm/day）作为单日灌量上限传入。
"""
from __future__ import annotations

from typing import List, Optional

from .curves import params_at
from .growth_models import IrrigationAction, Variety
from .irrigation import decide
from .models import (Crop, CropStage, DecideRequest, FieldInfo, Limits, Soil,
                     Weather)


def to_crop_stages(variety: Variety) -> List[CropStage]:
    """品种库生育期 → 核心决策模型的 CropStage（水肥参数不参与灌溉计算）。"""
    return [CropStage(name=s.name, startDay=s.startDay, endDay=s.endDay,
                      startKc=s.startKc, endKc=s.endKc,
                      startP=s.startP, endP=s.endP, zrMm=s.zrMm)
            for s in variety.stages]


def fused_irrigation(*, variety: Variety, days_after_sowing: int,
                     moisture: float, theta_fc: float, theta_wp: float,
                     area_m2: float, emitter_total_lph: float,
                     hard_max: float, hard_min: float = 0.0,
                     wet_ratio: float = 1.0, efficiency: float = 0.9,
                     max_duration_sec: int = 900, min_interval_h: float = 0.5,
                     last_irrig_ago_h: Optional[float] = None,
                     irrigated_today_mm: float = 0.0,
                     rainfall_today: float = 0.0,
                     et0_mm_day: Optional[float] = None) -> IrrigationAction:
    """融合决策：湿度实测 + 需水曲线 + 阈值 → OPEN（含时长）/ CLOSED。"""
    gp = params_at(variety, days_after_sowing, et0_mm_day)
    req = DecideRequest(
        crop=Crop(code=variety.code, stages=to_crop_stages(variety),
                  daysAfterSowing=days_after_sowing,
                  dailyWaterNeedMm=gp.water_need_mm_day),
        soil=Soil(thetaFc=theta_fc, thetaWp=theta_wp, moisture=moisture),
        weather=Weather(rainfallToday=rainfall_today,
                        irrigatedTodayMm=irrigated_today_mm),
        field=FieldInfo(areaM2=area_m2, wetRatio=wet_ratio, efficiency=efficiency,
                        emitterTotalLph=emitter_total_lph),
        limits=Limits(hardMax=hard_max, hardMin=hard_min,
                      maxDurationSec=max_duration_sec, minIntervalH=min_interval_h,
                      lastIrrigAgoH=last_irrig_ago_h),
    )
    r = decide(req)
    if r.decision == "IRRIGATE":
        return IrrigationAction(action="OPEN", volumeM3=r.volumeM3,
                                durationSec=r.durationSec, reasons=r.reasons)
    # HOLD / SKIP / FORBID 一律视为不开启，原因透传
    return IrrigationAction(action="CLOSED", volumeM3=0.0, durationSec=0,
                            reasons=r.reasons)
