package io.capyiot.contract.internal;

import java.util.Arrays;

public enum ServiceId {
    GATEWAY("gateway"), INGESTION("device-ingestion"), DEVICE("device-service"),
    FAULT("alarm-service"), WORKFLOW("workflow-service"), PLATFORM("platform-service"), AGENT("agent-service");

    private final String value;
    ServiceId(String value) { this.value = value; }
    public String value() { return value; }
    public String issuer() { return "capyiot-service/" + value; }
    public String audience() { return "capyiot-service/" + value; }

    public static ServiceId fromIssuer(String issuer) {
        return Arrays.stream(values()).filter(id -> id.issuer().equals(issuer)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Untrusted service issuer"));
    }
}
