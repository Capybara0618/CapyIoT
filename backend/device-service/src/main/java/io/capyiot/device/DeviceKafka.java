package io.capyiot.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.DeviceEvent;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

@Component
class DeviceKafka {
    private static final Logger LOG = LoggerFactory.getLogger(DeviceKafka.class);
    private final ObjectMapper json;
    private final Validator validator;
    private final DeviceStateTransactions transactions;
    private final DeviceStateProjection projection;
    private final DeviceNotifications notifications;
    private final KafkaTemplate<String, String> kafka;

    DeviceKafka(ObjectMapper json, Validator validator, DeviceStateTransactions transactions,
            DeviceStateProjection projection, DeviceNotifications notifications, KafkaTemplate<String, String> kafka) {
        this.json = json;
        this.validator = validator;
        this.transactions = transactions;
        this.projection = projection;
        this.notifications = notifications;
        this.kafka = kafka;
    }

    @KafkaListener(topics = DeviceEvent.TOPIC, groupId = "device-state-group", batch = "true")
    void consume(List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) throws Exception {
        for (var record : records) {
            DeviceEvent event;
            DeviceStateTransactions.Result result;
            try {
                event = json.readValue(record.value(), DeviceEvent.class);
                if (event == null || !validator.validate(event).isEmpty() || !event.deviceId().equals(record.key()) || !event.payload().isObject()
                        || event.occurredAt().isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                        || event.occurredAt().isAfter(Instant.now().plusSeconds(86400))
                        || event.receivedAt().isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                        || event.receivedAt().isAfter(Instant.now().plusSeconds(300)))
                    throw DeviceProblem.badRequest("Kafka 事件契约无效");
                result = transactions.persist(event, record.partition(), record.offset());
            } catch (JsonProcessingException | DeviceProblem error) {
                String rejected = json.writeValueAsString(Map.of("source", "device-service", "partition", record.partition(),
                        "offset", record.offset(), "receivedAt", Instant.now(), "reason", "INVALID_EVENT"));
                kafka.send(DeviceEvent.REJECTED_TOPIC, record.key(), rejected).get(15, TimeUnit.SECONDS);
                LOG.warn("Rejected Kafka event partition={} offset={}", record.partition(), record.offset());
                continue;
            }
            // A failed Redis write leaves the Kafka offset uncommitted. Redelivery repairs from MySQL.
            projection.apply(result.snapshot());
            if (result.created()) notifications.changed(event.campusId(), event.deviceId(), event.traceId(), result.stateChanged());
        }
        acknowledgment.acknowledge();
    }

    @Configuration
    static class RetryConfiguration {
        @Bean
        DefaultErrorHandler deviceConsumerErrors() {
            return new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
        }
    }
}
