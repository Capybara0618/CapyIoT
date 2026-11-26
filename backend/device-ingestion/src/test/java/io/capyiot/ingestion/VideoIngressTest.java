package io.capyiot.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.internal.DeviceApi;
import io.capyiot.contract.internal.ServiceId;
import io.capyiot.service.ServiceFailure;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.eclipse.paho.client.mqttv3.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class VideoIngressTest {
    @Test void acknowledgmentWaitsForKafkaAndFailedDeliveryRemainsUnacknowledged()throws Exception{
        exercise(false);exercise(true);
    }
    private void exercise(boolean fail)throws Exception{
        var json=new ObjectMapper().findAndRegisterModules();
        var devices=mock(DeviceAuthorityClient.class);
        when(devices.session("ipc-1","boot-1")).thenReturn(new DeviceApi.SessionContext("ipc-1","CAMPUS-A","IPC","SIMULATOR","boot-1",1,1,Instant.now().plusSeconds(900),null));
        @SuppressWarnings("unchecked") var kafka=(KafkaTemplate<String,String>)mock(KafkaTemplate.class);
        var pending=new CompletableFuture<SendResult<String,String>>();when(kafka.send(anyString(),anyString(),anyString())).thenReturn(pending);
        try(var clients=mockConstruction(MqttAsyncClient.class,(client,context)->when(client.isConnected()).thenReturn(true))){
            var ingress=new VideoIngress(kafka,json,new SimpleMeterRegistry(),"ssl://localhost:8883","capyiot-ingestion-test","unused-ca.pem",devices);
            try{
                var client=clients.constructed().getFirst();var capture=ArgumentCaptor.forClass(MqttCallback.class);verify(client).setCallback(capture.capture());
                var wire=json.createObjectNode().put("eventId",UUID.randomUUID().toString()).put("schemaVersion",1).put("deviceId","ipc-1").put("sessionId","boot-1").put("eventType","HEARTBEAT").put("streamKey","HEARTBEAT").put("sequence",1).put("occurredAt",Instant.now().toString());wire.putObject("payload").put("bootId","boot-1");
                var message=new MqttMessage(json.writeValueAsBytes(wire));message.setQos(1);
                capture.getValue().messageArrived("capyiot/v1/devices/ipc-1/sessions/boot-1/events",message);
                verify(client,never()).messageArrivedComplete(anyInt(),anyInt());
                var body=ArgumentCaptor.forClass(String.class);verify(kafka).send(eq("capyiot.device.heartbeat.v1"),eq("ipc-1"),body.capture());
                var normalized=json.readTree(body.getValue());assertThat(normalized.path("source").asText()).isEqualTo("SIMULATOR");assertThat(normalized.path("sessionEpoch").asLong()).isEqualTo(1);
                assertThat(body.getValue()).doesNotContain("sessionToken","test-only-service-key");
                if(fail){pending.completeExceptionally(new IllegalStateException("Kafka unavailable"));verify(client,never()).messageArrivedComplete(anyInt(),anyInt());}
                else{pending.complete(null);verify(client).messageArrivedComplete(anyInt(),eq(1));}
            }finally{ingress.stop();}
        }
    }
    @Test void unavailableDeviceAuthorityDoesNotRejectOrAcknowledgeTheMessage() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var devices=mock(DeviceAuthorityClient.class);
        when(devices.session("ipc-1","boot-1")).thenThrow(ServiceFailure.unavailable("DEVICE_AUTHORITY_UNAVAILABLE",ServiceId.DEVICE,true));
        @SuppressWarnings("unchecked") var kafka=(KafkaTemplate<String,String>)mock(KafkaTemplate.class);
        try(var clients=mockConstruction(MqttAsyncClient.class,(client,context)->when(client.isConnected()).thenReturn(true))){
            var ingress=new VideoIngress(kafka,json,new SimpleMeterRegistry(),"ssl://localhost:8883","capyiot-ingestion-test","unused-ca.pem",devices);
            try {
                var client=clients.constructed().getFirst();var capture=ArgumentCaptor.forClass(MqttCallback.class);verify(client).setCallback(capture.capture());
                var message=new MqttMessage("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));message.setQos(1);
                capture.getValue().messageArrived("capyiot/v1/devices/ipc-1/sessions/boot-1/events",message);
                verifyNoInteractions(kafka);verify(client,never()).messageArrivedComplete(anyInt(),anyInt());
            }finally{ingress.stop();}
        }
    }
    @Test void rejectedSessionIsRecordedWithoutCredentialMaterial() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var devices=mock(DeviceAuthorityClient.class);
        when(devices.session("ipc-1","boot-1")).thenThrow(ServiceFailure.denied(401,"DEVICE_AUTHENTICATION_FAILED"));
        @SuppressWarnings("unchecked") var kafka=(KafkaTemplate<String,String>)mock(KafkaTemplate.class);
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        try(var clients=mockConstruction(MqttAsyncClient.class)){
            var ingress=new VideoIngress(kafka,json,new SimpleMeterRegistry(),"ssl://localhost:8883","capyiot-ingestion-test","unused-ca.pem",devices);
            try {
                var message=new MqttMessage("{\"secret\":\"must-not-escape\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));message.setQos(1);
                ingress.publish("capyiot/v1/devices/ipc-1/sessions/boot-1/events",message);
                var body=ArgumentCaptor.forClass(String.class);verify(kafka).send(eq("capyiot.device.rejected.v1"),anyString(),body.capture());
                assertThat(body.getValue()).contains("payloadSha256").doesNotContain("must-not-escape");
            }finally{ingress.stop();}
        }
    }
}
