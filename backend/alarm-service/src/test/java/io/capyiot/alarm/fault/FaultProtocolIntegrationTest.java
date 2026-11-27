package io.capyiot.alarm.fault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import io.capyiot.contract.internal.*;
import io.capyiot.service.ServiceFailure;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    classes = io.capyiot.alarm.AlarmServiceApplication.class,
    properties = {
      "server.ssl.enabled=false",
      "capyiot.faults.clients.enabled=false",
      "capyiot.fault-runtime.enabled=false",
      "capyiot.domain-runtime.enabled=false"
    })
@Import(FaultProtocolIntegrationTest.Dependencies.class)
@AutoConfigureMockMvc
@Testcontainers
class FaultProtocolIntegrationTest {
  @Container
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4.11")
          .withDatabaseName("fault_test")
          .withUsername("fault_user")
          .withPassword("test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", MYSQL::getJdbcUrl);
    r.add("spring.datasource.username", MYSQL::getUsername);
    r.add("spring.datasource.password", MYSQL::getPassword);
    r.add(
        "capyiot.faults.command-fingerprint-key",
        () -> "stable-fault-test-fingerprint-key-over-32-chars");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Dependencies {
    @Bean
    FaultDevices devices() {
      return mock(FaultDevices.class);
    }

    @Bean
    TestOrders orders() {
      return new TestOrders();
    }

    @Bean
    FaultIdentity identities() {
      return jwt -> actor();
    }

    @Bean
    JwtDecoder userTokens() {
      return token -> {
        throw new org.springframework.security.oauth2.jwt.BadJwtException(
            "Explicit test credential required");
      };
    }
  }

  static AuthorizationContext actor() {
    return new AuthorizationContext(
        "supervisor",
        "supervisor",
        "主管",
        1,
        "session",
        List.of(
            new AuthorizationContext.Grant(
                "g", "OPS_SUPERVISOR", "PARK", "PARK-A", List.of(), null)));
  }

  static class TestOrders implements FaultOrders, WorkOrderQueryPort {
    final Map<String, WorkOrderApi.OperationResult> legacy = new ConcurrentHashMap<>();
    final Map<String, WorkOrderApi.OperationResult> operations = new ConcurrentHashMap<>();
    final Map<String, FaultOrders.State> states = new ConcurrentHashMap<>();
    volatile boolean unavailable;
    volatile java.util.function.Function<String, FaultOrders.State> hook;

    public WorkOrderApi.OperationResult legacy(String reservation) {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      var value = legacy.get(reservation);
      if (value == null) throw ServiceFailure.denied(404, "RESOURCE_NOT_FOUND");
      return value;
    }

    public WorkOrderApi.OperationResult operation(String id) {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      if (unavailable)
        throw ServiceFailure.unavailable(
            "WORKORDER_AUTHORITY_UNAVAILABLE", ServiceId.WORKFLOW, true);
      return operations.getOrDefault(
          id,
          new WorkOrderApi.OperationResult(
              id, WorkOrderApi.OperationState.UNKNOWN, null, null, null, null, Instant.now()));
    }

    public FaultOrders.State state(String id) {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      if (unavailable)
        throw ServiceFailure.unavailable(
            "WORKORDER_AUTHORITY_UNAVAILABLE", ServiceId.WORKFLOW, true);
      if (hook != null) return hook.apply(id);
      return states.getOrDefault(
          id, new FaultOrders.State(id, List.of(), List.of(), true, Instant.now()));
    }

    public List<OrderRef> forIncident(String id) {
      return state(id).orders().stream()
          .map(
              o ->
                  new OrderRef(
                      Long.parseLong(o.workOrderId()),
                      o.workOrderNo(),
                      id,
                      o.status().name(),
                      o.version()))
          .toList();
    }

    public Optional<OrderRef> forReservation(String reservation) {
      return Optional.empty();
    }
  }

  @Autowired FaultStore store;
  @Autowired FaultService faults;
  @Autowired FaultGate gate;
  @Autowired FaultDevices devices;
  @Autowired TestOrders orders;
  @Autowired FaultProgress progress;
  @Autowired ObjectMapper json;
  @Autowired MockMvc http;
  final AtomicReference<List<DeviceApi.ObservationPosition>> required =
      new AtomicReference<>(List.of());

  @BeforeEach
  void reset() {
    for (String table :
        List.of(
            "alarm_evidence",
            "alarm",
            "fault_consumer_blocker",
            "fault_pending_review",
            "fault_health_version",
            "fault_applied_position",
            "incident_work_order_projection",
            "incident_work_order_reservation",
            "incident_work_order_gate",
            "correlation_candidate",
            "incident_anomaly_link",
            "anomaly_evidence",
            "legacy_alarm_mapping",
            "incident",
            "anomaly",
            "fault_observation_cursor",
            "fault_device_observation",
            "inbox_event",
            "command_request",
            "outbox_event",
            "command_lock")) store.db.update("DELETE FROM " + table);
    orders.operations.clear();
    orders.legacy.clear();
    orders.states.clear();
    orders.unavailable = false;
    orders.hook = null;
    required.set(List.of());
    org.mockito.Mockito.reset(devices);
    when(devices.device(anyString()))
        .thenAnswer(
            inv -> {
              outside();
              String id = inv.getArgument(0);
              return new DeviceQueryPort.DeviceRef(
                  id,
                  id.startsWith("foreign") ? "PARK-B" : "PARK-A",
                  "AREA-A",
                  id,
                  id.startsWith("sw") ? "SWITCH" : id.startsWith("nvr") ? "NVR" : "IPC",
                  id,
                  "IN_SERVICE");
            });
    when(devices.requireVisibleDevices(any(), anyString(), anyCollection()))
        .thenAnswer(
            inv -> {
              outside();
              AuthorizationContext actor = inv.getArgument(0);
              String permission = inv.getArgument(1);
              actor.require(permission);
              Collection<String> ids = inv.getArgument(2);
              if (ids.stream().anyMatch(id -> id.startsWith("foreign"))) throw ApiProblem.missing();
              return ids.stream().map(devices::device).toList();
            });
    when(devices.visibleDeviceIds(any(), anyString())).thenReturn(Set.of("ipc-1", "ipc-2", "sw-1"));
    when(devices.networkRelations(anyString(), any()))
        .thenAnswer(
            inv -> {
              outside();
              return List.of();
            });
    when(devices.runtime(anyList()))
        .thenAnswer(
            inv -> {
              outside();
              List<String> ids = inv.getArgument(0);
              return new DeviceApi.RuntimeResult(
                  ids.stream()
                      .map(
                          id ->
                              new DeviceApi.RuntimeState(
                                  id,
                                  "ONLINE",
                                  "NORMAL",
                                  "FRESH",
                                  1,
                                  Instant.now(),
                                  Instant.now(),
                                  required.get()))
                      .toList(),
                  Instant.now(),
                  true);
            });
  }

  void outside() {
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  JsonNode connection(String device, String type, long version) {
    var e =
        json.createObjectNode()
            .put("eventId", key())
            .put("sourceContext", "device")
            .put("producer", "device-service")
            .put("schemaVersion", 1)
            .put("eventType", type)
            .put("aggregateId", device)
            .put("parkId", device.startsWith("foreign") ? "PARK-B" : "PARK-A")
            .put("occurredAt", Instant.now().toString());
    e.putObject("body").put("stateVersion", version).put("receivedAt", Instant.now().toString());
    return e;
  }

  String open() {
    faults.observation(connection("ipc-1", "DeviceConnectionLost", 1));
    String anomaly =
        store.db.queryForObject("SELECT anomaly_id FROM anomaly LIMIT 1", String.class);
    String id =
        faults
            .create(
                actor(),
                key(),
                new FaultModels.Create("视频设备失联", List.of(anomaly), FaultModels.Priority.P2, "证据核查"))
            .path("incidentId")
            .asText();
    faults.review(
        actor(), key(), id, new FaultModels.Review(FaultModels.Decision.CONFIRM, "确认故障", 0L));
    return id;
  }

  long version(String id) {
    return ((Number)
            store
                .db
                .queryForMap("SELECT version FROM incident WHERE incident_id=?", id)
                .get("version"))
        .longValue();
  }

  void recovered(String id) {
    faults.observation(connection("ipc-1", "DeviceConnectionRestored", 2));
    faults.processReviews();
  }

  WorkOrderApi.CommandBinding binding(String id) {
    var b =
        new WorkOrderApi.CommandBinding(
            key(), id, WorkOrderApi.Operation.CREATE, actor().userId(), key(), "a".repeat(64));
    state(b, WorkOrderApi.OperationState.PREPARED, null, null, null);
    return b;
  }

  void state(
      WorkOrderApi.CommandBinding b,
      WorkOrderApi.OperationState state,
      String reservation,
      String order,
      Long version) {
    orders.operations.put(
        b.operationId(),
        new WorkOrderApi.OperationResult(
            b.operationId(), state, b, reservation, order, version, Instant.now()));
  }

  @Test
  void reserveResponseLossReplaysOneImmutableReservation() {
    String id = open();
    var b = binding(id);
    var first = gate.reserve(actor(), id, b);
    var replay = gate.reserve(actor(), id, b);
    assertEquals(first.reservationId(), replay.reservationId());
    assertEquals(first.reservationId(), gate.query(b.operationId()).reservationId());
    assertEquals(
        1,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM incident_work_order_reservation", Integer.class));
    var different =
        new WorkOrderApi.CommandBinding(
            key(), id, b.operation(), b.actorUserId(), b.idempotencyKey(), "b".repeat(64));
    state(different, WorkOrderApi.OperationState.PREPARED, null, null, null);
    assertThrows(ApiProblem.class, () -> gate.reserve(actor(), id, different));
  }

  @Test
  void unknown404AndAgeNeverProveAbort() {
    String id = open();
    var b = binding(id);
    var r = gate.reserve(actor(), id, b);
    orders.operations.clear();
    orders.legacy.clear();
    store.db.update(
        "UPDATE incident_work_order_reservation SET"
            + " created_at=TIMESTAMPADD(DAY,-10,CURRENT_TIMESTAMP(3))");
    gate.reconcile(id);
    assertEquals(
        FaultApi.ReservationState.RECONCILIATION_REQUIRED, gate.query(b.operationId()).state());
    assertThrows(
        ServiceFailure.class,
        () -> gate.abort(r.reservationId(), new FaultApi.AbortAcknowledgement(b, "取消")));
    assertNotEquals(
        "RELEASED",
        store.db.queryForObject(
            "SELECT status FROM incident_work_order_reservation", String.class));
  }

  @Test
  void commitNotificationLossReconcilesAgainstTheOperationAuthority() {
    String id = open();
    var b = binding(id);
    var r = gate.reserve(actor(), id, b);
    state(b, WorkOrderApi.OperationState.COMMITTED, r.reservationId(), "42", 3L);
    gate.reconcile(id);
    assertEquals(FaultApi.ReservationState.COMMITTED, gate.query(b.operationId()).state());
    assertEquals(
        "42", gate.commit(r.reservationId(), new FaultApi.Commit(b, "42", 3)).workOrderId());
    assertThrows(
        ServiceFailure.class,
        () -> gate.commit(r.reservationId(), new FaultApi.Commit(b, "43", 3)));
  }

  @Test
  void abortRequiresMatchingAuthorityAndReleasedReservationsRejectLateCommit() {
    String id = open();
    var b = binding(id);
    var r = gate.reserve(actor(), id, b);
    state(b, WorkOrderApi.OperationState.ABORTED, r.reservationId(), null, null);
    assertEquals(
        FaultApi.ReservationState.RELEASED,
        gate.abort(r.reservationId(), new FaultApi.AbortAcknowledgement(b, "已终止")).state());
    state(b, WorkOrderApi.OperationState.COMMITTED, r.reservationId(), "42", 1L);
    assertThrows(
        ApiProblem.class, () -> gate.commit(r.reservationId(), new FaultApi.Commit(b, "42", 1)));
  }

  @Test
  void activeAuthorityOrderAndUnknownOperationBlockFinalClose() {
    String id = open();
    recovered(id);
    orders.states.put(
        id,
        new FaultOrders.State(
            id,
            List.of(
                new WorkOrderApi.OrderRef("42", "WO-42", WorkOrderApi.OrderStatus.IN_PROGRESS, 3)),
            List.of(),
            true,
            Instant.now()));
    assertThrows(
        ApiProblem.class,
        () ->
            faults.close(actor(), key(), id, new FaultModels.Close("REPAIRED", "核查", version(id))));
    orders.states.put(
        id,
        new FaultOrders.State(id, List.of(), List.of("pending-operation"), true, Instant.now()));
    assertThrows(
        ApiProblem.class,
        () ->
            faults.close(actor(), key(), id, new FaultModels.Close("REPAIRED", "核查", version(id))));
    assertEquals(
        "OPEN",
        store.db.queryForObject("SELECT gate_status FROM incident_work_order_gate", String.class));
  }

  @Test
  void healthyCloseCommitsJournalAndOutboxAndReplaysWithoutAnotherClose() throws Exception {
    String id = open();
    recovered(id);
    long expected = version(id);
    var c = new FaultModels.Close("NO_REPAIR_REQUIRED", "恢复事实已核查", expected);
    var result = faults.close(actor(), "close-same-key", id, c);
    assertEquals("CLOSED", result.path("status").asText());
    assertEquals(
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.toString()),
        faults.close(actor(), "close-same-key", id, c));
    assertEquals(
        1,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE event_type='IncidentClosed'", Integer.class));
  }

  @Test
  void missingOrPendingDeviceOutboxFactsBlockClose() {
    String id = open();
    recovered(id);
    required.set(
        List.of(
            new DeviceApi.ObservationPosition(
                "not-applied", "STATUS/DEVICE/device/VIDEO_SIGNAL", 2, 1)));
    var failure =
        assertThrows(
            ApiProblem.class,
            () ->
                faults.close(
                    actor(), key(), id, new FaultModels.Close("REPAIRED", "核查", version(id))));
    assertEquals("FAULT_EVIDENCE_INCOMPLETE", failure.code());
    assertNotEquals("CLOSED", gate.incident(id).get("status"));
  }

  @Test
  void oldClosingThreadCannotCrossARecoveredAndRefrozenGate() throws Exception {
    String id = open();
    recovered(id);
    long expected = version(id);
    var entered = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    orders.hook =
        target -> {
          entered.countDown();
          try {
            assertTrue(resume.await(10, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
          return new FaultOrders.State(target, List.of(), List.of(), true, Instant.now());
        };
    try (var executor = Executors.newSingleThreadExecutor()) {
      var close =
          executor.submit(
              () ->
                  assertThrows(
                      ApiProblem.class,
                      () ->
                          faults.close(
                              actor(),
                              key(),
                              id,
                              new FaultModels.Close("REPAIRED", "核查", expected))));
      try {
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        store.db.update(
            "UPDATE incident_work_order_gate SET"
                + " closing_at=TIMESTAMPADD(MINUTE,-2,CURRENT_TIMESTAMP(3))");
        gate.recoverClosingGates();
        var replacement = gate.freeze(id, expected);
        resume.countDown();
        close.get(10, TimeUnit.SECONDS);
        assertEquals(
            replacement.attempt(),
            store.db.queryForObject(
                "SELECT close_attempt_id FROM incident_work_order_gate", String.class));
        assertEquals(
            "CLOSING",
            store.db.queryForObject(
                "SELECT gate_status FROM incident_work_order_gate", String.class));
      } finally {
        resume.countDown();
      }
    }
  }

  @Test
  void reserveCannotCrossClosingGateAndRecoveryCannotReleasePendingReservations() {
    String id = open();
    var b = binding(id);
    var fence = gate.freeze(id, version(id));
    assertThrows(ApiProblem.class, () -> gate.reserve(actor(), id, b));
    gate.restore(fence);
    var r = gate.reserve(actor(), id, b);
    store.db.update(
        "UPDATE incident_work_order_reservation SET operation_id=NULL WHERE reservation_id=?",
        r.reservationId());
    gate.reconcile(id);
    assertEquals(
        "RECONCILIATION_REQUIRED",
        store.db.queryForObject(
            "SELECT status FROM incident_work_order_reservation", String.class));
  }

  @Test
  void ignoredQualityStillAdvancesProcessingProgressAndDuplicateDoesNotReapply() {
    var e =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            connection("ipc-1", "DeviceObservationAccepted", 0);
    var b = (com.fasterxml.jackson.databind.node.ObjectNode) e.path("body");
    b.put("eventType", "STATUS_OBSERVATION")
        .put("streamKey", "STATUS/DEVICE/device/VIDEO_SIGNAL")
        .put("sessionEpoch", 2)
        .put("sequence", 1);
    b.putObject("payload")
        .put("resourceType", "DEVICE")
        .put("resourceKey", "device")
        .put("quality", "BAD")
        .put("observationType", "VIDEO_SIGNAL")
        .put("value", "LOST");
    faults.observation(e);
    faults.observation(e);
    assertEquals(
        1, store.db.queryForObject("SELECT COUNT(*) FROM fault_applied_position", Integer.class));
    assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM anomaly", Integer.class));
    assertEquals(1, store.db.queryForObject("SELECT COUNT(*) FROM inbox_event", Integer.class));
    assertEquals(
        0, store.db.queryForObject("SELECT COUNT(*) FROM fault_pending_review", Integer.class));
  }

  @Test
  void poisonIsDurableIdempotentAndNeverMasqueradesAsHealthyOrComplete() {
    var record =
        new ConsumerRecord<String, String>(
            "capyiot.domain.device.v1", 0, 11, "device", "sensitive raw message");
    progress.poison(record, "INVALID_DOMAIN_ENVELOPE");
    progress.poison(record, "INVALID_DOMAIN_ENVELOPE");
    assertFalse(progress.healthComplete());
    assertEquals(
        1, store.db.queryForObject("SELECT COUNT(*) FROM fault_consumer_blocker", Integer.class));
    assertEquals(1, store.db.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class));
    assertFalse(
        store
            .db
            .queryForList("SELECT payload_json FROM outbox_event")
            .toString()
            .contains("sensitive raw message"));
    assertThrows(ApiProblem.class, () -> progress.ready("PARK-A", List.of()));
    progress.processed(record);
    assertFalse(progress.healthComplete());
  }

  @Test
  void validDependencyPauseRecoversOnlyAfterSuccessfulReprocessing() {
    var record =
        new ConsumerRecord<String, String>("capyiot.domain.device.v1", 1, 12, "ipc-1", "{}");
    progress.paused(record, "DEPENDENCY_TIMEOUT");
    assertFalse(progress.healthComplete());
    progress.processed(record);
    assertTrue(progress.healthComplete());
    assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class));
  }

  @Test
  void correlationUsesHistoricalTopologyAndCandidateRemainsUnconfirmed() {
    Instant observed = Instant.parse("2026-11-20T00:00:00Z");
    when(devices.networkRelations(anyString(), any()))
        .thenAnswer(
            inv -> {
              assertEquals(observed, inv.getArgument(1));
              return List.of(
                  Map.of("upstreamSwitchId", "sw-1", "downstreamDeviceId", "ipc-1"),
                  Map.of("upstreamSwitchId", "sw-1", "downstreamDeviceId", "ipc-2"));
            });
    for (String id : List.of("sw-1", "ipc-1", "ipc-2")) {
      var event =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              connection(id, "DeviceConnectionLost", 1);
      ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("body"))
          .put("receivedAt", observed.toString());
      faults.observation(event);
    }
    faults.correlate("PARK-A");
    assertEquals(
        "UNCONFIRMED",
        store.db.queryForObject("SELECT root_cause_status FROM incident", String.class));
    assertEquals(
        "PENDING_REVIEW", store.db.queryForObject("SELECT status FROM incident", String.class));
    assertEquals(
        1, store.db.queryForObject("SELECT COUNT(*) FROM correlation_candidate", Integer.class));
    verify(devices).networkRelations("PARK-A", observed);
  }

  @Test
  void healthIsPureLocalAndDependencyOutageLeavesDetailExplicitlyPartial() {
    String id = open();
    clearInvocations(devices);
    assertEquals("FAULT", faults.deviceHealth(Set.of("ipc-1")).get("ipc-1"));
    verifyNoInteractions(devices);
    orders.unavailable = true;
    var detail = faults.detail(actor(), id);
    assertNull(detail.get("workOrders"));
    assertEquals("UNAVAILABLE", detail.get("workOrdersAvailability"));
  }

  @Test
  void crossParkEvidenceAndActorBindingFailClosed() {
    String id = open();
    var b = binding(id);
    var foreign =
        new WorkOrderApi.CommandBinding(
            b.operationId(),
            id,
            b.operation(),
            "different-user",
            b.idempotencyKey(),
            b.requestHash());
    assertThrows(ApiProblem.class, () -> gate.reserve(actor(), id, foreign));
    faults.observation(connection("foreign-ipc", "DeviceConnectionLost", 1));
    var anomalies =
        store.db.queryForList("SELECT anomaly_id FROM anomaly ORDER BY anomaly_id", String.class);
    assertThrows(
        ApiProblem.class,
        () ->
            faults.create(
                actor(),
                key(),
                new FaultModels.Create("跨园区", anomalies, FaultModels.Priority.P2, "禁止")));
  }

  @Test
  void internalRoutesNeedServiceIdentityAndRetiredBridgeIsAlwaysRejected() throws Exception {
    http.perform(
            post("/internal/v1/health/batch")
                .contentType("application/json")
                .content("{\"deviceIds\":[\"ipc-1\"]}"))
        .andExpect(status().isUnauthorized());
    http.perform(
            post("/internal/fault-bridge/uncertain")
                .header("X-Core-Service-Key", "test-key")
                .contentType("application/json")
                .content("{\"reservationId\":\"r\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void ownedDatabaseCredentialCannotReadOtherSchemas() {
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> store.db.queryForList("SELECT * FROM mysql.user"));
  }

  @Test
  void concurrentSameKeyReserveHasOneResult() throws Exception {
    String id = open();
    var b = binding(id);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(
              () -> {
                start.await();
                return gate.reserve(actor(), id, b).reservationId();
              });
      var c =
          executor.submit(
              () -> {
                start.await();
                return gate.reserve(actor(), id, b).reservationId();
              });
      start.countDown();
      assertEquals(a.get(10, TimeUnit.SECONDS), c.get(10, TimeUnit.SECONDS));
      assertEquals(
          1,
          store.db.queryForObject(
              "SELECT COUNT(*) FROM incident_work_order_reservation", Integer.class));
    }
  }

  @Test
  void duplicatesAndStaleRecoveryDoNotReopenAnOlderInterval() {
    var lost = connection("ipc-1", "DeviceConnectionLost", 1);
    faults.observation(lost);
    faults.observation(lost);
    faults.observation(connection("ipc-1", "DeviceConnectionRestored", 0));
    assertEquals("ACTIVE", store.db.queryForObject("SELECT status FROM anomaly", String.class));
    faults.observation(connection("ipc-1", "DeviceConnectionRestored", 2));
    faults.observation(connection("ipc-1", "DeviceConnectionLost", 1));
    assertEquals("RECOVERED", store.db.queryForObject("SELECT status FROM anomaly", String.class));
    faults.observation(connection("ipc-1", "DeviceConnectionLost", 3));
    assertEquals(2, store.db.queryForObject("SELECT COUNT(*) FROM anomaly", Integer.class));
    assertEquals(
        1,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM anomaly WHERE previous_anomaly_id IS NOT NULL", Integer.class));
  }

  @Test
  void statusRulesRetainDistinctVideoRecordingLinkAndPoeFacts() {
    for (String type : List.of("VIDEO_SIGNAL", "RECORDING_STATUS", "LINK_STATUS", "POE_STATUS")) {
      String id =
          type.equals("VIDEO_SIGNAL")
              ? "ipc-1"
              : type.equals("RECORDING_STATUS") ? "nvr-1" : "sw-1";
      String resource =
          type.equals("VIDEO_SIGNAL")
              ? "DEVICE"
              : type.equals("RECORDING_STATUS") ? "VIDEO_CHANNEL" : "SWITCH_PORT";
      String resourceKey =
          resource.equals("DEVICE")
              ? "device"
              : resource.equals("VIDEO_CHANNEL") ? "channel:1" : "port:1";
      var e =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              connection(id, "DeviceObservationAccepted", 0);
      var body = (com.fasterxml.jackson.databind.node.ObjectNode) e.path("body");
      body.put("streamKey", "STATUS/" + resource + "/" + resourceKey + "/" + type)
          .put("sessionEpoch", 1)
          .put("sequence", 1)
          .put("eventType", "STATUS_OBSERVATION");
      body.putObject("payload")
          .put("resourceType", resource)
          .put("resourceKey", resourceKey)
          .put("quality", "GOOD")
          .put("observationType", type)
          .put(
              "value",
              switch (type) {
                case "VIDEO_SIGNAL" -> "LOST";
                case "RECORDING_STATUS" -> "NOT_RECORDING";
                case "LINK_STATUS" -> "DOWN";
                default -> "FAULT";
              });
      faults.observation(e);
    }
    assertEquals(
        Set.of(
            "CAMERA_NO_VIDEO",
            "NVR_RECORDING_STOPPED",
            "SWITCH_PORT_LINK_DOWN",
            "SWITCH_POE_FAULT"),
        Set.copyOf(store.db.queryForList("SELECT anomaly_type FROM anomaly", String.class)));
  }

  @Test
  void wrongResourceTypeCannotAdvanceProgressOrTriggerAnNvrRuleOnAnIpc() {
    var e =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            connection("ipc-1", "DeviceObservationAccepted", 0);
    var body = (com.fasterxml.jackson.databind.node.ObjectNode) e.path("body");
    body.put("streamKey", "STATUS/VIDEO_CHANNEL/channel:1/RECORDING_STATUS")
        .put("sessionEpoch", 1)
        .put("sequence", 1)
        .put("eventType", "STATUS_OBSERVATION");
    body.putObject("payload")
        .put("resourceType", "VIDEO_CHANNEL")
        .put("resourceKey", "channel:1")
        .put("quality", "GOOD")
        .put("observationType", "RECORDING_STATUS")
        .put("value", "NOT_RECORDING");
    assertThrows(IllegalArgumentException.class, () -> faults.observation(e));
    assertEquals(
        0, store.db.queryForObject("SELECT COUNT(*) FROM fault_applied_position", Integer.class));
    assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM anomaly", Integer.class));
  }

  @Test
  void historicalAlarmShapesDoNotExposeUnscopedParentIdentifiers() {
    store.db.update(
        "INSERT INTO"
            + " alarm(campus_id,device_id,device_name,building_id,alarm_type,severity,status,relation_kind,upstream_node_id,first_seen_at,incident_at,last_seen_at)"
            + " VALUES('PARK-A','ipc-1','摄像机','hidden-building','DEVICE_OFFLINE','HIGH','ACTIVE','DIRECT','hidden-upstream',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))");
    var historical = new HistoricalAlarmController(store, devices, jwt -> actor());
    var req = new org.springframework.mock.web.MockHttpServletRequest();
    var token =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture")
            .header("alg", "RS256")
            .subject("supervisor")
            .build();
    String result = json.valueToTree(historical.list(token, 1, 20, req).data()).toString();
    assertFalse(result.contains("hidden-building"));
    assertFalse(result.contains("hidden-upstream"));
  }

  @Test
  void legacyReservationIsBoundOnlyToAnExactlyMatchingCommittedReceipt() {
    String id = open();
    var b = binding(id);
    var r = gate.reserve(actor(), id, b);
    store.db.update(
        "UPDATE incident_work_order_reservation SET operation_id=NULL WHERE reservation_id=?",
        r.reservationId());
    var old =
        new WorkOrderApi.CommandBinding(
            "legacy-proof",
            id,
            b.operation(),
            b.actorUserId(),
            b.idempotencyKey(),
            b.requestHash());
    var result =
        new WorkOrderApi.OperationResult(
            old.operationId(),
            WorkOrderApi.OperationState.COMMITTED,
            old,
            r.reservationId(),
            "42",
            0L,
            Instant.now());
    orders.legacy.put(r.reservationId(), result);
    orders.operations.put(old.operationId(), result);
    gate.reconcile(id);
    assertEquals(FaultApi.ReservationState.COMMITTED, gate.query(old.operationId()).state());
  }

  @Test
  void mismatchedLegacyProofLeavesTheOriginalReservationUnresolved() {
    String id = open();
    var b = binding(id);
    var r = gate.reserve(actor(), id, b);
    store.db.update(
        "UPDATE incident_work_order_reservation SET operation_id=NULL WHERE reservation_id=?",
        r.reservationId());
    var wrong =
        new WorkOrderApi.CommandBinding(
            "legacy-wrong",
            id,
            b.operation(),
            "different-user",
            b.idempotencyKey(),
            b.requestHash());
    orders.legacy.put(
        r.reservationId(),
        new WorkOrderApi.OperationResult(
            wrong.operationId(),
            WorkOrderApi.OperationState.COMMITTED,
            wrong,
            r.reservationId(),
            "42",
            0L,
            Instant.now()));
    gate.reconcile(id);
    var row = store.db.queryForMap("SELECT * FROM incident_work_order_reservation");
    assertNull(row.get("operation_id"));
    assertEquals("RECONCILIATION_REQUIRED", row.get("status"));
  }
}
