# 一键部署（docker compose）

在安装了 Docker 24+ / compose v2 的机器上（deploy 目录内执行）：

```bash
# 1) 基础平台 + 云端 + 决策 + 前端（不含物理网关）
docker compose up -d --build

# 2) 无硬件演示：再启动仿真边缘网关（向 EMQX 打数据并响应阀控）
docker compose --profile simulate up -d --build edge-gateway-sim
```

组件与入口：

| 服务 | 地址 | 凭据 |
|---|---|---|
| 前端 | http://localhost | — |
| 云端后端 REST | http://localhost:8080/api | — |
| EMQX Dashboard | http://localhost:18083 | admin / public（首次登录需改密，可跳过） |
| EMQX MQTT | tcp://localhost:1883 | — |
| 决策服务 | http://localhost:8000/docs | — |
| InfluxDB UI | http://localhost:8086 | farm / farm12345 |
| PostgreSQL | localhost:5432 | farm / farm123，库 farm |

## 端到端演示流程

1. `edge-gateway-sim` 上线后，EMQX 中可见客户端 `gw-GW001`；InfluxDB bucket `telemetry` 出现 soil/weather/flow 数据（约 10s 一帧）。
2. 等待云端调度（每 5 分钟一轮），或立即触发：
   ```bash
   curl -X POST http://localhost:8080/api/control/run-cycle
   ```
   仿真初始湿度 21% < 番茄中期 θ_start≈22.8% → 自动创建作业、下发 OPEN。
3. 在 EMQX Dashboard 的 `farm/GW001/valve/command` 主题可看到指令；网关执行后湿度在仿真水文模型下逐步抬升，达计划水量后本地自动关阀并回报 `VOLUME_REACHED`，作业结算。
4. 前端「田块详情」可看湿度曲线 + θfc / θ_start / hardMax 阈值线、作业进度；手动开/关阀、切 AUTO/MANUAL、改阈值；「告警中心」看联锁事件。
5. 断网演练：`docker compose stop emqx`，观察网关日志 `queue depth` 上涨、SQLite 缓存；`start` 后自动补传。

## 真实硬件

在田间工控机安装 Python 3.11，复制 `edge-gateway/`，改 `config/gateway.yaml`：
`mode: modbus`、`modbus.serialPort: /dev/ttyS4`、`mqtt.host` 指向云端 EMQX，
`python -m app.main -c config/gateway.yaml`（建议 systemd 托管，挂载 /dev/ttyS*）。

## 本地开发（不用 Docker）

按 `decision-service` → `cloud-backend`(JDK17/Maven) → `web`(Node22) 各自 README 启动，
中间件可只跑 `docker compose up -d emqx postgres influxdb`。
