"""回调 Spring Boot：POST 融合决策到 /api/growth/callback。"""
from __future__ import annotations

import logging
from typing import Any, Dict

import httpx

log = logging.getLogger("decision-service.callback")


async def post_decision(client: httpx.AsyncClient, backend_url: str,
                        payload: Dict[str, Any]) -> Dict[str, Any]:
    """回调执行决策；返回 Spring 的 ApiResponse.data。异常向上抛由调度器兜底。"""
    url = f"{backend_url.rstrip('/')}/api/growth/callback"
    r = await client.post(url, json=payload)
    r.raise_for_status()
    body = r.json()
    log.info("callback field=%s decisionId=%s -> code=%s msg=%s",
             payload.get("fieldId"), payload.get("decisionId"),
             body.get("code"), body.get("msg"))
    return body
