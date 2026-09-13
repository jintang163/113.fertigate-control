"""灌溉决策核心：FAO-56 允许亏缺阈值法 + 灌量/时长 + 约束条件。

阈值：θ_start = θfc − p·(θfc − θwp)，p、Z_r、Kc 随作物生育期变化。
灌量：D = (θ_target − θ_now)/100 · Z_r · p_wet / η  [mm]
      V = D/1000 · A                                    [m³]
      T = V·1000 / Q_emitters                           [h → s]
"""
from __future__ import annotations

import datetime as dt
from typing import List, Optional

from .models import Decision, DecideRequest
from .crop_model import stage_at
from .et0 import et0_hargreaves


def _root_zone_moisture(req: DecideRequest) -> float:
    if req.soil.moisture is not None:
        return req.soil.moisture
    zones = req.soil.zones or []
    if zones:
        # 按埋深（代表层厚）加权，简化为深度加权平均
        zones_sorted = sorted(zones, key=lambda z: z.depth)
        total_w, acc = 0.0, 0.0
        prev = 0
        for z in zones_sorted:
            # 层 (prev, z.depth] 的含水率取本层传感器读数
            w = max(1, z.depth - prev)
            acc += z.moist * w
            total_w += w
            prev = z.depth
        return acc / total_w
    raise ValueError("必须提供 soil.moisture 或 soil.zones")


def decide(req: DecideRequest, now: Optional[dt.datetime] = None) -> Decision:
    now = now or dt.datetime.now()
    reasons: List[str] = []

    # ---- 1. 作物生育期参数 ----
    sp = stage_at(req.crop.stages, req.crop.daysAfterSowing)

    # ---- 2. 动态阈值 ----
    theta_start = round(req.soil.thetaFc - sp.p * (req.soil.thetaFc - req.soil.thetaWp), 2)
    theta_target = round(req.soil.thetaFc - req.field.targetOffsetPct, 2)
    moisture = round(_root_zone_moisture(req), 2)

    # ---- 3. ET0 / ETc（用于降雨 SKIP 判断与解释） ----
    w = req.weather
    doy = w.dayOfYear or now.timetuple().tm_yday
    et0 = etc = None
    if w.tMax is not None and w.tMin is not None:
        t_mean = w.airTemp if w.airTemp is not None else (w.tMax + w.tMin) / 2.0
        et0 = round(et0_hargreaves(t_mean, w.tMax, w.tMin, doy, w.latitude), 2)
        etc = round(et0 * sp.kc, 2)

    base = Decision(
        decision="HOLD", stage=sp.name, kc=sp.kc, p=sp.p, zrMm=sp.zr_mm,
        thetaStart=theta_start, thetaTarget=theta_target, moisture=moisture,
        et0MmDay=et0, etcMmDay=etc,
    )

    # ---- 4. 硬性禁止条件（FORBID）：联锁窗口 ----
    lim = req.limits
    if moisture >= lim.hardMax:
        base.decision = "FORBID"
        reasons.append(f"湿度 {moisture}% ≥ 硬上限 {lim.hardMax}%，禁止开阀（防涝/深层渗漏）")
        base.reasons = reasons
        return base
    if moisture <= lim.hardMin and lim.hardMin > 0:
        base.decision = "FORBID"
        reasons.append(f"湿度 {moisture}% ≤ 硬下限 {lim.hardMin}%，传感器疑似异常，禁止自动灌溉")
        base.reasons = reasons
        return base
    if lim.ec is not None and lim.ecMax is not None and lim.ec > lim.ecMax:
        base.decision = "FORBID"
        reasons.append(f"EC {lim.ec} mS/cm > 上限 {lim.ecMax}，停止注肥/灌溉以防盐害")
        base.reasons = reasons
        return base
    if lim.ph is not None:
        if (lim.phMin is not None and lim.ph < lim.phMin) or \
           (lim.phMax is not None and lim.ph > lim.phMax):
            base.decision = "FORBID"
            reasons.append(f"pH {lim.ph} 超出窗口 [{lim.phMin}, {lim.phMax}]，禁止注肥")
            base.reasons = reasons
            return base

    # ---- 5. 最小作业间隔防抖 ----
    if lim.lastIrrigAgoH is not None and lim.lastIrrigAgoH < lim.minIntervalH:
        base.decision = "HOLD"
        reasons.append(
            f"距上次灌溉 {lim.lastIrrigAgoH:.2f}h < 最小间隔 {lim.minIntervalH}h，保持等待")
        base.reasons = reasons
        return base

    # ---- 6. 降雨跳过：未来 N 日净雨可补足当前亏缺 ----
    # 当前根区水分亏缺（mm，相对灌到 θ_target）
    deficit_now = max(0.0, (theta_target - moisture) / 100.0 * sp.zr_mm * req.field.wetRatio)
    forecast = list(w.rainForecastMm or [])
    # 有效降雨系数取 0.8（FAO 经验）
    effective_rain = round(sum(forecast) * 0.8, 1)
    if forecast and deficit_now > 1.0:
        # 净雨可补足当前亏缺，或过程雨量达到配置阈值且足以覆盖近期需水 → 跳过
        rain_covers_need = effective_rain >= deficit_now
        rain_heavy = max(forecast) >= req.field.rainSkipMm and etc is not None and \
            effective_rain >= etc * len(forecast) * 0.7
        if rain_covers_need or rain_heavy:
            base.decision = "SKIP"
            reasons.append(
                f"未来 {len(forecast)} 日有效降雨约 {effective_rain}mm，可覆盖亏缺 "
                f"{round(deficit_now, 1)}mm，雨养延迟")
            base.reasons = reasons
            return base

    # ---- 7. 阈值判据 ----
    if moisture > theta_start:
        base.decision = "HOLD"
        reasons.append(
            f"湿度 {moisture}% > 启动阈值 θ_start {theta_start}%（p={sp.p}, 生育期={sp.name}），无需灌溉")
        base.reasons = reasons
        return base

    # ---- 8. IRRIGATE：计算灌量与时长 ----
    reasons.append(f"湿度 {moisture}% ≤ 启动阈值 θ_start {theta_start}%（p={sp.p}, 生育期={sp.name}）")
    d_need = (theta_target - moisture) / 100.0 * sp.zr_mm * req.field.wetRatio / req.field.efficiency
    d_need = max(0.0, d_need)

    # ---- 8b. 日需水曲线钳制（显式传入 dailyWaterNeedMm 时启用）：
    # 单日累计灌水不超过 日需水×安全系数 − 当日已灌 − 当日有效降雨
    clamp_reason = None
    if req.crop.dailyWaterNeedMm is not None:
        cap_mm = (req.crop.dailyWaterNeedMm * req.field.dailyNeedCapFactor
                  - w.irrigatedTodayMm - w.rainfallToday * 0.8)
        if cap_mm <= 0:
            base.decision = "HOLD"
            reasons.append(
                f"今日需水 {req.crop.dailyWaterNeedMm}mm 已满足"
                f"（已灌 {w.irrigatedTodayMm}mm + 有效降雨 {round(w.rainfallToday * 0.8, 1)}mm），保持")
            base.reasons = reasons
            return base
        if d_need > cap_mm:
            reasons.append(
                f"需补水深 {round(d_need, 2)}mm 超当日需水余量 {round(cap_mm, 2)}mm"
                f"（曲线 {req.crop.dailyWaterNeedMm}mm/d × {req.field.dailyNeedCapFactor}），按曲线钳制")
            d_need = cap_mm
            clamp_reason = "DAILY_NEED_CAP"

    volume = d_need / 1000.0 * req.field.areaM2
    if req.field.emitterTotalLph <= 0:
        raise ValueError("emitterTotalLph 必须为正")
    duration_sec = int(round(volume * 1000.0 / req.field.emitterTotalLph * 3600.0))

    # 单次最大时长削顶（少量多次），超出则本轮灌到上限，下轮继续
    if duration_sec > lim.maxDurationSec:
        ratio = lim.maxDurationSec / duration_sec
        duration_sec = lim.maxDurationSec
        volume = round(volume * ratio, 3)
        d_need = round(d_need * ratio, 2)
        clamp_reason = "DURATION_LIMIT"
        reasons.append(
            f"理论时长超限，削顶至 {lim.maxDurationSec}s，计划 {volume}m³，余量下轮续灌")

    base.decision = "IRRIGATE"
    base.deficitMm = round(d_need, 2)
    base.volumeM3 = round(volume, 3)
    base.durationSec = duration_sec
    base.clampReason = clamp_reason
    if etc is not None:
        reasons.append(f"当前 ET0≈{et0}mm/d, ETc(Kc={sp.kc})≈{etc}mm/d")
    reasons.append(
        f"根区深 {sp.zr_mm}mm，需补水深 {base.deficitMm}mm → {base.volumeM3}m³ / "
        f"{duration_sec // 60}min（湿润比 {req.field.wetRatio}, η={req.field.efficiency}）")
    base.reasons = reasons
    return base
