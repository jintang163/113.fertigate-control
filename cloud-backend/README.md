# cloud-backend — 水肥一体化智能灌溉系统 · 云端后端

Java 17 / Spring Boot 3.2.x 实现。职责：MQTT 接入（遥测/阀态/事件/心跳）、
PostgreSQL 业务库（JPA + Flyway）、InfluxDB 2 时序库、FAO-56 自动控制引擎
（编排 Python 决策服务 + 安全兜底）、REST API（:8080）。

## 1. 环境要求与启动顺序

需要：JDK 17+、Maven 3.8+，以及外部依赖（见 `deploy/docker-compose.yml`）：

1. PostgreSQL 16（库 `farm`，用户 `farm/farm`）
2. InfluxDB 2.x（org `farm`，bucket `telemetry`；应用启动时会自动创建 bucket）
3. EMQX 5.x（1883）
4. Python 决策服务（:8000，`POST /decide`）
5. 边缘网关（或模拟器）连接 EMQX 上报数据
6. 最后启动本服务与前端

```bash
mvn spring-boot:run
# 打包运行
mvn -DskipTests package && java -jar target/cloud-backend-1.0.0.jar
```

决策服务或 InfluxDB 不可用时应用仍可启动：决策自动降级为本地阈值计算
（响应 reasons 中带 `LOCAL_FALLBACK`，并打 WARN），Influx 写入失败仅告警不影响 MQTT 入库 PG。

## 2. 环境变量（均有默认值，可全部覆盖）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | `8080` | REST 端口 |
| `MQTT_HOST` | `tcp://emqx:1883` | EMQX 地址 |
| `MQTT_CLIENT_ID` | `cloud-backend` | 客户端 ID（唯一） |
| `MQTT_USERNAME` / `MQTT_PASSWORD` | 空 | 可选鉴权 |
| `PG_HOST` / `PG_PORT` / `PG_DB` / `PG_USER` / `PG_PASSWORD` | `postgres/5432/farm/farm/farm` | PostgreSQL |
| `INFLUX_URL` | `http://influxdb:8086` | InfluxDB 地址 |
| `INFLUX_TOKEN` | `my-super-admin-token` | 具备 bucket 读写/建 bucket 权限的 token |
| `INFLUX_ORG` / `INFLUX_BUCKET` | `farm` / `telemetry` | 组织与 bucket |
| `INFLUX_ENSURE_BUCKET` | `true` | 启动时自动建 bucket |
| `DECISION_URL` | `http://decision-service:8000` | Python 决策服务 |
| `CONTROL_EVAL_INTERVAL_MS` | `300000` | 自动评估周期 5min |
| `COMMAND_ACK_TIMEOUT_MS` | `30000` | STARTING 首次重发阈值 |
| `COMMAND_FINAL_TIMEOUT_MS` | `60000` | STARTING 最终中止阈值 |
| `DATA_STALENESS_SEC` | `600` | 遥测有效期 10min |
| `FARM_LATITUDE` | `34.5` | 无气象服务时的纬度 |

## 3. MQTT 约定（与 mqtt-protocol.md 一致）

订阅（QoS1，cleanStart=false 持久会话，自动重连并重订阅）：

- `farm/+/telemetry` — records 数组批量/补传 → 写 Influx（measurement: soil/weather/flow/valve，
  tag `deviceCode/gatewaySn/quality`，时间取记录 `ts` 毫秒）+ upsert `sensor_latest` + 设备心跳
- `farm/+/valve/status` — 阀态/流量；OPEN 确认命令、CLOSED 结算 RUNNING 作业
  （applied_m3、stop_reason、end_time、duration）；FAULT 置作业 ABORTED
- `farm/+/events` — 落 alarm；CRITICAL 联锁事件命中运行作业阀门时云端补发 CLOSE
- `farm/+/health` — 网关掉线/在线、queueDepth、fw

发布：

- `farm/{gw}/valve/command` — `valveCommand` 信封（commandId=UUID 幂等、jobId、plannedVolume、
  maxDurationSec、hardMaxMoisture、sensorCode）
- `farm/{gw}/config` — 轮询周期与联锁参数下发

## 4. 控制引擎（control-logic.md）

- 每 5min（`CONTROL_EVAL_INTERVAL_MS`）+ 手动 `POST /api/control/run-cycle`
- 对 enabled + AUTO 田块：多点湿度平均 → 严格按 api-contract.md 组装 `/decide` 请求
  （crop stages、daysAfterSowing、soil zones、weather、field、limits），5s 超时；
  失败降级本地 θfc−p·(θfc−θwp) 阈值 + 亏缺灌量公式
- IRRIGATE 前安全前置（AUTO 全部）：阀在线、网关闭线、数据 10min 内、湿度 < thetaStart、
  未触 hardMax、EC/pH 窗口、无未确认 CRITICAL、minInterval、同 field 无并发作业
- STARTING：30s 无 OPEN ACK → 原 payload 重发 1 次；60s 仍无 → WARN 告警 + 作业 ABORTED
- RUNNING 停止条件（评估周期 + telemetry 到达实时触发）：
  累计流量 ≥ planned（VOLUME_REACHED）、时长 ≥ maxDurationSec（DURATION_LIMIT）、
  湿度 ≥ thetaTarget（MOISTURE_TARGET 软停）、湿度 ≥ hardMax（CRITICAL + CLOSE）、
  传感器失联、阀 FAULT、MANUAL/REMOTE_CLOSE
- MANUAL 模式不自动启停；hardMax/CRITICAL 等安全联锁仍由 superviseRunning 与事件分支关阀
- 手动 OPEN 同样建作业（trigger_type=MANUAL），云端至少挡 hardMax/过期/CRITICAL/离线

## 5. 联调方式

边缘网关/模拟器：ClientId `gw-GW001`，向 `farm/GW001/telemetry` 发 records，
向 `farm/GW001/valve/status` 回报阀态，向 `farm/GW001/health` 发心跳。
种子数据：网关 GW001；SOIL-A1/A2（modbus 1/2）、WS-01（3）、V-01（4）、FM-01（5）；
田块 1「A区番茄地」滴灌 2000m²、2400L/h、阀 V-01。

前端（dev）：CORS 已对 `/api/**` 全放开。轮询 `/api/fields/1/status`、
`/api/telemetry?fieldId=1&metrics=soilMoist,ec,ph&range=24h&agg=10m`、
`/api/alarms?ack=false` 即可（WebSocket 按契约为可选项，未实现）。

## 6. curl 示例

```bash
# 健康检查（含 MQTT 连接状态）
curl -s localhost:8080/api/health

# 立即跑一轮全田块自动评估
curl -s -X POST localhost:8080/api/control/run-cycle

# 只调决策服务取建议（不动作）
curl -s -X POST localhost:8080/api/fields/1/decision/evaluate

# 手动开阀 2.4m³（AUTO 模式需 force=true；仍过云端硬联锁）
curl -s -X POST localhost:8080/api/fields/1/control \
  -H 'Content-Type: application/json' \
  -d '{"action":"OPEN","volumeM3":2.4,"force":true}'

# 手动关阀
curl -s -X POST localhost:8080/api/fields/1/control \
  -H 'Content-Type: application/json' -d '{"action":"CLOSE"}'

# 实时状态 / 时序 / 作业 / 告警
curl -s localhost:8080/api/fields/1/status
curl -s 'localhost:8080/api/telemetry?fieldId=1&metrics=soilMoist,ec,ph&range=24h&agg=10m'
curl -s 'localhost:8080/api/jobs?fieldId=1&page=0&size=20'
curl -s 'localhost:8080/api/alarms?ack=false'
curl -s -X POST localhost:8080/api/alarms/1/ack

# 给网关下发轮询/联锁参数（缺省值从田块 field_config 派生）
curl -s -X POST localhost:8080/api/gateways/GW001/config \
  -H 'Content-Type: application/json' \
  -d '{"pollIntervalSec":10,"interlocks":{"moistureHardMax":33,"moistureHardMin":8,"sensorLostSec":180}}'
```

## 7. 包结构

```
com.farm.irrigation
├── config    属性绑定 / RestClient / InfluxDBClient / CORS
├── mqtt      Paho 客户端、订阅分发、MqttGateway 出站、遥测事件
├── domain    JPA 实体
├── repo      Spring Data 仓库
├── service   遥测入库、阀态结算、事件、心跳、Influx、字段/作业/告警等
├── control   控制引擎、决策客户端(+本地降级)、快照、阀指令、状态机报告
├── web       REST 控制器
├── dto       请求/响应 DTO
└── common    ApiResponse / 全局异常
```
