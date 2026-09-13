# 水肥一体化智能灌溉控制系统

从土壤墒情感知、边缘采集联锁、MQTT 数据传输、云端决策到 PC 端可视化的 0→1 全栈实现。
依据 **土壤湿度阈值 + 作物生育期模型（FAO-56 允许亏缺灌溉）** 自动控制电磁阀启停，并与施肥（EC/pH）联动。

## 系统组成

| 目录 | 组件 | 技术栈 | 状态 |
|---|---|---|---|
| `edge-gateway/` | 边缘网关：Modbus/RS485 采集、断网缓存补传、本地联锁 | Python 3.11 / pymodbus / paho-mqtt / SQLite | ✅ 14 测试通过 |
| `decision-service/` | 作物模型决策 + **生长模型服务**：FAO-56 动态阈值、灌量时长、ET0、降雨跳过、品种/生育期管理、需水需肥曲线、水肥融合决策回调 | Python / FastAPI / SQLite | ✅ 35 测试通过 |
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

功能模块（V3 新增）：

| 模块 | 能力 |
|---|---|
| 设备管理 | 传感器/电磁阀/**施肥泵**/压力变送器注册 CRUD、在线/离线看门狗（心跳超时告警）、投运状态、开度显示 |
| 灌区管理 | 灌区划分（即田块）、作物品种、种植面积、轮灌优先级、灌溉时窗、作物**生育期记录** |
| 阈值策略 | 土壤湿度上下限、EC/pH 安全范围、**气象联动**（风速/气温/湿度/当日与预报降雨）、最小间隔 |
| 分区轮灌 | 按灌区优先级+墒情生成轮灌计划、顺序排程（自动落入时窗）、到点串行释放、取消/拦截标记 |
| 手动/自动 | 田块级 AUTO/MANUAL 切换；阀控 + **施肥泵启停/开度（device/command）**；轮灌 SCHEDULED 触发 |
| 安全联锁 | 通信中断、湿度硬上限、传感器失联、阀故障、EC/pH 超限、**缺水（水压低/流量低）**、**泵过载** 自动停止并告警；云+边缘双重保护 |
| 控制下发 | `valve/command`（电磁阀）+ `device/command`（施肥泵/调节阀，START/STOP/SET_OPENING 开度） |
| 灌肥台账 | 每次灌水/施肥起止时间、水量、肥液量（按注肥比折算）、执行方式 AUTO/MANUAL/SCHEDULED/MODEL/SAFETY、当日汇总 |
| **生长模型（V4 新增）** | 作物品种与生育期管理（苗期/花期/结果期/成熟期）、按品种+生育期的日需水曲线（mm/day）与日需肥曲线（N/P/K kg/ha/day）、融合决策（墒情+需水曲线+阈值 → 灌溉开/关/时长；EC/pH+需肥曲线 → 是否施肥/施肥量）、决策回调 Spring Boot 执行（田块级 `growthModelEnabled` 开关，triggerType=MODEL，水肥一体作业） |

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
