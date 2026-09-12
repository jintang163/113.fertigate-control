"""决策服务单元测试：阈值/灌量/削顶/降雨/联锁/生育期插值/分层湿度。"""
from __future__ import annotations

from app.irrigation import decide
from app.models import (
    Crop, CropStage, DecideRequest, FieldInfo, Limits, Soil, SoilZone, Weather,
)

TOMATO = [
    CropStage(name="initial", startDay=0, endDay=30,
              startKc=0.6, endKc=0.6, startP=0.5, endP=0.5, zrMm=200),
    CropStage(name="development", startDay=31, endDay=60,
              startKc=0.6, endKc=1.15, startP=0.5, endP=0.4, zrMm=400),
    CropStage(name="mid", startDay=61, endDay=100,
              startKc=1.15, endKc=1.15, startP=0.4, endP=0.4, zrMm=700),
    CropStage(name="late", startDay=101, endDay=130,
              startKc=1.15, endKc=0.8, startP=0.4, endP=0.5, zrMm=700),
]


def _req(moisture=21.0, ec=1.7, ph=6.4, rain=None, day=75,
         max_dur=900, last_ago=6.0):
    return DecideRequest(
        crop=Crop(code="tomato", stages=TOMATO, daysAfterSowing=day),
        soil=Soil(thetaFc=30.0, thetaWp=12.0, moisture=moisture),
        weather=Weather(tMax=32, tMin=21, rainfallToday=0,
                        rainForecastMm=rain or [], latitude=34.5, dayOfYear=200),
        field=FieldInfo(areaM2=2000, wetRatio=0.8, efficiency=0.9,
                        emitterTotalLph=2400, rainSkipMm=5),
        limits=Limits(hardMax=33.0, hardMin=8.0, ec=ec, ecMin=1.2, ecMax=2.6,
                      ph=ph, phMin=5.5, phMax=7.5, maxDurationSec=max_dur,
                      minIntervalH=0.5, lastIrrigAgoH=last_ago),
    )


def test_theta_start_mid_season_and_hold():
    # mid 期 p=0.4 → θ_start = 30 - 0.4*18 = 22.8
    r = decide(_req(moisture=25.0))
    assert r.thetaStart == 22.8
    assert r.decision == "HOLD"
    assert r.stage == "mid"


def test_irrigate_then_clamped_by_max_duration():
    r = decide(_req(moisture=20.0))
    assert r.decision == "IRRIGATE"
    # 理论: D=(29-20)/100*700*0.8/0.9 = 56.0mm, V=56/1000*2000 = 112m3
    # 理论时长 112000L/2400L/h = 46.7h，削顶 900s
    assert r.durationSec == 900
    assert r.clampReason == "DURATION_LIMIT"
    # 2400 L/h = 40 L/min → 15min 灌 600L = 0.6 m3
    assert r.volumeM3 == 0.6
    # 削顶后水量与补水深按比例: 0.6/112*56 = 0.3mm
    assert r.deficitMm == 0.3


def test_irrigate_without_clamp():
    # 小田块 + 大流量，时长落在 maxDurationSec 内
    req = _req(moisture=22.0, max_dur=7200)
    req.field.emitterTotalLph = 12000
    req.field.areaM2 = 100
    r = decide(req)
    assert r.decision == "IRRIGATE"
    assert r.clampReason is None
    # D=(29-22)/100*700*0.8/0.9 = 43.56mm, V=4.356m3, T=4356/12000*3600=1307s
    assert r.durationSec == 1307
    assert round(r.volumeM3, 2) == 4.36


def test_hold_just_above_threshold():
    assert decide(_req(moisture=22.9)).decision == "HOLD"


def test_forbid_hard_max():
    r = decide(_req(moisture=34.0))
    assert r.decision == "FORBID"
    assert "硬上限" in r.reasons[0]


def test_forbid_ec_over():
    assert decide(_req(moisture=20.0, ec=3.1)).decision == "FORBID"


def test_forbid_ph_out_of_window():
    assert decide(_req(moisture=20.0, ph=4.8)).decision == "FORBID"


def test_skip_when_rain_covers_deficit():
    # 亏缺 (29-20)%*700*0.8 = 50.4mm；有效雨 80*0.8=64mm → SKIP
    r = decide(_req(moisture=20.0, rain=[30, 30, 20]))
    assert r.decision == "SKIP"


def test_no_skip_when_rain_small():
    assert decide(_req(moisture=20.0, rain=[1, 1])).decision == "IRRIGATE"


def test_min_interval_holds():
    assert decide(_req(moisture=15.0, last_ago=0.2)).decision == "HOLD"


def test_stage_interpolation_development():
    # 第45天位于 development(31-60d): f=14/29 → p≈0.4517, θ_start≈21.87
    r = decide(_req(moisture=30.0, day=45))
    assert r.stage == "development"
    assert abs(r.thetaStart - 21.87) < 0.01


def test_root_zone_layer_weighted():
    req = _req()
    req.soil.moisture = None
    req.soil.zones = [SoilZone(depth=10, moist=24.0), SoilZone(depth=30, moist=20.0)]
    r = decide(req)
    # 0-10cm 权重10=24；10-30cm 权重20=20 → 21.33
    assert abs(r.moisture - 21.33) < 0.05
    assert r.decision == "IRRIGATE"


def test_et0_positive_and_etc_uses_kc():
    r = decide(_req(moisture=25.0))
    assert r.et0MmDay is not None and r.et0MmDay > 0
    # mid kc=1.15
    assert abs(r.etcMmDay - r.et0MmDay * 1.15) < 0.05
