package io.capyiot.alarm.fault;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.outbox.OutboxRelay;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class FaultRuntimeConfiguration {
  @Bean
  @ConditionalOnProperty(name = "capyiot.domain-runtime.enabled", havingValue = "true")
  OutboxRelay faultOutbox(
      FaultStore store, KafkaTemplate<String, String> kafka, ObjectMapper json) {
    return new OutboxRelay(store.db, kafka, json, store.tx.getTransactionManager(), true, 20);
  }

  // Poison facts persist a blocker and a hash-only dead letter. Valid dependency/store failures
  // retry without skipping the source record.
  @Bean
  DefaultErrorHandler faultDeliveryErrors() {
    var handler = new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
    handler.setClassifications(java.util.Map.of(Exception.class, true), true);
    return handler;
  }
}
