package io.capyiot.core.fault;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="capyiot.fault-runtime.enabled",havingValue="true")
class FaultRuntime {
    private final FaultService faults;private final ObjectMapper json;
    FaultRuntime(FaultService faults,ObjectMapper json){this.faults=faults;this.json=json;}
    @KafkaListener(topics="capyiot.domain.device.v1",groupId="core-fault-observation-v1")
    void consume(String body)throws Exception{faults.observation(json.readTree(body));}
    @KafkaListener(topics="capyiot.domain.workorder.v1",groupId="core-fault-workorder-v1")
    void workOrders(String body)throws Exception{faults.workOrderEvent(json.readTree(body));}
    @Scheduled(fixedDelay=10000)void recoverGates(){faults.recoverClosingGates();}
}
