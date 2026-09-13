"""施肥泵控制 + 缺水（水压低/流量低）+ 泵过载 + 通信中断联锁测试。"""
from __future__ import annotations

import os
import time

import pytest

from app.config import load_config
from app.messages import new_command_id
from app.pump_controller import PumpController
from app.valve_controller import ValveController

CONFIG = os.path.join(os.path.dirname(__file__), "..", "config", "gateway.example.yaml")


@pytest.fixture
def cfg(tmp_path):
    c = load_config(CONFIG)
    c.sqlitePath = str(tmp_path / "outbox.db")
    c.interlocks.sensorLostSec = 1.0
    c.interlocks.waterLostDelaySec = 0.05   # 缩短去抖
    c.pollIntervalSec = 0.05
    return c


@pytest.fixture
def pumps(cfg):
    calls: list[tuple[str, int]] = []
    ctl = PumpController(cfg)
    ctl.attach_driver(lambda code, opening: calls.append((code, opening)))
    ctl._drive_calls = calls  # type: ignore[attr-defined]
    return ctl


def frame(moist: float = 21.0, pressure: float = 250.0, instant: float = 2.4,
          total: float = 0.0, pump_current: float | None = None,
          pump_overload: bool = False):
    f = {
        "SOIL-A1": {"kind": "soil", "quality": "GOOD", "values": {
            "soilMoist": moist, "ec": 1.6, "ph": 6.4}},
        "FM-01": {"kind": "flow", "quality": "GOOD", "values": {
            "instantFlow": instant, "totalFlow": total}},
        "PS-01": {"kind": "pressure", "quality": "GOOD",
                  "values": {"pressure": pressure}},
    }
    if pump_current is not None:
        f["FP-01"] = {"kind": "pump", "quality": "GOOD", "values": {
            "opening": 100, "motorCurrent": pump_current, "overload": pump_overload}}
    return f


def pump_cmd(action: str = "START", opening: int = 100, **over):
    msg = {
        "type": "deviceCommand", "gatewaySn": "GW001", "commandId": new_command_id(),
        "deviceCode": "FP-01", "jobId": "JOB-1", "action": action,
        "opening": opening, "injectRatioPct": 1.5,
    }
    msg.update(over)
    return msg


# ----------------------------- 泵基本控制 ----------------------------- #

def test_pump_start_stop(pumps):
    pumps.evaluate(frame())
    replies = pumps.handle_command(pump_cmd("START", opening=80))
    assert any(m.get("state") == "RUNNING" and m.get("opening") == 80
               for m in replies if m["type"] == "deviceStatus")
    assert ("FP-01", 80) in pumps._drive_calls

    replies = pumps.handle_command(pump_cmd("STOP"))
    assert any(m.get("state") == "STOPPED" for m in replies if m["type"] == "deviceStatus")
    assert ("FP-01", 0) in pumps._drive_calls


def test_pump_command_idempotent(pumps):
    pumps.evaluate(frame())
    cmd = pump_cmd()
    pumps.handle_command(cmd)
    n = len(pumps._drive_calls)
    pumps.handle_command(dict(cmd))   # 重复 commandId
    assert len(pumps._drive_calls) == n


def test_pump_start_blocked_by_low_pressure(pumps):
    pumps.evaluate(frame(pressure=20.0))   # 缺水
    replies = pumps.handle_command(pump_cmd())
    codes = [m["code"] for m in replies if m["type"] == "event"]
    assert "COMMAND_REJECTED" in codes
    assert not any(code == "FP-01" and op > 0 for code, op in pumps._drive_calls)


def test_pump_overload_interlock(pumps):
    pumps.evaluate(frame())
    pumps.handle_command(pump_cmd())
    # 仿真帧：电流超过过载阈值 8A
    replies = pumps.evaluate(frame(pump_current=9.5))
    codes = [m["code"] for m in replies if m["type"] == "event"]
    assert "INTERLOCK_PUMP_OVERLOAD" in codes
    assert ("FP-01", 0) in pumps._drive_calls
    assert not pumps.is_any_running()


def test_pump_safety_stop_all(pumps):
    pumps.evaluate(frame())
    pumps.handle_command(pump_cmd())
    replies = pumps.safety_stop_all("INTERLOCK_COMM_LOST")
    assert any(m.get("code") == "INTERLOCK_COMM_LOST" for m in replies)
    assert not pumps.is_any_running()


# ----------------------------- 阀门缺水联锁 ----------------------------- #

@pytest.fixture
def valves(cfg):
    calls: list[tuple[str, bool]] = []
    ctl = ValveController(cfg)
    ctl.attach_driver(lambda code, open_: calls.append((code, open_)))
    ctl._drive_calls = calls  # type: ignore[attr-defined]
    return ctl


def _open_valve(ctl, planned=0.6):
    ctl.evaluate(frame(moist=20.0, total=10.0))
    ctl.handle_command({
        "type": "valveCommand", "gatewaySn": "GW001", "commandId": new_command_id(),
        "valveCode": "V-01", "jobId": "JOB-1", "command": "OPEN",
        "plannedVolume": planned, "maxDurationSec": 900,
        "hardMaxMoisture": 33.0, "sensorCode": "SOIL-A1"})


def test_valve_low_pressure_interlock(valves):
    _open_valve(valves)
    # 水压骤降，超过去抖时间
    valves.evaluate(frame(pressure=20.0))
    time.sleep(0.08)
    replies = valves.evaluate(frame(pressure=20.0))
    codes = [m["code"] for m in replies if m["type"] == "event"]
    assert "INTERLOCK_WATER_LOST" in codes
    assert ("V-01", False) in valves._drive_calls


def test_valve_low_flow_interlock(valves):
    _open_valve(valves)
    # 阀开但瞬时流量接近 0（启动 30s 宽限：把 run 开始时间回拨）
    rt = valves._valves["V-01"]
    rt.run.started_at = time.time() - 60
    valves.evaluate(frame(instant=0.0))
    time.sleep(0.08)
    replies = valves.evaluate(frame(instant=0.0))
    codes = [m["code"] for m in replies if m["type"] == "event"]
    assert "INTERLOCK_FLOW_LOW" in codes


def test_emergency_close_all_on_comm_lost(valves):
    _open_valve(valves)
    replies = valves.emergency_close_all("INTERLOCK_COMM_LOST")
    codes = [m["code"] for m in replies if m["type"] == "event"]
    assert "INTERLOCK_COMM_LOST" in codes
    status = [m for m in replies if m["type"] == "valveStatus"][0]
    assert status["state"] == "CLOSED"
    assert status["stopReason"] == "INTERLOCK_COMM_LOST"
