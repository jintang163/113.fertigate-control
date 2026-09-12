# 水肥一体化智能灌溉控制系统 — 总体架构

## 1. 分层架构

```
┌──────────────────────────── PC 前端 ────────────────────────────┐
│ Vue3 + TypeScript + Vite + Ant Design Vue 4 + ECharts          │
│ 仪表盘 / 田块阀门控制 / 阈值与作物模型配置 / 趋势曲线 / 告警     │
└───────────────▲───────────────────────────────┬─────────────────┘
                │ REST/JSON (8080)               │ WebSocket(告警,可选)
┌───────────────┴───────────────────────────────▼─────────────────┐
│                    Spring Boot 云端后端 (8080)                   │
│ MQTT 接入解析 │ 自动控制引擎(状态机) │ REST API │ 定时评估调度    │
│ PostgreSQL: 设备/田块/作物模型/阈值/作业/告警 (JPA + Flyway)     │
│ InfluxDB 2: 传感器遥测时序 (湿度/EC/pH/气象/流量/阀位)           │
└───────▲───────────────┬──────────────────────────────▲──────────┘
        │ MQTT          │ HTTP(决策评估)               │ MQTT 控制下发
┌───────┴───────┐  ┌─────┴──────────────┐             │
│  EMQX Broker  │  │ Python 决策服务     │             │
│   (1883)      │  │ FastAPI (8000)      │             │
│ 断网重传/共享  │  │ FAO-56 阈值 +      │             │
│ 订阅          │  │ 作物生育期模型      │             │
└───────▲───────┘  └────────────────────┘             │
        │ MQTT/TCP (1883)                              │
┌───────┴─────────────────────────────────────────────┴──────────┐
│                  边缘网关 (Python, 田间控制柜)                    │
│ Modbus/RS485 轮询 │ 协议转换 │ SQLite 断网缓存+补传 │ 本地联锁    │
│ 阀门驱动(继电器/Modbus DO) │ 流量计计量 │ 模拟器(无硬件时)        │
└───────▲─────────────────────────────────────────────────────────┘
        │ RS485 / Modbus-RTU
┌───────┴────────┐  ┌──────────┐  ┌──────────┐  ┌────────────────┐
│ 土壤: 湿度/EC/pH│  │ 气象站    │  │ 电磁阀    │  │ 文丘里+注肥泵  │
│ (多点 10/20/30) │  │ 温湿光雨  │  │+流量反馈  │  │ (EC/pH 调控)   │
└─────────────────┘  └──────────┘  └──────────┘  └────────────────┘
```

## 2. 技术栈与端口

| 组件 | 技术 | 端口 | 目录 |
|---|---|---|---|
| 边缘网关 | Python 3.11 / paho-mqtt / pymodbus / SQLite | — | `edge-gateway/` |
| 决策服务 | Python / FastAPI / uvicorn | 8000 | `decision-service/` |
| EMQX | 5.x | 1883, 18083 | `deploy/docker-compose.yml` |
| 云端后端 | Java 17 / Spring Boot 3 / Spring Integration MQTT / JPA / InfluxDB Java Client | 8080 | `cloud-backend/` |
| PostgreSQL | 16 | 5432 | — |
| InfluxDB | 2.x | 8086 | — |
| 前端 | Vue3 + Vite + TS + AntDV4 + ECharts5 | 5173(dev) | `web/` |

## 3. 核心域模型（PostgreSQL）

- `device` 设备：id, code, type(SOIL_SENSOR/WEATHER_STATION/VALVE/FLOW_METER), gateway_sn, modbus_addr, protocol_config(jsonb), online, last_heartbeat
- `field` 田块：id, name, crop_type, area_m2, irrigation_mode(DRIP/SPRINKLER), emitter_flow_lph, valve_id(主阀), sensors...（通过 field_sensor 关联）
- `crop_model` 作物模型：code, name（番茄/黄瓜/生菜…），按生育期 stages(jsonb)：Kc、可耗水系数 p、根深 zr；θfc/θwp 由土壤参数给出
- `field_config` 田块灌溉配置：field_id, θfc(田持体积%), θwp(萎蔫点), 安全上下限 hard_max/hard_min, mode(AUTO/MANUAL), max_duration_min, ec_min/ec_max, ph_min/ph_max, rain_skip_mm, forecast_days, min_interval_h, 灌水利用系数 η, 湿润比 p_wet
- `irrigation_job` 灌溉作业：id, field_id, trigger_type(AUTO/MANUAL/SAFETY_OFF), decision(jsonb), start_time/end_time, planned_m3/applied_m3, status(PLANNED/RUNNING/DONE/ABORTED), reason
- `alarm` 告警：level(INFO/WARN/CRITICAL), type, device_id/field_id, message, acknowledged, time
- `sensor_reading_latest` 最新值表（也可全部从 Influx 查，PG 中保留最新值便于控制引擎快速读取）

时序数据全部进入 InfluxDB bucket `telemetry`，measurement 规范见 `mqtt-protocol.md`。

## 4. 自动控制闭环

1. 网关每 10s 轮询 Modbus → MQTT `farm/{gatewaySn}/telemetry` 上报。
2. 后端解析 → InfluxDB 持久化；更新 PG 最新值。
3. 调度器每 5 分钟（可配置）对每个 AUTO 田块调用**控制引擎**：
   - 取最近湿度/EC/pH、未来降雨（气象，可缺省）、当前生育期作物模型；
   - 调用 Python 决策服务 `/decide`（FAO-56 阈值法）；
   - `IRRIGATE` → 创建作业并下发阀门开指令；`HOLD` → 不动；`SKIP`（降雨）→ 记录。
4. 网关执行阀控，回报 `valve/status`；运行期间持续计量流量；
   达到计划灌量 / 最大时长 / 硬上限湿度 / 联锁触发 → 关阀并回报，作业完成。
5. **安全优先**：边缘网关本地联锁（与云端无关）在任何模式下都能立即关阀。

## 5. 关键设计取舍

- **阈值是动态的**：灌溉触发点 θ_start = θfc − p·(θfc − θwp)，p、根深、Kc 随作物生育期变化，由 Python 服务计算；前端展示“当前阈值带”。
- **控制面与数据面分离**：决策服务无状态、可单测、可替换（未来可换 ML 模型）；Spring 引擎只做编排、持久化与安全兜底。
- **断网两级可用**：网关 SQLite 缓存数据补传 + 本地联锁保安全；云端控制在网关离线时不下发（避免消息堆积误动作）。
- **幂等指令**：所有 valve 命令带 `commandId`，网关对重复 ID 只回报不重复执行。
