package io.capyiot.workflow.work;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.capyiot.contract.internal.*;
import io.capyiot.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;

class WorkClientsTest {
  @BeforeEach
  void request() {
    var req = new MockHttpServletRequest();
    req.addHeader("Authorization", "Bearer a.b.c");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
  }

  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
  }

  WorkOrderApi.CommandBinding binding() {
    return new WorkOrderApi.CommandBinding(
        "op-1", "incident-1", WorkOrderApi.Operation.CREATE, "user-1", "key-1", "a".repeat(64));
  }

  @Test
  void reservationRepliesMustMatchTheOriginalImmutableBinding() {
    var client = mock(InternalHttpClient.class);
    var faults = new RemoteWorkFaults(client);
    var original = binding();
    var wrong =
        new WorkOrderApi.CommandBinding(
            "op-other",
            "incident-1",
            original.operation(),
            original.actorUserId(),
            original.idempotencyKey(),
            original.requestHash());
    when(client.call(
            eq(InternalEndpoint.RESERVATION_CREATE),
            any(),
            any(),
            eq(FaultApi.Reservation.class),
            any(),
            anyString(),
            eq("incident-1")))
        .thenReturn(
            new FaultApi.Reservation(
                "r-1", FaultApi.ReservationState.RESERVED, wrong, null, Instant.now()));
    assertThrows(ServiceFailure.class, () -> faults.reserve(original));
  }

  @Test
  void resourceScopeNeverAcceptsPartialAuthorizedIds() {
    var client = mock(InternalHttpClient.class);
    var devices = new WorkDevices(client);
    var resource =
        new DeviceApi.Resource(
            "ipc-1",
            "PARK-A",
            "area-1",
            "IPC-1",
            "IPC",
            "摄像机",
            "IN_SERVICE",
            List.of("floor-1"),
            1);
    when(client.call(
            eq(InternalEndpoint.DEVICE_SCOPE),
            any(),
            any(),
            eq(DeviceApi.ScopeResult.class),
            any(),
            anyString()))
        .thenReturn(new DeviceApi.ScopeResult(List.of(resource), Instant.now()));
    assertThrows(
        ServiceFailure.class,
        () ->
            devices.requireVisibleDevices(
                WorkOperationsIntegrationTest.actor("user-1", "OPS_SUPERVISOR"),
                "workorder.create",
                List.of("ipc-1", "ipc-2")));
  }

  @Test
  void personnelEligibilityReceivesAuthoritativeAncestorAreas() {
    var client = mock(InternalHttpClient.class);
    var personnel = new WorkPersonnel(client);
    var resource =
        new DeviceApi.Resource(
            "ipc-1",
            "PARK-A",
            "area-1",
            "IPC-1",
            "IPC",
            "摄像机",
            "IN_SERVICE",
            List.of("floor-1", "building-1"),
            7);
    when(client.call(
            eq(InternalEndpoint.DEVICE_REFERENCES),
            isNull(),
            any(),
            eq(DeviceApi.ScopeResult.class),
            any(),
            anyString()))
        .thenReturn(new DeviceApi.ScopeResult(List.of(resource), Instant.now()));
    when(client.call(
            eq(InternalEndpoint.USER_ELIGIBILITY),
            any(),
            any(),
            eq(PlatformApi.EligibilityResult.class),
            any(),
            anyString()))
        .thenAnswer(
            i -> {
              PlatformApi.EligibilityRequest request = i.getArgument(2);
              assertEquals(resource, request.devices().getFirst());
              return new PlatformApi.EligibilityResult(
                  List.of(
                      new PlatformApi.Eligibility(
                          "repairer", "维修人", PlatformApi.PersonnelRole.REPAIR_TECH, true)),
                  Instant.now(),
                  null);
            });
    assertDoesNotThrow(
        () ->
            personnel.requireRoleForDevices(
                "repairer",
                "REPAIR_TECH",
                List.of(
                    new DeviceQueryPort.DeviceRef(
                        "ipc-1", "PARK-A", "area-1", "IPC-1", "IPC", "摄像机", "IN_SERVICE"))));
  }
}
