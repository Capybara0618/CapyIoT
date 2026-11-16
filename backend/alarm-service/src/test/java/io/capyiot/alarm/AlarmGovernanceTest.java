package io.capyiot.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.capyiot.contract.DeviceSignal;
import io.capyiot.contract.TopologyEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false", "capyiot.outbox-enabled=false"
})
class AlarmGovernanceTest {
    private static final byte[] SIGNING_KEY = "alarm-integration-fixture-key-only".getBytes(StandardCharsets.UTF_8);
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11").withDatabaseName("alarm_db");

    @Autowired AlarmRules rules;
    @Autowired AlarmQueries queries;
    @Autowired TopologyProjection topology;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TestRestTemplate rest;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("capyiot.user-jwt-secret", () -> Base64.getEncoder().encodeToString(SIGNING_KEY));
    }

    @Test
    void groupsTimeoutsAndUpgradesWithLateExplicitEvidenceWithoutMergingTemperature() throws Exception {
        String campus = "GROUP";
        var now = Instant.now().minusSeconds(45);
        topology.replace(campus, 2, List.of(node("SW", "SWITCH", null), node("CAM", "CAMERA", "SW"),
                node("ACS", "ACCESS_CONTROL", "SW"), node("SEN", "SENSOR", "SW")));
        var left = signal(campus, "CAM", "SW", 2, "HEARTBEAT_TIMEOUT", "OFFLINE", now, null, null);
        rules.evaluate(left);
        rules.evaluate(left);
        rules.evaluate(signal(campus, "ACS", "SW", 2, "HEARTBEAT_TIMEOUT", "OFFLINE", now.plusSeconds(1), null, null));
        rules.evaluate(signal(campus, "SEN", "SW", 2, "SENSOR_READING", "ONLINE", now.plusSeconds(1), null, 41));
        var suspected = group(campus);
        assertThat(suspected.relationKind()).isEqualTo("SUSPECTED_UPSTREAM");
        assertThat(suspected.affectedCount()).isEqualTo(2);
        assertThat(queries.summary(campus).active()).isEqualTo(2);

        // Arrives after both children at Kafka, but occurred within the switch's correlation window.
        rules.evaluate(signal(campus, "SW", null, 3, "DEVICE_FAULT", "ONLINE", now.minusSeconds(10),
                AlarmRules.NETWORK, null));
        var confirmed = group(campus);
        assertThat(confirmed.id()).isEqualTo(suspected.id());
        assertThat(confirmed.relationKind()).isEqualTo("CONFIRMED_UPSTREAM");
        assertThat(confirmed.severity()).isEqualTo("CRITICAL");
        var detail = queries.detail(campus, confirmed.id(), 1, 1, 1, 20);
        assertThat(detail.children().items()).extracting(AlarmQueries.Alarm::alarmType)
                .containsExactlyInAnyOrder(AlarmRules.OFFLINE, AlarmRules.OFFLINE, AlarmRules.NETWORK);
        assertThat(detail.evidence().items()).extracting(AlarmQueries.Evidence::evidenceType).contains("UPSTREAM_FAULT");
        assertThat(detail.devices().items()).extracting(AlarmQueries.Impact::deviceId).containsExactlyInAnyOrder("SW", "CAM", "ACS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_event WHERE event_id=?", Long.class, left.eventId())).isEqualTo(1);

        var ack = rest.exchange("/api/v1/alarms/" + confirmed.id() + "/acknowledge", HttpMethod.POST,
                new HttpEntity<>(headers(campus)), String.class);
        assertThat(ack.getStatusCode().value()).isEqualTo(200);
        long acknowledgedVersion = json.readTree(ack.getBody()).path("data").path("version").asLong();
        assertThat(queries.acknowledge(campus, confirmed.id(), "operator").version()).isEqualTo(acknowledgedVersion);
        assertThat(rest.exchange("/api/v1/alarms/" + confirmed.id(), HttpMethod.GET,
                new HttpEntity<>(headers("OTHER")), String.class).getStatusCode().value()).isEqualTo(404);

        rules.evaluate(signal(campus, "CAM", "SW", 3, "HEARTBEAT", "ONLINE", now.plusSeconds(25), null, null));
        rules.evaluate(signal(campus, "ACS", "SW", 3, "HEARTBEAT", "ONLINE", now.plusSeconds(26), null, null));
        assertThat(group(campus).status()).isEqualTo("ACKNOWLEDGED");
        rules.evaluate(signal(campus, "SW", null, 4, "DEVICE_RECOVERED", "ONLINE", now.plusSeconds(27), null, null));
        assertThat(group(campus).status()).isEqualTo("RECOVERED");
        assertThat(queries.summary(campus).active()).isEqualTo(1);
        rules.evaluate(signal(campus, "SEN", "SW", 3, "SENSOR_READING", "ONLINE", now.plusSeconds(28), null, 34));
        assertThat(queries.summary(campus).active()).isEqualTo(1);
        rules.evaluate(signal(campus, "SEN", "SW", 4, "SENSOR_READING", "ONLINE", now.plusSeconds(29), null, 33));
        assertThat(queries.summary(campus).active()).isZero();
    }

    @Test
    void archivesEveryRepeatButReusesTheSameInteractionForAnIsolatedFlap() {
        String campus = "FLAP";
        topology.replace(campus, 2, List.of(node("CAM", "CAMERA", null)));
        var now = Instant.now().minusSeconds(35);
        rules.evaluate(signal(campus, "CAM", null, 2, "HEARTBEAT_TIMEOUT", "OFFLINE", now, null, null));
        long id = queries.list(campus, null, null, null, null, true, 1, 10).items().getFirst().id();
        rules.evaluate(signal(campus, "CAM", null, 3, "HEARTBEAT", "ONLINE", now.plusSeconds(2), null, null));
        rules.evaluate(signal(campus, "CAM", null, 4, "HEARTBEAT_TIMEOUT", "OFFLINE", now.plusSeconds(33), null, null));
        rules.evaluate(signal(campus, "CAM", null, 3, "HEARTBEAT", "ONLINE", now.plusSeconds(34), null, null));
        var alarms = queries.list(campus, null, null, null, null, true, 1, 10);
        assertThat(alarms.total()).isEqualTo(1);
        assertThat(alarms.items().getFirst().id()).isEqualTo(id);
        assertThat(alarms.items().getFirst().suppressedCount()).isEqualTo(1);
        assertThat(alarms.items().getFirst().status()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_event WHERE campus_id=?", Long.class, campus)).isEqualTo(4);
        assertThat(queries.detail(campus, id, 1, 1, 1, 20).evidence().items())
                .extracting(AlarmQueries.Evidence::evidenceType).contains("FLAP_SUPPRESSED");
    }

    @Test
    void comparesDownstreamTimeoutAgainstSwitchEvidenceAndCapsClusterSpan() {
        String campus = "WINDOW";
        var now = Instant.now().minusSeconds(200);
        topology.replace(campus, 2, List.of(node("SW", "SWITCH", null), node("A", "CAMERA", "SW"),
                node("B", "CAMERA", "SW"), node("C", "CAMERA", "SW")));
        rules.evaluate(signal(campus, "SW", null, 1, "DEVICE_FAULT", "ONLINE", now, AlarmRules.NETWORK, null));
        rules.evaluate(signal(campus, "A", "SW", 1, "HEARTBEAT_TIMEOUT", "OFFLINE", now.plusSeconds(59), null, null));
        rules.evaluate(signal(campus, "B", "SW", 1, "HEARTBEAT_TIMEOUT", "OFFLINE", now.plusSeconds(61), null, null));
        assertThat(group(campus).affectedCount()).isEqualTo(1);
        assertThat(queries.summary(campus).active()).isEqualTo(2);

        String withoutSwitchEvidence = "SPAN";
        topology.replace(withoutSwitchEvidence, 2, List.of(node("SW", "SWITCH", null), node("A", "CAMERA", "SW"),
                node("B", "CAMERA", "SW"), node("C", "CAMERA", "SW")));
        rules.evaluate(signal(withoutSwitchEvidence, "A", "SW", 1, "HEARTBEAT_TIMEOUT", "OFFLINE", now, null, null));
        rules.evaluate(signal(withoutSwitchEvidence, "B", "SW", 1, "HEARTBEAT_TIMEOUT", "OFFLINE", now.plusSeconds(61), null, null));
        rules.evaluate(signal(withoutSwitchEvidence, "C", "SW", 1, "HEARTBEAT_TIMEOUT", "OFFLINE", now.plusSeconds(31), null, null));
        assertThat(group(withoutSwitchEvidence).affectedCount()).isEqualTo(2);
        assertThat(queries.summary(withoutSwitchEvidence).active()).isEqualTo(2);
    }

    @Test
    void projectionDiscardsDuplicatesAndDetectsMissingVersions() {
        String campus = "TOPO";
        var root = node("SW", "SWITCH", null);
        var child = node("CAM", "CAMERA", "SW");
        topology.replace(campus, 2, List.of(root));
        assertThat(topology.apply(delta(campus, 4, child))).isFalse();
        assertThat(topology.node(campus, "CAM")).isNull();
        topology.replace(campus, 4, List.of(root, child));
        assertThat(topology.apply(delta(campus, 3, node("CAM", "CAMERA", null)))).isTrue();
        assertThat(topology.node(campus, "CAM").upstreamNodeId()).isEqualTo("SW");
        assertThat(topology.apply(delta(campus, 5, node("CAM", "CAMERA", null)))).isTrue();
        assertThat(topology.node(campus, "CAM").upstreamNodeId()).isNull();
    }

    private AlarmQueries.Alarm group(String campus) {
        return queries.list(campus, null, null, null, "NETWORK_INCIDENT", true, 1, 10).items().getFirst();
    }

    private DeviceSignal signal(String campus, String id, String parent, long version, String reason,
            String connectivity, Instant time, String fault, Integer temperature) {
        String key = UUID.randomUUID().toString();
        var payload = json.createObjectNode();
        if (fault != null) payload.put("faultCode", fault);
        if (temperature != null) payload.put("temperature", temperature);
        var state = new DeviceSignal.State(connectivity, fault == null ? "NORMAL" : "ABNORMAL",
                version, key, "ONLINE", "NORMAL", reason, payload, parent, true, 2);
        return new DeviceSignal(key, "DEVICE_STATE_CHANGED", 1, campus, id, time, Instant.now(), key, "device-service", state);
    }

    private TopologyEvent.Node node(String id, String type, String parent) {
        return new TopologyEvent.Node(id, id, id, type, "BLD-1", "1号楼", parent, true);
    }

    private TopologyEvent delta(String campus, long version, TopologyEvent.Node node) {
        Instant now = Instant.now();
        return new TopologyEvent(UUID.randomUUID().toString(), "TOPOLOGY_NODE_CHANGED", 1,
                campus, now, now, "trace", "device-service", new TopologyEvent.Change(version, node));
    }

    private HttpHeaders headers(String campus) {
        var jwt = JwtClaimsSet.builder().issuer("capyiot-gateway").subject("operator")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .claim("campusId", campus).claim("roles", List.of("OPERATOR")).build();
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(SIGNING_KEY));
        var headers = new HttpHeaders();
        headers.setBearerAuth(encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), jwt)).getTokenValue());
        return headers;
    }
}
