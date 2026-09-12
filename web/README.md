# 水肥一体化智能灌溉控制系统 — PC 前端

基于 **Vite 5 + TypeScript + Vue 3（`<script setup lang="ts">`）+ Ant Design Vue 4.x + ECharts 5 + Pinia + Vue Router 4 + axios** 实现，对接 Spring Boot 云端后端（`:8080`，REST 前缀 `/api`）。

## 启动

```bash
npm install        # 如慢可加 --registry=https://registry.npmmirror.com
npm run dev        # http://localhost:5173 ，已配置 /api → http://localhost:8080 代理
npm run build      # vue-tsc 类型检查 + vite 构建，产物 dist/
npm run preview    # 本地预览构建产物
```

环境要求：Node 18+（本机验证 Node 22）。

## 与后端联调

1. 启动云端后端 `cloud-backend`（Spring Boot，:8080）及决策服务（Python :8000）。
2. `npm run dev` 后，所有 `/api/**` 请求由 Vite 代理转发到 `http://localhost:8080`（见 `vite.config.ts`，改后端地址改这里）。
3. 后端未启动时页面不会崩溃：列表展示空态、顶栏网关徽标显示“后端未连接”，GET 失败仅 console.warn（避免轮询刷屏），写操作（POST/PUT）失败通过 `message.error` 提示。

统一响应体 `{ code, msg, data }` 在 `src/api/http.ts` 拦截器中解包，`code !== 0` 或 HTTP 错误进入 reject。

## 页面 / 路由 / 接口对应

| 路由 | 页面 | 主要接口 |
|---|---|---|
| `/dashboard` | 总览仪表盘（4 统计卡 / 各田块湿度 vs θ_start 进度条 / 最近作业 / 告警，30s 轮询） | `GET /fields`、`/fields/{id}/status`、`/devices`、`/jobs`、`/alarms`、`/gateways`（顶栏） |
| `/fields` | 田块卡片列表（作物、AUTO/MANUAL 开关、湿度、θ_start、阀门 Tag、最近作业） | `GET /fields`、`GET /fields/{id}/status`、`PUT /fields/{id}` |
| `/fields/:id` | 田块详情：状态区 / 手动控制（force 二次确认、联锁禁用）/ 湿度 ECharts 主图（θfc、θ_start、hardMax markLine + 作业 markArea，6h/24h/72h/7d）/ EC、pH 小图 / 决策试算 / FieldConfig 表单 | `GET /fields/{id}/status`、`POST /fields/{id}/control`、`POST /fields/{id}/decision/evaluate`、`GET /telemetry`、`GET /jobs`、`PUT /fields/{id}` |
| `/crops` | 作物模型表格 + Drawer 编辑 stages（name/startDay/endDay/startKc/endKc/startP/endP/zrMm 可增删） | `GET /crop-models`、`PUT /crop-models/{code}` |
| `/devices` | 网关列表（在线/queueDepth/下发配置弹窗）+ 设备表（类型/网关/modbus/在线/心跳/最新值） | `GET /gateways`、`POST /gateways/{sn}/config`、`GET /devices` |
| `/jobs` | 作业分页表（田块/状态筛选、状态 Tag、展开行显示 decision 依据 / stopReason / 水量时长） | `GET /jobs?fieldId=&status=&page=&size=` |
| `/alarms` | 告警表（级别颜色、未确认高亮、单条/批量 ack） | `GET /alarms?level=&ack=&page=`、`POST /alarms/{id}/ack` |

## 控制逻辑在界面上的表达

- 启动阈值 **θ_start = θfc − p·(θfc − θwp)**，p/Kc/根深随作物生育期变化，由决策服务计算；详情页状态区同时展示 θ_start / θfc / θwp 与硬上限。
- 手动开阀：MANUAL 直接生效（popconfirm）；AUTO 必须勾选 **force** 并经 Modal 二次确认，请求体 `{ action:'OPEN', volumeM3, force }`。
- 安全前置/边缘硬联锁（湿度 ≥ hardMax、数据缺失/传感器失联、阀门 FAULT、未确认 CRITICAL 告警）会禁用开阀按钮并以红色 Alert 列出原因；关阀按钮按阀门状态启停。
- 图表 markArea 标注今日 RUNNING（绿）/ DONE（黄）作业时段。

## 目录结构

```
web/
├── index.html
├── package.json
├── vite.config.ts          # /api → http://localhost:8080 代理
├── tsconfig.json / tsconfig.node.json
└── src/
    ├── main.ts / App.vue   # 全量引入 AntDV；a-layout 侧边导航 + 顶栏网关状态
    ├── api/                # http.ts（axios + ApiResponse 解包）、index.ts（各资源模块）
    ├── stores/             # Pinia: fields、devices
    ├── types/              # 按 API 契约的 interface
    ├── router/             # 7 个路由（无登录）
    ├── utils/              # 水量/时长/时间格式化、状态标签颜色
    ├── components/         # MoistureChart.vue、MetricLineChart.vue（ECharts5 自封装）
    ├── styles/main.css
    └── views/              # Dashboard / Fields / FieldDetail / Crops / Devices / Jobs / Alarms
```

## 图表配色

土壤湿度 `#1677ff`（折线 + 蓝色面积渐变）、EC `#52c41a`、pH `#faad14`；θfc 蓝色虚线、θ_start 橙色虚线、硬上限红色虚线；tooltip `trigger: axis`，主图含 inside + slider dataZoom，ResizeObserver 自适应。
