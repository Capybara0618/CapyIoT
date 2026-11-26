package io.capyiot.legacydevice;

import io.capyiot.contract.Telemetry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "capyiot.outbox-enabled", havingValue = "true", matchIfMissing = true)
class DeviceOutbox {
    private static final Logger LOG = LoggerFactory.getLogger(DeviceOutbox.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;

    DeviceOutbox(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelayString = "${capyiot.outbox-delay-ms:500}")
    void publish() {
        for (int i = 0; i < 20; i++) {
            String lease = UUID.randomUUID().toString();
            Map<String, Object> event = transactions.execute(status -> {
                // A leased or retrying predecessor must finish before another replica sends a later state.
                var rows = jdbc.queryForList("""
                        SELECT candidate.* FROM outbox_event candidate
                        WHERE candidate.status IN ('PENDING','RETRY') AND candidate.next_retry_at<=CURRENT_TIMESTAMP(3)
                          AND NOT EXISTS (
                            SELECT 1 FROM outbox_event predecessor
                            WHERE predecessor.topic=candidate.topic AND predecessor.message_key=candidate.message_key
                              AND predecessor.status IN ('PENDING','RETRY') AND predecessor.id<candidate.id
                          )
                        ORDER BY candidate.id LIMIT 1 FOR UPDATE SKIP LOCKED
                        """);
                if (rows.isEmpty()) return null;
                var row = rows.getFirst();
                jdbc.update("""
                        UPDATE outbox_event SET delivery_token=?,next_retry_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(3))
                        WHERE id=?
                        """, lease, row.get("id"));
                return row;
            });
            if (event == null) return;
            try (var scope = Telemetry.parent((String) event.get("trace_parent"))) {
                kafka.send((String) event.get("topic"), (String) event.get("message_key"),
                        (String) event.get("payload_json")).get(15, TimeUnit.SECONDS);
                jdbc.update("""
                        UPDATE outbox_event SET status='SENT',sent_at=CURRENT_TIMESTAMP(3),delivery_token=NULL
                        WHERE id=? AND delivery_token=?
                        """, event.get("id"), lease);
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                jdbc.update("""
                        UPDATE outbox_event SET status='RETRY',retry_count=retry_count+1,delivery_token=NULL,
                            next_retry_at=TIMESTAMPADD(SECOND,5,CURRENT_TIMESTAMP(3))
                        WHERE id=? AND delivery_token=?
                        """, event.get("id"), lease);
                LOG.warn("Outbox retry eventId={}", event.get("event_id"));
                return;
            }
        }
    }
}
