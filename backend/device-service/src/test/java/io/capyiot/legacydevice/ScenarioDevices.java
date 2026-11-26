package io.capyiot.legacydevice;

import static io.capyiot.contract.DeviceMessage.Type.HEARTBEAT;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.DeviceMessage;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class ScenarioDevices {
    record Candidate(String id, String code, String name, String type, String buildingId,
            String buildingName, String upstreamId) {}
    record Catalogue(List<Candidate> devices, String setupMessage) {}
    record Credential(String deviceId, String campusId, String secret) {}
    private record PublishKey(String runId, Instant phaseAt, DeviceMessage.Type type) {}

    private static final class Channel {
        final String sessionId = UUID.randomUUID().toString();
        final Map<PublishKey, Instant> sent = new HashMap<>();
        DeviceModels.SessionResult session;
        String owner;
        Instant phaseAt;
        long seq;
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final DeviceSessions sessions;
    private final ObjectMapper json;
    private final String broker;
    private final Path file;
    // Only the scheduled runner uses the MQTT connection and channels.
    private final Map<String, Channel> channels = new HashMap<>();
    private MqttAsyncClient mqtt;

    ScenarioDevices(JdbcTemplate jdbc, DeviceSessions sessions, ObjectMapper json,
            @Value("${capyiot.scenario-device-file}") String file,
            @Value("${capyiot.scenario-mqtt-url}") String broker) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.sessions = sessions;
        this.json = json;
        this.broker = broker;
        this.file = file.isBlank() ? null : Path.of(file);
    }

    boolean enabled() { return file != null; }

    void clearChannels() {
        channels.clear();
    }

    Map<String, Credential> credentials() {
        if (file == null || !Files.isRegularFile(file)) return Map.of();
        try (var input = Files.newInputStream(file)) {
            var array = json.readTree(input);
            if (array == null || !array.isArray() || array.size() > 128)
                throw ScenarioRuns.unavailable("演示设备配置须为最多 128 台设备的 JSON 列表");
            var credentials = new HashMap<String, Credential>();
            for (var item : array) {
                var credential = new Credential(item.path("deviceId").asText(), item.path("campusId").asText(),
                        item.path("secret").asText());
                if (credential.deviceId().isBlank() || credential.campusId().isBlank() || credential.secret().isBlank()
                        || credentials.putIfAbsent(credential.deviceId(), credential) != null)
                    throw ScenarioRuns.unavailable("演示设备配置包含缺失或重复的设备凭据");
            }
            return credentials;
        } catch (IOException error) {
            throw ScenarioRuns.unavailable("无法读取演示设备配置，请检查本地文件");
        }
    }

    Catalogue catalogue(String campus) {
        try {
            var devices = candidates(campus, credentials());
            return new Catalogue(devices, devices.isEmpty() ?
                    "请在项目目录运行 python scripts/demo_devices.py，初始化当前园区的演示设备" : null);
        } catch (DeviceProblem error) {
            return new Catalogue(List.of(), error.getMessage());
        }
    }

    private List<Candidate> candidates(String campus, Map<String, Credential> credentials) {
        var allowed = credentials.values().stream().filter(item -> item.campusId().equals(campus))
                .map(Credential::deviceId).toList();
        if (allowed.isEmpty()) return List.of();
        return named.query("""
                SELECT d.id,d.device_code,d.name,d.type,d.building_id,b.name building_name,n.upstream_device_id
                FROM device d JOIN building b ON b.id=d.building_id
                LEFT JOIN network_link n ON n.downstream_device_id=d.id
                WHERE d.campus_id=:campus AND d.enabled=1 AND d.id IN (:allowed) ORDER BY b.id,d.device_code
                """, Map.of("campus", campus, "allowed", allowed), (rs, row) ->
                new Candidate(rs.getString("id"), rs.getString("device_code"), rs.getString("name"), rs.getString("type"),
                        rs.getString("building_id"), rs.getString("building_name"), rs.getString("upstream_device_id")));
    }

    List<String> targets(String campus, ScenarioRuns.Template template, String id) {
        var available = candidates(campus, credentials());
        if (available.isEmpty()) throw ScenarioRuns.unavailable("当前园区没有可运行的演示设备");
        var target = available.stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> DeviceProblem.badRequest("请选择当前园区内已启用的演示设备"));
        String type = target.type();
        boolean suitable = switch (template) {
            case NETWORK_UPSTREAM_FAILURE -> type.equals("SWITCH");
            case SENSOR_THRESHOLD_BREACH -> type.equals("SENSOR");
            case DEVICE_FLAPPING -> !type.equals("SWITCH");
        };
        if (!suitable) throw DeviceProblem.badRequest("设备类型与场景不匹配");
        var result = new ArrayList<String>();
        result.add(id);
        if (template == ScenarioRuns.Template.NETWORK_UPSTREAM_FAILURE) {
            available.stream().filter(device -> id.equals(device.upstreamId()))
                    .map(Candidate::id).forEach(result::add);
            if (result.size() < 3 || result.size() > 12)
                throw DeviceProblem.badRequest("交换机须关联 2–11 台已启用的演示下游设备");
        }
        return List.copyOf(result);
    }

    void send(ScenarioRuns.Run run, Credential credential, DeviceMessage.Type type, Integer temperature)
            throws IOException, MqttException {
        if (credential == null || !credential.campusId().equals(run.campusId()))
            throw ScenarioRuns.unavailable("运行范围内有设备缺少本地凭据");
        send(credential, run.id(), run.phase(), run.phaseAt(), type, temperature);
    }

    private void send(Credential credential, String owner, ScenarioRuns.Phase phase, Instant phaseAt,
            DeviceMessage.Type type, Integer temperature) throws IOException, MqttException {
        Channel channel = channels.computeIfAbsent(credential.deviceId(), key -> new Channel());
        Instant now = Instant.now();
        var activeSessions = jdbc.queryForList("SELECT active_session_id FROM device WHERE id=? AND enabled=1",
                String.class, credential.deviceId());
        if (activeSessions.isEmpty()) throw DeviceProblem.conflict("演示设备已停用");
        String active = activeSessions.getFirst();
        boolean takenOver = channel.session != null && !channel.sessionId.equals(active);
        boolean newPhase = !java.util.Objects.equals(channel.phaseAt, phaseAt);
        if (takenOver && (owner == null || !newPhase && owner.equals(channel.owner)))
            throw DeviceProblem.conflict("演示设备已切换到其他上报会话");
        if (takenOver) {
            channel = new Channel();
            channels.put(credential.deviceId(), channel);
        }
        if (newPhase || !java.util.Objects.equals(owner, channel.owner)) channel.sent.clear();
        channel.owner = owner;
        channel.phaseAt = phaseAt;
        var key = new PublishKey(owner, phaseAt, type);
        Instant previous = channel.sent.get(key);
        if (previous != null && now.isBefore(previous.plusSeconds(10))) return;
        if (channel.session == null || channel.session.expiresAt().isBefore(now.plusSeconds(60))) {
            channel.session = sessions.open(new DeviceModels.SessionRequest(credential.deviceId(),
                    credential.secret(), channel.sessionId));
        }
        connect();
        var payload = json.createObjectNode();
        if (owner != null) payload.put("scenarioRunId", owner).put("scenarioPhase", phase.name());
        if (temperature != null) payload.put("temperature", temperature);
        if (type == DeviceMessage.Type.DEVICE_FAULT) payload.put("faultCode", "NETWORK_UPSTREAM_FAILURE");
        if (type == DeviceMessage.Type.SENSOR_READING && temperature != null && temperature >= 35)
            payload.put("healthStatus", "ABNORMAL");
        var message = new DeviceMessage(UUID.randomUUID().toString(), type, 1, credential.campusId(),
                credential.deviceId(), channel.sessionId, channel.seq++, now, payload, channel.session.sessionToken());
        mqtt.publish("capyiot/" + credential.campusId() + "/devices/" + credential.deviceId() + "/events",
                json.writeValueAsBytes(message), 1, false).waitForCompletion(2500);
        channel.sent.put(key, now);
    }

    void keepAlive(Map<String, Credential> credentials, List<String> reserved) {
        for (String id : List.copyOf(channels.keySet())) {
            if (reserved.contains(id)) continue;
            Credential credential = credentials.get(id);
            if (credential == null) {
                channels.remove(id);
                continue;
            }
            try {
                send(credential, null, null, null, HEARTBEAT, null);
            } catch (DeviceProblem error) {
                channels.remove(id);
            } catch (IOException | MqttException error) {
                // Retry on the next tick when the broker is reachable again.
                return;
            }
        }
    }

    private void connect() throws MqttException {
        if (mqtt == null) mqtt = new MqttAsyncClient(broker, "capyiot-scenario-" + UUID.randomUUID(), new MemoryPersistence());
        if (!mqtt.isConnected()) {
            var options = new MqttConnectOptions();
            options.setConnectionTimeout(2);
            mqtt.connect(options).waitForCompletion(2500);
        }
    }

    @PreDestroy
    void close() throws MqttException {
        if (mqtt == null) return;
        try {
            if (mqtt.isConnected()) mqtt.disconnectForcibly(0, 1000, false);
        } finally {
            mqtt.close(true);
        }
    }
}
