package io.capyiot.ingestion;

import io.capyiot.contract.DeviceEvent;
import io.capyiot.contract.TopologyEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
class DeviceTopics {
    @Bean org.springframework.kafka.core.KafkaAdmin.NewTopics videoTopics() {
        return new org.springframework.kafka.core.KafkaAdmin.NewTopics(java.util.stream.Stream.of(
                "capyiot.device.heartbeat.v1","capyiot.device.status.v1","capyiot.device.event.v1","capyiot.device.rejected.v1",
                "capyiot.domain.device.v1","capyiot.domain.fault.v1","capyiot.domain.workorder.v1","capyiot.domain.platform.v1")
                .flatMap(name->java.util.stream.Stream.of(name,name+".dlq"))
                .map(name->TopicBuilder.name(name).partitions(8).replicas(1).build()).toArray(NewTopic[]::new));
    }
    @Bean NewTopic topologyChanges() { return TopicBuilder.name(TopologyEvent.TOPIC).partitions(8).replicas(1).build(); }
    @Bean NewTopic deviceEvents() { return TopicBuilder.name(DeviceEvent.TOPIC).partitions(8).replicas(1).build(); }
    @Bean NewTopic deviceStatus() { return TopicBuilder.name(DeviceEvent.STATUS_TOPIC).partitions(8).replicas(1).build(); }
    @Bean NewTopic rejectedEvents() { return TopicBuilder.name(DeviceEvent.REJECTED_TOPIC).partitions(1).replicas(1).build(); }
}
