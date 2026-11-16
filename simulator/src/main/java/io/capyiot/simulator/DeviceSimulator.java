package io.capyiot.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class DeviceSimulator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static class Device {
        final String id, campusId, secret, type, sessionId;
        long seq;
        String token;
        Instant expires;
        ObjectNode pending;
        Device(JsonNode config, long startSeq) {
            id = config.path("deviceId").asText(); campusId = config.path("campusId").asText();
            secret = config.path("secret").asText(); type = config.path("type").asText();
            sessionId = UUID.randomUUID().toString(); seq = startSeq;
            if (id.isBlank() || campusId.isBlank() || secret.isBlank()) throw new IllegalArgumentException("Device configuration is incomplete");
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        String api = options.getOrDefault("api-url", System.getenv().getOrDefault("API_BASE_URL", "http://localhost:8088"));
        String broker = options.getOrDefault("mqtt-url", System.getenv().getOrDefault("MQTT_URL", "tcp://localhost:1883"));
        String file = options.getOrDefault("devices", System.getenv().getOrDefault("DEVICE_FILE", "var/demo-devices.json"));
        long start = Long.parseLong(options.getOrDefault("start-seq", "0"));
        long interval = Long.parseLong(options.getOrDefault("interval-ms", "10000"));
        long rounds = Long.parseLong(options.getOrDefault("rounds", "0"));
        boolean duplicate = Boolean.parseBoolean(options.getOrDefault("duplicate-first", "false"));
        if (start < 0 || start > 9007199254740991L || interval < 1 || rounds < 0) throw new IllegalArgumentException("Invalid sequence, interval or rounds");
        var devices = new ArrayList<Device>();
        JsonNode configuration = JSON.readTree(Files.readString(Path.of(file)));
        if (!configuration.isArray() || configuration.isEmpty()) throw new IllegalArgumentException("Device configuration must be a nonempty JSON array");
        configuration.forEach(config -> devices.add(new Device(config, start)));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var mqtt = new MqttClient(broker, "capyiot-sim-" + UUID.randomUUID(), new MemoryPersistence());
        var settings = new MqttConnectOptions();
        settings.setCleanSession(true); settings.setAutomaticReconnect(true); settings.setConnectionTimeout(10);
        mqtt.setTimeToWait(15000);
        mqtt.connect(settings);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { if (mqtt.isConnected()) mqtt.disconnect(); mqtt.close(); }
            catch (MqttException ignored) { /* The process is already stopping. */ }
        }));
        try {
            for (long round = 0; rounds == 0 || round < rounds; round++) {
                for (Device device : devices) {
                    if (device.token == null || device.expires.isBefore(Instant.now().plusSeconds(60))) openSession(http, api, device);
                    if (device.pending == null) device.pending = heartbeat(device);
                    device.pending.put("sessionToken", device.token);
                    byte[] body = JSON.writeValueAsBytes(device.pending);
                    String topic = "capyiot/" + device.campusId + "/devices/" + device.id + "/events";
                    try {
                        mqtt.publish(topic, body, 1, false);
                        if (duplicate && round == 0) mqtt.publish(topic, body, 1, false);
                        device.pending = null;
                        device.seq++;
                    } catch (MqttException unavailable) {
                        System.err.println("MQTT publish will retry with the same eventId for device " + device.id);
                    }
                }
                System.out.println("Heartbeat round " + (round + 1) + " completed for " + devices.size() + " simulated devices");
                if (rounds == 0 || round + 1 < rounds) Thread.sleep(interval);
            }
        } finally {
            if (mqtt.isConnected()) mqtt.disconnect();
            mqtt.close();
        }
    }

    private static void openSession(HttpClient http, String api, Device device) throws Exception {
        String body = JSON.writeValueAsString(Map.of("deviceId", device.id, "secret", device.secret, "sessionId", device.sessionId));
        var request = HttpRequest.newBuilder(URI.create(api + "/api/v1/device-sessions")).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Device session failed with HTTP " + response.statusCode() + " for " + device.id);
        JsonNode result = JSON.readTree(response.body()).path("data");
        device.token = result.path("sessionToken").asText();
        device.expires = Instant.parse(result.path("expiresAt").asText());
    }

    private static ObjectNode heartbeat(Device device) {
        if (device.seq > 9007199254740991L) throw new IllegalStateException("Sequence limit reached");
        var message = JSON.createObjectNode().put("eventId", UUID.randomUUID().toString()).put("eventType", "HEARTBEAT")
                .put("schemaVersion", 1).put("campusId", device.campusId).put("deviceId", device.id)
                .put("sessionId", device.sessionId).put("seq", device.seq).put("occurredAt", Instant.now().toString());
        var payload = message.putObject("payload").put("healthStatus", "NORMAL");
        if (device.type.equals("SENSOR")) payload.put("temperature", 22.0 + Math.random() * 2);
        return message;
    }

    private static Map<String, String> options(String[] args) {
        if (args.length % 2 != 0) throw new IllegalArgumentException("Arguments use --name value pairs");
        var allowed = Set.of("api-url", "mqtt-url", "devices", "start-seq", "interval-ms", "rounds", "duplicate-first");
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2))) throw new IllegalArgumentException("Unknown simulator argument");
            options.put(args[i].substring(2), args[i + 1]);
        }
        return options;
    }
}
