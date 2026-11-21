package io.capyiot.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.DeviceEvent;
import io.capyiot.contract.DeviceMessage;
import io.capyiot.contract.Telemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component("mqttHealthIndicator")
class MqttIngestion implements SmartLifecycle, HealthIndicator {
    private static final Logger LOG = LoggerFactory.getLogger(MqttIngestion.class);
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final Validator validator;
    private final JwtDecoder sessions;
    private final MqttAsyncClient mqtt;
    private final ScheduledExecutorService connection = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore inflight = new Semaphore(128);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean reconnect = new AtomicBoolean();
    private final Object ackLock = new Object();
    private volatile boolean running;
    private volatile boolean subscribed;
    private final Counter acknowledged;

    MqttIngestion(KafkaTemplate<String, String> kafka, ObjectMapper json, Validator validator, MeterRegistry meters,
            @Value("${capyiot.mqtt-url}") String url,
            @Value("${capyiot.mqtt-client-id}") String clientId,
            @Value("${capyiot.device-jwt-secret}") String secret) throws MqttException {
        this.kafka = kafka; this.json = json; this.validator = validator;
        acknowledged = meters.counter("capyiot.mqtt.ack");
        meters.gauge("capyiot.mqtt.inflight", inflight, permits -> 128 - permits.availablePermits());
        byte[] bytes = Base64.getDecoder().decode(secret);
        if (bytes.length < 32) throw new IllegalArgumentException("Device signing key is too short");
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(bytes, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("capyiot-device-service"));
        sessions = decoder;
        mqtt = new MqttAsyncClient(url, clientId, new MemoryPersistence());
        mqtt.setManualAcks(true);
        mqtt.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) {
                synchronized (ackLock) { generation.incrementAndGet(); subscribed = false; }
                LOG.warn("MQTT disconnected, persistent session will reconnect");
            }
            @Override public void deliveryComplete(IMqttDeliveryToken token) {}
            @Override public void messageArrived(String topic, MqttMessage message) throws Exception {
                long receivedGeneration = generation.get();
                inflight.acquire();
                Span span = Telemetry.start("mqtt receive", SpanKind.CONSUMER)
                        .setAttribute("messaging.system", "mqtt");
                try (var scope = span.makeCurrent()) {
                    publish(topic, message).whenComplete((result, error) -> {
                        try {
                            synchronized (ackLock) {
                                if (error != null) {
                                    span.setStatus(StatusCode.ERROR);
                                    reconnect.set(true); generation.incrementAndGet();
                                    LOG.warn("Kafka confirmation failed, MQTT message remains unacknowledged");
                                } else if (receivedGeneration == generation.get() && !reconnect.get() && mqtt.isConnected() && message.getQos() > 0) {
                                    mqtt.messageArrivedComplete(message.getId(), message.getQos());
                                    acknowledged.increment();
                                }
                            }
                        } catch (MqttException failure) {
                            span.setStatus(StatusCode.ERROR);
                            reconnect.set(true);
                            generation.incrementAndGet();
                        } finally {
                            span.end();
                            inflight.release();
                        }
                    });
                } catch (Exception error) {
                    span.setStatus(StatusCode.ERROR);
                    span.end();
                    inflight.release(); reconnect.set(true); generation.incrementAndGet();
                    LOG.warn("Publish could not start, MQTT message remains unacknowledged");
                }
            }
        });
    }

    private CompletableFuture<?> publish(String topic, MqttMessage message) throws Exception {
        String target = DeviceEvent.TOPIC;
        String key;
        String body;
        try {
            if (message.getPayload().length > 16384 || message.getQos() != 1) throw new IllegalArgumentException();
            var tree = json.readTree(message.getPayload());
            if (!tree.path("seq").isIntegralNumber()) throw new IllegalArgumentException();
            DeviceMessage wire = json.treeToValue(tree, DeviceMessage.class);
            if (!validator.validate(wire).isEmpty() || !wire.payload().isObject()) throw new IllegalArgumentException();
            String health = wire.payload().path("healthStatus").asText("NORMAL");
            if (!List.of("NORMAL", "ABNORMAL").contains(health)) throw new IllegalArgumentException();
            if (wire.occurredAt().isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                    || wire.occurredAt().isAfter(Instant.now().plusSeconds(86400))) throw new IllegalArgumentException();
            String[] parts = topic.split("/", -1);
            if (parts.length != 5 || !parts[0].equals("capyiot") || !parts[2].equals("devices") || !parts[4].equals("events")
                    || !parts[1].equals(wire.campusId()) || !parts[3].equals(wire.deviceId())) throw new IllegalArgumentException();
            Jwt session = sessions.decode(wire.sessionToken());
            if (!session.getAudience().contains("capyiot-ingestion") || !session.getSubject().equals(wire.deviceId())
                    || !wire.campusId().equals(session.getClaimAsString("campusId"))
                    || !wire.sessionId().equals(session.getClaimAsString("sessionId"))) throw new IllegalArgumentException();
            Number epoch = session.getClaim("sessionEpoch");
            if (epoch == null || epoch.longValue() < 1 || epoch.longValue() > 9007199254740991L) throw new IllegalArgumentException();
            Span.current().setAttribute("capyiot.event_id", wire.eventId());
            var event = new DeviceEvent(wire.eventId(), wire.eventType(), 1, wire.campusId(), wire.deviceId(), wire.sessionId(),
                    epoch.longValue(), wire.seq(), wire.occurredAt(), Instant.now(),
                    Telemetry.traceId(UUID.randomUUID().toString()), "device-ingestion", wire.payload());
            key = event.deviceId();
            body = json.writeValueAsString(event);
        } catch (Exception invalid) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(message.getPayload()));
            var rejection = Map.of("source", "device-ingestion", "topic", topic, "receivedAt", Instant.now(),
                    "reason", "INVALID_DEVICE_MESSAGE", "payloadSha256", hash);
            target = DeviceEvent.REJECTED_TOPIC;
            key = hash;
            body = json.writeValueAsString(rejection);
        }
        return kafka.send(target, key, body);
    }

    @Override public void start() {
        running = true;
        connection.scheduleWithFixedDelay(this::maintainConnection, 0, 1, TimeUnit.SECONDS);
    }

    private void maintainConnection() {
        if (!running) return;
        try {
            if (reconnect.getAndSet(false) && mqtt.isConnected()) {
                subscribed = false;
                mqtt.disconnectForcibly(1000, 1000, false);
            }
            if (!mqtt.isConnected()) {
                var options = new MqttConnectOptions();
                options.setCleanSession(false);
                options.setAutomaticReconnect(false);
                options.setConnectionTimeout(10);
                options.setKeepAliveInterval(30);
                synchronized (ackLock) { generation.incrementAndGet(); }
                mqtt.connect(options).waitForCompletion(15000);
                mqtt.subscribe("capyiot/+/devices/+/events", 1).waitForCompletion(15000);
                subscribed = true;
                LOG.info("MQTT persistent subscription ready");
            }
        } catch (MqttException error) { subscribed = false; reconnect.set(true); LOG.warn("MQTT connect/subscribe will retry reason={}", error.getReasonCode()); }
    }

    @Override public void stop() {
        running = false;
        connection.shutdownNow();
        try { mqtt.disconnectForcibly(1000, 1000, false); mqtt.close(); }
        catch (MqttException error) { LOG.debug("MQTT shutdown completed without active connection"); }
    }
    @Override public boolean isRunning() { return running; }
    @Override public Health health() { return subscribed && mqtt.isConnected() ? Health.up().build() : Health.down().build(); }
}
