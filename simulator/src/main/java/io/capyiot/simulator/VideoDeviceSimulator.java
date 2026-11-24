package io.capyiot.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** Virtual IPC/NVR/switch devices; no video streams, device control or AI agent. */
final class VideoDeviceSimulator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_SEQUENCE = 9_007_199_254_740_991L;

    private static final class Device {
        final String id, type, code, secret, pointId, upstreamId;
        final String sessionId = UUID.randomUUID().toString();
        final String bootId = UUID.randomUUID().toString();
        final Instant bootedAt = Instant.now();
        final Map<String, Long> sequences = new HashMap<>();
        final Map<String, String> faultIds = new HashMap<>();
        final Deque<ObjectNode> pending = new ArrayDeque<>();
        final MqttClient mqtt;
        Instant expires = Instant.EPOCH;
        String token;
        boolean faultQueued, recoveryQueued, duplicateSent;

        Device(JsonNode c, String broker, ScheduledExecutorService workers) throws MqttException {
            id = c.path("deviceId").asText(); type = c.path("deviceType").asText();
            code = c.path("assetCode").asText(); secret = c.path("deviceSecret").asText();
            pointId = c.path("monitoringPointId").asText(); upstreamId = c.path("upstreamDeviceId").asText();
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || secret.isBlank() || !Set.of("IPC", "NVR", "SWITCH").contains(type)) {
                throw new IllegalArgumentException("V1 configuration requires deviceId, deviceType and deviceSecret");
            }
            mqtt = new MqttClient(broker, id, new MemoryPersistence(), workers);
            mqtt.setTimeToWait(15_000);
        }

        ObjectNode envelope(String eventType, String stream, ObjectNode payload) {
            long sequence = sequences.merge(stream, 1L, Long::sum);
            if (sequence > MAX_SEQUENCE) throw new IllegalStateException("Stream sequence exhausted; start a new session");
            ObjectNode result = JSON.createObjectNode().put("eventId", UUID.randomUUID().toString()).put("schemaVersion", 1)
                    .put("deviceId", id).put("sessionId", sessionId).put("eventType", eventType)
                    .put("streamKey", stream).put("sequence", sequence).put("occurredAt", Instant.now().toString());
            result.set("payload", payload);
            return result;
        }

        void heartbeat() {
            pending.add(envelope("HEARTBEAT", "HEARTBEAT", JSON.createObjectNode().put("bootId", bootId)
                    .put("uptimeSeconds", Duration.between(bootedAt, Instant.now()).toSeconds()).put("firmwareVersion", "simulator-v1")));
        }

        void status(String resource, String key, String observation, String value) {
            ObjectNode p = resource(resource, key).put("observationType", observation).put("value", value).put("quality", "GOOD");
            if (type.equals("IPC") && !pointId.isBlank()) p.put("monitoringPointId", pointId);
            pending.add(envelope("STATUS_OBSERVATION", "STATUS/" + resource + "/" + key + "/" + observation, p));
        }

        void fault(String resource, String key, String code, boolean recovered) {
            String faultKey = resource + "/" + key + "/" + code;
            ObjectNode p = resource(resource, key).put("faultCode", code);
            if (recovered) {
                String eventId = faultIds.get(faultKey);
                if (eventId != null) p.put("relatedFaultEventId", eventId);
                p.put("recoveryEvidence", "Simulator resumed the same resource after the configured fault interval");
            }
            ObjectNode e = envelope(recovered ? "DEVICE_RECOVERED" : "DEVICE_FAULT", "EVENT/" + resource + "/" + key, p);
            if (!recovered) faultIds.put(faultKey, e.path("eventId").asText());
            pending.add(e);
        }

        void connect(HttpClient http, String api, SSLContext tls) throws Exception {
            if (!expires.isAfter(Instant.now().plusSeconds(90))) {
                ObjectNode request = JSON.createObjectNode().put("deviceId", id).put("sessionId", sessionId).put("secret", secret);
                var response = http.send(HttpRequest.newBuilder(URI.create(api + "/api/v1/device-sessions"))
                        .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(request))).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    throw new SecurityException("Device session denied; check asset commissioning and local credentials");
                }
                if (response.statusCode() != 200) throw new java.io.IOException("Device session temporarily unavailable: HTTP " + response.statusCode());
                JsonNode data = JSON.readTree(response.body()).path("data");
                String replacement = data.path("sessionToken").asText();
                Instant expiry = Instant.parse(data.path("expiresAt").asText());
                if (replacement.isBlank() || !expiry.isAfter(Instant.now())) throw new java.io.IOException("Invalid device session response");
                // MQTT cannot replace CONNECT credentials in-place. Renew the same session and
                // reconnect with the same device clientId; independent stream counters remain intact.
                if (mqtt.isConnected()) mqtt.disconnect(5_000);
                token = replacement; expires = expiry;
            }
            if (!mqtt.isConnected()) {
                var options = new MqttConnectOptions();
                options.setSocketFactory(tls.getSocketFactory()); options.setHttpsHostnameVerificationEnabled(true);
                options.setUserName(id); options.setPassword(token.toCharArray()); options.setCleanSession(true);
                options.setAutomaticReconnect(false); options.setConnectionTimeout(10); options.setKeepAliveInterval(20);
                mqtt.connect(options);
            }
        }

        void publish(boolean duplicate) throws Exception {
            String topic = "capyiot/v1/devices/" + id + "/sessions/" + sessionId + "/events";
            while (!pending.isEmpty()) {
                ObjectNode e = pending.getFirst();
                byte[] bytes = JSON.writeValueAsBytes(e);
                mqtt.publish(topic, bytes, 1, false);
                if (duplicate && !duplicateSent) {
                    mqtt.publish(topic, bytes, 1, false);
                    duplicateSent = true;
                }
                // On publish failure retain the same eventId, sequence and timestamp for retry.
                pending.removeFirst();
            }
        }

        void close() {
            try { if (mqtt.isConnected()) mqtt.disconnect(2_000); } catch (MqttException ignored) { }
            try { mqtt.close(true); } catch (MqttException ignored) { }
        }
    }

    static void run(Map<String, String> options) throws Exception {
        String api = option(options, "api-url", "API_BASE_URL", "https://localhost:8443").replaceAll("/+$", "");
        String broker = option(options, "mqtt-url", "MQTT_URL", "ssl://localhost:8883");
        String file = option(options, "devices", "DEVICE_FILE", "var/video-devices.json");
        if (!URI.create(api).getScheme().equals("https") || !URI.create(broker).getScheme().equals("ssl")) {
            throw new IllegalArgumentException("V1 requires HTTPS for sessions and MQTTS for device messages");
        }
        long interval = Long.parseLong(options.getOrDefault("interval-ms", "10000"));
        long rounds = Long.parseLong(options.getOrDefault("rounds", "0"));
        long outageSeconds = Long.parseLong(options.getOrDefault("fault-seconds", "60"));
        String scenario = options.getOrDefault("scenario", "steady");
        if (interval < 100 || interval > 60_000 || rounds < 0 || outageSeconds < 1 || outageSeconds > 86_400 || !Set.of("steady", "switch-outage").contains(scenario)) {
            throw new IllegalArgumentException("Invalid interval, rounds or scenario");
        }
        SSLContext tls = trust(Path.of(option(options, "ca-file", "MQTT_CA_FILE", "var/tls/ca.pem")));
        HttpClient http = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5)).build();
        JsonNode configs = JSON.readTree(Files.readString(Path.of(file)));
        if (!configs.isArray() || configs.isEmpty() || configs.size() > 100) throw new IllegalArgumentException("Configure 1 to 100 virtual devices");
        var devices = new ArrayList<Device>();
        var workers = Executors.newScheduledThreadPool(8, Thread.ofPlatform().daemon().name("video-simulator-", 0).factory());
        Set<String> ids = new HashSet<>();
        Thread shutdown = new Thread(() -> devices.forEach(Device::close), "video-simulator-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            for (JsonNode config : configs) {
                var device = new Device(config, broker, workers);
                if (!ids.add(device.id)) { device.close(); throw new IllegalArgumentException("Duplicate deviceId in simulator configuration"); }
                devices.add(device);
            }
            Device root = devices.stream().filter(d -> d.type.equals("SWITCH")).findFirst().orElse(null);
            Set<String> affected = new HashSet<>();
            if (scenario.equals("switch-outage")) {
                if (root == null) throw new IllegalArgumentException("Switch-outage requires a switch and at least two downstream IPCs");
                for (Device d : devices) if (d.type.equals("IPC") && d.upstreamId.equals(root.id)) affected.add(d.id);
                if (affected.size() < 2) throw new IllegalArgumentException("Switch-outage requires two IPCs with upstreamDeviceId matching the switch");
            }
            long faultAt = 2;
            long recoveryAt = faultAt + Math.max(2, (outageSeconds * 1000 + interval - 1) / interval);
            if (scenario.equals("switch-outage") && rounds != 0) rounds = Math.max(rounds, recoveryAt + 2);
            for (long round = 0; rounds == 0 || round < rounds; round++) {
                boolean fault = scenario.equals("switch-outage") && round >= faultAt && round < recoveryAt;
                for (Device d : devices) {
                    if (fault && affected.contains(d.id)) continue; // Physical upstream loss: no fake heartbeat.
                    try {
                        d.connect(http, api, tls);
                        if (d.pending.isEmpty()) {
                            d.heartbeat();
                            d.status("DEVICE", "device", "OPERATING_MODE", "NORMAL");
                            if (d.type.equals("IPC")) d.status("DEVICE", "device", "VIDEO_SIGNAL", "PRESENT");
                            if (d.type.equals("SWITCH")) {
                                d.status("SWITCH_PORT", "port:1", "LINK_STATUS", fault && d == root ? "DOWN" : "UP");
                                d.status("SWITCH_PORT", "port:1", "POE_STATUS", fault && d == root ? "FAULT" : "POWERED");
                            }
                            if (d.type.equals("NVR")) for (int channel = 1; channel <= 2; channel++) {
                                d.status("VIDEO_CHANNEL", "channel:" + channel, "RECORDING_STATUS", fault ? "NOT_RECORDING" : "RECORDING");
                            }
                            if (fault && !d.faultQueued && (d == root || d.type.equals("NVR"))) {
                                d.fault(d == root ? "DEVICE" : "VIDEO_CHANNEL", d == root ? "device" : "channel:1",
                                        d == root ? "SWITCH_DEVICE_ERROR" : "NVR_RECORDING_STOPPED", false);
                                d.faultQueued = true;
                            }
                            if (!fault && d.faultQueued && !d.recoveryQueued) {
                                d.fault(d == root ? "DEVICE" : "VIDEO_CHANNEL", d == root ? "device" : "channel:1",
                                        d == root ? "SWITCH_DEVICE_ERROR" : "NVR_RECORDING_STOPPED", true);
                                d.recoveryQueued = true;
                            }
                        }
                        d.publish(Boolean.parseBoolean(options.getOrDefault("duplicate-first", "false")));
                    } catch (SecurityException denied) {
                        throw denied; // Never log a response body, device secret or CONNECT token.
                    } catch (Exception unavailable) {
                        if (unavailable instanceof InterruptedException) { Thread.currentThread().interrupt(); throw unavailable; }
                        if (rounds != 0 && round + 1 == rounds) throw new IllegalStateException("Virtual device delivery incomplete; pending messages retained until process exit");
                        System.err.println("Virtual device " + d.code + " temporarily unavailable; retrying on next round");
                    }
                }
                System.out.println("Virtual device round " + (round + 1) + " completed; scenario=" + scenario + ", faultActive=" + fault);
                if (rounds == 0 || round + 1 < rounds) Thread.sleep(interval);
            }
        } finally {
            Runtime.getRuntime().removeShutdownHook(shutdown);
            devices.forEach(Device::close); workers.shutdownNow();
        }
    }

    private static ObjectNode resource(String type, String key) { return JSON.createObjectNode().put("resourceType", type).put("resourceKey", key); }
    private static String option(Map<String, String> options, String key, String env, String fallback) {
        return options.getOrDefault(key, System.getenv().getOrDefault(env, fallback));
    }
    private static SSLContext trust(Path file) throws Exception {
        var store = KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null, null);
        try (var input = Files.newInputStream(file)) {
            int index = 0;
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(input)) store.setCertificateEntry("ca-" + index++, cert);
            if (index == 0) throw new IllegalArgumentException("CA file is empty");
        }
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        var context = SSLContext.getInstance("TLS"); context.init(null, trust.getTrustManagers(), null); return context;
    }
}
