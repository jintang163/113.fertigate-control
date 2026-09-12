"""FastAPI 入口：/healthz 与 /decide。"""
from __future__ import annotations

import logging

from fastapi import FastAPI, HTTPException

from .irrigation import decide
from .models import DecideRequest, Decision

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("decision-service")

app = FastAPI(title="作物模型灌溉决策服务", version="1.0.0")


@app.get("/healthz")
def healthz() -> dict:
    return {"status": "ok"}


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
