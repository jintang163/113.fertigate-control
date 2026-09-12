package com.farm.irrigation.mqtt;

public final class MqttTopics {

    private MqttTopics() {}

    public static String valveCommand(String gatewaySn) {
        return "farm/" + gatewaySn + "/valve/command";
    }

    public static String config(String gatewaySn) {
        return "farm/" + gatewaySn + "/config";
    }

    /** Extract gateway SN from a topic like farm/{sn}/telemetry. */
    public static String gatewayOf(String topic) {
        if (topic == null) {
            return null;
        }
        String[] parts = topic.split("/");
        if (parts.length >= 3 && "farm".equals(parts[0])) {
            return parts[1];
        }
        return null;
    }
}
