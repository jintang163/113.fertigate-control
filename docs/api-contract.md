# REST API 契约（Spring Boot :8080，前缀 /api）

统一响应：`{ "code": 0, "msg": "ok", "data": ... }`，错误 code≠0，HTTP 状态语义化。时间为 ISO-8601 或 epoch ms。

## 设备 / 网关
- `GET /api/devices` — 设备列表（可按 gatewaySn/type 过滤）
- `GET /api/devices/{code}` — 详情含在线状态、最新遥测
- `GET /api/gateways` — 网关列表、在线、queueDepth
- `POST /api/gateways/{sn}/config` — 下发轮询/联锁参数（转 MQTT config）

## 田块 / 配置
- `GET /api/fields` / `POST /api/fields` / `PUT /api/fields/{id}`
- field: `{ id, name, cropCode, areaM2, irrigationMode, emitterTotalLph, valveCode, sensorCodes:[], config: {...} }`
- config 字段（FieldConfig）：
  `thetaFc, thetaWp, mode(AUTO|MANUAL), hardMaxOffsetPct(默认3), hardMin, maxDurationSec, minIntervalH,
   ecMin, ecMax, phMin, phMax, rainSkipMm, wetRatio, efficiency, enabled`
- `GET /api/fields/{id}/status` — 实时状态：
  ```json
  { "fieldId":1,"mode":"AUTO","valveState":"CLOSED","moisture":21.4,"thetaStart":22.8,
    "thetaFc":30.0,"thetaWp":12.0,"stage":"mid","jobId":null,"lastIrrigTime":"...","alarms":[] }
  ```

## 作物模型
- `GET /api/crop-models` / `GET /api/crop-models/{code}` / `PUT /api/crop-models/{code}`
- `{ code:"tomato", name:"番茄", stages:[ {"name":"initial","days":30,"kc":0.6,"p":0.5,"zrMm":200}, ... ] }`

## 控制
- `POST /api/fields/{id}/decision/evaluate` — 立即调用决策服务（不执行），返回建议（调试用）
- `POST /api/fields/{id}/control` — 手动控制：body `{ "action":"OPEN"|"CLOSE", "volumeM3": 2.4 }`
  MANUAL 模式下直接生效；AUTO 模式需 `force:true`。
- `POST /api/control/run-cycle` — 触发一轮全田块自动评估（调度器每 5min 自动执行；手动触发便于演示）

## 作业
- `GET /api/jobs?fieldId=&status=&page=&size=` — 分页：`{records,total,page,size}`
- `GET /api/jobs/{id}` — 作业详情（决策依据、计划/实际水量、stopReason）

## 告警
- `GET /api/alarms?level=&ack=&page=` / `POST /api/alarms/{id}/ack`

## 时序数据（代理 InfluxDB）
- `GET /api/telemetry?fieldId=1&metrics=soilMoist,ec,ph&range=24h&agg=10m`
  返回：`{ "columns":["ts","soilMoist",...], "rows":[[...]] }`，附带阈值线由前端按 field status 绘制。
- `GET /api/telemetry/latest?fieldId=1`

## WebSocket（可选）
- `/ws/monitor`（STOMP）：topic `/topic/fields/{id}` 推送 valveStatus / alarm / decision，前端实时刷新。

## 决策服务（Python :8000，仅集群内调用）
- `GET /healthz`
- `POST /decide`，请求：
  ```json
  {
    "crop": {"code":"tomato","stages":[...], "daysAfterSowing":75},
    "soil": {"thetaFc":30.0,"thetaWp":12.0,"moisture":21.4,"zones":[{"depth":100,"moist":20.1}]},
    "weather": {"airTemp":26,"tMax":32,"tMin":21,"rainfallToday":0,"rainForecastMm":[0,0,2],"latitude":34.5},
    "field": {"areaM2":2000,"wetRatio":0.8,"efficiency":0.9,"emitterTotalLph":2400},
    "limits": {"hardMax":33.0,"hardMin":8.0,"ec":1.7,"ecMin":1.2,"ecMax":2.6,"ph":6.4,"phMin":5.5,"phMax":7.5,"maxDurationSec":900,"minIntervalH":0.5,"lastIrrigAgoH":6}
  }
  ```
  响应：
  ```json
  { "decision":"IRRIGATE","stage":"mid","thetaStart":22.8,"thetaTarget":29.0,
    "deficitMm":10.2,"volumeM3":2.27,"durationSec":3400,"clampReason":"DURATION_LIMIT",
    "et0MmDay":4.6,"etcMmDay":5.29,"reasons":["moisture 21.4% <= thetaStart 22.8%"] }
  ```
