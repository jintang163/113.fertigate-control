package com.farm.irrigation.mqtt;

import com.farm.irrigation.config.MqttProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Paho based MQTT client. Single cloud-side subscriber with QoS1 and
 * automatic reconnect; subscriptions are re-established on every
 * (re)connect via {@link MqttCallbackExtended#connectComplete}.
 */
@Component
public class PahoMqttClient implements MqttGateway {

    private static final Logger log = LoggerFactory.getLogger(PahoMqttClient.class);

    private final MqttProperties props;
    private final MqttMessageDispatcher dispatcher;

    private volatile MqttClient client;
    private volatile MqttConnectOptions connectOptions;
    private volatile boolean connected;

    public PahoMqttClient(MqttProperties props, MqttMessageDispatcher dispatcher) {
        this.props = props;
        this.dispatcher = dispatcher;
    }

    @PostConstruct
    public void start() {
        try {
            client = new MqttClient(props.getHost(), props.getClientId(), new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(false);
            opts.setKeepAliveInterval(props.getKeepAliveSec());
            opts.setConnectionTimeout(props.getConnectionTimeoutSec());
            opts.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            if (props.getUsername() != null && !props.getUsername().isBlank()) {
                opts.setUserName(props.getUsername());
            }
            if (props.getPassword() != null && !props.getPassword().isBlank()) {
                opts.setPassword(props.getPassword().toCharArray());
            }
            opts.setMaxInflight(1000);

            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    connected = true;
                    log.info("MQTT connected to {} (reconnect={})", serverURI, reconnect);
                    subscribeAll();
                }

                @Override
                public void connectionLost(Throwable cause) {
                    connected = false;
                    log.warn("MQTT connection lost: {}", cause == null ? "unknown" : cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                    try {
                        dispatcher.dispatch(topic, payload);
                    } catch (Exception ex) {
                        log.error("Failed to dispatch MQTT message on {}: {}", topic, ex.getMessage(), ex);
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // no-op
                }
            });

            log.info("Connecting MQTT broker {} as {}", props.getHost(), props.getClientId());
            this.connectOptions = opts;
            client.connect(opts);
        } catch (MqttException e) {
            // connect failure must not kill the app; automatic reconnect keeps retrying
            log.error("Initial MQTT connect failed: {} (auto-reconnect will retry)", e.getMessage());
        }
    }

    private void subscribeAll() {
        try {
            client.subscribe(props.getTopics().getTelemetry(), 1);
            client.subscribe(props.getTopics().getValveStatus(), 1);
            client.subscribe(props.getTopics().getDeviceStatus(), 1);
            client.subscribe(props.getTopics().getEvents(), 1);
            client.subscribe(props.getTopics().getHealth(), 1);
            log.info("Subscribed to telemetry/valve-status/device-status/events/health (QoS1)");
        } catch (MqttException e) {
            log.error("MQTT subscribe failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
            }
            if (client != null) {
                client.close();
            }
        } catch (MqttException e) {
            log.warn("MQTT shutdown error: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 15_000)
    public void ensureConnected() {
        try {
            if (client != null && !client.isConnected()) {
                log.info("Retrying MQTT connect to {}", props.getHost());
                if (connectOptions != null) {
                    client.connect(connectOptions);
                } else {
                    client.connect();
                }
            }
        } catch (MqttException e) {
            log.debug("MQTT retry connect failed: {}", e.getMessage());
        }
    }

    @Override
    public void publish(String topic, String jsonPayload) {
        try {
            if (client == null || !client.isConnected()) {
                log.warn("MQTT not connected, drop publish to {}", topic);
                return;
            }
            MqttMessage message = new MqttMessage(jsonPayload.getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            client.publish(topic, message);
            log.debug("Published to {}: {}", topic, jsonPayload);
        } catch (MqttException e) {
            log.error("MQTT publish to {} failed: {}", topic, e.getMessage());
        }
    }

    @Override
    public boolean isConnected() {
        return connected && client != null && client.isConnected();
    }
}
