package io.capyiot.alarm.fault;

import com.fasterxml.jackson.databind.*;
import io.capyiot.contract.internal.DomainProducer;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "capyiot.fault-runtime.enabled", havingValue = "true")
class FaultRuntime {
  private final FaultService faults;
  private final FaultGate gate;
  private final FaultProgress progress;
  private final ObjectMapper json;
  private final boolean historical;

  FaultRuntime(
      FaultService faults,
      FaultGate gate,
      FaultProgress progress,
      ObjectMapper json,
      @Value("${capyiot.faults.allow-historical-core:true}") boolean historical) {
    this.faults = faults;
    this.gate = gate;
    this.progress = progress;
    this.json = json;
    this.historical = historical;
  }

  @KafkaListener(topics = "capyiot.domain.device.v1", groupId = "core-fault-observation-v1")
  void consume(ConsumerRecord<String, String> record) {
    handle(record, DomainProducer.DEVICE);
  }

  @KafkaListener(topics = "capyiot.domain.workorder.v1", groupId = "core-fault-workorder-v1")
  void workOrders(ConsumerRecord<String, String> record) {
    handle(record, DomainProducer.WORKORDER);
  }

  void handle(ConsumerRecord<String, String> record, DomainProducer producer) {
    RequestCalls.background(
        () -> {
          JsonNode event;
          try {
            event = json.readTree(record.value());
            validate(event, record, producer);
          } catch (Exception invalid) {
            progress.poison(record, "INVALID_DOMAIN_ENVELOPE");
            return;
          }
          try {
            if (producer == DomainProducer.DEVICE) faults.observation(event);
            else faults.workOrderEvent(event);
            progress.processed(record);
          } catch (ApiProblem invalid) {
            if (invalid.status() >= 500) {
              pause(record, "BUSINESS_STORE_UNAVAILABLE");
              throw invalid;
            }
            progress.poison(record, "INVALID_DOMAIN_FACT");
          } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            progress.poison(record, "INVALID_DOMAIN_FACT");
          } catch (RuntimeException unavailable) {
            pause(
                record,
                unavailable instanceof io.capyiot.service.ServiceFailure f
                    ? f.code()
                    : "PROCESSING_PAUSED");
            throw unavailable;
          }
          // Store/authority failures propagate: no source offset advance; successful local
          // processing leaves durable review work.
        });
  }

  private void pause(ConsumerRecord<String, String> record, String code) {
    try {
      progress.paused(record, code);
    } catch (org.springframework.dao.DataAccessException ignored) {
      /* The database is unavailable; source offset remains unacknowledged. */
    }
  }

  void validate(JsonNode e, ConsumerRecord<String, String> r, DomainProducer producer) {
    if (e == null
        || !e.isObject()
        || !producer.accepts(
            r.topic(), e.path("sourceContext").asText(), e.path("producer").asText(), historical)
        || e.path("schemaVersion").asInt() != 1
        || !e.path("schemaVersion").isIntegralNumber()
        || !e.path("eventId").asText().matches("[A-Za-z0-9_-]{1,64}")
        || !e.path("parkId").asText().matches("[A-Za-z0-9_-]{1,64}")
        || !e.path("aggregateId").asText().matches("[A-Za-z0-9_-]{1,64}")
        || !java.util.Objects.equals(r.key(), e.path("aggregateId").asText())
        || !e.path("body").isObject()) throw new IllegalArgumentException();
    Instant.parse(e.path("occurredAt").asText());
    String type = e.path("eventType").asText();
    if (type.isBlank()) throw new IllegalArgumentException();
    var body = e.path("body");
    if (producer == DomainProducer.DEVICE
        && java.util.Set.of(
                "DeviceConnectionLost", "DeviceConnectionRestored", "DeviceObservationAccepted")
            .contains(type)) {
      if (type.equals("DeviceObservationAccepted")) {
        for (String field : java.util.List.of("sessionEpoch", "sequence"))
          if (!body.path(field).isIntegralNumber()
              || !body.path(field).canConvertToLong()
              || body.path(field).asLong() < 0) throw new IllegalArgumentException();
        if (body.path("streamKey").asText().isBlank()
            || body.path("streamKey").asText().length() > 255
            || !body.path("payload").isObject()) throw new IllegalArgumentException();
      } else if (!body.path("stateVersion").isIntegralNumber()
          || !body.path("stateVersion").canConvertToLong()
          || body.path("stateVersion").asLong() < 0) throw new IllegalArgumentException();
    }
  }

  @Scheduled(fixedDelay = 10000)
  void recoverGates() {
    background(gate::recoverClosingGates);
  }

  @Scheduled(fixedDelay = 5000)
  void reservations() {
    background(gate::reconcilePending);
  }

  @Scheduled(fixedDelay = 5000)
  void reviews() {
    background(faults::processReviews);
  }

  private void background(Runnable action) {
    if (io.capyiot.contract.RuntimeGate.draining()) return;
    try {
      RequestCalls.background(action);
    } catch (RuntimeException failure) {
      System.getLogger(getClass().getName())
          .log(
              System.Logger.Level.WARNING,
              "Fault maintenance deferred: {0}",
              failure.getClass().getSimpleName());
    }
  }
}
