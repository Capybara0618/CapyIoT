package io.capyiot.alarm.fault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.capyiot.contract.internal.*;
import io.capyiot.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RemoteFaultOrdersTest {
  @Test
  void multipleAuthorityPagesAreCombinedWithoutTruncatingTheCloseDecision() {
    var client = mock(InternalHttpClient.class);
    var remote = new RemoteFaultOrders(client);
    var first =
        java.util.stream.IntStream.range(1, 101)
            .mapToObj(
                i ->
                    new WorkOrderApi.OrderRef(
                        Integer.toString(i), "WO-" + i, WorkOrderApi.OrderStatus.CLOSED, 1))
            .toList();
    when(client.call(
            eq(InternalEndpoint.INCIDENT_ORDER_STATE),
            isNull(),
            isNull(),
            eq(WorkOrderApi.IncidentOrderState.class),
            any(),
            anyString(),
            eq("incident-1")))
        .thenReturn(
            new WorkOrderApi.IncidentOrderState(
                "incident-1", first, List.of(), true, Instant.now(), "page-2"));
    when(client.call(
            eq(InternalEndpoint.INCIDENT_ORDER_PAGE),
            isNull(),
            any(),
            eq(WorkOrderApi.IncidentOrderState.class),
            any(),
            anyString(),
            eq("incident-1")))
        .thenReturn(
            new WorkOrderApi.IncidentOrderState(
                "incident-1",
                List.of(
                    new WorkOrderApi.OrderRef("101", "WO-101", WorkOrderApi.OrderStatus.OPEN, 1)),
                List.of(),
                true,
                Instant.now(),
                null));
    RequestCalls.background(
        () -> {
          var state = remote.state("incident-1");
          assertEquals(101, state.orders().size());
          assertFalse(state.allTerminal());
        });
  }

  @Test
  void cursorCyclesAndPartialAuthorityCannotBeUsedToClose() {
    var client = mock(InternalHttpClient.class);
    var remote = new RemoteFaultOrders(client);
    var repeating =
        new WorkOrderApi.IncidentOrderState(
            "incident-1", List.of(), List.of(), true, Instant.now(), "again");
    when(client.call(
            eq(InternalEndpoint.INCIDENT_ORDER_STATE),
            isNull(),
            isNull(),
            eq(WorkOrderApi.IncidentOrderState.class),
            any(),
            anyString(),
            eq("incident-1")))
        .thenReturn(repeating);
    when(client.call(
            eq(InternalEndpoint.INCIDENT_ORDER_PAGE),
            isNull(),
            any(),
            eq(WorkOrderApi.IncidentOrderState.class),
            any(),
            anyString(),
            eq("incident-1")))
        .thenReturn(repeating);
    RequestCalls.background(
        () -> assertThrows(ServiceFailure.class, () -> remote.state("incident-1")));
    when(client.call(
            eq(InternalEndpoint.INCIDENT_ORDER_STATE),
            isNull(),
            isNull(),
            eq(WorkOrderApi.IncidentOrderState.class),
            any(),
            anyString(),
            eq("incident-1")))
        .thenReturn(
            new WorkOrderApi.IncidentOrderState(
                "incident-1", List.of(), List.of(), false, Instant.now(), null));
    RequestCalls.background(
        () -> assertThrows(ServiceFailure.class, () -> remote.state("incident-1")));
  }

  @Test
  void mismatchedOperationIdentityCannotProveACommitOrAbort() {
    var client = mock(InternalHttpClient.class);
    var remote = new RemoteFaultOrders(client);
    when(client.call(
            eq(InternalEndpoint.OPERATION_QUERY),
            isNull(),
            isNull(),
            eq(WorkOrderApi.OperationResult.class),
            any(),
            anyString(),
            eq("op-1")))
        .thenReturn(
            new WorkOrderApi.OperationResult(
                "other-op",
                WorkOrderApi.OperationState.UNKNOWN,
                null,
                null,
                null,
                null,
                Instant.now()));
    RequestCalls.background(
        () -> assertThrows(ServiceFailure.class, () -> remote.operation("op-1")));
  }
}
