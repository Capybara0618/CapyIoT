package io.capyiot.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.outbox.OutboxRelay;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class PlatformRuntime {
    @Bean @ConditionalOnProperty(name="capyiot.foundation.bootstrap.enabled",havingValue="true")
    ApplicationRunner seedPlatform(PlatformService platform,@Value("${capyiot.foundation.bootstrap.admin-hash}") String admin,
            @Value("${capyiot.foundation.bootstrap.operator-hash}") String operator) {
        if(admin.isBlank()||operator.isBlank())throw new IllegalArgumentException("Bootstrap password hashes are required");
        return args->platform.seed(admin,operator);
    }
    @Bean @ConditionalOnProperty(name="capyiot.domain-runtime.enabled",havingValue="true")
    OutboxRelay platformOutbox(JdbcTemplate db,KafkaTemplate<String,String> kafka,ObjectMapper json,PlatformTransactionManager transactions) {
        return new OutboxRelay(db,kafka,json,transactions,true,20);
    }
    @Bean @ConditionalOnProperty(name="capyiot.domain-runtime.enabled",havingValue="true")
    DefaultErrorHandler platformDeliveryErrors(KafkaTemplate<String,String> kafka) {
        var recoverer=new DeadLetterPublishingRecoverer(kafka,(record,error)->new org.apache.kafka.common.TopicPartition(record.topic()+".dlq",record.partition()));
        recoverer.setFailIfSendResultIsError(true);var handler=new DefaultErrorHandler(recoverer,new FixedBackOff(1000,5));
        handler.setClassifications(java.util.Map.of(Exception.class,true),true);handler.setAckAfterHandle(true);return handler;
    }
}
