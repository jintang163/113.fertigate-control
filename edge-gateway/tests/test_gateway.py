"""边缘网关核心逻辑测试：outbox 补传、本地联锁、幂等命令、仿真水文。"""
from __future__ import annotations

import os
import time

import pytest

from app.config import load_config
from app.messages import new_command_id
from app.outbox import Outbox
from app.simulator import FieldSimulator
from app.valve_controller import ValveController

CONFIG = os.path.join(os.path.dirname(__file__), "..", "config", "gateway.example.yaml")


@pytest.fixture
def cfg(tmp_path):
    c = load_config(CONFIG)
    c.sqlitePath = str(tmp_path / "outbox.db")
    c.interlocks.sensorLostSec = 1.0        # 测试中缩短失联判定
    c.pollIntervalSec = 0.05
    return c


@pytest.fixture
def controller(cfg):
    calls: list[tuple[str, bool]] = []

    def drive(code: str, open_: bool) -> None:
        calls.append((code, open_))

    ctl = ValveController(cfg)
    ctl.attach_driver(drive)
    ctl._drive_calls = calls  # type: ignore[attr-defined]
    return ctl


def good_frame(moist: float = 21.0, flow_total: float = 0.0):
    return {
        "SOIL-A1": {"kind": "soil", "quality": "GOOD", "values": {
            "soilMoist": moist, "ec": 1.6, "ph": 6.4}},
        "SOIL-A2": {"kind": "soil", "quality": "GOOD", "values": {
            "soilMoist": moist + 2, "ec": 1.5, "ph": 6.5}},
        "FM-01": {"kind": "flow", "quality": "GOOD", "values": {
            "instantFlow": 2.4, "totalFlow": flow_total}},
        "V-01": {"kind": "valve", "quality": "GOOD", "values": {"state": "CLOSED"}},
    }


def open_cmd(**over):
    msg = {
        "type": "valveCommand", "gatewaySn": "GW001", "commandId": new_command_id(),
        "valveCode": "V-01", "jobId": "JOB-1", "command": "OPEN",
        "plannedVolume": 0.6, "maxDurationSec": 900,
        "hardMaxMoisture": 33.0, "sensorCode": "SOIL-A1",
    }
    msg.update(over)
    return msg


# ----------------------------- outbox ----------------------------- #

def test_outbox_fifo_and_delete(tmp_path):
    ob = Outbox(str(tmp_path / "o.db"))
    for i in range(5):
        ob.add("farm/GW001/telemetry", {"ts": i})
    assert ob.depth() == 5
    batch = ob.pending(3)
    assert [x[0] for x in batch] == [1, 2, 3]
    ob.delete_up_to(3)
    assert [x[0] for x in ob.pending(10)] == [4, 5]
    ob.close()


# --------------------------- 命令安全前置 --------------------------- #

def test_open_rejected_when_sensor_never_seen(controller):
    replies = controller.handle_command(open_cmd())
    kinds = [m["code"] for m in replies if m["type"] == "event"]
    assert "COMMAND_REJECTED" in kinds
    assert not any(open_ for _, open_ in controller._drive_calls if open_ is True)


def test_open_then_interlock_hard_max(controller):
    controller.evaluate(good_frame(moist=21.0))
    cmd = open_cmd()
    replies = controller.handle_command(cmd)
    assert any(m.get("state") == "OPEN" for m in replies if m["type"] == "valveStatus")
    assert ("V-01", True) in controller._drive_calls

    # 湿度冲破硬上限 → 联锁关阀
    replies = controller.evaluate(good_frame(moist=33.5, flow_total=0.1))
    codes = [m["code"] for m in replies if m["type"] == "event"]
    assert "INTERLOCK_MOISTURE_HARD_MAX" in codes
    status = [m for m in replies if m["type"] == "valveStatus"][0]
    assert status["state"] == "CLOSED"
    assert status["stopReason"] == "INTERLOCK_MOISTURE_HARD_MAX"
    assert ("V-01", False) in controller._drive_calls


def test_interlock_sensor_lost(controller):
    controller.evaluate(good_frame(moist=21.0))
    controller.handle_command(open_cmd())
    time.sleep(1.1)
    # 失联：帧中没有该传感器
    replies = controller.evaluate({"FM-01": {"kind": "flow", "quality": "GOOD",
                                             "values": {"totalFlow": 0.0}}})
    codes = [m["code"] for m in replies if m["type"] == "event"]
    assert "INTERLOCK_SENSOR_LOST" in codes


def test_interlock_sensor_fault_quality(controller):
    controller.evaluate(good_frame(moist=21.0))
    controller.handle_command(open_cmd())
    frame = good_frame(moist=21.0)
    frame["SOIL-A1"] = {"kind": "soil", "quality": "SENSOR_FAULT", "values": {}}
    replies = controller.evaluate(frame)
    codes = [m["code"] for m in replies if m["type"] == "event"]
    assert "INTERLOCK_SENSOR_LOST" in codes


def test_volume_reached_auto_close(controller):
    controller.evaluate(good_frame(moist=20.0, flow_total=10.0))
    cmd = open_cmd(plannedVolume=0.6)
    controller.handle_command(cmd)
    # 流量计累计增加 0.6m3 → 达量关阀
    replies = controller.evaluate(good_frame(moist=22.0, flow_total=10.6))
    status = [m for m in replies if m["type"] == "valveStatus"][0]
    assert status["state"] == "CLOSED"
    assert status["stopReason"] == "VOLUME_REACHED"
    assert status["appliedVolume"] == pytest.approx(0.6, abs=0.001)


def test_duration_limit_close(controller):
    controller.evaluate(good_frame(moist=20.0))
    cmd = open_cmd(maxDurationSec=0)     # 立即超时
    controller.handle_command(cmd)
    replies = controller.evaluate(good_frame(moist=20.5))
    status = [m for m in replies if m["type"] == "valveStatus"][0]
    assert status["stopReason"] == "DURATION_LIMIT"


def test_ec_high_interlock(controller):
    controller.evaluate(good_frame(moist=20.0))
    controller.handle_command(open_cmd())
    frame = good_frame(moist=20.0)
    frame["SOIL-A1"]["values"]["ec"] = 3.5
    controller.evaluate(frame)
    time.sleep(0.12)
    replies = controller.evaluate(frame)   # 连续2周期超限才动作
    codes = [m["code"] for m in replies if m["type"] == "event"]
    assert "INTERLOCK_EC_HIGH" in codes


def test_command_idempotent(controller):
    controller.evaluate(good_frame(moist=21.0))
    cmd = open_cmd()
    controller.handle_command(cmd)
    n = len(controller._drive_calls)
    # 重复 commandId：不重复驱动
    controller.handle_command(dict(cmd))
    assert len(controller._drive_calls) == n


def test_remote_close(controller):
    controller.evaluate(good_frame(moist=21.0))
    controller.handle_command(open_cmd())
    replies = controller.handle_command({
        "commandId": new_command_id(), "valveCode": "V-01",
        "jobId": "JOB-1", "command": "CLOSE"})
    status = [m for m in replies if m["type"] == "valveStatus"][0]
    assert status["state"] == "CLOSED"
    assert status["stopReason"] == "REMOTE_CLOSE"


# ----------------------------- 仿真水文 ----------------------------- #

def test_simulator_irrigation_raises_moisture(cfg):
    sim = FieldSimulator([d.__dict__ for d in cfg.devices], cfg.simulate)
    before = sim.soils["SOIL-A1"].moisture
    sim.set_valve("V-01", True)
    # 灌 10 分钟
    sim.step(600)
    after = sim.soils["SOIL-A1"].moisture
    assert after > before


def test_simulator_deep_drainage_cap(cfg):
    sim = FieldSimulator([d.__dict__ for d in cfg.devices], cfg.simulate)
    sim.soils["SOIL-A1"].moisture = sim.soils["SOIL-A1"].theta_fc - 0.2
    sim.set_valve("V-01", True)
    sim.step(3600)
    # 不超过田持（深层渗漏削顶）
    assert sim.soils["SOIL-A1"].moisture <= sim.soils["SOIL-A1"].theta_fc + 1e-9


def test_simulator_et_depletes_soil(cfg):
    sim = FieldSimulator([d.__dict__ for d in cfg.devices], cfg.simulate)
    before = sim.soils["SOIL-A1"].moisture
    sim.step(86400)
    assert sim.soils["SOIL-A1"].moisture < before


def test_full_local_cycle_with_simulator(cfg):
    """端到端：干土 → 云端 OPEN 被接受 → 模拟灌水 → 达量本地自动关阀。"""
    sim = FieldSimulator([d.__dict__ for d in cfg.devices], cfg.simulate)
    calls: list[tuple[str, bool]] = []
    ctl = ValveController(cfg)
    ctl.attach_driver(lambda code, o: (calls.append((code, o)), sim.set_valve(code, o)))

    frame = sim.poll()
    ctl.evaluate(frame)
    cmd = open_cmd(plannedVolume=0.3, maxDurationSec=3600)
    replies = ctl.handle_command(cmd)
    assert any(m.get("state") == "OPEN" for m in replies)

    closed = None
    total = 0.0
    for _ in range(200):
        sim.step(cfg.pollIntervalSec * 20 if False else 30)
        frame = sim.poll()
        replies = ctl.evaluate(frame)
        for m in replies:
            if m.get("type") == "valveStatus" and m["state"] == "CLOSED":
                closed = m
        if closed:
            break
    assert closed is not None
    assert closed["stopReason"] == "VOLUME_REACHED"
    assert sim.valve_state("V-01") == "CLOSED"
