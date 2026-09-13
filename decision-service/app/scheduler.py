"""生长模型调度器：定时拉取 Spring Boot 田块状态 → 融合决策 → 回调执行。

每轮流程（仅处理 field.config.growthModelEnabled=true 的田块）：
  GET /api/fields                 → 田块与配置（品种、面积、阈值参数）
  GET /api/fields/{id}/status     → 实时湿度 / EC / pH
  GET /api/ledger/summary?date=   → 当日已灌水量（日需水曲线钳制）
  GET /api/ledger?kind=FERTIGATION → 距上次施肥天数
  → fused_irrigation + decide_fertigation
  → POST /api/growth/callback（幂等 decisionId）
单田块异常不中断整轮。
"""
from __future__ import annotations

import asyncio
import datetime as dt
import logging
import uuid
from typing import Any, Dict, List, Optional

import httpx

from .callback import post_decision
from .curves import params_at
from .fertigation import decide_fertigation
from .growth_irrigation import fused_irrigation
from .growth_models import (FertigationAction, FertigationRequest,
                            GrowthCallback, IrrigationAction)
from .variety_store import VarietyStore

log = logging.getLogger("decision-service.scheduler")


def _days_after_sowing(sowing_date: Optional[str]) -> int:
    if not sowing_date:
        return 0
    try:
        d = dt.date.fromisoformat(str(sowing_date)[:10])
        return max(0, (dt.date.today() - d).days)
    except ValueError:
        return 0


def _days_since(iso_ts: Optional[str]) -> float:
    if not iso_ts:
        return 1.0
    try:
        t = dt.datetime.fromisoformat(str(iso_ts).replace("Z", "+00:00"))
        if t.tzinfo is None:
            t = t.replace(tzinfo=dt.timezone.utc)
        delta = dt.datetime.now(dt.timezone.utc) - t
        return max(0.5, delta.total_seconds() / 86400.0)
    except ValueError:
        return 1.0


class GrowthScheduler:
    def __init__(self, backend_url: str, store: VarietyStore,
                 interval_sec: int = 300,
                 client: Optional[httpx.AsyncClient] = None):
        self.backend_url = backend_url.rstrip("/")
        self.store = store
        self.interval_sec = interval_sec
        self._client = client
        self._owns_client = client is None

    async def _get(self, path: str, params: Optional[dict] = None) -> Any:
        r = await self._client.get(f"{self.backend_url}{path}", params=params)
        r.raise_for_status()
        return r.json().get("data")

    async def run_forever(self) -> None:
        if self._client is None:
            self._client = httpx.AsyncClient(timeout=10.0)
        log.info("growth scheduler started: backend=%s interval=%ss",
                 self.backend_url, self.interval_sec)
        try:
            while True:
                try:
                    results = await self.run_cycle()
                    log.info("growth cycle done: %s", results)
                except Exception as e:  # 整轮失败（如后端不可达）下轮继续
                    log.warning("growth cycle failed: %s", e)
                await asyncio.sleep(self.interval_sec)
        finally:
            if self._owns_client and self._client is not None:
                await self._client.aclose()

    async def run_cycle(self) -> List[Dict[str, Any]]:
        if self._client is None:
            self._client = httpx.AsyncClient(timeout=10.0)
        fields = await self._get("/api/fields") or []
        results: List[Dict[str, Any]] = []
        for f in fields:
            cfg = f.get("config") or {}
            if not cfg.get("growthModelEnabled"):
                continue
            try:
                results.append(await self._process_field(f))
            except Exception as e:
                log.warning("field %s growth decision failed: %s", f.get("id"), e)
                results.append({"fieldId": f.get("id"), "error": str(e)})
        return results

    async def _process_field(self, f: Dict[str, Any]) -> Dict[str, Any]:
        field_id = f["id"]
        cfg = f.get("config") or {}
        status = await self._get(f"/api/fields/{field_id}/status") or {}

        variety = (self.store.get(f.get("cropVariety") or "")
                   or self.store.get(f.get("cropCode") or ""))
        if variety is None:
            return {"fieldId": field_id, "skipped":
                    f"品种库无 {f.get('cropVariety') or f.get('cropCode')}"}

        moisture = status.get("moisture")
        if moisture is None:
            return {"fieldId": field_id, "skipped": "无有效墒情数据"}

        days = _days_after_sowing(f.get("sowingDate"))
        theta_fc = float(cfg.get("thetaFc") or status.get("thetaFc") or 30.0)
        theta_wp = float(cfg.get("thetaWp") or status.get("thetaWp") or 12.0)
        hard_max = theta_fc + float(cfg.get("hardMaxOffsetPct") or 3.0)
        area_m2 = float(f.get("areaM2") or 0)
        emitter_lph = float(f.get("emitterTotalLph") or 0)
        # 距上次灌溉小时数（最小间隔防抖）
        last_irrig_ago_h = None
        if status.get("lastIrrigTime"):
            last_irrig_ago_h = _days_since(status["lastIrrigTime"]) * 24.0

        # 当日已灌水量（mm）← 台账汇总 waterM3：1mm×1m² = 1L = 0.001m³
        irrigated_today_mm = 0.0
        try:
            today = dt.date.today().isoformat()
            summary = await self._get("/api/ledger/summary", {"date": today}) or {}
            water_m3 = float(summary.get("waterM3") or 0.0)
            if area_m2 > 0:
                irrigated_today_mm = round(water_m3 * 1000.0 / area_m2, 2)
        except Exception as e:
            log.debug("ledger summary unavailable: %s", e)

        irrigation: IrrigationAction = fused_irrigation(
            variety=variety, days_after_sowing=days,
            moisture=float(moisture), theta_fc=theta_fc, theta_wp=theta_wp,
            area_m2=area_m2, emitter_total_lph=emitter_lph or 1.0,
            hard_max=hard_max,
            hard_min=float(cfg.get("hardMin") or 0.0),
            wet_ratio=float(cfg.get("wetRatio") or 1.0),
            efficiency=float(cfg.get("efficiency") or 0.9),
            max_duration_sec=int(cfg.get("maxDurationSec") or 900),
            min_interval_h=float(cfg.get("minIntervalH") or 0.5),
            last_irrig_ago_h=last_irrig_ago_h,
            irrigated_today_mm=irrigated_today_mm,
        )

        # 距上次施肥天数 ← 最近一条 FERTIGATION 台账
        days_since_fert = 1.0
        try:
            ledger = await self._get("/api/ledger",
                                     {"fieldId": field_id, "kind": "FERTIGATION",
                                      "size": 1}) or {}
            records = ledger.get("records") or []
            if records:
                days_since_fert = _days_since(records[0].get("endTime")
                                              or records[0].get("startTime"))
        except Exception as e:
            log.debug("ledger query unavailable: %s", e)

        gp = params_at(variety, days)
        fert_req = FertigationRequest(
            varietyCode=variety.code, daysAfterSowing=days, areaM2=area_m2,
            ec=status.get("ec"), ph=status.get("ph"),
            daysSinceLastFert=days_since_fert,
            irrigationWaterM3=irrigation.volumeM3 if irrigation.action == "OPEN" else None,
        )
        fert = decide_fertigation(fert_req, variety)
        fert_action = FertigationAction(
            shouldFertilize=fert.decision == "FERTIGATE",
            fertilizerL=fert.fertilizerL, npkKg=fert.npkKg,
            injectRatioPct=fert.injectRatioPct, reasons=fert.reasons)

        payload = GrowthCallback(
            decisionId=uuid.uuid4().hex,
            fieldId=field_id,
            decidedAt=dt.datetime.now(dt.timezone.utc).isoformat(),
            irrigation=irrigation, fertigation=fert_action,
        )
        resp = await post_decision(self._client, self.backend_url,
                                   payload.model_dump())
        return {"fieldId": field_id, "stage": gp.stage.name,
                "irrigation": irrigation.action, "volumeM3": irrigation.volumeM3,
                "fertigate": fert_action.shouldFertilize,
                "callback": resp.get("data", resp)}
