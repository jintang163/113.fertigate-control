package com.farm.irrigation.mqtt;

/**
 * Outbound MQTT gateway used by the control engine and REST API
 * to publish valve commands and configuration pushes.
 */
public interface MqttGateway {

    /** Publish a JSON payload with QoS 1. Never throws: failures are logged. */
    void publish(String topic, String jsonPayload);

    boolean isConnected();
}
