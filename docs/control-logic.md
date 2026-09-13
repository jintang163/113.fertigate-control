# 自动控制逻辑（水肥一体化）

## 1. 决策原理：FAO-56 土壤水分亏缺法（允许亏缺灌溉）

核心思想：不等到土壤干到萎蔫点才浇水，而是在根区有效水分消耗到**可允许亏缺比例 p** 时启动灌溉，灌至田持（或略低），实现“少量多次”的精量灌溉。

记（均为体积含水率，%）：

- θfc：田间持水量（field capacity）
- θwp：萎蔫点（wilting point）
- TAW = (θfc − θwp) · Z_r：根区总有效水量（mm），Z_r 根深（mm）
- RAW = p · TAW：可允许耗水量（mm），p 为作物可消耗比例（无胁迫阈值，FAO-56 表22，常见 0.3–0.6）
- **启动阈值（体积含水率）：θ_start = θfc − p·(θfc − θwp)**
- 恢复目标：θ_target = θfc（滴灌可设 θfc − 1%~2% 防深层渗漏）

判据：

| 当前根区平均湿度 θ | 决策 |
|---|---|
| θ ≤ θ_start 且无约束冲突 | **IRRIGATE**（计算灌量/时长） |
| θ_start < θ < θfc | **HOLD**（不动作） |
| 未来 N 日预报降雨 ≥ 作物日耗水 × N（或净雨可补足亏缺） | **SKIP**（雨养，延迟灌溉） |
| θ ≥ hardMax 或传感器失联/EC/pH 超限 | **FORBID**（禁止开阀；运行中则联锁关阀） |

## 2. 灌量与施肥量计算

亏缺水深（需补到 θ_target）：

```
D_need (mm) = (θ_target − θ_now)/100 · Z_r · p_wet / η
```

- `p_wet` 湿润比（滴灌 0.3–0.9，视布置；漫灌=1.0）
- `η` 灌溉水利用系数（滴灌 ~0.9–0.95）

净灌量：

```
V (m³) = D_need(mm)/1000 · A(m²)
```

（D_need 单位 mm = L/m²，除以 1000 得 m³/m²）

滴灌时长：

```
T (h) = V / Q_emitters        Q_emitters = 单滴头流量(L/h) × 滴头数（或系统总流量 m³/h×1000）
```

施肥（与灌溉联动，文丘里/比例注肥泵）：

```
肥液量 = 目标养分增量(kg/亩→kg/m²) × A / 肥液浓度(kg/L)
EC/pH 闭环：混肥后 EC ∈ [ecMin, ecMax]（按作物/生育期），pH ∈ [phMin, phMax]；
超限 → 减注/停注肥，仅灌清水（边缘本地联锁 ecHigh/phLow 兜底）。
```

## 3. 作物生育期模型

每种作物一组参数，按**积温（GDD）或播后天数**划分生育期，各期 Kc、p、Z_r 不同：

示例（番茄，FAO-56）：

| 生育期 | 天数(典型) | Kc | p | Z_r (mm) |
|---|---|---|---|---|
| 苗期 initial | 0–30 | 0.6 | 0.5 | 200 |
| 发育期 dev | 31–60 | 0.6→1.15 | 0.5→0.4 | 400 |
| 中期 mid | 61–100 | 1.15 | 0.4 | 700 |
| 后期 late | 101–130 | 1.15→0.8 | 0.4→0.5 | 700 |

参考作物蒸散（气象站有辐射/温度时 Hargreaves 简化）：

```
ET0 = 0.0023 · (T_mean + 17.8) · (T_max − T_min)^0.5 · Ra     (mm/d)
Ra 由纬度与日序推算（Hargreases 外空辐射）
ETc = Kc · ET0
```

ETc 用于：① 校验湿度下降趋势合理性；② 降雨 SKIP 判断（rainForecast ≥ ETc × N）；③ 湿度传感器异常时的水量平衡兜底估算。

决策服务输入：当前 θ、θfc/θwp、作物模型+播期/GDD、气象（温/雨/预报）、田块参数（A、p_wet、η、滴头总流量）、EC/pH。
输出：`decision=IRRIGATE/HOLD/SKIP/FORBID`、动态 θ_start/θ_target、计划水量 m³、时长 s、依据说明、生育期。

## 4. 云端控制引擎状态机（每个田块一个阀门逻辑实例）

```
IDLE ──(评估: IRRIGATE, 全部安全条件通过)──► STARTING ──(收到阀 OPEN ACK)──► RUNNING
  ▲                                          │                              │
  │                                          ▼                              ▼
  └────────────────────────── DONE ◄── CLOSED+结算 ◄── 停止条件成立 ─────────┘
IDLE: HOLD/SKIP → 等待；FORBID → 告警
STARTING: 命令下发后 30s 未收到 OPEN ACK → 重试1次 → 失败告警，回 IDLE
RUNNING 停止条件（任一）：
  1. 累计流量 ≥ plannedVolume（VOLUME_REACHED）
  2. 运行时长 ≥ maxDurationSec（DURATION_LIMIT）
  3. 实时湿度 ≥ θ_target（MOISTURE_TARGET，云端软停）
  4. 湿度 ≥ hardMax / 传感器失联 / 阀故障（安全关断）
  5. MANUAL 停止 / 模式被切 MANUAL
停止：下发 CLOSE（幂等 commandId）→ 收 CLOSED → 写作业实际水量、结束
```

安全前置（OPEN 前必须全部满足）：阀门在线、最近一次湿度数据 < θ_start、数据未过期（staleness，默认 10min）、
hardMax 未触发、EC/pH 在窗口内、无未确认 CRITICAL 告警、距上次作业 ≥ minIntervalH（AUTO 防抖）、
网关在线、无同管线其它阀门在灌（水力冲突，简化为同 field 单阀互斥）。

手动优先：田块 `mode=MANUAL` 时引擎只监视不自动启停；前端按钮直接下发 OPEN/CLOSE，仍受边缘硬联锁约束。

## 5. 边缘本地联锁（独立于云端，最高优先级）

网关在每个采集周期（10s）执行，**任何模式下生效**：

1. `θ ≥ moistureHardMax`（默认 θfc+3 个百分点，可下发）→ 所有关联阀强制 CLOSE，事件 `INTERLOCK_MOISTURE_HARD_MAX`
2. 湿度传感器 `sensorLostSec`（默认 180s）无有效数据 → 禁止开阀；运行中则关阀 `INTERLOCK_SENSOR_LOST`
3. 阀位反馈与命令不一致超过 60s / Modbus 故障 → `INTERLOCK_VALVE_FAULT` 关阀
4. EC > ecHigh（3.0 mS/cm，防肥害烧根）→ 停止注肥；持续超限关阀 `INTERLOCK_EC_HIGH`
5. pH 超出 [phLow, phHigh]（5.0–8.0）→ `INTERLOCK_PH_*`
6. OPEN 命令自带兜底：plannedVolume/maxDurationSec/hardMaxMoisture，即使与云端失联也能自行关断。
7. 网关重启后默认所有阀 CLOSED、泵 STOPPED（继电器常闭设计），等待云端状态同步，不记忆“开”。
8. **缺水联锁**：主管道水压 < `pressureMinKpa`（默认 80kPa）持续 `waterLostDelaySec`（默认 15s）
   → `INTERLOCK_WATER_LOST` 关阀停泵；阀开且瞬时流量 < `flowMinM3h`（启动 30s 宽限后）持续超限
   → `INTERLOCK_FLOW_LOW`（爆管/堵塞/缺水）关阀停泵。
9. **施肥泵过载**：泵电流 > `pumpOverloadA`（默认 8A）或状态位过载 → `INTERLOCK_PUMP_OVERLOAD` 紧急停泵并关阀。
10. **通信中断联锁**：与云端连接丢失超过 `commLostSec`（默认 300s，曾成功连接后才判定）
    → `INTERLOCK_COMM_LOST` 本地紧急关闭全部阀门与施肥泵；OPEN 自带兜底参数保证即使立即失联也安全。

## 5b. 云端安全联锁（边缘联锁的第二道防线）

- 开阀前置（任何模式）：阀/网关在线、数据新鲜、湿度 < hardMax、**水压 ≥ pressureMinKpa**、
  **泵未过载**、无未确认 CRITICAL 告警；
  AUTO/SCHEDULED 另加：θ < θ_start、EC/pH 在窗口、最小间隔、气象联动（风速/气温/湿度/当日与预报降雨）、
  灌区灌溉时窗（windowStart/End + 周位图）、同 field 无并发作业。
- 运行监管：水压低/低流量（按 waterLostDelaySec 去抖，低流量 30s 启动宽限）、泵过载、
  湿度硬上限、传感器失联、达量、超时、湿度达标、阀故障——任一触发即下发 CLOSE 并停注肥泵。
- 轮灌计划项释放被前置拦截时标记 BLOCKED 并告警，不影响后续灌区排程。

## 5c. 施肥泵与轮灌

- 注肥泵走通用执行器通道 `device/command`（START/STOP/SET_OPENING + opening 0-100，幂等 commandId），
  状态走 `device/status`（opening/motorCurrent/overload）。
- 灌区 `fertPlan` 定义 PRE_WATER（清水）/ MID_RUN（注肥）/ FLUSH（冲洗）三阶段，
  引擎按作业时长比例切换；泵开度按 `注肥比% × 水流量 / 泵额定流量(capacityLph)` 折算。
- 分区轮灌：按优先级（小者优先）+ 墒情（越旱越优先）排序，串行排程（同主管同时只灌一个区），
  各项 scheduledStart 自动落入本区灌溉时窗；作业 DONE 后自动释放下一区。
- 每次作业结算自动写灌肥台账（WATER 必写；有注肥配置时追加 FERTIGATION，记录肥液量与执行方式）。

## 6. 时序与参数默认值

- 采集周期 10s；心跳 60s；云端评估周期 5min（可配）；数据 staleness 10min
- 最小作业间隔 30min；单次最长 15min（滴灌“少量多次”，可配）
- 湿度取多点传感器（10/20/30cm）按根深加权平均；单点评废按质量码剔除
