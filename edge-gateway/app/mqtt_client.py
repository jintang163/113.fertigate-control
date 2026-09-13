"""MQTT 客户端：持久会话 + QoS1 + outbox 断网补传。

- 采集产生的所有消息先写 Outbox，再由本发布循环在 PUBACK 后删除；
- 订阅 farm/{sn}/valve/command 与 .../config（QoS1, clean_start=False），
  broker 端缓存离线期间的控制指令；
- 重连后发布循环自动从最旧缓存开始补传，ts 保留原始采样时间。
"""
from __future__ import annotations

import json
import logging
import threading
import time
from typing import Any, Callable, Dict, Optional

import paho.mqtt.client as mqtt

from .outbox import Outbox

log = logging.getLogger("mqtt-client")


class MqttClient:
    def __init__(self, gateway_sn: str, mqtt_cfg: Dict[str, Any], outbox: Outbox,
                 batch_size: int = 50):
        self.gw = gateway_sn
        self.outbox = outbox
        self.batch = batch_size
        host = mqtt_cfg.get("host", "emqx")
        port = int(mqtt_cfg.get("port", 1883))
        # paho v2 CallbackAPIVersion.VERSION2；持久会话
        self._client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"gw-{gateway_sn}", clean_session=False,
        )
        if mqtt_cfg.get("username"):
            self._client.username_pw_set(mqtt_cfg["username"], mqtt_cfg.get("password"))
        self._client.on_publish = self._on_publish
        self._client.will_set(f"farm/{gateway_sn}/health",
                              json.dumps({"type": "health", "gatewaySn": gateway_sn,
                                          "ts": int(time.time() * 1000), "online": False}),
                              qos=1, retain=True)
        self._client.on_connect = self._on_connect
        self._client.on_disconnect = self._on_disconnect
        self._client.on_message = self._on_message
        self._host, self._port = host, port
        self.on_command: Optional[Callable[[Dict[str, Any]], None]] = None
        self.on_device_command: Optional[Callable[[Dict[str, Any]], None]] = None
        self.on_config: Optional[Callable[[Dict[str, Any]], None]] = None
        self.connected = threading.Event()
        self._stop = threading.Event()
        self._inflight: Dict[int, int] = {}   # mid → outbox id
        self._lock = threading.Lock()

    # --------------------------------------------------------------- #
    def start(self) -> None:
        self._client.connect_async(self._host, self._port, keepalive=30)
        self._client.loop_start()
        threading.Thread(target=self._publish_loop, daemon=True,
                         name="outbox-flush").start()

    def stop(self) -> None:
        self._stop.set()
        self._client.loop_stop()
        self._client.disconnect()

    def topics(self) -> Dict[str, str]:
        return {
            "telemetry": f"farm/{self.gw}/telemetry",
            "valveStatus": f"farm/{self.gw}/valve/status",
            "deviceStatus": f"farm/{self.gw}/device/status",
            "events": f"farm/{self.gw}/events",
            "health": f"farm/{self.gw}/health",
            "command": f"farm/{self.gw}/valve/command",
            "deviceCommand": f"farm/{self.gw}/device/command",
            "config": f"farm/{self.gw}/config",
        }

    # --------------------------------------------------------------- #
    def _on_connect(self, client, userdata, flags, reason_code, properties=None):
        if not getattr(reason_code, "is_failure", False):
            log.info("MQTT 已连接 %s:%s（持久会话 present=%s）",
                     self._host, self._port, getattr(flags, "session_present", "?"))
            client.subscribe(self.topics()["command"], qos=1)
            client.subscribe(self.topics()["deviceCommand"], qos=1)
            client.subscribe(self.topics()["config"], qos=1)
            self.connected.set()
        else:
            log.error("MQTT 连接失败: %s", reason_code)

    def _on_disconnect(self, *a, **kw):
        log.warning("MQTT 断开，将自动重连并补传缓存")
        self.connected.clear()
        with self._lock:
            self._inflight.clear()   # PUBACK 未确认的消息仍留在 outbox

    def _on_publish(self, client, userdata, mid, reason_code=None, properties=None):
        # QoS1 PUBACK 到达；outbox 清理由发布循环在 wait_for_publish 后执行
        with self._lock:
            self._inflight.pop(mid, None)

    def _on_message(self, client, userdata, msg):
        try:
            payload = json.loads(msg.payload.decode("utf-8"))
        except Exception as e:
            log.error("非法 JSON 消息: %s", e)
            return
        if msg.topic.endswith("/valve/command"):
            log.info("收到阀控命令: %s", payload.get("commandId"))
            if self.on_command:
                self.on_command(payload)
        elif msg.topic.endswith("/device/command"):
            log.info("收到设备命令: %s %s", payload.get("deviceCode"), payload.get("action"))
            if self.on_device_command:
                self.on_device_command(payload)
        elif msg.topic.endswith("/config"):
            log.info("收到配置下发")
            if self.on_config:
                self.on_config(payload)

    # --------------------------------------------------------------- #
    def publish_nowait(self, kind: str, payload: Dict[str, Any]) -> None:
        """统一出站入口：先落盘，发布循环保证送达。"""
        self.outbox.add(self.topics()[kind], payload)

    def _publish_loop(self) -> None:
        while not self._stop.is_set():
            if not self.connected.is_set():
                time.sleep(0.5)
                continue
            rows = self.outbox.pending(self.batch)
            if not rows:
                time.sleep(0.2)
                continue
            acked_ids: list[int] = []
            for ob_id, topic, payload_str in rows:
                try:
                    info = self._client.publish(topic, payload_str.encode("utf-8"), qos=1)
                except Exception as e:
                    log.warning("发布异常，稍后重试: %s", e)
                    break
                if info.rc == mqtt.MQTT_ERR_QUEUE_SIZE:
                    break
                with self._lock:
                    self._inflight[info.mid] = ob_id
                info.wait_for_publish(timeout=5)
                # wait 成功即 PUBACK(QoS1 由 paho 在内部确认)；保守地以 is_published 判定
                if info.is_published():
                    acked_ids.append(ob_id)
                else:
                    break
            if acked_ids:
                self.outbox.delete_up_to(max(acked_ids))
            time.sleep(0.05)
