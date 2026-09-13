# 作物模型灌溉决策服务（Decision Service）

无状态 Python 服务：输入墒情、气象、田块与作物生育期模型，输出灌溉决策。
被云端后端控制引擎调用（集群内），也可独立用于方案试算。

**V4 起同时承载「生长模型服务」**：作物品种与生育期管理、日需水/需肥曲线、
水肥融合决策，并按调度周期回调 Spring Boot 执行（见下文「生长模型服务」）。

## 算法

- **动态阈值（FAO-56 允许亏缺灌溉）**：θ_start = θfc − p·(θfc−θwp)，
  p / Kc / 根深 Zr 按播后天数在生育期 stages 间线性插值。
- **ET0**：Hargreaves-Samani（纬度 + 日序 + Tmax/Tmin 解析地外辐射），ETc = Kc·ET0。
- **灌量**：D = (θ_target−θ)/100·Zr·湿润比/η [mm]，V = D/1000·A [m³]，T = V·1000/Q [h]；
  超过单次最大时长则削顶（`clampReason=DURATION_LIMIT`），余量下轮续灌。
- **日需水曲线钳制**（可选）：传入 `crop.dailyWaterNeedMm` 后，单日累计灌水不超过
  `日需水 × field.dailyNeedCapFactor(默认1.2) − weather.irrigatedTodayMm − 有效降雨`，
  超出按 `clampReason=DAILY_NEED_CAP` 钳制，余量不足时 HOLD。
- **决策**：IRRIGATE / HOLD / SKIP（未来有效降雨可覆盖亏缺）/ FORBID（hardMax、EC、pH、传感器异常）。
- 根区多点湿度按埋深层厚加权平均。

## 运行

```bash
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
# 交互文档 http://localhost:8000/docs
```

## 接口

- `GET /healthz`
- `POST /decide` — 请求/响应格式见 `../docs/api-contract.md` 末尾

## 生长模型服务

### 品种与生育期管理（SQLite 持久化，内置番茄/黄瓜）

- `GET /api/varieties` / `POST /api/varieties` / `GET|PUT|DELETE /api/varieties/{code}`
- 品种 stages 覆盖苗期(seedling)/花期(flowering)/结果期(fruiting)/成熟期(maturity)，
  每阶段含灌溉参数（Kc/p/Zr）与水肥参数（日需水 mm/day、N/P/K kg/ha/day、EC/pH 目标带）

### 曲线

- `GET /api/varieties/{code}/water-curve?et0=4.6` — 按品种+生育期的逐日需水曲线 mm/day
- `GET /api/varieties/{code}/fert-curve` — 逐日 N/P/K kg/ha/day

### 融合决策

- 灌溉：土壤湿度实测 + 日需水曲线 + 阈值上下限 → 开/关/持续时间（`POST /decide` 传入
  `crop.dailyWaterNeedMm` 即启用曲线钳制）
- 施肥：`POST /decide/fertigation` — EC/pH 实测 + 需肥曲线 → 是否施肥、N/P/K kg、
  肥液 L、建议注肥比（pH 出窗 / EC 超限 → FORBID）

### 回调 Spring Boot 执行

调度器（`GROWTH_SCHEDULER_ENABLED=1` 启用，周期 `GROWTH_EVAL_INTERVAL_SEC` 默认 300s）：
拉取 `GET {BACKEND_URL}/api/fields`（仅 `config.growthModelEnabled=true` 的田块）与
`/api/fields/{id}/status` → 融合决策 → `POST {BACKEND_URL}/api/growth/callback`
（幂等 decisionId；云端经全量安全前置后以 triggerType=MODEL 执行，水肥一体）。
`POST /growth/run-cycle` 可手动触发一轮（演示/调试）。

环境变量：`VARIETY_DB_PATH`（默认 ./data/varieties.db）、`BACKEND_URL`、
`GROWTH_SCHEDULER_ENABLED`、`GROWTH_EVAL_INTERVAL_SEC`。

## 测试

```bash
python -m pytest tests/ -q     # 35 用例：阈值/削顶/降雨/联锁/插值/分层湿度/ET0
                               # + 品种库/需水需肥曲线/施肥决策/灌溉曲线钳制/回调链路
```
