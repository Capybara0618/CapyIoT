package io.capyiot.legacyalarm;

import io.capyiot.contract.AlarmEvent;
import io.capyiot.outbox.OutboxRelay;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Component
class AlarmOutbox {
    private final JdbcTemplate jdbc;
    private final OutboxRelay relay;

    AlarmOutbox(JdbcTemplate jdbc, OutboxRelay relay) {
        this.jdbc = jdbc;
        this.relay = relay;
    }

    @Bean
    NewTopic alarmEvents() {
        return TopicBuilder.name(AlarmEvent.TOPIC).partitions(8).replicas(1).build();
    }

    AlarmEvent.Source source(String campus, long id) {
        return jdbc.query("""
                SELECT id,campus_id,device_name,alarm_type,severity,status,relation_kind,parent_alarm_id,version,recovered_at
                FROM alarm WHERE campus_id=? AND id=?
                """, (rs, row) -> {
            var recovered = rs.getTimestamp("recovered_at");
            return new AlarmEvent.Source(id, campus, "ALM-%06d".formatted(id), rs.getString("device_name"),
                    rs.getString("alarm_type"), rs.getString("severity"), rs.getString("status"),
                    rs.getString("relation_kind"), rs.getObject("parent_alarm_id", Long.class), rs.getLong("version"),
                    recovered == null ? null : recovered.toInstant());
        }, campus, id).stream().findFirst().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "告警不存在"));
    }

    void publish(String campus, Set<Long> ids, Instant occurred, String trace) {
        for (long id : ids) {
            String eventId = UUID.randomUUID().toString();
            var payload = source(campus, id);
            var event = new AlarmEvent(eventId, AlarmEvent.CHANGED, 1, campus, id,
                    occurred, Instant.now(), trace, "alarm-service", payload);
            relay.enqueue(campus, "ALARM", Long.toString(id), event.eventType(), eventId, AlarmEvent.TOPIC, event);
        }
    }
}
