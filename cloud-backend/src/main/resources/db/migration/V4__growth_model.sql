-- V4__growth_model.sql : Python 生长模型服务集成
--
-- 模块对应：
--   生长模型驱动 -> field_config.growth_model_enabled（田块级开关）：
--                   true 时该田块由 Python 生长模型服务融合决策（品种+生育期需水需肥曲线）
--                   并通过 POST /api/growth/callback 回调执行，云端定时评估跳过该田块，
--                   避免双重决策；执行仍走 ControlEngine 全量安全前置。
--   执行方式     -> trigger_type 新增 MODEL（生长模型回调触发），台账 execution_mode 同步。

ALTER TABLE field_config ADD COLUMN IF NOT EXISTS growth_model_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN field_config.growth_model_enabled IS '生长模型驱动开关：true 时由 Python 生长模型服务回调决策（triggerType=MODEL），云端定时评估跳过该田块';
