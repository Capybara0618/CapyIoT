package io.capyiot.workflow;

import static io.capyiot.workflow.WorkOrderModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sun.net.httpserver.HttpServer;
import io.capyiot.contract.AlarmEvent;
import io.capyiot.contract.ApiResponse;
import io.capyiot.outbox.OutboxRelay;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "capyiot.outbox-enabled=false")
@Import(WorkflowIntegrationTest.Topic.class)
class WorkflowIntegrationTest {
    private static final String CAMPUS = "CAMPUS-A";
    private static final byte[] JWT_KEY = "workflow-fixture-only-signing-key-32".getBytes(StandardCharsets.UTF_8);
    private static final Map<Long, AlarmEvent.Source> AUTHORITATIVE = new ConcurrentHashMap<>();
    private static HttpServer alarm;

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11").withDatabaseName("workflow_db");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1")
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        var fixtureJson = new ObjectMapper().findAndRegisterModules();
        alarm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        alarm.createContext("/api/v1/alarms/", exchange -> {
            String[] parts = exchange.getRequestURI().getPath().split("/");
            var source = AUTHORITATIVE.get(Long.parseLong(parts[4]));
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            int code = authorization == null || !authorization.startsWith("Bearer ") ? 401 : source == null ? 503 : 200;
            byte[] body = fixtureJson.writeValueAsBytes(ApiResponse.ok(source, "workflow-source-fixture"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        alarm.start();
        registry.add("capyiot.legacy-auth-enabled",()->true);
        registry.add("capyiot.user-jwt-secret", () -> Base64.getEncoder().encodeToString(JWT_KEY));
        registry.add("capyiot.alarm-service-url", () -> "http://127.0.0.1:" + alarm.getAddress().getPort());
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @AfterAll
    static void stopAlarm() { if (alarm != null) alarm.stop(0); }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topic {
        @Bean NewTopic alarmEvents() { return TopicBuilder.name(AlarmEvent.TOPIC).partitions(4).replicas(1).build(); }
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkOrderStore store;
    @Autowired WorkOrderService orders;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TestRestTemplate http;
    @Autowired OutboxRelay relay;

    @Test
    void kafkaDuplicatesAndManualRequestsShareOneOrderAndIgnoreOldAlarmVersions() throws Exception {
        long id = 101;
        var open = source(id, 1, "OPEN", true);
        var event = event(open);
        send(event);
        send(event);
        send(event(open));
        var recovered = source(id, 3, "RECOVERED", true);
        send(event(recovered));
        send(event(source(id, 2, "OPEN", true)));
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            var found = store.bySource(CAMPUS, id);
            assertThat(found).isNotNull();
            assertThat(found.source()).isEqualTo(recovered);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_order WHERE source_alarm_id=?", Long.class, id)).isOne();
        });

        JsonNode result = post("/workorders", Map.of("sourceAlarmId", id), "operator", CAMPUS, "click-101", 200);
        assertThat(result.path("data").path("created").asBoolean()).isFalse();
        long orderId = result.path("data").path("order").path("id").asLong();
        assertThat(post("/workorders", Map.of("sourceAlarmId", id), "operator", CAMPUS, "click-101", 200)
                .path("data").path("order").path("id").asLong()).isEqualTo(orderId);
        assertThat(post("/workorders", Map.of("sourceAlarmId", id, "title", "不同请求"), "operator", CAMPUS, "click-101", 409)
                .path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_event WHERE event_id=?", Long.class, event.eventId())).isOne();
        assertThat(store.timeline(orderId, 1, 30).total()).isOne();
    }

    @Test
    void onlyVerifiedCurrentRecoveryAllowsClosureAndHistorySurvivesVersionConflicts() throws Exception {
        long id = 102;
        AUTHORITATIVE.put(id, source(id, 1, "OPEN", false));
        var reply = post("/workorders", Map.of("sourceAlarmId", id), "operator", CAMPUS, null, 200);
        assertThat(reply.path("data").path("created").asBoolean()).isTrue();
        long orderId = reply.path("data").path("order").path("id").asLong();
        assertThat(change(orderId, 0, "CLOSE", "", 409).path("code").asText()).isEqualTo("WORKORDER_ILLEGAL_TRANSITION");
        change(orderId, 0, "ASSIGN", "", 200);
        assertThat(change(orderId, 0, "START_PROCESSING", "", 409).path("code").asText()).isEqualTo("WORKORDER_VERSION_CONFLICT");
        change(orderId, 1, "START_PROCESSING", "", 200);
        change(orderId, 2, "ADD_NOTE", "已更换线缆", 200);
        change(orderId, 3, "COMPLETE_PROCESSING", "已恢复链路，等待确认", 200);
        assertThat(change(orderId, 4, "VERIFY_RECOVERY", "核对来源", 409).path("code").asText()).isEqualTo("ALARM_NOT_RECOVERED");
        AUTHORITATIVE.remove(id);
        assertThat(change(orderId, 4, "VERIFY_RECOVERY", "核对来源", 503).path("code").asText()).isEqualTo("ALARM_SERVICE_UNAVAILABLE");
        AUTHORITATIVE.put(id, source(id, 2, "RECOVERED", false));
        change(orderId, 4, "VERIFY_RECOVERY", "心跳和告警已恢复", 200);
        AUTHORITATIVE.put(id, source(id, 3, "OPEN", false));
        assertThat(change(orderId, 5, "CLOSE", "", 409).path("code").asText()).isEqualTo("WORKORDER_RECOVERY_REQUIRED");
        AUTHORITATIVE.put(id, source(id, 4, "RECOVERED", false));
        change(orderId, 5, "VERIFY_RECOVERY", "再次核对恢复", 200);
        var closed = change(orderId, 6, "CLOSE", "处置完成", 200);
        assertThat(closed.path("data").path("status").asText()).isEqualTo("CLOSED");
        var other = call("/workorders/" + orderId, HttpMethod.GET, null, "operator", "OTHER", null, 404);
        assertThat(other.path("code").asText()).isEqualTo("NOT_FOUND");
        var history = orders.detail(new Actor(CAMPUS, "operator", false), orderId, 1, 30).timeline();
        assertThat(history.items()).extracting(Entry::action).containsExactly("CREATE", "ASSIGN", "START_PROCESSING",
                "ADD_NOTE", "COMPLETE_PROCESSING", "VERIFY_RECOVERY", "VERIFY_RECOVERY", "CLOSE");
        assertThat(history.items().get(3).remark()).isEqualTo("已更换线缆");
    }

    @Test
    void repeatedConsumerFailureIsDurableAndAdminCanCompensateWithoutDuplicates() throws Exception {
        long id = 103;
        var event = event(source(id, 1, "OPEN", true));
        jdbc.execute("ALTER TABLE work_order ADD CONSTRAINT fixture_write_failure CHECK (source_alarm_id<>103)");
        try {
            var sent = kafka.send(AlarmEvent.TOPIC, Long.toString(id), json.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM workflow_dead_letter WHERE source_partition=? AND source_offset=? AND status='DEAD'
                    """, Long.class, sent.getRecordMetadata().partition(), sent.getRecordMetadata().offset())).isOne());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_event WHERE event_id=?", Long.class, event.eventId())).isZero();
            relay.dispatch();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE topic=? AND status='SENT'",
                    Long.class, WorkflowKafka.DEAD_TOPIC)).isGreaterThanOrEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE work_order DROP CHECK fixture_write_failure");
        }
        long failed = jdbc.queryForObject("SELECT id FROM workflow_dead_letter WHERE message_key=?", Long.class, Long.toString(id));
        post("/workorders/dead-letters/" + failed + "/retry", null, "operator", CAMPUS, null, 403);
        post("/workorders/dead-letters/" + failed + "/retry", null, "admin", CAMPUS, null, 200);
        post("/workorders/dead-letters/" + failed + "/retry", null, "admin", CAMPUS, null, 404);
        send(event);
        assertThat(store.bySource(CAMPUS, id)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_order WHERE source_alarm_id=?", Long.class, id)).isOne();
        assertThat(jdbc.queryForObject("SELECT resolved_by FROM workflow_dead_letter WHERE id=?", String.class, failed)).isEqualTo("admin");
    }

    @Test
    void aNewRelayReclaimsAnExpiredLeaseAndRetainsTheOriginalEventId() throws Exception {
        var event = event(source(104, 1, "OPEN", true));
        orders.consume(event);
        var order = store.bySource(CAMPUS, 104);
        String eventId = jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE aggregate_type='WORKORDER' AND aggregate_id=?",
                String.class, String.valueOf(order.id()));
        jdbc.update("""
                UPDATE outbox_event SET delivery_token='expired-owner',next_retry_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3))
                WHERE event_id=?
                """, eventId);
        new OutboxRelay(jdbc, kafka, json, transactions, false, 8).dispatch();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, eventId)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT delivery_token FROM outbox_event WHERE event_id=?", String.class, eventId)).isNull();
    }

    private void send(AlarmEvent event) throws Exception {
        kafka.send(AlarmEvent.TOPIC, Long.toString(event.alarmId()), json.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
    }

    private AlarmEvent.Source source(long id, long version, String status, boolean automatic) {
        return new AlarmEvent.Source(id, CAMPUS, "ALM-" + id, "测试交换机", "NETWORK_INCIDENT", "CRITICAL",
                status, automatic ? "CONFIRMED_UPSTREAM" : "SUSPECTED_UPSTREAM", null,
                version, status.equals("RECOVERED") ? Instant.parse("2026-11-16T10:00:00Z") : null);
    }

    private AlarmEvent event(AlarmEvent.Source source) {
        Instant now = Instant.now();
        String eventId = UUID.randomUUID().toString();
        return new AlarmEvent(eventId, AlarmEvent.CHANGED, 1, CAMPUS, source.id(), now, now, eventId, "alarm-service", source);
    }

    private JsonNode change(long id, long version, String action, String remark, int status) throws Exception {
        return post("/workorders/" + id + "/transitions", Map.of("expectedVersion", version, "action", action,
                "remark", remark), "operator", CAMPUS, null, status);
    }

    private JsonNode post(String path, Map<String, ?> body, String role, String campus, String key, int status) throws Exception {
        return call(path, HttpMethod.POST, body, role, campus, key, status);
    }

    private JsonNode call(String path, HttpMethod method, Map<String, ?> body, String role, String campus,
            String key, int status) throws Exception {
        var claims = JwtClaimsSet.builder().issuer("capyiot-gateway").subject(role).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).claim("roles", List.of(role.toUpperCase(java.util.Locale.ROOT)))
                .claim("campusId", campus).build();
        var token = new NimbusJwtEncoder(new ImmutableSecret<>(JWT_KEY))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) headers.set("Idempotency-Key", key);
        var reply = http.exchange("/api/v1" + path, method, new HttpEntity<>(body, headers), String.class);
        assertThat(reply.getStatusCode().value()).as(method + " " + path + " response " + reply.getBody()).isEqualTo(status);
        return json.readTree(reply.getBody());
    }
}
