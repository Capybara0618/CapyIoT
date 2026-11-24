package io.capyiot.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "capyiot.outbox-enabled=false", "capyiot.sweep-enabled=false"})
class DatabaseMigrationTest {
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
            .withDatabaseName("device_db");

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String key = java.util.Base64.getEncoder().encodeToString("test-only-device-key-at-least-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        registry.add("capyiot.legacy-auth-enabled",()->true);
        registry.add("capyiot.user-jwt-secret", () -> key);
        registry.add("capyiot.device-jwt-secret", () -> key);
    }

    @Test
    void flywayCreatesTheOwnedSchemaAndPreventsDuplicateOutboxEvents() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("device_db");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class))
                .isEqualTo(14);
        String insert = """
                INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, event_id, topic, message_key, payload_json)
                VALUES ('DEVICE', 'device-1', 'DEVICE_UPDATED', 'event-1', 'iot.device.status.v1', 'device-1', '{}')
                """;
        jdbc.update(insert);
        assertThatThrownBy(() -> jdbc.update(insert)).isInstanceOf(DuplicateKeyException.class);
    }
}
