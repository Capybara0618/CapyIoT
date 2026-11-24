package io.capyiot.core.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.*;
import io.capyiot.core.ApiProblem;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="capyiot.device-runtime.enabled",havingValue="true")
class DeviceStateRuntime {
    private final DeviceStateService states;private final ObjectMapper json;private final KafkaTemplate<String,String> kafka;
    private final long heartbeatSeconds,freshnessSeconds;
    DeviceStateRuntime(DeviceStateService states,ObjectMapper json,KafkaTemplate<String,String> kafka,
        @Value("${capyiot.device-runtime.heartbeat-timeout-seconds:45}") long heartbeat,@Value("${capyiot.device-runtime.freshness-timeout-seconds:120}") long freshness){this.states=states;this.json=json;this.kafka=kafka;heartbeatSeconds=heartbeat;freshnessSeconds=freshness;}
    @KafkaListener(topics={VideoDeviceMessage.HEARTBEAT_TOPIC,VideoDeviceMessage.STATUS_TOPIC,VideoDeviceMessage.EVENT_TOPIC},groupId="core-device-state-v1")
    void consume(ConsumerRecord<String,String> record) throws Exception {
        try {
            var event=json.readValue(record.value(),VideoDeviceEvent.class);
            if(!record.key().equals(event.deviceId()))throw ApiProblem.invalid("Kafka key 不一致");
            states.accept(event,record.partition(),record.offset());
        }catch(ApiProblem|com.fasterxml.jackson.core.JsonProcessingException|IllegalArgumentException invalid){
            kafka.send("capyiot.device.rejected.v1",record.key(),json.writeValueAsString(Map.of("source","core-device-state","reason","INVALID_OR_REVOKED_EVENT","partition",record.partition(),"offset",record.offset()))).get(15,java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    @Scheduled(fixedDelayString="${capyiot.device-runtime.sweep-ms:5000}") void sweep(){states.expire(Instant.now(),heartbeatSeconds,freshnessSeconds);}
}
