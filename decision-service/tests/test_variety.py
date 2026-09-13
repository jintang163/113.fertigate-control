"""品种库与曲线测试：种子数据 / CRUD / 需水需肥曲线。"""
from __future__ import annotations

import pytest

from app.curves import fert_curve, params_at, stage_at, water_curve
from app.growth_models import GrowthStage, Variety
from app.variety_store import VarietyStore


@pytest.fixture()
def store(tmp_path):
    s = VarietyStore(str(tmp_path / "varieties.db"))
    yield s
    s.close()


def test_seed_varieties_present(store):
    codes = [v.code for v in store.list()]
    assert "tomato" in codes and "cucumber" in codes
    tomato = store.get("tomato")
    assert [s.name for s in tomato.stages] == ["seedling", "flowering", "fruiting", "maturity"]
    assert tomato.stages[0].label == "苗期"


def test_upsert_and_delete(store):
    v = Variety(code="pepper", name="辣椒", cropCode="pepper", stages=[
        GrowthStage(name="seedling", label="苗期", startDay=0, endDay=25,
                    startKc=0.5, endKc=0.5, startP=0.5, endP=0.5, zrMm=150,
                    waterMmDay=1.0, nKgHaDay=0.5, pKgHaDay=0.2, kKgHaDay=0.7,
                    ecMin=1.0, ecMax=2.0, phMin=5.5, phMax=6.5),
    ])
    store.upsert(v)
    got = store.get("pepper")
    assert got.name == "辣椒" and got.stages[0].kKgHaDay == 0.7
    # 更新同名 code
    v.name = "彩椒"
    store.upsert(v)
    assert store.get("pepper").name == "彩椒"
    assert store.delete("pepper") is True
    assert store.get("pepper") is None
    assert store.delete("pepper") is False


def test_stage_at_boundaries(store):
    tomato = store.get("tomato")
    assert stage_at(tomato.stages, 0).name == "seedling"
    assert stage_at(tomato.stages, 30).name == "seedling"
    assert stage_at(tomato.stages, 31).name == "flowering"
    assert stage_at(tomato.stages, 75).name == "fruiting"
    assert stage_at(tomato.stages, 200).name == "maturity"   # 超出取最后阶段


def test_water_curve_uses_stage_baseline_without_et0(store):
    tomato = store.get("tomato")
    curve = water_curve(tomato)
    assert len(curve) == 131                       # 0..130 天
    assert curve[10].stage == "seedling"
    assert curve[10].etcMmDay == 1.5               # 苗期基准
    assert curve[75].etcMmDay == 5.0               # 结果期基准


def test_water_curve_with_et0_uses_kc(store):
    tomato = store.get("tomato")
    curve = water_curve(tomato, et0_mm_day=4.0)
    # 结果期 Kc=1.15 → 4.6 mm/day
    assert abs(curve[75].etcMmDay - 4.6) < 0.01
    # 花期中段 Kc 在 0.6→1.15 间插值
    assert 0.6 * 4.0 < curve[45].etcMmDay < 1.15 * 4.0


def test_fert_curve_daily_npk(store):
    tomato = store.get("tomato")
    curve = fert_curve(tomato)
    assert curve[75].nKgHaDay == 2.0
    assert curve[75].kKgHaDay == 3.0
    assert curve[10].nKgHaDay == 0.8


def test_params_at_interpolates_kc(store):
    tomato = store.get("tomato")
    p = params_at(tomato, 45, et0_mm_day=5.0)
    assert p.stage.name == "flowering"
    # 第45天: f=(45-31)/29≈0.4828 → kc≈0.6+0.4828*0.55≈0.8655
    assert abs(p.kc - 0.8655) < 0.01
    assert abs(p.water_need_mm_day - p.kc * 5.0) < 0.01
