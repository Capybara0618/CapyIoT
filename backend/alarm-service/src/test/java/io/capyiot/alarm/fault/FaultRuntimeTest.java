package io.capyiot.alarm.fault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import io.capyiot.contract.internal.*;
import io.capyiot.service.ServiceFailure;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class FaultRuntimeTest {
  ObjectMapper json = new ObjectMapper();

  String fact() {
    return "{\"eventId\":\"fact-1\",\"schemaVersion\":1,\"producer\":\"device-service\",\"sourceContext\":\"device\",\"aggregateId\":\"ipc-1\",\"parkId\":\"PARK-A\",\"occurredAt\":\"2026-11-27T00:00:00Z\",\"eventType\":\"DeviceConnectionLost\",\"body\":{\"stateVersion\":1}}";
  }

  @Test
  void validDependencyFailureIsPausedAndRethrownInsteadOfDeadLettered() {
    var faults = mock(FaultService.class);
    var progress = mock(FaultProgress.class);
    var runtime = new FaultRuntime(faults, mock(FaultGate.class), progress, json, true);
    var record =
        new ConsumerRecord<String, String>("capyiot.domain.device.v1", 0, 1, "ipc-1", fact());
    doThrow(ServiceFailure.unavailable("DEPENDENCY_TIMEOUT", ServiceId.DEVICE, true))
        .when(faults)
        .observation(any());
    assertThrows(ServiceFailure.class, () -> runtime.consume(record));
    verify(progress).paused(record, "DEPENDENCY_TIMEOUT");
    verify(progress, never()).poison(any(), anyString());
    verify(progress, never()).processed(any());
  }

  @Test
  void unsupportedSchemaAndWrongProducerBecomeDurableBlockersBeforeRules() {
    var faults = mock(FaultService.class);
    var progress = mock(FaultProgress.class);
    var runtime = new FaultRuntime(faults, mock(FaultGate.class), progress, json, false);
    runtime.consume(
        new ConsumerRecord<>(
            "capyiot.domain.device.v1",
            0,
            1,
            "ipc-1",
            fact().replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
    runtime.consume(
        new ConsumerRecord<>(
            "capyiot.domain.device.v1",
            0,
            2,
            "ipc-1",
            fact().replace("device-service", "core-service")));
    verify(progress, times(2)).poison(any(), eq("INVALID_DOMAIN_ENVELOPE"));
    verifyNoInteractions(faults);
  }

  @Test
  void successfulReprocessingResolvesOnlyTheRetryMarker() {
    var faults = mock(FaultService.class);
    var progress = mock(FaultProgress.class);
    var runtime = new FaultRuntime(faults, mock(FaultGate.class), progress, json, true);
    var record =
        new ConsumerRecord<String, String>("capyiot.domain.device.v1", 0, 1, "ipc-1", fact());
    runtime.consume(record);
    verify(faults).observation(any());
    verify(progress).processed(record);
    verify(progress, never()).poison(any(), anyString());
  }
}
