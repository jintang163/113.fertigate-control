# 作物模型灌溉决策服务（Decision Service）

无状态 Python 服务：输入墒情、气象、田块与作物生育期模型，输出灌溉决策。
被云端后端控制引擎调用（集群内），也可独立用于方案试算。

## 算法

- **动态阈值（FAO-56 允许亏缺灌溉）**：θ_start = θfc − p·(θfc−θwp)，
  p / Kc / 根深 Zr 按播后天数在生育期 stages 间线性插值。
- **ET0**：Hargreaves-Samani（纬度 + 日序 + Tmax/Tmin 解析地外辐射），ETc = Kc·ET0。
- **灌量**：D = (θ_target−θ)/100·Zr·湿润比/η [mm]，V = D/1000·A [m³]，T = V·1000/Q [h]；
  超过单次最大时长则削顶（`clampReason=DURATION_LIMIT`），余量下轮续灌。
- **决策**：IRRIGATE / HOLD / SKIP（未来有效降雨可覆盖亏缺）/ FORBID（hardMax、EC、pH、传感器异常）。
- 根区多点湿度按埋深层厚加权平均。

## 运行

```bash
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
# 交互文档 http://localhost:8000/docs
```

## 接口

- `GET /healthz`
- `POST /decide` — 请求/响应格式见 `../docs/api-contract.md` 末尾

## 测试

```bash
python -m pytest tests/ -q     # 13 用例：阈值/削顶/降雨/联锁/插值/分层湿度/ET0
```
