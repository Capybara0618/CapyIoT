package io.capyiot.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class OutboxConfiguration {
  @Bean
  public OutboxRelay outboxRelay(
      JdbcTemplate jdbc,
      KafkaTemplate<String, String> kafka,
      ObjectMapper json,
      PlatformTransactionManager manager,
      @Value("${capyiot.outbox-enabled:true}") boolean enabled,
      @Value("${capyiot.outbox-max-attempts:8}") int maxAttempts) {
    return new OutboxRelay(jdbc, kafka, json, manager, enabled, maxAttempts);
  }
}
