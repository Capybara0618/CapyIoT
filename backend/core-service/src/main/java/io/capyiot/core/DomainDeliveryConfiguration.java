package io.capyiot.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.common.TopicPartition;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class DomainDeliveryConfiguration {
    @Bean DefaultErrorHandler domainDeliveryErrors(KafkaTemplate<String,String> kafka){
        var recoverer=new DeadLetterPublishingRecoverer(kafka,(record,error)->new TopicPartition(record.topic()+".dlq",record.partition()));
        // Original topic/partition/offset/group and exception metadata are DLT headers.
        // A failed DLQ publish must not advance the source offset.
        recoverer.setFailIfSendResultIsError(true);
        var handler=new DefaultErrorHandler(recoverer,new FixedBackOff(1000,5));
        handler.setClassifications(java.util.Map.of(Exception.class,true),true);
        handler.setAckAfterHandle(true);return handler;
    }
}
