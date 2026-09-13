"""FastAPI 入口：/healthz、/decide、生长模型（品种/曲线/施肥决策/回调调度）。"""
from __future__ import annotations

import asyncio
import logging
import os
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, HTTPException

from .curves import fert_curve, water_curve
from .fertigation import decide_fertigation
from .growth_models import FertigationDecision, FertigationRequest, Variety
from .irrigation import decide
from .models import DecideRequest, Decision
from .scheduler import GrowthScheduler
from .variety_store import VarietyStore

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("decision-service")

VARIETY_DB_PATH = os.environ.get("VARIETY_DB_PATH", "./data/varieties.db")
BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")
EVAL_INTERVAL_SEC = int(os.environ.get("GROWTH_EVAL_INTERVAL_SEC", "300"))
SCHEDULER_ENABLED = os.environ.get("GROWTH_SCHEDULER_ENABLED", "0") == "1"

store = VarietyStore(VARIETY_DB_PATH)
scheduler = GrowthScheduler(BACKEND_URL, store, EVAL_INTERVAL_SEC)


@asynccontextmanager
async def lifespan(_: FastAPI):
    task: Optional[asyncio.Task] = None
    if SCHEDULER_ENABLED:
        task = asyncio.create_task(scheduler.run_forever())
        log.info("growth scheduler enabled, backend=%s interval=%ss",
                 BACKEND_URL, EVAL_INTERVAL_SEC)
    yield
    if task is not None:
        task.cancel()
    store.close()


app = FastAPI(title="作物生长模型决策服务", version="2.0.0", lifespan=lifespan)


@app.get("/healthz")
def healthz() -> dict:
    return {"status": "ok"}


# ---------------------------------------------------------------- 灌溉决策（既有）

@app.post("/decide", response_model=Decision)
def post_decide(req: DecideRequest) -> Decision:
    try:
        result = decide(req)
    except ValueError as e:
        # 例如既无 soil.moisture 也无 zones：无法计算根区湿度
        raise HTTPException(status_code=400, detail=str(e)) from e
    log.info("decide=%s stage=%s θ=%s θ_start=%s V=%sm3 T=%ss",
             result.decision, result.stage, result.moisture, result.thetaStart,
             result.volumeM3, result.durationSec)
    return result


# ---------------------------------------------------------------- 品种与生育期管理

@app.get("/api/varieties")
def list_varieties() -> list[Variety]:
    return store.list()


@app.post("/api/varieties", status_code=201)
def create_variety(v: Variety) -> Variety:
    if not v.stages:
        raise HTTPException(status_code=400, detail="stages 不能为空")
    return store.upsert(v)


@app.get("/api/varieties/{code}")
def get_variety(code: str) -> Variety:
    v = store.get(code)
    if v is None:
        raise HTTPException(status_code=404, detail=f"品种不存在: {code}")
    return v


@app.put("/api/varieties/{code}")
def update_variety(code: str, v: Variety) -> Variety:
    if store.get(code) is None:
        raise HTTPException(status_code=404, detail=f"品种不存在: {code}")
    v.code = code
    if not v.stages:
        raise HTTPException(status_code=400, detail="stages 不能为空")
    return store.upsert(v)


@app.delete("/api/varieties/{code}")
def delete_variety(code: str) -> dict:
    if not store.delete(code):
        raise HTTPException(status_code=404, detail=f"品种不存在: {code}")
    return {"deleted": code}


# ---------------------------------------------------------------- 需水 / 需肥曲线

@app.get("/api/varieties/{code}/water-curve")
def get_water_curve(code: str, et0: Optional[float] = None,
                    days: Optional[int] = None) -> list:
    """日需水曲线（mm/day）：ET0 缺省时用品种阶段基准值。"""
    v = store.get(code)
    if v is None:
        raise HTTPException(status_code=404, detail=f"品种不存在: {code}")
    return water_curve(v, et0_mm_day=et0, days=days)


@app.get("/api/varieties/{code}/fert-curve")
def get_fert_curve(code: str, days: Optional[int] = None) -> list:
    """日需肥曲线（N/P/K kg/ha/day）。"""
    v = store.get(code)
    if v is None:
        raise HTTPException(status_code=404, detail=f"品种不存在: {code}")
    return fert_curve(v, days=days)


# ---------------------------------------------------------------- 施肥融合决策

@app.post("/decide/fertigation", response_model=FertigationDecision)
def post_decide_fertigation(req: FertigationRequest) -> FertigationDecision:
    v = store.get(req.varietyCode)
    if v is None:
        raise HTTPException(status_code=404, detail=f"品种不存在: {req.varietyCode}")
    result = decide_fertigation(req, v)
    log.info("fertigation=%s variety=%s stage=%s ec=%s L=%s",
             result.decision, v.code, result.stage, result.ec, result.fertilizerL)
    return result


# ---------------------------------------------------------------- 回调调度（手动触发）

@app.post("/growth/run-cycle")
async def run_growth_cycle() -> list:
    """立即执行一轮 生长模型评估→回调（调试用；定时调度由 lifespan 驱动）。"""
    return await scheduler.run_cycle()
