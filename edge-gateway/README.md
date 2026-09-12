# 边缘网关（Edge Gateway）

田间控制柜侧服务：Modbus/RS485 采集 → 协议转换 → MQTT(EMQX) 上报，
内置 **SQLite 断网缓存补传** 与**独立本地安全联锁**（与云端失联也能自动关阀）。

## 目录

```
app/
  config.py           配置加载（YAML + ${ENV:default}）
  modbus_io.py        真实 Modbus-RTU 采集/阀门驱动（pymodbus）
  simulator.py        无硬件田间仿真器（含蒸散/增墒/深层渗漏水文模型）
  messages.py         MQTT JSON 信封构造（遵循 docs/mqtt-protocol.md）
  outbox.py           SQLite 断网缓存（FIFO，PUBACK 后删除）
  mqtt_client.py      paho MQTT：持久会话/QoS1/自动重连/补传循环
  valve_controller.py 阀控器 + 本地联锁（硬上限/失联/达量/超时/EC/pH/幂等）
  main.py             主程序（采集周期 + 心跳 + 线程编排）
config/gateway.example.yaml
tests/test_gateway.py  14 个用例（联锁/幂等/缓存/水文/端到端本地闭环）
```

## 运行

```bash
python3 -m venv --without-pip .venv && .venv/bin/python get-pip.py   # 首次
pip install -r requirements.txt

# 无硬件演示（内置仿真器；无 broker 时数据自动落 SQLite 缓存）
python -m app.main -c config/gateway.example.yaml --simulate

# 真实硬件：编辑 config 中 modbus.serialPort 与 mode: modbus
python -m app.main -c /etc/farm/gateway.yaml
```

环境变量：`MQTT_HOST`（默认 emqx）、`MQTT_PORT`、`GW_CONFIG`。

## 本地联锁（最高优先级，任何模式生效）

| 条件 | 动作 | 事件 code |
|---|---|---|
| θ ≥ hardMaxMoisture（命令自带，默认 θfc+3%） | 立即关阀 | INTERLOCK_MOISTURE_HARD_MAX |
| 联锁传感器失联（默认 180s 无 GOOD 数据） | 拒绝开阀/运行中关阀 | INTERLOCK_SENSOR_LOST |
| 累计水量 ≥ plannedVolume | 自动关阀 | VOLUME_REACHED |
| 运行时长 ≥ maxDurationSec | 自动关阀 | DURATION_LIMIT |
| EC > ecHigh 持续 2 周期 | 关阀停肥 | INTERLOCK_EC_HIGH |
| pH ∉ [phLow, phHigh] | 关阀 | INTERLOCK_PH_LOW/HIGH |

OPEN 命令自带 plannedVolume / maxDurationSec / hardMaxMoisture 三个兜底参数，
即使与 EMQX、云端全部失联，网关也能自行安全关断。重启后所有阀门默认 CLOSED。

## 断网行为

- 采集与上传解耦：每帧消息先写 `data/gateway-outbox.db`；
- QoS1 PUBACK 后删除；断网期间积压，重连（paho 自动）后从最旧记录补传，`ts` 为原始采样时间；
- 控制订阅使用 clean_start=false 持久会话 + commandId 幂等，不丢不重。

## 测试

```bash
python -m pytest tests/ -q
```
