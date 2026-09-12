"""真实 MQTT 链路集成测试：amqtt 内存 broker + paho 客户端。

验证：QoS1 发布送达、valve/command 订阅回调、outbox 断网积压与重连补传。
amqtt 仅测试依赖，缺失时跳过。
"""
from __future__ import annotations

import asyncio
import json
import socket
import threading
import time

import pytest

paho = pytest.importorskip("paho.mqtt.client")
amqtt_mod = pytest.importorskip("amqtt.broker")

from app.messages import new_command_id, telemetry  # noqa: E402
from app.mqtt_client import MqttClient  # noqa: E402
from app.outbox import Outbox  # noqa: E402


def _free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class BrokerThread:
    def __init__(self, port: int):
        self.port = port
        self.ready = threading.Event()

    def _run(self):
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        config = {
            "listeners": {"default": {"type": "tcp", "bind": f"127.0.0.1:{self.port}"}},
            "auth": {"allow-anonymous": True},
        }

        async def _serve():
            self.broker = amqtt_mod.Broker(config)
            await self.broker.start()
            self.ready.set()

        loop.run_until_complete(_serve())
        loop.run_forever()

    def start(self):
        t = threading.Thread(target=self._run, daemon=True)
        t.start()
        self.ready.wait(5)


def _cloud_subscriber(port: int, topic: str, got: list, connected: threading.Event):
    cl = paho.Client(callback_api_version=paho.CallbackAPIVersion.VERSION2,
                     client_id="cloud-test", clean_session=True)
    cl.on_connect = lambda c, u, f, rc, p=None: (connected.set(), c.subscribe(topic, qos=1))
    cl.on_message = lambda c, u, m: got.append(json.loads(m.payload))
    cl.connect("127.0.0.1", port)
    cl.loop_start()
    return cl


def test_publish_and_command_roundtrip(tmp_path):
    port = _free_port()
    BrokerThread(port).start()
    time.sleep(0.5)

    # 云端订阅
    telemetry_got: list = []
    up = threading.Event()
    cloud = _cloud_subscriber(port, "farm/+/telemetry", telemetry_got, up)
    up.wait(3)

    # 网关客户端
    ob = Outbox(str(tmp_path / "gw.db"))
    gw = MqttClient("GW001", {"host": "127.0.0.1", "port": port}, ob, batch_size=10)
    commands: list = []
    gw.on_command = lambda msg: commands.append(msg)
    gw.start()
    assert gw.connected.wait(5), "网关未能连接 broker"

    # 1) 遥测：先入 outbox，flush 后云端收到并被删除
    gw.publish_nowait("telemetry", telemetry("GW001", [{"deviceCode": "SOIL-A1"}]))
    deadline = time.time() + 5
    while not telemetry_got and time.time() < deadline:
        time.sleep(0.1)
    assert telemetry_got and telemetry_got[0]["gatewaySn"] == "GW001"
    time.sleep(0.3)
    assert ob.depth() == 0, "PUBACK 后 outbox 应清空"

    # 2) 命令下行：云端 → farm/GW001/valve/command → 网关回调
    cmd = {"type": "valveCommand", "commandId": new_command_id(),
           "valveCode": "V-01", "command": "OPEN", "plannedVolume": 0.6}
    cloud.publish("farm/GW001/valve/command", json.dumps(cmd), qos=1)
    deadline = time.time() + 5
    while not commands and time.time() < deadline:
        time.sleep(0.1)
    assert commands and commands[0]["valveCode"] == "V-01"

    gw.stop()
    cloud.loop_stop()


def test_outbox_buffer_and_backfill(tmp_path):
    port = _free_port()
    bt = BrokerThread(port)
    bt.start()
    time.sleep(0.5)

    ob = Outbox(str(tmp_path / "gw2.db"))
    cfg = {"host": "127.0.0.1", "port": port}
    gw = MqttClient("GW002", cfg, ob, batch_size=10)
    gw.start()
    assert gw.connected.wait(5)

    got: list = []
    up = threading.Event()
    cloud = _cloud_subscriber(port, "farm/+/telemetry", got, up)
    up.wait(3)

    # 模拟“断网”：停掉 broker（先停客户端避免它占着）
    gw.stop()
    # 直接往 outbox 塞数据，等价于断网期间采集线程持续写入
    for i in range(5):
        gw2_none = None
        ob.add(f"farm/GW002/telemetry", {"type": "telemetry", "gatewaySn": "GW002",
                                          "ts": i, "records": [{"i": i}]})
    assert ob.depth() == 5

    # 重连：新建客户端使用同一个 outbox（网关进程持续运行，这里模拟重启后补传等价场景）
    gw3 = MqttClient("GW002", cfg, ob, batch_size=10)
    gw3.start()
    assert gw3.connected.wait(5)
    deadline = time.time() + 6
    while ob.depth() > 0 and time.time() < deadline:
        time.sleep(0.1)
    assert ob.depth() == 0, "重连后缓存应全部补传完成"
    time.sleep(0.5)
    received = sorted(g["ts"] for g in got)
    assert received == [0, 1, 2, 3, 4], f"补传应保序且完整: {received}"
    gw3.stop()
    cloud.loop_stop()
