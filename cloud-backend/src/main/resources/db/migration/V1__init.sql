-- V1__init.sql : core schema for water-fertilizer irrigation cloud backend

CREATE TABLE device (
    id               BIGSERIAL PRIMARY KEY,
    code             VARCHAR(64)  NOT NULL UNIQUE,
    type             VARCHAR(32)  NOT NULL,
    gateway_sn       VARCHAR(64),
    modbus_addr      INTEGER,
    protocol_config  JSONB,
    online           BOOLEAN      NOT NULL DEFAULT FALSE,
    queue_depth      INTEGER      NOT NULL DEFAULT 0,
    last_heartbeat   TIMESTAMPTZ
);
COMMENT ON COLUMN device.type IS 'SOIL_SENSOR / WEATHER_STATION / VALVE / FLOW_METER / GATEWAY';

CREATE TABLE field_t (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    crop_code           VARCHAR(32)  NOT NULL,
    area_m2             NUMERIC(12,2) NOT NULL,
    irrigation_mode     VARCHAR(16)  NOT NULL DEFAULT 'DRIP',
    emitter_total_lph   NUMERIC(12,2),
    valve_code          VARCHAR(64),
    sowing_date         DATE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE field_sensor (
    field_id    BIGINT NOT NULL REFERENCES field_t(id) ON DELETE CASCADE,
    device_code VARCHAR(64) NOT NULL,
    sensor_role VARCHAR(32) NOT NULL DEFAULT 'SOIL',
    PRIMARY KEY (field_id, device_code)
);

CREATE TABLE crop_model (
    code   VARCHAR(32) PRIMARY KEY,
    name   VARCHAR(128) NOT NULL,
    stages JSONB NOT NULL DEFAULT '[]'::jsonb
);

CREATE TABLE field_config (
    field_id             BIGINT PRIMARY KEY REFERENCES field_t(id) ON DELETE CASCADE,
    theta_fc             NUMERIC(6,2),
    theta_wp             NUMERIC(6,2),
    mode                 VARCHAR(8) NOT NULL DEFAULT 'AUTO',
    hard_max_offset_pct  NUMERIC(6,2) NOT NULL DEFAULT 3,
    hard_min             NUMERIC(6,2),
    max_duration_sec     INTEGER,
    min_interval_h       NUMERIC(6,2),
    ec_min               NUMERIC(6,2),
    ec_max               NUMERIC(6,2),
    ph_min               NUMERIC(6,2),
    ph_max               NUMERIC(6,2),
    rain_skip_mm         NUMERIC(6,2),
    wet_ratio            NUMERIC(5,3),
    efficiency           NUMERIC(5,3),
    enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE irrigation_job (
    id              BIGSERIAL PRIMARY KEY,
    field_id        BIGINT NOT NULL REFERENCES field_t(id),
    trigger_type    VARCHAR(16) NOT NULL,
    decision        JSONB,
    start_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
    end_time        TIMESTAMPTZ,
    planned_m3      NUMERIC(10,3),
    applied_m3      NUMERIC(10,3) DEFAULT 0,
    duration_sec    INTEGER,
    status          VARCHAR(16) NOT NULL,
    stop_reason     VARCHAR(48),
    valve_code      VARCHAR(64),
    job_biz_code    VARCHAR(40)
);
CREATE INDEX idx_job_field_start ON irrigation_job(field_id, start_time DESC);
CREATE INDEX idx_job_status ON irrigation_job(status);

CREATE TABLE valve_command (
    command_id   VARCHAR(64) PRIMARY KEY,
    job_id       BIGINT REFERENCES irrigation_job(id),
    valve_code   VARCHAR(64) NOT NULL,
    command      VARCHAR(8)  NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    payload      JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ack_at       TIMESTAMPTZ,
    retry_count  INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_valve_command_job ON valve_command(job_id);

CREATE TABLE alarm (
    id           BIGSERIAL PRIMARY KEY,
    level        VARCHAR(16) NOT NULL,
    type         VARCHAR(64) NOT NULL,
    device_code  VARCHAR(64),
    field_id     BIGINT REFERENCES field_t(id),
    message      TEXT,
    context      JSONB,
    acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_alarm_created ON alarm(created_at DESC);
CREATE INDEX idx_alarm_pending ON alarm(level, acknowledged) WHERE acknowledged = FALSE;

CREATE TABLE sensor_latest (
    device_code  VARCHAR(64) PRIMARY KEY,
    field_id     BIGINT,
    payload      JSONB NOT NULL,
    state        VARCHAR(16),
    ts           TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_sensor_latest_field ON sensor_latest(field_id);
