"""作物生育期模型：按播后天数在 stages 间线性插值 Kc / p / Z_r。"""
from __future__ import annotations

from dataclasses import dataclass
from typing import List

from .models import CropStage


@dataclass
class StageParams:
    name: str
    kc: float
    p: float
    zr_mm: float


def _interp(day: int, s: CropStage) -> float:
    """阶段内按天线性插值系数（startDay 处取 start 值，endDay 处取 end 值）。"""
    if s.endDay <= s.startDay:
        return s.startKc
    f = (day - s.startDay) / (s.endDay - s.startDay)
    f = min(1.0, max(0.0, f))
    return s.startKc + f * (s.endKc - s.startKc)


def stage_at(stages: List[CropStage], day: int) -> StageParams:
    if not stages:
        raise ValueError("crop stages 不能为空")
    ordered = sorted(stages, key=lambda s: s.startDay)
    cur = ordered[-1]
    for s in ordered:
        if s.startDay <= day <= s.endDay:
            cur = s
            break
        if day < s.startDay:
            cur = s
            break
    f = 0.0
    if cur.endDay > cur.startDay:
        f = min(1.0, max(0.0, (day - cur.startDay) / (cur.endDay - cur.startDay)))
    kc = cur.startKc + f * (cur.endKc - cur.startKc)
    p = cur.startP + f * (cur.endP - cur.startP)
    return StageParams(name=cur.name, kc=round(kc, 4), p=round(p, 4), zr_mm=cur.zrMm)
