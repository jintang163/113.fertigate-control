"""灌溉融合决策（生长模型版）测试：需水曲线钳制 + 开/关输出。"""
from __future__ import annotations

import pytest

from app.growth_irrigation import fused_irrigation
from app.variety_store import VarietyStore


@pytest.fixture()
def tomato(tmp_path):
    s = VarietyStore(str(tmp_path / "v.db"))
    yield s.get("tomato")
    s.close()


def _call(tomato, moisture=20.0, irrigated_today=0.0, day=75):
    # 结果期: p=0.4 → θ_start=22.8；日需水基准 5.0mm/d
    return fused_irrigation(
        variety=tomato, days_after_sowing=day, moisture=moisture,
        theta_fc=30.0, theta_wp=12.0, area_m2=100.0, emitter_total_lph=12000,
        hard_max=33.0, hard_min=8.0, wet_ratio=1.0, efficiency=0.9,
        max_duration_sec=900, irrigated_today_mm=irrigated_today)


def test_open_with_duration_when_dry(tomato):
    r = _call(tomato, moisture=20.0)
    assert r.action == "OPEN"
    assert r.durationSec > 0 and r.volumeM3 > 0


def test_closed_when_wet(tomato):
    r = _call(tomato, moisture=25.0)
    assert r.action == "CLOSED"
    assert r.durationSec == 0


def test_daily_need_curve_clamps_volume(tomato):
    r = _call(tomato, moisture=20.0)
    # 理论需水 (29-20)%×700/0.9=70mm；曲线钳制 5.0×1.2=6mm
    # V = 6/1000×100 = 0.6m³, T = 600/12000×3600 = 180s
    assert r.volumeM3 == pytest.approx(0.6, abs=0.01)
    assert r.durationSec == 180
    assert any("曲线钳制" in x for x in r.reasons)


def test_closed_when_daily_need_already_met(tomato):
    # 当日已灌 6mm ≥ 5.0×1.2 → 今日需水已满足
    r = _call(tomato, moisture=20.0, irrigated_today=6.0)
    assert r.action == "CLOSED"
    assert any("已满足" in x for x in r.reasons)


def test_closed_on_hard_max(tomato):
    r = _call(tomato, moisture=34.0)
    assert r.action == "CLOSED"
    assert any("硬上限" in x for x in r.reasons)
