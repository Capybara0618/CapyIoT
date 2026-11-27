package io.capyiot.workflow.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.outbox.OutboxRelay;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
class WorkRuntimeConfiguration {
  @Bean
  @ConditionalOnProperty(name = "capyiot.domain-runtime.enabled", havingValue = "true")
  OutboxRelay workOutbox(
      WorkOrderStore store, KafkaTemplate<String, String> kafka, ObjectMapper json) {
    return new OutboxRelay(store.db, kafka, json, store.tx.getTransactionManager(), true, 20);
  }
}
