package io.capyiot.ingestion;

import io.capyiot.contract.DeviceEvent;
import io.capyiot.contract.TopologyEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
class DeviceTopics {
    @Bean NewTopic topologyChanges() { return TopicBuilder.name(TopologyEvent.TOPIC).partitions(8).replicas(1).build(); }
    @Bean NewTopic deviceEvents() { return TopicBuilder.name(DeviceEvent.TOPIC).partitions(8).replicas(1).build(); }
    @Bean NewTopic deviceStatus() { return TopicBuilder.name(DeviceEvent.STATUS_TOPIC).partitions(8).replicas(1).build(); }
    @Bean NewTopic rejectedEvents() { return TopicBuilder.name(DeviceEvent.REJECTED_TOPIC).partitions(1).replicas(1).build(); }
}
