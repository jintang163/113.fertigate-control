"""施肥融合决策：EC/pH 实测 + 品种需肥曲线 → 是否施肥 / 施肥量。

判据：
  pH 出目标带            → FORBID（先调酸碱，不宜注肥）
  EC > 目标上限          → FORBID（盐分已高，注肥加重盐害）
  EC 缺失                → HOLD（无实测依据，不自动施肥）
  EC ≥ 目标下限          → HOLD（养分充足）
  EC < 目标下限          → FERTIGATE

施肥量：日需量(kg/ha/day) × 面积(ha) × 距上次施肥天数 × EC 亏缺系数(0.5~1.5)
        → N/P/K kg；按肥液养分浓度折肥液 L；有本轮灌溉水量时折算注肥比 %。
"""
from __future__ import annotations

from typing import Optional

from .curves import stage_at
from .growth_models import FertigationDecision, FertigationRequest, Variety

# EC 亏缺系数上下限：亏多少补多少，但避免极端放大/归零
_DEFICIT_MIN = 0.5
_DEFICIT_MAX = 1.5


def decide_fertigation(req: FertigationRequest, variety: Variety) -> FertigationDecision:
    stage = stage_at(variety.stages, req.daysAfterSowing)
    base = FertigationDecision(
        decision="HOLD", stage=stage.name, varietyCode=variety.code,
        ec=req.ec, ph=req.ph,
        ecTarget=[stage.ecMin, stage.ecMax] if stage.ecMin is not None else None,
        phTarget=[stage.phMin, stage.phMax] if stage.phMin is not None else None,
    )
    reasons = base.reasons

    # ---- 硬性禁止 ----
    if req.ph is not None and stage.phMin is not None and stage.phMax is not None:
        if req.ph < stage.phMin or req.ph > stage.phMax:
            base.decision = "FORBID"
            reasons.append(f"pH {req.ph} 超出目标带 [{stage.phMin}, {stage.phMax}]，"
                           f"先调酸碱，禁止注肥")
            return base
    if req.ec is not None and stage.ecMax is not None and req.ec > stage.ecMax:
        base.decision = "FORBID"
        reasons.append(f"EC {req.ec} mS/cm > 目标上限 {stage.ecMax}，盐分已高，停止注肥防盐害")
        return base

    # ---- 数据不足 / 养分充足 ----
    if req.ec is None:
        reasons.append("无 EC 实测值，不自动施肥（等待传感器数据）")
        return base
    if stage.ecMin is None:
        reasons.append(f"品种 {variety.code} 阶段 {stage.name} 未配置 EC 目标带，不自动施肥")
        return base
    if req.ec >= stage.ecMin:
        reasons.append(f"EC {req.ec} mS/cm ≥ 目标下限 {stage.ecMin}，养分充足，保持")
        return base

    # ---- FERTIGATE：按需肥曲线 × EC 亏缺系数定量 ----
    span = (stage.ecMax - stage.ecMin) if stage.ecMax is not None else 0.0
    deficit_ratio = 1.0 if span <= 0 else (stage.ecMin - req.ec) / span
    factor = min(_DEFICIT_MAX, max(_DEFICIT_MIN, 1.0 + deficit_ratio))
    days = max(0.5, req.daysSinceLastFert)
    area_ha = req.areaM2 / 10000.0

    n = round(stage.nKgHaDay * area_ha * days * factor, 3)
    p = round(stage.pKgHaDay * area_ha * days * factor, 3)
    k = round(stage.kKgHaDay * area_ha * days * factor, 3)
    total_kg = n + p + k
    fertilizer_l = 0.0
    if total_kg > 0 and req.solutionNutrientKgPerL > 0:
        fertilizer_l = round(total_kg / req.solutionNutrientKgPerL, 2)

    inject_ratio: Optional[float] = None
    if fertilizer_l > 0 and req.irrigationWaterM3 and req.irrigationWaterM3 > 0:
        ratio = fertilizer_l / (req.irrigationWaterM3 * 1000.0) * 100.0
        inject_ratio = round(min(req.maxInjectRatioPct, ratio), 2)
        if ratio > req.maxInjectRatioPct:
            reasons.append(f"理论注肥比 {ratio:.2f}% 超上限，削顶至 {req.maxInjectRatioPct}%")

    base.decision = "FERTIGATE"
    base.npkKg = {"n": n, "p": p, "k": k}
    base.fertilizerL = fertilizer_l
    base.injectRatioPct = inject_ratio
    reasons.append(
        f"EC {req.ec} mS/cm < 目标下限 {stage.ecMin}（{stage.name}），"
        f"需肥 N/P/K = {n}/{p}/{k} kg（{days:.1f}d × {area_ha:.3f}ha × 系数{factor:.2f}）"
        f"→ 肥液 {fertilizer_l} L")
    if inject_ratio is not None:
        reasons.append(f"本轮灌溉 {req.irrigationWaterM3} m³，建议注肥比 {inject_ratio}%")
    return base
