package io.capyiot.legacyworkflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.AlarmEvent;
import io.capyiot.contract.KafkaTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

@Component
class WorkflowKafka {
    static final String DEAD_TOPIC = "iot.alarm.events.dead.v1";
    static final class InvalidEvent extends RuntimeException {
        InvalidEvent() { super("Invalid alarm event"); }
    }

    private final ObjectMapper json;
    private final WorkOrderService orders;

    WorkflowKafka(ObjectMapper json, WorkOrderService orders) {
        this.json = json;
        this.orders = orders;
    }

    @KafkaListener(topics = AlarmEvent.TOPIC, groupId = WorkOrderService.GROUP)
    void consume(ConsumerRecord<String, String> record) {
        var event = decode(record.key(), record.value());
        var span = KafkaTelemetry.process(record, event.eventId());
        try (var scope = span.makeCurrent()) {
            orders.consume(event);
        } catch (RuntimeException error) {
            span.setStatus(StatusCode.ERROR);
            throw error;
        } finally {
            span.end();
        }
    }

    AlarmEvent decode(String key, String value) {
        if (value == null) throw new InvalidEvent();
        AlarmEvent event;
        try {
            event = json.readValue(value, AlarmEvent.class);
        } catch (JsonProcessingException invalid) {
            throw new InvalidEvent();
        }
        if (event == null || event.schemaVersion() != 1 || !AlarmEvent.CHANGED.equals(event.eventType())
                || event.eventId() == null || !event.eventId().matches("[A-Za-z0-9_-]{1,64}") || !fits(event.campusId(), 64)
                || !fits(event.traceId(), 64) || !"alarm-service".equals(event.producer())
                || event.occurredAt() == null || event.receivedAt() == null
                || event.alarmId() < 1 || !Long.toString(event.alarmId()).equals(key)) throw new InvalidEvent();
        var source = event.payload();
        if (source == null || !event.campusId().equals(source.campusId()) || source.id() != event.alarmId()
                || source.version() < 0 || !fits(source.alarmNo(), 64) || !fits(source.deviceName(), 100)
                || !fits(source.type(), 32) || !fits(source.relationKind(), 24)
                || source.status() == null || !List.of("OPEN", "ACKNOWLEDGED", "RECOVERED").contains(source.status())
                || source.severity() == null || !List.of("CRITICAL", "WARNING").contains(source.severity())
                || (source.recovered() && source.recoveredAt() == null)) throw new InvalidEvent();
        return event;
    }

    private static boolean fits(String value, int length) {
        return value != null && !value.isBlank() && value.length() <= length;
    }

    @Configuration(proxyBeanMethods = false)
    static class KafkaConfig {
        @Bean
        DefaultErrorHandler workflowErrors(WorkflowDeadLetters deadLetters) {
            var handler = new DefaultErrorHandler(deadLetters::store, new FixedBackOff(1000, 2));
            handler.addNotRetryableExceptions(InvalidEvent.class);
            return handler;
        }

        @Bean
        NewTopic workorderEvents() {
            return TopicBuilder.name(WorkOrderService.TOPIC).partitions(8).replicas(1).build();
        }

        @Bean
        NewTopic alarmDeadLetterEvents() {
            return TopicBuilder.name(DEAD_TOPIC).partitions(8).replicas(1).build();
        }
    }
}
