package io.capyiot.device.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.outbox.OutboxRelay;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class DeviceRuntimeConfiguration {
    @Bean @ConditionalOnProperty(name="capyiot.domain-runtime.enabled",havingValue="true")
    OutboxRelay assetOutbox(AssetStore store,KafkaTemplate<String,String> kafka,ObjectMapper json){return new OutboxRelay(store.db,kafka,json,store.tx.getTransactionManager(),true,20);}
    // Malformed/revoked messages are durably sent to rejected by DeviceStateRuntime. Store outages cannot skip a valid record.
    @Bean DefaultErrorHandler deviceDeliveryErrors(){return new DefaultErrorHandler(new FixedBackOff(1000,FixedBackOff.UNLIMITED_ATTEMPTS));}
}
