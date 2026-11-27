package io.capyiot.workflow.work;

import static io.capyiot.workflow.work.WorkOrderModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import io.capyiot.contract.internal.*;
import io.capyiot.service.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
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
    classes = io.capyiot.workflow.WorkflowServiceApplication.class,
    properties = {
      "server.ssl.enabled=false",
      "capyiot.workorders.clients.enabled=false",
      "capyiot.domain-runtime.enabled=false",
      "capyiot.workorder-runtime.enabled=false"
    })
@Import(WorkOperationsIntegrationTest.Dependencies.class)
@AutoConfigureMockMvc
@Testcontainers
class WorkOperationsIntegrationTest {
  @Container
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4.11")
          .withDatabaseName("work_test")
          .withUsername("work_user")
          .withPassword("test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", MYSQL::getJdbcUrl);
    r.add("spring.datasource.username", MYSQL::getUsername);
    r.add("spring.datasource.password", MYSQL::getPassword);
    r.add(
        "capyiot.workorders.command-fingerprint-key",
        () -> "stable-workflow-fixture-key-over-32-characters");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Dependencies {
    @Bean
    WorkDevices devices() {
      return mock(WorkDevices.class);
    }

    @Bean
    WorkFaults faults() {
      return mock(WorkFaults.class);
    }

    @Bean
    UserAssignmentPort personnel() {
      return mock(UserAssignmentPort.class);
    }

    @Bean
    WorkIdentity identities() {
      return new WorkIdentity() {
        public AuthorizationContext current(org.springframework.security.oauth2.jwt.Jwt jwt) {
          return actor(
              AgentDelegation.isAgent(jwt) ? jwt.getClaimAsString("actorUserId") : jwt.getSubject(),
              "OPS_SUPERVISOR");
        }

        public List<Map<String, Object>> assignmentCandidates(
            AuthorizationContext actor, List<DeviceQueryPort.DeviceRef> refs) {
          return List.of();
        }
      };
    }

    @Bean
    JwtDecoder userTokens() {
      return raw -> {
        throw new org.springframework.security.oauth2.jwt.BadJwtException(
            "Explicit test credential required");
      };
    }
  }

  @Autowired WorkOrderStore store;
  @Autowired WorkOrderService orders;
  @Autowired WorkOperations operations;
  @Autowired WorkFacts facts;
  @Autowired WorkDevices devices;
  @Autowired WorkFaults faults;
  @Autowired UserAssignmentPort personnel;
  @Autowired ObjectMapper json;
  @Autowired MockMvc http;

  static AuthorizationContext actor(String user, String role) {
    return new AuthorizationContext(
        user,
        user,
        user,
        1,
        "session",
        List.of(new AuthorizationContext.Grant("g", role, "PARK", "PARK-A", List.of(), null)));
  }

  AuthorizationContext supervisor() {
    return actor("supervisor", "OPS_SUPERVISOR");
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  void outside() {
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
  }

  @BeforeEach
  void reset() {
    for (String table :
        List.of(
            "work_order_operation_device",
            "work_order_request",
            "work_order_operation",
            "repair_record",
            "acceptance_record",
            "work_order_history",
            "work_order_comment",
            "work_order_device_item",
            "work_order",
            "outbox_event",
            "command_lock")) store.db.update("DELETE FROM " + table);
    org.mockito.Mockito.reset(devices, faults, personnel);
    when(devices.device(anyString()))
        .thenAnswer(
            i -> {
              outside();
              String id = i.getArgument(0);
              return new DeviceQueryPort.DeviceRef(
                  id,
                  id.startsWith("foreign") ? "PARK-B" : "PARK-A",
                  "area-1",
                  id,
                  "IPC",
                  id,
                  "IN_SERVICE");
            });
    when(devices.requireVisibleDevices(any(), anyString(), anyCollection()))
        .thenAnswer(
            i -> {
              outside();
              AuthorizationContext actor = i.getArgument(0);
              actor.require(i.getArgument(1));
              Collection<String> ids = i.getArgument(2);
              if (ids.stream().anyMatch(id -> id.startsWith("foreign"))) throw ApiProblem.missing();
              return ids.stream().map(devices::device).toList();
            });
    when(devices.visibleDeviceIds(any(), anyString()))
        .thenAnswer(
            i -> {
              outside();
              ((AuthorizationContext) i.getArgument(0)).require(i.getArgument(1));
              return Set.of("ipc-1", "ipc-2");
            });
    when(faults.reference(anyString()))
        .thenAnswer(
            i -> {
              outside();
              return new FaultApi.IncidentRef(i.getArgument(0), "PARK-A", "OPEN", "P2", 1);
            });
    doAnswer(
            i -> {
              outside();
              WorkOrderApi.CommandBinding binding = i.getArgument(0);
              assertEquals(
                  WorkOrderApi.OperationState.PREPARED,
                  operations.result(binding.operationId()).state());
              return new FaultApi.Reservation(
                  "reservation-" + binding.operationId(),
                  FaultApi.ReservationState.RESERVED,
                  binding,
                  null,
                  Instant.now());
            })
        .when(faults)
        .reserve(any());
    doAnswer(
            i -> {
              outside();
              return null;
            })
        .when(faults)
        .commit(anyString(), any());
    doAnswer(
            i -> {
              outside();
              return null;
            })
        .when(personnel)
        .requireRoleForDevices(anyString(), anyString(), anyCollection());
  }

  Create create(Source source) {
    return new Create(
        source,
        source == Source.INCIDENT ? "incident-1" : null,
        List.of(new DeviceItem("ipc-1", DeviceRole.MAIN, "无视频")),
        "维修摄像机",
        "核查视频信号",
        "VIDEO",
        Priority.P2,
        null,
        "恢复视频",
        null);
  }

  long manual() {
    return Long.parseLong(
        orders
            .create(supervisor(), key(), key(), create(Source.MANUAL))
            .path("workOrderId")
            .asText());
  }

  WorkOperations.Intent prepare(String key) {
    return operations.prepare(
        supervisor(),
        "PARK-A",
        key,
        "WorkOrderCreated",
        create(Source.INCIDENT),
        "incident-1",
        null,
        "request-1",
        List.of("ipc-1"));
  }

  @Test
  void manualOrderHasNoFaultReservationAndAtomicOperationReceipt() {
    String key = key();
    var command = create(Source.MANUAL);
    var created = orders.create(supervisor(), key, "request-1", command);
    assertEquals("OPEN", created.path("status").asText());
    var operation =
        store.db.queryForObject("SELECT operation_id FROM work_order_operation", String.class);
    var status = operations.result(operation);
    assertEquals(WorkOrderApi.OperationState.COMMITTED, status.state());
    assertNull(status.binding().incidentId());
    assertNull(status.reservationId());
    assertFalse(orders.create(supervisor(), key, "replay", command).path("created").asBoolean());
    verifyNoInteractions(faults);
    assertEquals(1, store.db.queryForObject("SELECT COUNT(*) FROM work_order", Integer.class));
    assertEquals(
        1,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM work_order_request WHERE operation_id IS NOT NULL",
            Integer.class));
  }

  @Test
  void incidentCreatePublishesOperationAndReservationWithoutCrossDatabaseTransactions() {
    var result = orders.create(supervisor(), key(), "request-1", create(Source.INCIDENT));
    assertTrue(result.path("created").asBoolean());
    var row = store.db.queryForMap("SELECT * FROM work_order_operation");
    assertEquals("COMMITTED", row.get("state"));
    assertEquals("ACKNOWLEDGED", row.get("notification_status"));
    var event =
        json.valueToTree(
                store.db.queryForMap(
                    "SELECT payload_json FROM outbox_event WHERE event_type='WorkOrderCreated'"))
            .path("payload_json")
            .asText();
    assertTrue(event.contains(row.get("operation_id").toString()));
    assertTrue(event.contains(row.get("reservation_id").toString()));
  }

  @Test
  void lostReservationResponseKeepsOneIntentAndSameOperationOnRetry() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(
            i -> {
              WorkOrderApi.CommandBinding b = i.getArgument(0);
              if (calls.incrementAndGet() == 1)
                throw ServiceFailure.unavailable("DEPENDENCY_TIMEOUT", ServiceId.FAULT, false)
                    .unknown();
              return new FaultApi.Reservation(
                  "r-1", FaultApi.ReservationState.RESERVED, b, null, Instant.now());
            })
        .when(faults)
        .reserve(any());
    String key = key();
    var failure =
        assertThrows(
            ApiProblem.class,
            () -> orders.create(supervisor(), key, "request-1", create(Source.INCIDENT)));
    assertEquals("WORKORDER_OPERATION_PENDING", failure.code());
    String op = failure.details().get("operationId").toString();
    assertEquals(
        WorkOrderApi.OperationState.RECONCILIATION_REQUIRED, operations.result(op).state());
    var result = orders.create(supervisor(), key, "retry", create(Source.INCIDENT));
    assertEquals("OPEN", result.path("status").asText());
    assertEquals(
        1, store.db.queryForObject("SELECT COUNT(*) FROM work_order_operation", Integer.class));
  }

  @Test
  void failedCommitNotificationNeverTurnsSuccessfulCreateIntoRetryableBusinessFailure() {
    doThrow(ServiceFailure.unavailable("DEPENDENCY_UNAVAILABLE", ServiceId.FAULT, true))
        .when(faults)
        .commit(anyString(), any());
    var result = orders.create(supervisor(), key(), key(), create(Source.INCIDENT));
    assertEquals("OPEN", result.path("status").asText());
    var row = store.db.queryForMap("SELECT * FROM work_order_operation");
    assertEquals("COMMITTED", row.get("state"));
    assertEquals("PENDING", row.get("notification_status"));
    assertTrue(((Number) row.get("notification_attempts")).intValue() > 0);
  }

  @Test
  void abortedIntentFencesAReservationArrivingLate() {
    var intent = prepare(key());
    operations.abort(intent, "VALIDATION_FAILED");
    doAnswer(
            i ->
                new FaultApi.Reservation(
                    "late-r",
                    FaultApi.ReservationState.RESERVED,
                    i.getArgument(0),
                    null,
                    Instant.now()))
        .when(faults)
        .reserve(any());
    assertThrows(ApiProblem.class, () -> operations.reserve(intent));
    assertThrows(
        ApiProblem.class,
        () ->
            operations.execute(
                intent,
                supervisor(),
                create(Source.INCIDENT),
                () -> {
                  fail("Late mutation must not run");
                  return Map.of();
                }));
    assertEquals(WorkOrderApi.OperationState.ABORTED, operations.result(intent.id()).state());
  }

  @Test
  void committedIntentCannotBeChangedToAborted() {
    var result = orders.create(supervisor(), key(), key(), create(Source.INCIDENT));
    var intent =
        operations
            .existing(
                supervisor(),
                store.db.queryForObject(
                    "SELECT idempotency_key FROM work_order_operation", String.class),
                "WorkOrderCreated",
                create(Source.INCIDENT))
            .orElseThrow();
    operations.abort(intent, "late-cancel");
    assertEquals(WorkOrderApi.OperationState.COMMITTED, operations.result(intent.id()).state());
    assertEquals(result.path("workOrderId").asText(), operations.result(intent.id()).workOrderId());
  }

  @Test
  void sameKeyConcurrentCreateReturnsOneOrderAndOneReceipt() throws Exception {
    String key = key();
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first =
          workers.submit(
              () -> {
                start.await();
                return orders
                    .create(supervisor(), key, key, create(Source.MANUAL))
                    .path("workOrderId")
                    .asText();
              });
      var second =
          workers.submit(
              () -> {
                start.await();
                return orders
                    .create(supervisor(), key, key, create(Source.MANUAL))
                    .path("workOrderId")
                    .asText();
              });
      start.countDown();
      assertEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
    }
    assertEquals(1, store.db.queryForObject("SELECT COUNT(*) FROM work_order", Integer.class));
    assertEquals(
        1, store.db.queryForObject("SELECT COUNT(*) FROM work_order_request", Integer.class));
  }

  @Test
  void publishedApiDocumentCanBeGenerated() throws Exception {
    var result =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/v3/api-docs")
                    .with(
                        org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors.jwt()))
            .andReturn();
    assertEquals(
        200,
        result.getResponse().getStatus(),
        () -> {
          var error = result.getResolvedException();
          if (error == null) return "OpenAPI document unavailable";
          var trace = new java.io.StringWriter();
          error.printStackTrace(new java.io.PrintWriter(trace));
          return trace.toString();
        });
    assertTrue(
        new ObjectMapper().readTree(result.getResponse().getContentAsString()).has("openapi"));
  }

  @Test
  void sameKeyDifferentBodyIsRejectedWithoutASecondOrder() {
    String key = key();
    orders.create(supervisor(), key, key, create(Source.MANUAL));
    var c = create(Source.MANUAL);
    var changed =
        new Create(
            c.sourceType(),
            null,
            c.deviceItems(),
            "其他标题",
            c.description(),
            c.workCategory(),
            c.priority(),
            null,
            c.expectedResult(),
            null);
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(ApiProblem.class, () -> orders.create(supervisor(), key, key, changed))
            .code());
  }

  @Test
  void staleIntentIsAtomicallyAbortedAndUnknownReservationIsNotAcknowledged() {
    var intent = prepare(key());
    when(faults.query(intent.id())).thenThrow(ServiceFailure.denied(404, "RESOURCE_NOT_FOUND"));
    store.db.update(
        "UPDATE work_order_operation SET updated_at=TIMESTAMPADD(MINUTE,-5,CURRENT_TIMESTAMP(3))");
    RequestCalls.background(operations::recover);
    assertEquals(WorkOrderApi.OperationState.ABORTED, operations.result(intent.id()).state());
    assertEquals("PENDING", operations.row(intent.id()).get("notification_status"));
    assertThrows(ApiProblem.class, () -> operations.reserve(intent));
  }

  @Test
  void recoveredAbortCanFindAReservationWhoseResponseWasLost() {
    var intent = prepare(key());
    operations.abort(intent, "cancelled");
    when(faults.query(intent.id()))
        .thenReturn(
            new FaultApi.Reservation(
                "lost-r",
                FaultApi.ReservationState.RESERVED,
                intent.binding(),
                null,
                Instant.now()));
    operations.notifyResult(intent);
    verify(faults).abort(eq("lost-r"), argThat(c -> c.binding().equals(intent.binding())));
    assertEquals("lost-r", operations.result(intent.id()).reservationId());
    assertEquals("ACKNOWLEDGED", operations.row(intent.id()).get("notification_status"));
  }

  @Test
  void invalidMainDeviceCrossParkAndAssignmentRolesAreRejectedBeforeMutation() {
    var c = create(Source.MANUAL);
    assertThrows(
        ApiProblem.class,
        () ->
            orders.create(
                supervisor(),
                key(),
                key(),
                new Create(
                    c.sourceType(),
                    null,
                    List.of(new DeviceItem("ipc-1", DeviceRole.RELATED, "问题")),
                    c.title(),
                    c.description(),
                    c.workCategory(),
                    c.priority(),
                    null,
                    c.expectedResult(),
                    null)));
    assertThrows(
        ApiProblem.class,
        () ->
            orders.create(
                supervisor(),
                key(),
                key(),
                new Create(
                    c.sourceType(),
                    null,
                    List.of(new DeviceItem("foreign-ipc", DeviceRole.MAIN, "问题")),
                    c.title(),
                    c.description(),
                    c.workCategory(),
                    c.priority(),
                    null,
                    c.expectedResult(),
                    null)));
    long id = manual();
    assertThrows(
        ApiProblem.class,
        () -> orders.assign(supervisor(), key(), id, new Assign("same", "same", 0L, null), false));
  }

  @Test
  void eightStateWorkflowRetainsIndependentRepairAndAcceptanceWithDeviceResults() {
    long id = manual();
    var repairer = actor("repairer", "REPAIR_TECH");
    var acceptor = actor("acceptor", "ACCEPTANCE_STAFF");
    orders.assign(supervisor(), key(), id, new Assign("repairer", "acceptor", 0L, null), false);
    assertThrows(
        ApiProblem.class,
        () -> orders.accept(actor("other", "REPAIR_TECH"), key(), id, new Version(1L)));
    orders.accept(repairer, key(), id, new Version(1L));
    orders.start(repairer, key(), id, new Version(2L));
    orders.repair(repairer, key(), id, new Repair("DIAGNOSIS", "电缆松动", List.of(), 3L));
    orders.submit(
        repairer,
        key(),
        id,
        new Completion("修复连接", List.of(new DeviceResult("ipc-1", "视频正常")), List.of(), 4L));
    orders.acceptance(
        acceptor,
        key(),
        id,
        new Acceptance(
            AcceptanceResult.RETURN, "仍需检查", List.of(new Check("视频稳定", false)), List.of(), 5L));
    orders.start(repairer, key(), id, new Version(6L));
    orders.submit(
        repairer,
        key(),
        id,
        new Completion("重新接线", List.of(new DeviceResult("ipc-1", "视频稳定")), List.of(), 7L));
    orders.acceptance(
        acceptor,
        key(),
        id,
        new Acceptance(
            AcceptanceResult.PASS, "通过", List.of(new Check("视频稳定", true)), List.of(), 8L));
    assertEquals("CLOSED", orders.detail(supervisor(), id).get("status"));
    assertEquals(
        2, store.db.queryForObject("SELECT COUNT(*) FROM acceptance_record", Integer.class));
  }

  @Test
  void wrongAssigneeAndIncompleteDeviceCompletionCannotChangeTheOrder() {
    long id = manual();
    orders.assign(supervisor(), key(), id, new Assign("repairer", "acceptor", 0L, null), false);
    assertThrows(
        ApiProblem.class,
        () -> orders.accept(actor("acceptor", "REPAIR_TECH"), key(), id, new Version(1L)));
    orders.accept(actor("repairer", "REPAIR_TECH"), key(), id, new Version(1L));
    orders.start(actor("repairer", "REPAIR_TECH"), key(), id, new Version(2L));
    assertThrows(
        ApiProblem.class,
        () ->
            orders.submit(
                actor("repairer", "REPAIR_TECH"),
                key(),
                id,
                new Completion("结果缺失", List.of(new DeviceResult("ipc-2", "正常")), List.of(), 3L)));
    assertEquals("IN_PROGRESS", orders.detail(supervisor(), id).get("status"));
  }

  @Test
  void reopenMustReserveBeforeChangingATerminalOrder() {
    long id =
        Long.parseLong(
            orders
                .create(supervisor(), key(), key(), create(Source.INCIDENT))
                .path("workOrderId")
                .asText());
    store.db.update(
        "UPDATE work_order SET"
            + " status='CLOSED',assigned_repairer_id='repairer',designated_acceptor_id='acceptor'"
            + " WHERE id=?",
        id);
    doAnswer(
            i -> {
              WorkOrderApi.CommandBinding b = i.getArgument(0);
              outside();
              assertEquals(WorkOrderApi.Operation.REOPEN, b.operation());
              assertEquals(
                  "CLOSED",
                  store.db.queryForObject(
                      "SELECT status FROM work_order WHERE id=?", String.class, id));
              return new FaultApi.Reservation(
                  "reopen-r", FaultApi.ReservationState.RESERVED, b, null, Instant.now());
            })
        .when(faults)
        .reserve(any());
    var result = orders.reopen(supervisor(), key(), key(), id, new Reason(0L, "验收后发现问题"));
    assertEquals("ASSIGNED", result.path("status").asText());
    assertEquals(
        2,
        store.db.queryForObject(
            "SELECT COUNT(*) FROM work_order_operation WHERE state='COMMITTED'", Integer.class));
  }

  @Test
  void pureAssignmentAndAuthorityFactsNeverCallUpstreamServices() {
    long id = manual();
    store.db.update(
        "UPDATE work_order SET assigned_repairer_id='repairer',designated_acceptor_id='acceptor'"
            + " WHERE id=?",
        id);
    clearInvocations(devices, faults, personnel);
    var assignments =
        facts.assigned(
            new WorkOrderApi.AssignmentRequest(
                "repairer",
                "PARK-A",
                PlatformApi.AssignmentRelation.REPAIRER,
                List.of("ipc-1", "ipc-2")));
    assertEquals(List.of("ipc-1"), assignments.assignedDeviceIds());
    assertTrue(facts.incident("empty-incident", null).allTerminal());
    operations.result("missing-op");
    verifyNoInteractions(devices, faults, personnel);
  }

  @Test
  void authorityPaginationReturnsAllRowsAndDoesNotHidePendingOperations() {
    for (int i = 0; i < 105; i++)
      store.db.update(
          "INSERT INTO"
              + " work_order(campus_id,source_business_key,title,priority,status,created_by,work_order_no,source_type,incident_id)"
              + " VALUES('PARK-A',?,'fixture','P2','CLOSED','fixture',?,'INCIDENT','incident-many')",
          "source-" + i,
          "WO-FIX-" + i);
    var command = create(Source.INCIDENT);
    operations.prepare(
        supervisor(),
        "PARK-A",
        key(),
        "WorkOrderCreated",
        command,
        "incident-many",
        null,
        key(),
        List.of("ipc-1"));
    var first = facts.incident("incident-many", null);
    assertEquals(100, first.orders().size());
    assertEquals(1, first.pendingOperationIds().size());
    assertFalse(first.allTerminal());
    var last = facts.incident("incident-many", first.nextCursor());
    assertEquals(5, last.orders().size());
    assertNull(last.nextCursor());
    assertThrows(ApiProblem.class, () -> facts.incident("other-incident", first.nextCursor()));
  }

  @Test
  void pendingStatusIsUnknownForMissingOperationsAndScopeCheckedForUserQueries() throws Exception {
    assertEquals(WorkOrderApi.OperationState.UNKNOWN, operations.result("missing-op").state());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/internal/v1/assignments/scope-facts")
                .contentType("application/json")
                .content("{}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
  }

  @Test
  void ownedCredentialCannotReadOtherSchemas() {
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> store.db.queryForList("SELECT * FROM mysql.user"));
  }

  @Test
  void abortAndLateCommitRaceHaveExactlyOneTerminalOutcome() throws Exception {
    var intent = operations.reserve(prepare(key()));
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var abort =
          pool.submit(
              () -> {
                start.await();
                operations.abort(intent, "concurrent-cancel");
                return true;
              });
      var commit =
          pool.submit(
              () -> {
                start.await();
                try {
                  return operations.execute(
                      intent,
                      supervisor(),
                      create(Source.INCIDENT),
                      () -> {
                        store.db.update(
                            "INSERT INTO"
                                + " work_order(campus_id,source_business_key,title,priority,status,created_by,work_order_no,source_type,incident_id,last_reservation_id)"
                                + " VALUES('PARK-A',?,'fixture','P2','OPEN','supervisor',?,'INCIDENT','incident-1',?)",
                            key(),
                            "WO-" + key(),
                            intent.reservation());
                        long id = store.db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                        return Map.of("workOrderId", Long.toString(id), "version", 0);
                      });
                } catch (ApiProblem aborted) {
                  return null;
                }
              });
      start.countDown();
      abort.get(15, TimeUnit.SECONDS);
      commit.get(15, TimeUnit.SECONDS);
    }
    var state = operations.result(intent.id());
    assertTrue(
        Set.of(WorkOrderApi.OperationState.COMMITTED, WorkOrderApi.OperationState.ABORTED)
            .contains(state.state()));
    assertEquals(
        state.state() == WorkOrderApi.OperationState.COMMITTED ? 1 : 0,
        store.db.queryForObject("SELECT COUNT(*) FROM work_order", Integer.class));
  }

  @Test
  void confirmedAgentCreationChecksRawBodyAndReceiptOwnership() throws Exception {
    String key = "agent-confirm-1";
    byte[] body = json.writeValueAsBytes(create(Source.MANUAL));
    String hash =
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
    var now = Instant.now();
    var token =
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("delegated-fixture")
            .header("alg", "RS256")
            .header("kid", "test-key")
            .subject("capyiot-agent-service")
            .claim("iss", "capyiot-agent-service")
            .claim("aud", List.of("capyiot-workflow"))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(60))
            .claim("jti", "jwt-1")
            .claim("command", "CREATE_WORK_ORDER")
            .claim("actorUserId", "supervisor")
            .claim("confirmationId", "confirm-1")
            .claim("draftId", "draft-1")
            .claim("draftRevision", 1L)
            .claim("requestHash", hash)
            .claim("idempotencyKey", key)
            .build();
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/workorders")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(token))
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/workorders/commands/" + key)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(token)))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    byte[] changed =
        new String(body, java.nio.charset.StandardCharsets.UTF_8)
            .replace("维修摄像机", "改变标题")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/workorders")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(token))
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(changed))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    assertEquals(1, store.db.queryForObject("SELECT COUNT(*) FROM work_order", Integer.class));
  }

  @Test
  void knownReservationRejectionAbortsBeforeAnyLocalOrderCanCommit() {
    doThrow(ServiceFailure.denied(409, "VERSION_CONFLICT")).when(faults).reserve(any());
    var error =
        assertThrows(
            ApiProblem.class,
            () -> orders.create(supervisor(), key(), key(), create(Source.INCIDENT)));
    assertEquals("WORKORDER_OPERATION_ABORTED", error.code());
    String id = error.details().get("operationId").toString();
    assertEquals(WorkOrderApi.OperationState.ABORTED, operations.result(id).state());
    assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM work_order", Integer.class));
  }

  @Test
  void confirmedReceiptCannotBeReboundToAnotherDraft() {
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
        new org.springframework.web.context.request.ServletRequestAttributes(request));
    try {
      var original =
          new AgentDelegation.Binding(
              "supervisor", "confirm-1", "draft-1", 1, "a".repeat(64), "agent-confirm-1");
      request.setAttribute("capyiot.delegation", original);
      orders.create(supervisor(), original.idempotencyKey(), "request-1", create(Source.MANUAL));
      request.setAttribute(
          "capyiot.delegation",
          new AgentDelegation.Binding(
              "supervisor",
              "confirm-other",
              "draft-other",
              2,
              original.requestHash(),
              original.idempotencyKey()));
      assertThrows(
          ApiProblem.class,
          () ->
              orders.create(
                  supervisor(), original.idempotencyKey(), "request-1", create(Source.MANUAL)));
      assertThrows(
          ApiProblem.class,
          () ->
              orders.commandReceipt(
                  supervisor(),
                  new AgentDelegation.Binding(
                      "supervisor",
                      "confirm-other",
                      "draft-other",
                      2,
                      original.requestHash(),
                      original.idempotencyKey())));
    } finally {
      org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }
  }
}
