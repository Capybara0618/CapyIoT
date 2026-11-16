package io.capyiot.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.capyiot.contract.DeviceEvent;
import io.capyiot.contract.DeviceMessage;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MqttIngestionTest {
    private static final byte[] KEY = "ingestion-test-key-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1")
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m");
    @Container static final GenericContainer<?> MQTT = new GenericContainer<>("emqx/emqx:5.8.9")
            .withEnv("EMQX_NODE__NAME", "emqx@127.0.0.1")
            .withEnv("EMQX_NODE__COOKIE", "integration-test-cookie")
            .withEnv("EMQX_DASHBOARD__LISTENERS__HTTP__BIND", "0").withExposedPorts(1883);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("capyiot.mqtt-url", () -> "tcp://" + MQTT.getHost() + ":" + MQTT.getMappedPort(1883));
        registry.add("capyiot.mqtt-client-id", () -> "ingestion-integration-test");
        registry.add("capyiot.device-jwt-secret", () -> Base64.getEncoder().encodeToString(KEY));
    }

    @Autowired MqttIngestion ingestion;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry meters;

    @Test
    void validatesIdentityAndPublishesNormalizedEventsWithoutCredentials() throws Exception {
        awaitReady();
        String id = "device-" + UUID.randomUUID();
        var wire = message(id);
        var producer = producer();
        try (var consumer = consumer()) {
            producer.publish(topic(id), json.writeValueAsBytes(wire), 1, false);
            var received = read(consumer, record -> record.topic().equals(DeviceEvent.TOPIC) && record.key().equals(id));
            var normalized = json.readTree(received.value());
            assertThat(normalized.path("eventId").asText()).isEqualTo(wire.eventId());
            assertThat(normalized.path("sessionEpoch").asLong()).isEqualTo(4);
            assertThat(normalized.path("producer").asText()).isEqualTo("device-ingestion");
            assertThat(normalized.has("sessionToken")).isFalse();
            assertThat(received.value()).doesNotContain(wire.sessionToken());
            producer.publish(topic("wrong-device"), json.writeValueAsBytes(wire), 1, false);
            var rejected = read(consumer, record -> record.topic().equals(DeviceEvent.REJECTED_TOPIC));
            assertThat(json.readTree(rejected.value()).path("reason").asText()).isEqualTo("INVALID_DEVICE_MESSAGE");
            assertThat(rejected.value()).doesNotContain(wire.sessionToken());
        } finally { producer.disconnect(); producer.close(); }
    }

    @Test
    void waitsForKafkaConfirmationBeforeAcknowledgingQosOne() throws Exception {
        awaitReady();
        await().atMost(Duration.ofSeconds(20)).until(() -> meters.get("capyiot.mqtt.inflight").gauge().value() == 0);
        String id = "backpressure-" + UUID.randomUUID();
        var wire = message(id);
        double before = meters.get("capyiot.mqtt.ack").counter().count();
        var docker = DockerClientFactory.instance().client();
        var producer = producer();
        try (var consumer = consumer()) {
            docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
            try {
                producer.publish(topic(id), json.writeValueAsBytes(wire), 1, false);
                await().atMost(Duration.ofSeconds(10)).until(() -> meters.get("capyiot.mqtt.inflight").gauge().value() > 0);
                Thread.sleep(1500);
                assertThat(meters.get("capyiot.mqtt.ack").counter().count()).isEqualTo(before);
            } finally { docker.unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
            var event = read(consumer, record -> record.topic().equals(DeviceEvent.TOPIC) && record.key().equals(id));
            assertThat(json.readTree(event.value()).path("eventId").asText()).isEqualTo(wire.eventId());
            await().atMost(Duration.ofSeconds(35)).until(() -> meters.get("capyiot.mqtt.ack").counter().count() > before);
        } finally { producer.disconnect(); producer.close(); }
    }

    private void awaitReady() { await().atMost(Duration.ofSeconds(45)).until(() -> ingestion.health().getStatus().equals(Status.UP)); }
    private String topic(String device) { return "capyiot/CAMPUS-A/devices/" + device + "/events"; }
    private DeviceMessage message(String id) {
        var claims = JwtClaimsSet.builder().issuer("capyiot-device-service").subject(id)
                .audience(List.of("capyiot-ingestion")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .claim("campusId", "CAMPUS-A").claim("sessionId", "test-session").claim("sessionEpoch", 4L).build();
        String token = new NimbusJwtEncoder(new ImmutableSecret<>(KEY)).encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new DeviceMessage(UUID.randomUUID().toString(), DeviceMessage.Type.HEARTBEAT, 1, "CAMPUS-A", id,
                "test-session", 0L, Instant.now(), json.createObjectNode().put("healthStatus", "NORMAL"), token);
    }
    private MqttClient producer() throws MqttException {
        var client = new MqttClient("tcp://" + MQTT.getHost() + ":" + MQTT.getMappedPort(1883), "test-publisher-" + UUID.randomUUID(), new MemoryPersistence());
        client.setTimeToWait(15000);
        client.connect();
        return client;
    }
    private KafkaConsumer<String, String> consumer() {
        var consumer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "test-" + UUID.randomUUID(), "auto.offset.reset", "earliest", "enable.auto.commit", false,
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class));
        consumer.subscribe(List.of(DeviceEvent.TOPIC, DeviceEvent.REJECTED_TOPIC));
        return consumer;
    }
    private ConsumerRecord<String, String> read(KafkaConsumer<String, String> consumer, Predicate<ConsumerRecord<String, String>> match) {
        Instant deadline = Instant.now().plusSeconds(45);
        while (Instant.now().isBefore(deadline)) {
            for (var record : consumer.poll(Duration.ofMillis(500))) if (match.test(record)) return record;
        }
        throw new AssertionError("Expected Kafka record was not received");
    }
}
