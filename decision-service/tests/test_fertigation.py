"""施肥融合决策测试：EC/pH 窗口 / 需肥曲线定量 / 注肥比折算。"""
from __future__ import annotations

import pytest

from app.fertigation import decide_fertigation
from app.growth_models import FertigationRequest
from app.variety_store import VarietyStore


@pytest.fixture()
def tomato(tmp_path):
    s = VarietyStore(str(tmp_path / "v.db"))
    yield s.get("tomato")
    s.close()


def _req(ec=1.2, ph=6.0, day=75, area=2000.0, days_since=2.0, water_m3=None):
    return FertigationRequest(varietyCode="tomato", daysAfterSowing=day,
                              areaM2=area, ec=ec, ph=ph,
                              daysSinceLastFert=days_since,
                              irrigationWaterM3=water_m3)


def test_fertigate_when_ec_below_target(tomato):
    # 结果期 EC 目标 [1.8, 2.8]，实测 1.2 → 施肥
    r = decide_fertigation(_req(ec=1.2), tomato)
    assert r.decision == "FERTIGATE"
    assert r.stage == "fruiting"
    # N = 2.0 kg/ha/d × 0.2ha × 2d × 系数(1+0.5=1.5 封顶内) = 1.2kg
    assert r.npkKg["n"] == pytest.approx(1.2, abs=0.01)
    assert r.npkKg["k"] == pytest.approx(1.8, abs=0.01)
    # 肥液 = (1.2+0.36+1.8)/0.3 = 11.2 L
    assert r.fertilizerL == pytest.approx(11.2, abs=0.05)


def test_hold_when_ec_sufficient(tomato):
    r = decide_fertigation(_req(ec=2.0), tomato)
    assert r.decision == "HOLD"
    assert r.fertilizerL == 0.0


def test_hold_when_ec_missing(tomato):
    r = decide_fertigation(_req(ec=None), tomato)
    assert r.decision == "HOLD"
    assert "无 EC" in r.reasons[0]


def test_forbid_when_ec_too_high(tomato):
    r = decide_fertigation(_req(ec=3.2), tomato)
    assert r.decision == "FORBID"
    assert "盐害" in r.reasons[0]


def test_forbid_when_ph_out_of_window(tomato):
    r = decide_fertigation(_req(ec=1.2, ph=7.5), tomato)
    assert r.decision == "FORBID"
    assert "pH" in r.reasons[0]


def test_inject_ratio_from_irrigation_volume(tomato):
    r = decide_fertigation(_req(ec=1.2, water_m3=2.0), tomato)
    assert r.decision == "FERTIGATE"
    # 11.2L / 2000L = 0.56%
    assert r.injectRatioPct == pytest.approx(0.56, abs=0.01)


def test_inject_ratio_capped(tomato):
    # 极小水量 → 注肥比削顶到 maxInjectRatioPct
    req = _req(ec=1.2, water_m3=0.2)
    req.maxInjectRatioPct = 1.0
    r = decide_fertigation(req, tomato)
    assert r.injectRatioPct == 1.0
    assert any("削顶" in x for x in r.reasons)


def test_seedling_stage_uses_its_own_curve(tomato):
    r = decide_fertigation(_req(ec=0.8, day=10, days_since=1.0), tomato)
    assert r.decision == "FERTIGATE"
    assert r.stage == "seedling"
    # N = 0.8 × 0.2ha × 1d × 系数 → 远小于结果期
    assert r.npkKg["n"] < 0.3
