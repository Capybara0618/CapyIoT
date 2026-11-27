package io.capyiot.alarm.fault;

import io.micrometer.core.instrument.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "capyiot.fault-runtime.enabled", havingValue = "true")
final class FaultKafkaLag {
  private final Admin admin;
  private final Map<String, String> topics =
      Map.of(
          "core-fault-observation-v1",
          "capyiot.domain.device.v1",
          "core-fault-workorder-v1",
          "capyiot.domain.workorder.v1");
  private final Map<String, java.util.concurrent.atomic.AtomicReference<Double>> lag =
      new LinkedHashMap<>();

  FaultKafkaLag(KafkaAdmin kafka, MeterRegistry meters) {
    var config = new HashMap<>(kafka.getConfigurationProperties());
    config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2500);
    config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000);
    admin = Admin.create(config);
    topics.forEach(
        (group, topic) -> {
          var value = new java.util.concurrent.atomic.AtomicReference<>(Double.NaN);
          lag.put(group, value);
          Gauge.builder("capyiot.kafka.group.lag", value, v -> v.get())
              .tag("group", group)
              .tag("topic", topic)
              .register(meters);
        });
  }

  @Scheduled(fixedDelay = 15000, initialDelay = 3000)
  void read() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    try {
      var descriptions = get(admin.describeTopics(topics.values()).allTopicNames(), deadline);
      var latest = new HashMap<TopicPartition, OffsetSpec>();
      var earliest = new HashMap<TopicPartition, OffsetSpec>();
      descriptions.forEach(
          (name, topic) ->
              topic
                  .partitions()
                  .forEach(
                      p -> {
                        var partition = new TopicPartition(name, p.partition());
                        latest.put(partition, OffsetSpec.latest());
                        earliest.put(partition, OffsetSpec.earliest());
                      }));
      var endsFuture = admin.listOffsets(latest).all();
      var startsFuture = admin.listOffsets(earliest).all();
      var committed =
          new HashMap<
              String,
              KafkaFuture<
                  Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata>>>();
      topics
          .keySet()
          .forEach(
              group ->
                  committed.put(
                      group,
                      admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()));
      var ends = get(endsFuture, deadline);
      var starts = get(startsFuture, deadline);
      for (var entry : topics.entrySet()) {
        var positions = get(committed.get(entry.getKey()), deadline);
        long value =
            ends.entrySet().stream()
                .filter(e -> e.getKey().topic().equals(entry.getValue()))
                .mapToLong(
                    e -> {
                      long first = starts.get(e.getKey()).offset();
                      var offset = positions.get(e.getKey());
                      return Math.max(
                          0,
                          e.getValue().offset()
                              - Math.max(first, offset == null ? first : offset.offset()));
                    })
                .sum();
        lag.get(entry.getKey()).set((double) value);
      }
    } catch (InterruptedException stop) {
      Thread.currentThread().interrupt();
      lag.values().forEach(v -> v.set(Double.NaN));
    } catch (Exception unavailable) {
      lag.values().forEach(v -> v.set(Double.NaN));
    }
  }

  private <T> T get(KafkaFuture<T> future, long deadline) throws Exception {
    long left = deadline - System.nanoTime();
    if (left <= 0) throw new TimeoutException();
    return future.get(left, TimeUnit.NANOSECONDS);
  }

  @jakarta.annotation.PreDestroy
  void close() {
    admin.close(Duration.ofSeconds(2));
  }
}
