# 水肥一体化智能灌溉控制系统

从土壤墒情感知、边缘采集联锁、MQTT 数据传输、云端决策到 PC 端可视化的 0→1 全栈实现。
依据 **土壤湿度阈值 + 作物生育期模型（FAO-56 允许亏缺灌溉）** 自动控制电磁阀启停，并与施肥（EC/pH）联动。

## 系统组成

| 目录 | 组件 | 技术栈 | 状态 |
|---|---|---|---|
| `edge-gateway/` | 边缘网关：Modbus/RS485 采集、断网缓存补传、本地联锁 | Python 3.11 / pymodbus / paho-mqtt / SQLite | ✅ 14 测试通过 |
| `decision-service/` | 作物模型决策：FAO-56 动态阈值、灌量时长、ET0、降雨跳过 | Python / FastAPI | ✅ 13 测试通过 |
| `cloud-backend/` | 云端：MQTT 接入、控制引擎状态机、REST、时序/关系持久化 | Java 17 / Spring Boot 3 / JPA / InfluxDB Client | ✅ 完整实现 |
| `web/` | PC 前端：仪表盘、阀控、阈值曲线、作物模型、告警 | Vue3 + TS + Vite + Ant Design Vue 4 + ECharts | ✅ build 通过 |
| `deploy/` | EMQX / PostgreSQL / InfluxDB 全栈编排 | docker compose | ✅ |
| `docs/` | 架构、MQTT 协议、控制逻辑、API 契约 | — | ✅ |

## 自动灌溉逻辑（一句话版）

```
θ_start = θfc − p(θfc − θwp)      # p、根深 Zr、Kc 随作物生育期变化（决策服务计算）
if 根区平均湿度 ≤ θ_start 且 无降雨/EC/pH/硬上限等约束:
    灌量 V = (θ_target−θ)/100 · Zr · 湿润比/η · 面积 ;  时长 T = V / 滴头总流量
    云端建作业 → MQTT 下发 OPEN（幂等 commandId + 达量/超时/硬上限兜底参数）
    网关执行；达量/超时/超湿/联锁任一成立 → 自动关阀回报 → 作业结算
else 保持（HOLD）/ 雨养跳过（SKIP）/ 禁止（FORBID）
```

完整规则（状态机、安全前置、边缘联锁）见 [`docs/control-logic.md`](docs/control-logic.md)。

## 数据流

```
传感器(湿度/EC/pH/气象/流量) --RS485/Modbus--> 边缘网关
  → SQLite outbox（断网缓存）→ MQTT/QoS1(EMQX) → Spring Boot
  → InfluxDB(时序) + PostgreSQL(设备/配置/作业/告警)
控制环：调度器5min → 决策服务 /decide → 引擎安全检查 → MQTT valve/command → 网关 → 电磁阀
边缘侧本地联锁（与云端无关）：硬上限/失联/达量/超时/EC/pH → 立即关阀
前端：REST 轮询 + ECharts 墒情/阈值曲线，AUTO/MANUAL 与手动阀控
```

## 快速开始

```bash
cd deploy
docker compose up -d --build                      # 全套平台与服务
docker compose --profile simulate up -d edge-gateway-sim   # 无硬件演示
# 打开 http://localhost ，或 curl -X POST http://localhost:8080/api/control/run-cycle
```

详见 [`deploy/README.md`](deploy/README.md) 与 [`docs/architecture.md`](docs/architecture.md)。

## 文档

- [总体架构](docs/architecture.md)
- [MQTT 协议规范](docs/mqtt-protocol.md)
- [自动控制逻辑](docs/control-logic.md)
- [REST API 契约](docs/api-contract.md)

## 测试

```bash
( cd decision-service && python -m pytest tests/ -q )
( cd edge-gateway     && python -m pytest tests/ -q )
```
