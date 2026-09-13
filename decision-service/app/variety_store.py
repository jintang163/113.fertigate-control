"""作物品种与生育期库（SQLite 持久化）。

品种 = 作物类型 + 栽培品种；每个品种挂一组生育期阶段（苗期/花期/结果期/成熟期…）。
阶段内同时携带灌溉模型参数（Kc / p / Zr）与水肥需求参数
（日需水 mm/day、N/P/K kg/ha/day、EC/pH 目标带），供曲线计算与融合决策使用。
"""
from __future__ import annotations

import datetime as dt
import json
import os
import sqlite3
import threading
from typing import List, Optional

from .growth_models import GrowthStage, Variety

_SCHEMA = """
CREATE TABLE IF NOT EXISTS variety (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    crop_code TEXT NOT NULL,
    stages_json TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
"""


def _seed_varieties() -> List[Variety]:
    """内置品种：番茄 / 黄瓜（设施滴灌典型参数，可经 API 修改）。"""
    tomato = Variety(
        code="tomato", name="番茄", cropCode="tomato",
        stages=[
            GrowthStage(name="seedling", label="苗期", startDay=0, endDay=30,
                        startKc=0.6, endKc=0.6, startP=0.5, endP=0.5, zrMm=200,
                        waterMmDay=1.5, nKgHaDay=0.8, pKgHaDay=0.3, kKgHaDay=1.0,
                        ecMin=1.2, ecMax=2.0, phMin=5.5, phMax=6.5),
            GrowthStage(name="flowering", label="花期", startDay=31, endDay=60,
                        startKc=0.6, endKc=1.15, startP=0.5, endP=0.45, zrMm=400,
                        waterMmDay=3.5, nKgHaDay=1.5, pKgHaDay=0.5, kKgHaDay=2.0,
                        ecMin=1.5, ecMax=2.5, phMin=5.5, phMax=6.5),
            GrowthStage(name="fruiting", label="结果期", startDay=61, endDay=100,
                        startKc=1.15, endKc=1.15, startP=0.45, endP=0.4, zrMm=700,
                        waterMmDay=5.0, nKgHaDay=2.0, pKgHaDay=0.6, kKgHaDay=3.0,
                        ecMin=1.8, ecMax=2.8, phMin=5.5, phMax=6.8),
            GrowthStage(name="maturity", label="成熟期", startDay=101, endDay=130,
                        startKc=1.15, endKc=0.8, startP=0.4, endP=0.5, zrMm=700,
                        waterMmDay=4.0, nKgHaDay=1.2, pKgHaDay=0.4, kKgHaDay=1.8,
                        ecMin=1.8, ecMax=2.8, phMin=5.5, phMax=6.8),
        ])
    cucumber = Variety(
        code="cucumber", name="黄瓜", cropCode="cucumber",
        stages=[
            GrowthStage(name="seedling", label="苗期", startDay=0, endDay=20,
                        startKc=0.6, endKc=0.6, startP=0.5, endP=0.5, zrMm=150,
                        waterMmDay=1.2, nKgHaDay=0.6, pKgHaDay=0.2, kKgHaDay=0.8,
                        ecMin=1.0, ecMax=1.8, phMin=5.5, phMax=6.5),
            GrowthStage(name="flowering", label="花期", startDay=21, endDay=45,
                        startKc=0.6, endKc=1.0, startP=0.5, endP=0.45, zrMm=300,
                        waterMmDay=3.0, nKgHaDay=1.2, pKgHaDay=0.4, kKgHaDay=1.6,
                        ecMin=1.2, ecMax=2.2, phMin=5.5, phMax=6.5),
            GrowthStage(name="fruiting", label="结果期", startDay=46, endDay=90,
                        startKc=1.0, endKc=1.0, startP=0.45, endP=0.4, zrMm=500,
                        waterMmDay=4.5, nKgHaDay=1.8, pKgHaDay=0.5, kKgHaDay=2.5,
                        ecMin=1.5, ecMax=2.5, phMin=5.5, phMax=6.8),
            GrowthStage(name="maturity", label="成熟期", startDay=91, endDay=110,
                        startKc=1.0, endKc=0.75, startP=0.4, endP=0.5, zrMm=500,
                        waterMmDay=3.5, nKgHaDay=1.0, pKgHaDay=0.3, kKgHaDay=1.5,
                        ecMin=1.5, ecMax=2.5, phMin=5.5, phMax=6.8),
        ])
    return [tomato, cucumber]


class VarietyStore:
    """品种库：启动建表，空库时写入内置品种。"""

    def __init__(self, path: str):
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.executescript(_SCHEMA)
        self._conn.commit()
        if not self.list():
            for v in _seed_varieties():
                self.upsert(v)

    @staticmethod
    def _row_to_variety(row: tuple) -> Variety:
        stages = [GrowthStage(**s) for s in json.loads(row[3])]
        return Variety(code=row[0], name=row[1], cropCode=row[2],
                       stages=stages, updatedAt=row[4])

    def list(self) -> List[Variety]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT code, name, crop_code, stages_json, updated_at "
                "FROM variety ORDER BY code").fetchall()
            return [self._row_to_variety(r) for r in rows]

    def get(self, code: str) -> Optional[Variety]:
        with self._lock:
            row = self._conn.execute(
                "SELECT code, name, crop_code, stages_json, updated_at "
                "FROM variety WHERE code = ?", (code,)).fetchone()
            return self._row_to_variety(row) if row else None

    def upsert(self, v: Variety) -> Variety:
        v.updatedAt = dt.datetime.now(dt.timezone.utc).isoformat()
        stages_json = json.dumps([s.model_dump() for s in v.stages],
                                 ensure_ascii=False)
        with self._lock:
            self._conn.execute(
                "INSERT INTO variety(code, name, crop_code, stages_json, updated_at) "
                "VALUES (?,?,?,?,?) "
                "ON CONFLICT(code) DO UPDATE SET name=excluded.name, "
                "crop_code=excluded.crop_code, stages_json=excluded.stages_json, "
                "updated_at=excluded.updated_at",
                (v.code, v.name, v.cropCode, stages_json, v.updatedAt))
            self._conn.commit()
        return v

    def delete(self, code: str) -> bool:
        with self._lock:
            cur = self._conn.execute("DELETE FROM variety WHERE code = ?", (code,))
            self._conn.commit()
            return cur.rowcount > 0

    def close(self) -> None:
        with self._lock:
            self._conn.close()
