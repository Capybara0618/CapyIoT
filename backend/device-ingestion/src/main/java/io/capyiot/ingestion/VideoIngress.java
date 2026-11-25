package io.capyiot.ingestion;

import com.fasterxml.jackson.databind.*;
import io.capyiot.contract.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import io.micrometer.core.instrument.*;

@Component("mqttHealthIndicator")
@ConditionalOnProperty(name="capyiot.legacy-ingress-enabled",havingValue="false",matchIfMissing=true)
class VideoIngress implements SmartLifecycle,HealthIndicator {
    private static final System.Logger LOG=System.getLogger(VideoIngress.class.getName());
    private final KafkaTemplate<String,String> kafka;private final ObjectMapper json;private final RestClient core;
    private final MqttAsyncClient mqtt;private final String clientId,ca;private final ScheduledExecutorService connection=Executors.newSingleThreadScheduledExecutor();
    private final Semaphore slots=new Semaphore(128);private final AtomicLong generation=new AtomicLong();private final AtomicBoolean reconnect=new AtomicBoolean();
    private final Object ackLock=new Object();private final Counter acknowledgments;private volatile boolean running,subscribed;private volatile Instant renewAt=Instant.EPOCH;
    VideoIngress(KafkaTemplate<String,String> kafka,ObjectMapper json,MeterRegistry meters,@Value("${capyiot.mqtt-url}") String url,
                 @Value("${capyiot.mqtt-client-id}") String clientId,@Value("${MQTT_CA_FILE:../var/tls/ca.pem}") String ca,
                 @Value("${CORE_SERVICE_URL:http://localhost:8085}") String coreUrl,@Value("${CORE_SERVICE_KEY:}") String key) throws MqttException {
        if(!url.startsWith("ssl://")||key.length()<32)throw new IllegalArgumentException("A09 requires MQTTS and a Core service credential");
        this.kafka=kafka;this.json=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);this.clientId=clientId;this.ca=ca;
        var requests=new SimpleClientHttpRequestFactory();requests.setConnectTimeout(Duration.ofSeconds(2));requests.setReadTimeout(Duration.ofSeconds(3));
        core=RestClient.builder().baseUrl(coreUrl).requestFactory(requests).defaultHeader("X-Core-Service-Key",key).build();
        acknowledgments=meters.counter("capyiot.mqtt.ack");meters.gauge("capyiot.mqtt.inflight",slots,s->128-s.availablePermits());
        mqtt=new MqttAsyncClient(url,clientId,new MemoryPersistence());mqtt.setManualAcks(true);
        mqtt.setCallback(new MqttCallback(){
            @Override public void connectionLost(Throwable cause){synchronized(ackLock){generation.incrementAndGet();subscribed=false;}}
            @Override public void deliveryComplete(IMqttDeliveryToken token){}
            @Override public void messageArrived(String topic,MqttMessage message)throws Exception{
                long receivedGeneration=generation.get();slots.acquire();
                try{
                    publish(topic,message).whenComplete((result,error)->{
                        try{synchronized(ackLock){
                            if(error!=null){reconnect.set(true);generation.incrementAndGet();}
                            else if(receivedGeneration==generation.get()&&!reconnect.get()&&mqtt.isConnected()) {mqtt.messageArrivedComplete(message.getId(),message.getQos());acknowledgments.increment();}
                        }}catch(MqttException failure){reconnect.set(true);generation.incrementAndGet();}finally{slots.release();}
                    });
                }catch(Exception failure){slots.release();reconnect.set(true);generation.incrementAndGet();LOG.log(System.Logger.Level.WARNING,"Ingress unavailable; message remains unacknowledged");}
            }
        });
    }
    CompletableFuture<?> publish(String topic,MqttMessage message)throws Exception {
        Instant received=Instant.now();VideoDeviceEvent event;
        try {
            if(message.getPayload().length>16384||message.getQos()!=1||message.isRetained())throw new IllegalArgumentException();
            JsonNode wire=json.readTree(message.getPayload());String[] parts=topic.split("/",-1);
            if(parts.length!=7||!parts[0].equals("capyiot")||!parts[1].equals("v1")||!parts[2].equals("devices")||!parts[4].equals("sessions")||!parts[6].equals("events"))throw new IllegalArgumentException();
            if(!parts[3].matches("[A-Za-z0-9_-]{1,64}")||!parts[5].matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException();
            // A subscriber cannot read a publisher's CONNECT password. Broker validates RS256/ACL;
            // ingestion independently reloads the authoritative live session on every message.
            JsonNode context=core.get().uri("/internal/devices/{id}/sessions/{sid}",parts[3],parts[5]).retrieve().body(JsonNode.class);
            if(context==null)throw new IllegalStateException("Session context unavailable");
            var parsed=VideoDeviceMessage.validate(wire,topic,context.path("deviceType").asText(),received);
            if(parsed.payload().has("monitoringPointId")&&(!context.hasNonNull("monitoringPointId")||!parsed.payload().path("monitoringPointId").asText().equals(context.path("monitoringPointId").asText())))throw new IllegalArgumentException();
            event=new VideoDeviceEvent(parsed.eventId(),1,parsed.deviceId(),parsed.sessionId(),parsed.eventType(),parsed.streamKey(),parsed.sequence(),parsed.occurredAt(),parsed.payload(),
                    context.path("parkId").asText(),context.path("deviceType").asText(),context.path("source").asText(),context.path("sessionEpoch").asLong(),context.path("credentialVersion").asLong(),received,UUID.randomUUID().toString(),"device-ingestion");
        }catch(HttpClientErrorException.Unauthorized|HttpClientErrorException.NotFound|IllegalArgumentException|com.fasterxml.jackson.core.JsonProcessingException invalid){
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(message.getPayload()));
            return kafka.send("capyiot.device.rejected.v1",hash,json.writeValueAsString(Map.of("source","device-ingestion","receivedAt",received,"reason","INVALID_DEVICE_MESSAGE","payloadSha256",hash)));
        }
        String target=switch(event.eventType()){case "HEARTBEAT"->VideoDeviceMessage.HEARTBEAT_TOPIC;case "STATUS_OBSERVATION"->VideoDeviceMessage.STATUS_TOPIC;default->VideoDeviceMessage.EVENT_TOPIC;};
        return kafka.send(target,event.deviceId(),json.writeValueAsString(event));
    }
    @Override public void start(){running=true;connection.scheduleWithFixedDelay(this::maintain,0,1,TimeUnit.SECONDS);}
    private void maintain(){
        if(!running)return;
        try{
            if((reconnect.getAndSet(false)||Instant.now().isAfter(renewAt))&&mqtt.isConnected()){subscribed=false;mqtt.disconnectForcibly(1000,1000,false);}
            if(!mqtt.isConnected()){
                var identity=core.post().uri("/internal/ingestion/token").body(Map.of("clientId",clientId)).retrieve().body(JsonNode.class);
                if(identity==null||!identity.path("token").isTextual())throw new IllegalStateException("Ingestion credential unavailable");
                var options=new MqttConnectOptions();options.setCleanSession(false);options.setConnectionTimeout(10);options.setKeepAliveInterval(30);
                options.setSocketFactory(LocalTls.sockets(ca));options.setHttpsHostnameVerificationEnabled(true);options.setUserName("device-ingestion");options.setPassword(identity.path("token").asText().toCharArray());
                synchronized(ackLock){generation.incrementAndGet();}mqtt.connect(options).waitForCompletion(15000);
                mqtt.subscribe("capyiot/v1/devices/+/sessions/+/events",1).waitForCompletion(15000);renewAt=Instant.now().plusSeconds(720);subscribed=true;
            }
        }catch(Exception failure){subscribed=false;reconnect.set(true);LOG.log(System.Logger.Level.WARNING,"Secure MQTT connection will retry");}
    }
    @Override public void stop(){running=false;connection.shutdownNow();try{mqtt.disconnectForcibly(1000,1000,false);mqtt.close();}catch(MqttException ignored){}}
    @Override public boolean isRunning(){return running;}
    @Override public Health health(){return subscribed&&mqtt.isConnected()?Health.up().build():Health.down().build();}
}
