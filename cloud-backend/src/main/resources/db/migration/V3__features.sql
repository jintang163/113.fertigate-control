-- V3__features.sql : 设备管理增强 / 灌区 / 阈值策略(气象联动) / 轮灌 / 施肥泵 / 安全联锁 / 灌肥台账
--
-- 模块对应：
--   设备管理  -> device 扩展(name/status/opening/linked_field/params) + 离线检测
--   灌区管理  -> field_t 扩展(作物品种/优先级/施肥泵/注肥比/灌溉时窗) + crop_stage_record
--   阈值策略  -> field_config 扩展(土壤湿度下限、气象联动条件)
--   轮灌调度  -> rotation_plan / rotation_plan_item
--   控制下发  -> device_command（通用执行器通道：电磁阀/施肥泵/调节阀）
--   安全联锁  -> field_config 水压/流量/电机过载阈值；联锁事件仍记入 alarm
--   执行方式  -> trigger_type 已有 AUTO/MANUAL/SAFETY_OFF；新增 SCHEDULED(轮灌计划触发)
--   灌肥台账  -> irrigation_ledger

-- ---------------------------------------------------------------------------
-- 1. 设备表扩展：命名、设备状态、执行器开度、所属灌区、注册时间、参数
-- ---------------------------------------------------------------------------
ALTER TABLE device ADD COLUMN IF NOT EXISTS name          VARCHAR(128);
ALTER TABLE device ADD COLUMN IF NOT EXISTS status        VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';
-- 执行器（电磁阀/施肥泵/调节阀）当前开度 0-100；传感器为 NULL
ALTER TABLE device ADD COLUMN IF NOT EXISTS opening       INTEGER;
ALTER TABLE device ADD COLUMN IF NOT EXISTS linked_field  BIGINT REFERENCES field_t(id);
ALTER TABLE device ADD COLUMN IF NOT EXISTS registered_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE device ADD COLUMN IF NOT EXISTS params        JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS idx_device_type ON device(type);
CREATE INDEX IF NOT EXISTS idx_device_gateway ON device(gateway_sn);
CREATE INDEX IF NOT EXISTS idx_device_status ON device(status);

COMMENT ON COLUMN device.type IS 'SOIL_SENSOR / WEATHER_STATION / VALVE / FLOW_METER / FERT_PUMP / PRESSURE_SENSOR / GATEWAY';
COMMENT ON COLUMN device.status IS 'UNKNOWN / ENABLED / DISABLED / FAULT（投运状态；在线性见 online）';
COMMENT ON COLUMN device.opening IS '执行器当前开度 0-100（仅 VALVE/FERT_PUMP 有意义）';
COMMENT ON COLUMN device.params IS '设备参数：capacityLph、linkedPressureSensor、normalOpen 等';

-- ---------------------------------------------------------------------------
-- 2. 灌区（田块）扩展：作物品种、轮灌优先级、施肥泵与注肥比、灌溉时窗
-- ---------------------------------------------------------------------------
ALTER TABLE field_t ADD COLUMN IF NOT EXISTS crop_variety     VARCHAR(64);
ALTER TABLE field_t ADD COLUMN IF NOT EXISTS priority         INTEGER NOT NULL DEFAULT 100;
ALTER TABLE field_t ADD COLUMN IF NOT EXISTS fert_pump_code   VARCHAR(64);
ALTER TABLE field_t ADD COLUMN IF NOT EXISTS inject_ratio_pct NUMERIC(6,3) NOT NULL DEFAULT 0.000;
-- 自动/轮灌允许执行的时间窗（一天中的分钟数，本地时区；可空=不限制）
ALTER TABLE field_t ADD COLUMN IF NOT EXISTS window_start_min SMALLINT;
ALTER TABLE field_t ADD COLUMN IF NOT EXISTS window_end_min   SMALLINT;
ALTER TABLE field_t ADD COLUMN IF NOT EXISTS window_days      VARCHAR(7) DEFAULT '1111111';
-- 注肥阶段（jsonb 数组）：[{"phase":"MID_RUN","ratioPct":1.2,"durationFraction":0.6}, ...]
ALTER TABLE field_t ADD COLUMN IF NOT EXISTS fert_plan        JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_field_priority ON field_t(priority);

-- ---------------------------------------------------------------------------
-- 3. 阈值策略配置扩展：土壤湿度下限 + 气象联动条件 + 缺水/过载联锁阈值
-- ---------------------------------------------------------------------------
ALTER TABLE field_config ADD COLUMN IF NOT EXISTS moisture_lower_pct   NUMERIC(6,2);
ALTER TABLE field_config ADD COLUMN IF NOT EXISTS moisture_upper_pct   NUMERIC(6,2);
-- 气象联动
ALTER TABLE field_config ADD COLUMN IF NOT EXISTS weather_linked       BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE field_config ADD COLUMN IF NOT EXISTS wind_max_ms          NUMERIC(6,2);
ALTER TABLE field_config ADD COLUMN IF NOT EXISTS temp_min             NUMERIC(6,2);
ALTER TABLE field_config ADD COLUMN IF NOT EXISTS temp_max             NUMERIC(6,2);
ALTER TABLE field_config ADD COLUMN IF NOT EXISTS humidity_min         NUMERIC(6,2);
ALTER TABLE field_config ADD COLUMN IF NOT EXISTS rain_today_skip_mm   NUMERIC(6,2);
ALTER TABLE field_config ADD COLUMN IF NOT EXISTS forecast_skip_mm     NUMERIC(6,2);
ALTER TABLE field_config ADD COLUMN IF NOT EXISTS forecast_days        SMALLINT NOT NULL DEFAULT 3;
-- 安全联锁：水源侧（水压 kPa / 阀开期望瞬时流量 m³/h，低于阈值持续 delay 秒判缺水）
ALTER TABLE field_config ADD COLUMN IF NOT EXISTS pressure_min_kpa     NUMERIC(8,2);
ALTER TABLE field_config ADD COLUMN IF NOT EXISTS flow_min_m3h         NUMERIC(8,3);
ALTER TABLE field_config ADD COLUMN IF NOT EXISTS water_lost_delay_sec INTEGER NOT NULL DEFAULT 15;
-- 执行器电气联锁：施肥泵过载电流 A（上报 motorCurrent）
ALTER TABLE field_config ADD COLUMN IF NOT EXISTS pump_overload_a      NUMERIC(8,2);

COMMENT ON COLUMN field_config.weather_linked IS 'true 时自动开阀前校验气象联动条件（风速/温湿度/当日降雨/预报降雨）';

-- ---------------------------------------------------------------------------
-- 4. 作物生育期记录（人工登记，用于播后天数/GDD 模型的人工校正与追溯）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS crop_stage_record (
    id           BIGSERIAL PRIMARY KEY,
    field_id     BIGINT NOT NULL REFERENCES field_t(id) ON DELETE CASCADE,
    stage_code   VARCHAR(32) NOT NULL,
    stage_name   VARCHAR(64),
    record_date  DATE NOT NULL,
    note         VARCHAR(256),
    operator     VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_stage_record_field ON crop_stage_record(field_id, record_date DESC);

-- ---------------------------------------------------------------------------
-- 5. 分区轮灌计划：按灌区、时间、优先级生成
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rotation_plan (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(128) NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'DRAFT',  -- DRAFT/SCHEDULED/RUNNING/DONE/CANCELLED
    plan_date        DATE,
    generated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    generated_by     VARCHAR(64) NOT NULL DEFAULT 'AUTO',   -- AUTO / MANUAL
    note             VARCHAR(256),
    total_planned_m3 NUMERIC(12,3) DEFAULT 0,
    total_applied_m3 NUMERIC(12,3) DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_rotation_status_date ON rotation_plan(status, plan_date);

CREATE TABLE IF NOT EXISTS rotation_plan_item (
    id                 BIGSERIAL PRIMARY KEY,
    plan_id            BIGINT NOT NULL REFERENCES rotation_plan(id) ON DELETE CASCADE,
    field_id           BIGINT NOT NULL REFERENCES field_t(id),
    seq                INTEGER NOT NULL,
    priority           INTEGER NOT NULL DEFAULT 100,
    scheduled_start    TIMESTAMPTZ,
    planned_volume_m3  NUMERIC(10,3),
    job_id             BIGINT REFERENCES irrigation_job(id),
    status             VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/RELEASED/DONE/SKIPPED/BLOCKED
    skip_reason        VARCHAR(128),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS rotation_item_plan ON rotation_plan_item(plan_id, seq);
CREATE INDEX IF NOT EXISTS rotation_item_due ON rotation_plan_item(status, scheduled_start);

-- ---------------------------------------------------------------------------
-- 6. 通用执行器控制指令（电磁阀 / 施肥泵 / 调节阀），复用幂等语义
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_command (
    command_id   VARCHAR(64) PRIMARY KEY,
    device_code  VARCHAR(64) NOT NULL,
    device_type  VARCHAR(32),
    job_id       BIGINT REFERENCES irrigation_job(id),
    action       VARCHAR(16) NOT NULL,      -- START / STOP / SET_OPENING
    opening      INTEGER,                   -- 0-100
    payload      JSONB,
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/SENT/ACKED/DONE/FAILED/TIMEOUT/REJECTED
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ack_at       TIMESTAMPTZ,
    retry_count  INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_device_command_job ON device_command(job_id);
CREATE INDEX IF NOT EXISTS idx_device_command_status ON device_command(status);

-- ---------------------------------------------------------------------------
-- 7. 灌肥台账：每次灌水/施肥的开始/结束、用量、执行方式
--    WATER 清水灌溉；FERTIGATION 施肥灌溉（肥液量来自注肥泵开度×时长×额定流量）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS irrigation_ledger (
    id               BIGSERIAL PRIMARY KEY,
    field_id         BIGINT NOT NULL REFERENCES field_t(id),
    job_id           BIGINT REFERENCES irrigation_job(id) ON DELETE SET NULL,
    plan_item_id     BIGINT REFERENCES rotation_plan_item(id) ON DELETE SET NULL,
    kind             VARCHAR(16) NOT NULL,             -- WATER / FERTIGATION
    start_time       TIMESTAMPTZ NOT NULL,
    end_time         TIMESTAMPTZ,
    water_m3         NUMERIC(10,3) NOT NULL DEFAULT 0,
    fertilizer_kg    NUMERIC(10,3) NOT NULL DEFAULT 0,
    fertilizer_l     NUMERIC(10,3) NOT NULL DEFAULT 0, -- 肥液量 L
    fertilizer_name  VARCHAR(64),
    inject_ratio_pct NUMERIC(6,3),
    execution_mode   VARCHAR(16) NOT NULL,             -- AUTO / MANUAL / SCHEDULED / SAFETY
    stop_reason      VARCHAR(48),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ledger_field_start ON irrigation_ledger(field_id, start_time DESC);
CREATE INDEX IF NOT EXISTS idx_ledger_job ON irrigation_ledger(job_id);
CREATE INDEX IF NOT EXISTS idx_ledger_kind_start ON irrigation_ledger(kind, start_time DESC);

-- ---------------------------------------------------------------------------
-- 8. 种子数据补充：压力传感器 + 施肥泵；A区绑定注肥泵；联锁阈值与气象联动
-- ---------------------------------------------------------------------------
INSERT INTO device (code, type, name, gateway_sn, modbus_addr, protocol_config, online, status, params)
VALUES
 ('PS-01', 'PRESSURE_SENSOR', '主管道压力变送器', 'GW001', 6,
  '{"unit":"kPa","range":[0,600]}'::jsonb, FALSE, 'UNKNOWN', '{}'::jsonb),
 ('FP-01', 'FERT_PUMP', '比例注肥泵1号', 'GW001', 7,
  '{"normallyClosed":true}'::jsonb, FALSE, 'UNKNOWN',
  '{"capacityLph":120,"linkedPressureSensor":"PS-01","linkedFlowMeter":"FM-01"}'::jsonb)
ON CONFLICT (code) DO NOTHING;

UPDATE field_t
   SET crop_variety = '金棚一号', priority = 10, fert_pump_code = 'FP-01',
       inject_ratio_pct = 1.5,
       window_start_min = 360, window_end_min = 1140, window_days = '1111111',
       fert_plan = jsonb_build_array(
         jsonb_build_object('phase','PRE_WATER',  'ratioPct', 0.0, 'durationFraction', 0.2),
         jsonb_build_object('phase','MID_RUN',    'ratioPct', 1.5, 'durationFraction', 0.6),
         jsonb_build_object('phase','FLUSH',      'ratioPct', 0.0, 'durationFraction', 0.2))
 WHERE id = 1;

UPDATE field_config
   SET moisture_lower_pct = theta_wp + 2,
       moisture_upper_pct = theta_fc,
       weather_linked = TRUE,
       wind_max_ms = 6.0,
       temp_min = 5.0,
       temp_max = 38.0,
       humidity_min = 30.0,
       rain_today_skip_mm = 5.0,
       forecast_skip_mm = 10.0,
       forecast_days = 3,
       pressure_min_kpa = 80.0,
       flow_min_m3h = 0.5,
       water_lost_delay_sec = 15,
       pump_overload_a = 8.0
 WHERE field_id = 1;

-- 主管道压力与注肥泵接入 A 区关联
INSERT INTO field_sensor (field_id, device_code, sensor_role) VALUES
 (1, 'PS-01', 'PRESSURE'),
 (1, 'FP-01', 'PUMP')
ON CONFLICT DO NOTHING;
