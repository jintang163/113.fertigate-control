"""回调链路测试：调度器拉取田块 → 融合决策 → POST /api/growth/callback。"""
from __future__ import annotations

import asyncio
import datetime as dt
import json

import httpx

from app.scheduler import GrowthScheduler
from app.variety_store import VarietyStore

BACKEND = "http://test-backend"


def _field(growth_enabled=True):
    return {
        "id": 1, "name": "1号棚", "cropCode": "tomato", "cropVariety": "tomato",
        "areaM2": 2000, "emitterTotalLph": 2400,
        "sowingDate": (dt.date.today() - dt.timedelta(days=60)).isoformat(),
        "config": {"growthModelEnabled": growth_enabled, "thetaFc": 30.0,
                   "thetaWp": 12.0, "hardMaxOffsetPct": 3.0,
                   "maxDurationSec": 900, "minIntervalH": 0.5},
    }


def _mock_client(captured: list, fields):
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/fields":
            return httpx.Response(200, json={"code": 0, "data": fields})
        if path == "/api/fields/1/status":
            return httpx.Response(200, json={"code": 0, "data": {
                "moisture": 20.0, "ec": 1.2, "ph": 6.0, "valveState": "CLOSED"}})
        if path == "/api/ledger/summary":
            return httpx.Response(200, json={"code": 0, "data": {"waterM3": 0.0}})
        if path == "/api/ledger":
            return httpx.Response(200, json={"code": 0, "data": {"records": []}})
        if path == "/api/growth/callback":
            captured.append(json.loads(request.content))
            return httpx.Response(200, json={"code": 0, "data": {"accepted": True}})
        return httpx.Response(404, json={"code": 404, "msg": path})

    return httpx.AsyncClient(transport=httpx.MockTransport(handler),
                             base_url=BACKEND)


def test_cycle_decides_and_callbacks(tmp_path):
    async def _run():
        store = VarietyStore(str(tmp_path / "v.db"))
        captured: list = []
        client = _mock_client(captured, [_field()])
        sched = GrowthScheduler(BACKEND, store, client=client)
        try:
            results = await sched.run_cycle()
        finally:
            await client.aclose()
            store.close()
        return results, captured

    results, captured = asyncio.run(_run())

    assert len(results) == 1
    r = results[0]
    assert r["fieldId"] == 1
    assert r["irrigation"] == "OPEN"          # 湿度 20 < θ_start(花期末 21.9)
    assert r["fertigate"] is True             # EC 1.2 < 花期下限 1.5

    assert len(captured) == 1
    payload = captured[0]
    assert payload["fieldId"] == 1
    assert payload["decisionId"]
    assert payload["irrigation"]["action"] == "OPEN"
    assert payload["irrigation"]["volumeM3"] > 0
    assert payload["irrigation"]["durationSec"] > 0
    assert payload["fertigation"]["shouldFertilize"] is True
    assert payload["fertigation"]["fertilizerL"] > 0
    assert payload["fertigation"]["npkKg"]["n"] > 0


def test_cycle_skips_fields_without_growth_model(tmp_path):
    async def _run():
        store = VarietyStore(str(tmp_path / "v.db"))
        captured: list = []
        client = _mock_client(captured, [_field(growth_enabled=False)])
        sched = GrowthScheduler(BACKEND, store, client=client)
        try:
            results = await sched.run_cycle()
        finally:
            await client.aclose()
            store.close()
        return results, captured

    results, captured = asyncio.run(_run())

    assert results == []
    assert captured == []                     # 未开启生长模型的田块不回调
