package io.capyiot.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL-backed relay for services that own an outbox. Each instance uses only its service database. */
public final class OutboxRelay {
    public record Dead(long id, String eventId, String eventType, String topic, int attempts,
            String error, Instant createdAt) {}
    private record Delivery(long id, String eventId, String topic, String key, String json, int attempts) {}

    private static final System.Logger LOG = System.getLogger(OutboxRelay.class.getName());
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final TransactionTemplate claim;
    private final boolean scheduled;
    private final int maxAttempts;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, ObjectMapper json,
            PlatformTransactionManager manager, boolean scheduled, int maxAttempts) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.json = json;
        this.scheduled = scheduled;
        this.maxAttempts = maxAttempts;
        if (maxAttempts < 1) throw new IllegalArgumentException("Outbox max attempts must be positive");
        this.claim = new TransactionTemplate(manager);
        this.claim.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void enqueue(String campus, String aggregate, String id, String type, String eventId,
            String topic, Object event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox must be written in the business transaction");
        }
        String jsonBody;
        try {
            jsonBody = json.writeValueAsString(event);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize outbox event", failure);
        }
        jdbc.update("""
                INSERT INTO outbox_event(campus_id,aggregate_type,aggregate_id,event_type,event_id,topic,message_key,payload_json)
                VALUES (?,?,?,?,?,?,?,?)
                """, campus, aggregate, id, type, eventId, topic, id, jsonBody);
    }

    @Scheduled(fixedDelayString = "${capyiot.outbox-delay-ms:500}")
    public void tick() {
        if (scheduled) dispatch();
    }

    public void dispatch() {
        for (int i = 0; i < 20; i++) {
            String lease = UUID.randomUUID().toString();
            Delivery delivery = claim.execute(status -> {
                var ready = jdbc.query("""
                        SELECT candidate.id,candidate.event_id,candidate.topic,candidate.message_key,
                            candidate.payload_json,candidate.retry_count
                        FROM outbox_event candidate
                        WHERE candidate.status IN ('PENDING','RETRY') AND candidate.next_retry_at<=CURRENT_TIMESTAMP(3)
                          AND NOT EXISTS (
                            SELECT 1 FROM outbox_event predecessor
                            WHERE predecessor.topic=candidate.topic AND predecessor.message_key=candidate.message_key
                              AND predecessor.status IN ('PENDING','RETRY') AND predecessor.id<candidate.id)
                        ORDER BY candidate.id LIMIT 1 FOR UPDATE SKIP LOCKED
                        """, (rs, row) -> new Delivery(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6)));
                if (ready.isEmpty()) return null;
                var item = ready.getFirst();
                jdbc.update("""
                        UPDATE outbox_event SET delivery_token=?,next_retry_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(3))
                        WHERE id=?
                        """, lease, item.id());
                return item;
            });
            if (delivery == null) return;
            try {
                kafka.send(delivery.topic(), delivery.key(), delivery.json()).get(15, TimeUnit.SECONDS);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return; // The lease expires and another process reclaims it.
                }
                int attempts = delivery.attempts() + 1;
                String error = failure.getCause() == null ? failure.getClass().getSimpleName()
                        : failure.getCause().getClass().getSimpleName();
                jdbc.update("""
                        UPDATE outbox_event SET status=?,retry_count=?,last_error=?,delivery_token=NULL,
                            next_retry_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3))
                        WHERE id=? AND delivery_token=?
                        """, attempts >= maxAttempts ? "DEAD" : "RETRY", attempts, error,
                        Math.min(60, 1 << Math.min(attempts, 6)), delivery.id(), lease);
                LOG.log(System.Logger.Level.WARNING, "Outbox delivery failed for event {0} (attempt {1}, {2})",
                        delivery.eventId(), attempts, error);
                return;
            }
            jdbc.update("""
                    UPDATE outbox_event SET status='SENT',sent_at=CURRENT_TIMESTAMP(3),delivery_token=NULL,last_error=NULL
                    WHERE id=? AND delivery_token=?
                    """, delivery.id(), lease);
        }
    }

    public List<Dead> dead(String campus, long after, int size) {
        return jdbc.query("""
                SELECT id,event_id,event_type,topic,retry_count,last_error,created_at FROM outbox_event
                WHERE campus_id=? AND status='DEAD' AND id>? ORDER BY id LIMIT ?
                """, (rs, row) -> new Dead(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getString(6), rs.getTimestamp(7).toInstant()), campus, after, size);
    }

    public boolean retry(String campus, long id, String actor) {
        return jdbc.update("""
                UPDATE outbox_event SET status='RETRY',retry_count=0,next_retry_at=CURRENT_TIMESTAMP(3),
                    retried_by=?,retried_at=CURRENT_TIMESTAMP(3)
                WHERE campus_id=? AND status='DEAD' AND id=?
                """, actor, campus, id) == 1;
    }
}
