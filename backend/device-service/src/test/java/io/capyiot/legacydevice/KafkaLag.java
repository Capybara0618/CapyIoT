package io.capyiot.legacydevice;

import io.capyiot.contract.DeviceEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "capyiot.kafka-lag-enabled", havingValue = "true")
class KafkaLag {
    private final Admin admin;
    private volatile double lag = Double.NaN;

    KafkaLag(KafkaAdmin kafka, MeterRegistry meters) {
        var config = new HashMap<>(kafka.getConfigurationProperties());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2500);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000);
        admin = Admin.create(config);
        Gauge.builder("capyiot.kafka.group.lag", this, reader -> reader.lag)
                .tag("topic", DeviceEvent.TOPIC).tag("group", "device-state-group")
                .description("End offset minus committed offset, summed for the device consumer group")
                .register(meters);
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 3000)
    void read() {
        try {
            var topic = admin.describeTopics(List.of(DeviceEvent.TOPIC)).topicNameValues()
                    .get(DeviceEvent.TOPIC).get(3500, TimeUnit.MILLISECONDS);
            var end = new HashMap<TopicPartition, OffsetSpec>();
            var start = new HashMap<TopicPartition, OffsetSpec>();
            for (var partition : topic.partitions()) {
                var key = new TopicPartition(DeviceEvent.TOPIC, partition.partition());
                end.put(key, OffsetSpec.latest());
                start.put(key, OffsetSpec.earliest());
            }
            var committed = admin.listConsumerGroupOffsets("device-state-group").partitionsToOffsetAndMetadata();
            var beginnings = admin.listOffsets(start).all();
            var ends = admin.listOffsets(end).all().get(3500, TimeUnit.MILLISECONDS);
            var offsets = committed.get(3500, TimeUnit.MILLISECONDS);
            var starts = beginnings.get(3500, TimeUnit.MILLISECONDS);
            lag = ends.entrySet().stream().mapToLong(entry -> {
                var offset = offsets.get(entry.getKey());
                long first = starts.get(entry.getKey()).offset();
                long position = offset == null ? first : Math.max(first, offset.offset());
                return Math.max(0, entry.getValue().offset() - position);
            }).sum();
        } catch (InterruptedException shutdown) {
            Thread.currentThread().interrupt();
        } catch (Exception unavailable) {
            lag = Double.NaN;
        }
    }

    @PreDestroy
    void close() {
        admin.close(Duration.ofSeconds(2));
    }
}
