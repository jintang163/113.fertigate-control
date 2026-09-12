-- V2__seed.sql : demo gateway / devices / tomato field / crop model

-- Gateway itself is registered as a device row of type GATEWAY for health tracking
INSERT INTO device (code, type, gateway_sn, modbus_addr, protocol_config, online, last_heartbeat)
VALUES ('GW001', 'GATEWAY', 'GW001', NULL,
        '{"pollIntervalSec":10,"fw":"1.0.0"}'::jsonb, FALSE, NULL);

INSERT INTO device (code, type, gateway_sn, modbus_addr, protocol_config) VALUES
 ('SOIL-A1', 'SOIL_SENSOR',     'GW001', 1, '{"depthMm":100,"baud":9600}'::jsonb),
 ('SOIL-A2', 'SOIL_SENSOR',     'GW001', 2, '{"depthMm":200,"baud":9600}'::jsonb),
 ('WS-01',   'WEATHER_STATION', 'GW001', 3, '{"baud":4800}'::jsonb),
 ('V-01',    'VALVE',           'GW001', 4, '{"normallyClosed":true}'::jsonb),
 ('FM-01',   'FLOW_METER',      'GW001', 5, '{"kFactor":12.5,"unit":"m3"}'::jsonb);

-- Tomato crop model: linear interpolation expressed with start/end pairs
-- (initial 0-30d, development 30-60d, mid 60-100d, late 100-130d)
INSERT INTO crop_model (code, name, stages) VALUES
 ('tomato', '番茄', jsonb_build_array(
   jsonb_build_object('name','initial','startDay',0,'endDay',30,
                      'startKc',0.6,'endKc',0.6,'startP',0.5,'endP',0.5,'zrMm',200),
   jsonb_build_object('name','development','startDay',30,'endDay',60,
                      'startKc',0.6,'endKc',1.15,'startP',0.5,'endP',0.4,'zrMm',400),
   jsonb_build_object('name','mid','startDay',60,'endDay',100,
                      'startKc',1.15,'endKc',1.15,'startP',0.4,'endP',0.4,'zrMm',700),
   jsonb_build_object('name','late','startDay',100,'endDay',130,
                      'startKc',1.15,'endKc',0.8,'startP',0.4,'endP',0.5,'zrMm',700)
 ));

-- Field: zone A, tomato, drip irrigation, total emitter flow 2400 L/h
INSERT INTO field_t (id, name, crop_code, area_m2, irrigation_mode, emitter_total_lph, valve_code, sowing_date)
VALUES (1, 'A区番茄地', 'tomato', 2000, 'DRIP', 2400, 'V-01', DATE '2026-06-29');
SELECT setval(pg_get_serial_sequence('field_t','id'), GREATEST((SELECT MAX(id) FROM field_t), 1));

INSERT INTO field_sensor (field_id, device_code, sensor_role) VALUES
 (1, 'SOIL-A1', 'SOIL'),
 (1, 'SOIL-A2', 'SOIL'),
 (1, 'FM-01',   'FLOW'),
 (1, 'WS-01',   'WEATHER');

INSERT INTO field_config
 (field_id, theta_fc, theta_wp, mode, hard_max_offset_pct, hard_min,
  max_duration_sec, min_interval_h, ec_min, ec_max, ph_min, ph_max,
  rain_skip_mm, wet_ratio, efficiency, enabled)
VALUES
 (1, 30, 12, 'AUTO', 3, 8,
  900, 0.5, 1.2, 2.6, 5.5, 7.5,
  5, 0.8, 0.9, TRUE);
