# MQTT 协议规范（边缘网关 ⇄ 云端）

Broker: EMQX 5.x，TCP `1883`（生产启用 TLS/8883）。ClientId 唯一：`gw-{gatewaySn}`；使用**持久会话**（clean_start=False, QoS1）支撑断网消息不丢。

## 1. 主题定义

| 方向 | 主题 | QoS | 说明 |
|---|---|---|---|
| 网关→云 | `farm/{gatewaySn}/telemetry` | 1 | 传感器批次数据，可含多条记录（支持补传） |
| 网关→云 | `farm/{gatewaySn}/valve/status` | 1 | 阀位/流量/作业进度 |
| 网关→云 | `farm/{gatewaySn}/device/status` | 1 | 通用执行器状态：施肥泵/调节阀（开度/电流/过载） |
| 网关→云 | `farm/{gatewaySn}/events` | 1 | 联锁动作、故障、降雨跳过等事件 |
| 网关→云 | `farm/{gatewaySn}/health` | 1 | 心跳 + 固件/缓存队列深度 |
| 云→网关 | `farm/{gatewaySn}/valve/command` | 1 | 阀门开关命令（幂等） |
| 云→网关 | `farm/{gatewaySn}/device/command` | 1 | 施肥泵启停/开度、调节阀开度（幂等） |
| 云→网关 | `farm/{gatewaySn}/config` | 1 | 阈值/轮询周期等参数下发 |
| 云→网关 | `farm/{contractId}/config` | 1 | 面向所有网关的配置下发（保留，本系统用 gatewaysn 单播） |

后端订阅通配：`farm/+/telemetry`、`farm/+/valve/status`、`farm/+/device/status`、`farm/+/events`、`farm/+/health`。

## 2. 通用字段

所有消息为 JSON，UTF-8，统一信封：

```json
{ "type": "telemetry", "gatewaySn": "GW001", "ts": 1726100000000, "records": [ ... ] }
```

- `ts` 为采样时刻（非上报时刻），毫秒时间戳，用于补传排序与去重。
- QoS1 可能重复，后端按 `(gatewaySn, ts, metric)` 去重（InfluxDB 同 timestamp/tag 的写天然覆盖）。

## 3. telemetry 消息（数据面）

`records[]` 每条形如：

```json
{
  "deviceCode": "SOIL-A1",
  "kind": "soil",
  "ts": 1726100000000,
  "values": { "soilMoisture": 21.4, "ec": 1.65, "ph": 6.4, "temp": 22.1 },
  "quality": "GOOD"
}
```

- `kind`: `soil` | `weather` | `flow` | `valve`
- 土壤点: values: `soilMoist`(体积含水率 %), `ec`(mS/cm), `ph`, `soilTemp`(℃)
- 气象: `airTemp`, `airHumidity`, `solarRadiation`(W/m²), `rainfall`(mm/周期), `windSpeed`, `et0`(可选)
- 流量: `instantFlow`(m³/h), `totalFlow`(m³)
- 阀位（遥测形式，也可走 valve/status）: `open` 0/1
- `quality`: `GOOD` | `SENSOR_FAULT` | `STALE`

## 4. valve/status（执行反馈）

```json
{
  "type": "valveStatus",
  "gatewaySn": "GW001",
  "ts": 1726100005000,
  "valveCode": "V-01",
  "jobId": "J20260912A",
  "commandId": "d3f1...",
  "state": "OPEN" ,            // OPEN / CLOSED / OPENING / CLOSING / FAULT
  "instantFlow": 2.1,
  "totalFlow": 1.23,
  "appliedVolume": 0.85,
  "stopReason": null
}
```

## 5. valve/command（控制面，幂等）

```json
{
  "type": "valveCommand",
  "gatewaySn": "GW001",
  "commandId": "uuid-v4",
  "valveCode": "V-01",
  "jobId": "J20260912A",
  "command": "OPEN",            // OPEN / CLOSE
  "plannedVolume": 2.4,         // OPEN 时给出，用于本地计量关断兜底
  "maxDurationSec": 900,        // 本地超时关断兜底
  "hardMaxMoisture": 32.0,      // 本地湿度硬上限联锁
  "sensorCode": "SOIL-A1",      // 硬上限取数传感器
  "ts": 1726100000000
}
```

网关职责：
1. 接到 OPEN：若本地硬联锁条件已成立（硬上限、传感器失联、阀门故障）→ 拒绝并 REJECTED 事件；
2. 执行并在 5s 内回报 valve/status；
3. OPEN 期间满足任一停止条件（达量/超时/超湿/联锁）→ **自动关阀**，status 中给 `stopReason`：
   `VOLUME_REACHED | DURATION_LIMIT | MOISTURE_HARD_MAX | INTERLOCK_* | REMOTE_CLOSE`
4. 重复 `commandId` → 不重复执行，重放当前状态。

## 6. events（事件/联锁）

```json
{
  "type": "event",
  "gatewaySn": "GW001",
  "ts": 1726100020000,
  "level": "CRITICAL",
  "code": "INTERLOCK_MOISTURE_HARD_MAX",
  "valveCode": "V-01",
  "message": "土壤湿度 33.1% 超过硬上限 32%，阀门已强制关闭",
  "context": { "moisture": 33.1, "hardMax": 32.0 }
}
```

事件 code 枚举：`INTERLOCK_MOISTURE_HARD_MAX`、`INTERLOCK_SENSOR_LOST`、`INTERLOCK_VALVE_FAULT`、`INTERLOCK_EC_HIGH`、`INTERLOCK_PH_LOW`、
`INTERLOCK_PH_HIGH`、`INTERLOCK_WATER_LOST`（主管道水压低）、`INTERLOCK_FLOW_LOW`（阀开低流量）、
`INTERLOCK_PUMP_OVERLOAD`（施肥泵过载）、`INTERLOCK_COMM_LOST`（通信中断紧急停车）、
`COMMAND_REJECTED`、`RAIN_SKIP`、`SENSOR_FAULT`。

## 6b. device/command 与 device/status（施肥泵/通用执行器）

云→网关 `farm/{gw}/device/command`（与 valve/command 同样幂等，commandId 去重）：

```json
{
  "type": "deviceCommand",
  "gatewaySn": "GW001",
  "commandId": "uuid-v4",
  "deviceCode": "FP-01",
  "deviceType": "FERT_PUMP",
  "jobId": "J20260913F1-12",
  "action": "START",              // START / STOP / SET_OPENING
  "opening": 60,                  // 0-100 开度（比例注肥泵/调节阀）
  "injectRatioPct": 1.5,
  "ts": 1726100000000
}
```

网关回报 `farm/{gw}/device/status`：

```json
{
  "type": "deviceStatus",
  "gatewaySn": "GW001",
  "ts": 1726100005000,
  "deviceCode": "FP-01",
  "commandId": "uuid-v4",
  "jobId": "J20260913F1-12",
  "state": "RUNNING",             // RUNNING / STOPPED / FAULT
  "opening": 60,
  "motorCurrent": 2.4,            // A，超过 pumpOverloadA 判过载
  "pressure": 248.0,              // kPa（可选）
  "overload": false
}
```

云端职责：施肥泵状态/电流/开度入库（device 表 + sensor_latest + Influx `device` measurement）；
`overload=true` 或 `state=FAULT` 立即 CRITICAL 告警并安全关阀；commandId 用于指令 ACK。

## 7. health 心跳

每 60s：`{"type":"health","gatewaySn":"GW001","ts":...,"uptimeSec":12345,"queueDepth":12,"fw":"1.0.0"}`

## 8. config 下发

```json
{
  "type": "config",
  "gatewaySn": "GW001",
  "pollIntervalSec": 10,
  "interlocks": {
    "moistureHardMax": 32.0,
    "moistureHardMin": 8.0,
    "sensorLostSec": 180,
    "ecHigh": 3.0,
    "phLow": 5.0, "phHigh": 8.0,
    "pressureMinKpa": 80.0,
    "flowMinM3h": 0.5,
    "waterLostDelaySec": 15,
    "pumpOverloadA": 8.0,
    "commLostSec": 300
  }
}
```

新增联锁字段：`pressureMinKpa` 主管道水压下限、`flowMinM3h` 阀开最低瞬时流量（持续 `waterLostDelaySec`
判缺水并关阀停泵）、`pumpOverloadA` 施肥泵过载电流、`commLostSec` 与云端通信中断紧急停车时间。

## 9. 断网缓存与补传

1. 采集线程与上传线程解耦：采集即写本地 SQLite（`outbox` 表）。
2. MQTT 在线时按批（≤100 条）发布，ACK(QoS1 PUBACK) 后删除缓存。
3. 重连后自动从最旧记录开始补传，`ts` 为原始采样时间。
4. 控制指令走独立订阅，QoS1 + 持久会话 + 命令幂等，保证不丢不重。
