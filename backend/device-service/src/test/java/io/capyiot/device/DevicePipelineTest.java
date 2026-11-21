package io.capyiot.device;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.capyiot.contract.DeviceEvent;
import io.capyiot.contract.DeviceMessage;
import io.capyiot.contract.TopologyEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "capyiot.offline-sweep-ms=3600000")
@Import(DevicePipelineTest.Topics.class)
class DevicePipelineTest {
    private static final byte[] KEY = "test-only-device-key-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11").withDatabaseName("device_db");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.8-alpine").withExposedPorts(6379);
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1")
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("capyiot.user-jwt-secret", () -> Base64.getEncoder().encodeToString(KEY));
        registry.add("capyiot.device-jwt-secret", () -> Base64.getEncoder().encodeToString(KEY));
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @TestConfiguration
    static class Topics {
        @Bean NewTopic raw() { return TopicBuilder.name(DeviceEvent.TOPIC).partitions(8).replicas(1).build(); }
        @Bean NewTopic status() { return TopicBuilder.name(DeviceEvent.STATUS_TOPIC).partitions(8).replicas(1).build(); }
        @Bean NewTopic rejected() { return TopicBuilder.name(DeviceEvent.REJECTED_TOPIC).partitions(1).replicas(1).build(); }
        @Bean NewTopic topologyChanges() { return TopicBuilder.name(TopologyEvent.TOPIC).partitions(8).replicas(1).build(); }
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired StringRedisTemplate redis;
    @Autowired DeviceStateProjection projection;
    @Autowired DeviceStateTransactions states;
    @Autowired OfflineSweep sweep;
    @Autowired net.javacrumbs.shedlock.core.LockProvider sweepLock;
    @MockitoSpyBean DeviceNotifications notifications;

    @Test
    void offlineSweepRejectsStaleCandidatesAndExpiresOnceThenHeartbeatsRecover() throws Exception {
        var registered = register("CAMERA", null);
        String id = registered.path("device").path("id").asText();
        session(registered, "sweep-session", 200);
        DeviceEvent heartbeat = event(id, 1, "sweep-session", 1, DeviceMessage.Type.HEARTBEAT);
        send(heartbeat);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(redis.opsForHash().get("device:state:" + id, "status")).isEqualTo("ONLINE"));
        var before = states.snapshot(id);
        Instant expiredAt = before.heartbeat().plusSeconds(31);
        assertThat(states.expire(id, before.version() - 1, expiredAt, 30000).changed()).isFalse();
        sweep.sweep(before.heartbeat().plusSeconds(29));
        assertThat(states.snapshot(id).connectivity()).isEqualTo("ONLINE");
        redis.delete("device:state:" + id);
        sweep.sweep(expiredAt); // Lost Hash is repaired from MySQL.
        assertThat(redis.opsForHash().get("device:state:" + id, "status")).isEqualTo("ONLINE");
        sweep.sweep(expiredAt);
        sweep.sweep(expiredAt);
        assertThat(states.snapshot(id).version()).isEqualTo(before.version() + 1);
        assertThat(states.snapshot(id).connectivity()).isEqualTo("OFFLINE");
        assertThat(redis.opsForZSet().score("device:offline:deadline", id)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM device_state_history WHERE device_id=? AND reason='HEARTBEAT_TIMEOUT'
                """, Long.class, id)).isEqualTo(1);
        send(event(id, 1, "sweep-session", 2, DeviceMessage.Type.HEARTBEAT));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(redis.opsForHash().get("device:state:" + id, "status")).isEqualTo("ONLINE"));
    }

    @Test
    void twoJdbcShedLockProvidersCannotOwnTheSweepTogether() {
        var anotherReplica = new net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider(
                net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbc).usingDbTime().build());
        var config = new net.javacrumbs.shedlock.core.LockConfiguration(
                Instant.now(), "device-offline-sweep", Duration.ofSeconds(30), Duration.ZERO);
        var lock = sweepLock.lock(config).orElseThrow();
        try {
            assertThat(anotherReplica.lock(config)).isEmpty();
        } finally {
            lock.unlock();
        }
        var second = anotherReplica.lock(config);
        assertThat(second).isPresent();
        second.orElseThrow().unlock();
    }

    @Test
    void pendingPredecessorBlocksSameOutboxStreamButNotOtherDevices() {
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        String independent = UUID.randomUUID().toString();
        String sameKey = UUID.randomUUID().toString();
        String sql = """
                INSERT INTO outbox_event(aggregate_type,aggregate_id,event_type,event_id,topic,message_key,
                    payload_json,next_retry_at)
                VALUES ('DEVICE',?,'DEVICE_STATE_CHANGED',?,?,?,'{}',TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)))
                """;
        jdbc.update(sql, sameKey, first, DeviceEvent.STATUS_TOPIC, sameKey, 90);
        jdbc.update(sql, sameKey, second, DeviceEvent.STATUS_TOPIC, sameKey, 0);
        jdbc.update(sql, independent, independent, DeviceEvent.STATUS_TOPIC, independent, 0);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(outboxStatus(independent)).isEqualTo("SENT"));
        assertThat(outboxStatus(second)).isEqualTo("PENDING");
        jdbc.update("UPDATE outbox_event SET next_retry_at=CURRENT_TIMESTAMP(3) WHERE event_id=?", first);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(outboxStatus(first)).isEqualTo("SENT");
            assertThat(outboxStatus(second)).isEqualTo("SENT");
        });
    }

    private String outboxStatus(String eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, eventId);
    }

    @Test
    void openApiExportsTheActualDeviceAndEventSchemas() throws Exception {
        var headers = new HttpHeaders();
        var claims = JwtClaimsSet.builder().issuer("capyiot-gateway").subject("test-user").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600)).claim("roles", List.of("ADMIN")).claim("campusId", "CAMPUS-A").build();
        headers.setBearerAuth(new NimbusJwtEncoder(new ImmutableSecret<>(KEY)).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue());
        var response = rest.exchange("/api/v1/openapi", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode spec = json.readTree(response.getBody());
        assertThat(spec.path("openapi").asText()).startsWith("3.1");
        assertThat(spec.path("paths").has("/api/v1/devices")).isTrue();
        assertThat(spec.path("paths").has("/api/v1/events")).isTrue();
        assertThat(spec.path("components").path("schemas").path("DeviceView").path("properties").has("healthStatus")).isTrue();
        assertThat(spec.path("components").path("schemas").path("DeviceView").path("properties").has("passwordHash")).isFalse();
    }

    @Test
    void registrationValidatesOwnershipRoleLocationAndOptimisticVersion() throws Exception {
        var device = register("CAMERA", null);
        String id = device.path("device").path("id").asText();
        assertThat(call(HttpMethod.GET, "/devices/" + id, null, "OPERATOR", "CAMPUS-A", 200).path("deviceCode").asText()).startsWith("TEST-");
        call(HttpMethod.GET, "/devices/" + id, null, "ADMIN", "OTHER-CAMPUS", 404);
        call(HttpMethod.POST, "/devices", createBody("CAMERA", null), "OPERATOR", "CAMPUS-A", 403);
        var invalid = createBody("CAMERA", null); invalid.put("floorId", "FL-2-1");
        call(HttpMethod.POST, "/devices", invalid, "ADMIN", "CAMPUS-A", 400);
        var patch = new HashMap<String, Object>();
        patch.put("name", "更新后的设备"); patch.put("enabled", false); patch.put("buildingId", "BLD-1");
        patch.put("floorId", "FL-1-1"); patch.put("expectedVersion", 0);
        call(HttpMethod.PATCH, "/devices/" + id, patch, "ADMIN", "CAMPUS-A", 200);
        call(HttpMethod.PATCH, "/devices/" + id, patch, "ADMIN", "CAMPUS-A", 409);
        call(HttpMethod.GET, "/devices?size=101", null, "ADMIN", "CAMPUS-A", 400);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM device WHERE id=?", String.class, id))
                .doesNotContain(device.path("deviceSecret").asText());
    }

    @Test
    void sessionEpochIsServerAssignedAndRetiredSessionCannotReturn() throws Exception {
        var device = register("SWITCH", null);
        var first = session(device, "boot-one", 200);
        assertThat(session(device, "boot-one", 200).path("sessionEpoch")).isEqualTo(first.path("sessionEpoch"));
        assertThat(session(device, "boot-two", 200).path("sessionEpoch").asLong()).isEqualTo(first.path("sessionEpoch").asLong() + 1);
        session(device, "boot-one", 409);
        call(HttpMethod.POST, "/device-sessions", Map.of("deviceId", device.path("device").path("id").asText(),
                "secret", "invalid", "sessionId", "boot-three"), null, null, 401);
    }

    @Test
    void topologyRejectsCyclesUnderTheCampusVersionLock() throws Exception {
        String first = register("SWITCH", null).path("device").path("id").asText();
        String second = register("SWITCH", null).path("device").path("id").asText();
        long version = call(HttpMethod.GET, "/topology/snapshot", null, "ADMIN", "CAMPUS-A", 200).path("version").asLong();
        call(HttpMethod.POST, "/topology/links", Map.of("upstreamDeviceId", first, "downstreamDeviceId", second, "expectedVersion", version), "ADMIN", "CAMPUS-A", 200);
        call(HttpMethod.POST, "/topology/links", Map.of("upstreamDeviceId", second, "downstreamDeviceId", first, "expectedVersion", version + 1), "ADMIN", "CAMPUS-A", 400);
    }

    @Test
    void kafkaDeduplicatesAndKeepsStateMonotonicAcrossSequencesAndSessions() throws Exception {
        var registered = register("CAMERA", null);
        String id = registered.path("device").path("id").asText();
        var opened = session(registered, "session-one", 200);
        DeviceEvent first = event(id, opened.path("sessionEpoch").asLong(), "session-one", 5, DeviceMessage.Type.HEARTBEAT);
        send(first); send(first);
        send(event(id, 1, "session-one", 6, DeviceMessage.Type.DEVICE_FAULT));
        send(event(id, 1, "session-one", 7, DeviceMessage.Type.HEARTBEAT));
        send(event(id, 1, "session-one", 2, DeviceMessage.Type.HEARTBEAT));
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            assertThat(redis.opsForHash().get("device:state:" + id, "seq")).isEqualTo("7");
            assertThat(redis.opsForHash().get("device:state:" + id, "health")).isEqualTo("ABNORMAL");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_event WHERE device_id=?", Long.class, id)).isEqualTo(4);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_event WHERE event_id=?", Long.class, first.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT processing_status FROM device_event WHERE device_id=? AND seq=2", String.class, id)).isEqualTo("IGNORED_STALE");
        var second = session(registered, "session-two", 200);
        send(event(id, second.path("sessionEpoch").asLong(), "session-two", 0, DeviceMessage.Type.HEARTBEAT));
        send(event(id, second.path("sessionEpoch").asLong(), "session-two", 1, DeviceMessage.Type.DEVICE_RECOVERED));
        send(event(id, 1, "session-one", 100, DeviceMessage.Type.HEARTBEAT));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(redis.opsForHash().get("device:state:" + id, "sessionEpoch")).isEqualTo("2");
            assertThat(redis.opsForHash().get("device:state:" + id, "seq")).isEqualTo("1");
            assertThat(redis.opsForHash().get("device:state:" + id, "health")).isEqualTo("NORMAL");
            assertThat(jdbc.queryForList("SELECT processing_status FROM device_event WHERE device_id=? AND seq=100",
                    String.class, id)).containsExactly("IGNORED_SESSION");
        });
        projection.apply(new DeviceStateTransactions.Snapshot(id, "OFFLINE", "ABNORMAL", 1, 5, Instant.now(), 1));
        assertThat(redis.opsForHash().get("device:state:" + id, "seq")).isEqualTo("1");
        assertThat(redis.opsForHash().get("device:state:" + id, "status")).isEqualTo("ONLINE");
        assertThat(redis.opsForZSet().score("device:offline:deadline", id)).isNotNull();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND status='SENT'", Long.class, id)).isGreaterThanOrEqualTo(3));
    }

    @Test
    void redisFailureDoesNotCommitKafkaAndRedeliveryRepairsPersistedSnapshot() throws Exception {
        var registered = register("SENSOR", null);
        String id = registered.path("device").path("id").asText();
        var opened = session(registered, "retry-session", 200);
        var event = event(id, opened.path("sessionEpoch").asLong(), "retry-session", 0, DeviceMessage.Type.HEARTBEAT);
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            var sent = kafka.send(DeviceEvent.TOPIC, id, json.writeValueAsString(event)).get(10, TimeUnit.SECONDS).getRecordMetadata();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM device_event WHERE event_id=?", Long.class, event.eventId())).isEqualTo(1));
            Thread.sleep(4000);
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
                var offset = admin.listConsumerGroupOffsets("device-state-group").partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS)
                        .get(new TopicPartition(DeviceEvent.TOPIC, sent.partition()));
                assertThat(offset == null || offset.offset() <= sent.offset()).isTrue();
            }
        } finally { docker.unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        await().atMost(Duration.ofSeconds(35)).untilAsserted(() -> assertThat(redis.opsForHash().get("device:state:" + id, "status")).isEqualTo("ONLINE"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(notifications, atLeastOnce())
                .changed("CAMPUS-A", id, event.traceId(), false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_event WHERE event_id=?", Long.class, event.eventId())).isEqualTo(1);
    }

    @Test
    void assetCursorTraversesEqualTimestampsAndRespectsCampusAndBuilding() throws Exception {
        var first = register("CAMERA", null).path("device").path("id").asText();
        var second = register("SENSOR", null).path("device").path("id").asText();
        jdbc.update("UPDATE device SET created_at=? WHERE id IN (?,?)",
                java.sql.Timestamp.from(Instant.now().plusSeconds(10)), first, second);
        String path = "/devices/cursor?buildingId=BLD-1&size=1";
        var page = call(HttpMethod.GET, path, null, "OPERATOR", "CAMPUS-A", 200);
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(
                java.util.stream.Stream.of(first, second).max(String::compareTo).orElseThrow());
        var next = call(HttpMethod.GET, path + "&cursor=" + page.path("nextCursor").asText(), null,
                "OPERATOR", "CAMPUS-A", 200);
        assertThat(next.path("items").get(0).path("id").asText()).isEqualTo(
                java.util.stream.Stream.of(first, second).min(String::compareTo).orElseThrow());
        var foreign = call(HttpMethod.GET, path, null, "OPERATOR", "ANOTHER-CAMPUS", 200);
        assertThat(foreign.path("items").size()).isZero();
        call(HttpMethod.GET, path + "&cursor=bad", null, "OPERATOR", "CAMPUS-A", 400);
    }

    @Test
    void incrementalCursorCoversMultiplePartitionsAndSmallPages() throws Exception {
        var first = register("CAMERA", null); var second = register("SENSOR", null);
        String firstId = first.path("device").path("id").asText(); String secondId = second.path("device").path("id").asText();
        session(first, "cursor-a", 200); session(second, "cursor-b", 200);
        String cursor = call(HttpMethod.GET, "/events?size=100", null, "ADMIN", "CAMPUS-A", 200).path("resumeCursor").asText();
        var a = event(firstId, 1, "cursor-a", 0, DeviceMessage.Type.HEARTBEAT);
        var b = event(secondId, 1, "cursor-b", 0, DeviceMessage.Type.HEARTBEAT);
        kafka.send(new ProducerRecord<>(DeviceEvent.TOPIC, 0, firstId, json.writeValueAsString(a))).get(10, TimeUnit.SECONDS);
        kafka.send(new ProducerRecord<>(DeviceEvent.TOPIC, 1, secondId, json.writeValueAsString(b))).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_event WHERE event_id IN (?,?)", Long.class, a.eventId(), b.eventId())).isEqualTo(2));
        var found = new HashSet<String>();
        for (int page = 0; page < 20; page++) {
            var result = call(HttpMethod.GET, "/events?size=1&cursor=" + cursor, null, "ADMIN", "CAMPUS-A", 200);
            result.path("items").forEach(row -> found.add(row.path("eventId").asText()));
            cursor = result.path("nextCursor").asText();
            if (!result.path("hasMore").asBoolean()) break;
        }
        assertThat(found).contains(a.eventId(), b.eventId());
    }

    private JsonNode register(String type, String upstream) throws Exception { return call(HttpMethod.POST, "/devices", createBody(type, upstream), "ADMIN", "CAMPUS-A", 200); }
    private Map<String, Object> createBody(String type, String upstream) {
        var body = new HashMap<String, Object>();
        body.put("deviceCode", "TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        body.put("name", "集成测试设备"); body.put("type", type); body.put("buildingId", "BLD-1"); body.put("floorId", "FL-1-1");
        if (upstream != null) body.put("upstreamNodeId", upstream);
        return body;
    }
    private JsonNode session(JsonNode registered, String sessionId, int expected) throws Exception {
        return call(HttpMethod.POST, "/device-sessions", Map.of("deviceId", registered.path("device").path("id").asText(),
                "secret", registered.path("deviceSecret").asText(), "sessionId", sessionId), null, null, expected);
    }
    private DeviceEvent event(String id, long epoch, String session, long seq, DeviceMessage.Type type) {
        return new DeviceEvent(UUID.randomUUID().toString(), type, 1, "CAMPUS-A", id, session, epoch, seq,
                Instant.now(), Instant.now(), UUID.randomUUID().toString(), "device-ingestion", json.createObjectNode().put("healthStatus", "NORMAL"));
    }
    private void send(DeviceEvent event) throws Exception { kafka.send(DeviceEvent.TOPIC, event.deviceId(), json.writeValueAsString(event)).get(10, TimeUnit.SECONDS); }
    private JsonNode call(HttpMethod method, String path, Object body, String role, String campus, int expected) throws Exception {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (role != null) {
            var claims = JwtClaimsSet.builder().issuer("capyiot-gateway").subject("test-user").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                    .claim("roles", List.of(role)).claim("campusId", campus).build();
            String token = new NimbusJwtEncoder(new ImmutableSecret<>(KEY)).encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
            headers.setBearerAuth(token);
        }
        var response = rest.exchange("/api/v1" + path, method, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().value()).as(method + " " + path).isEqualTo(expected);
        return json.readTree(response.getBody()).path("data");
    }
}
