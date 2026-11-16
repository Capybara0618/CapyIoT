package io.capyiot.alarm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.DeviceEvent;
import io.capyiot.contract.DeviceSignal;
import io.capyiot.contract.TopologyEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

@Component
class AlarmKafka {
    private static final Logger LOG = LoggerFactory.getLogger(AlarmKafka.class);
    private final ObjectMapper json;
    private final TopologySync sync;
    private final TopologyProjection topology;
    private final AlarmRules rules;

    AlarmKafka(ObjectMapper json, TopologySync sync, TopologyProjection topology, AlarmRules rules) {
        this.json = json;
        this.sync = sync;
        this.topology = topology;
        this.rules = rules;
    }

    @KafkaListener(topics = TopologyEvent.TOPIC, groupId = "alarm-topology-projection")
    void consumeTopology(ConsumerRecord<String, String> record) {
        var event = read(record, TopologyEvent.class);
        if (event == null || event.schemaVersion() != 1 || event.campusId() == null
                || !event.campusId().equals(record.key()) || event.payload() == null
                || event.payload().version() < 1 || event.payload().node() == null) return;
        if (!topology.apply(event)) sync.refresh(event.campusId(), event.payload().version());
    }

    @KafkaListener(topics = DeviceEvent.STATUS_TOPIC, groupId = "alarm-rule-group")
    void consumeSignal(ConsumerRecord<String, String> record) {
        var event = read(record, DeviceSignal.class);
        if (event == null || event.schemaVersion() != 1 || event.campusId() == null || event.eventId() == null
                || event.deviceId() == null || !event.deviceId().equals(record.key()) || event.payload() == null
                || event.payload().reason() == null || event.payload().causationId() == null
                || event.receivedAt() == null || event.occurredAt() == null) return;
        sync.ensure(event.campusId(), event.payload().topologyVersion());
        rules.evaluate(event);
    }

    private <T> T read(ConsumerRecord<String, String> record, Class<T> type) {
        try {
            return json.readValue(record.value(), type);
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            LOG.warn("Invalid internal event topic={} partition={} offset={}", record.topic(), record.partition(), record.offset());
            return null;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Retry {
        @Bean
        DefaultErrorHandler alarmConsumerErrors() {
            return new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
        }
    }
}
