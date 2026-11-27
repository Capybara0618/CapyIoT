package io.capyiot.workflow.work;

import static io.capyiot.workflow.work.WorkOrderModels.*;

import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "capyiot.workorders.enabled", havingValue = "true")
public class WorkOrderService implements WorkOrderQueryPort, AssignmentQueryPort {
  private final WorkOrderStore store;
  private final DeviceQueryPort devices;
  private final WorkFaults gate;
  private final WorkOperations operations;
  private final UserAssignmentPort users;
  private final ObjectMapper json;
  private final CommandJournal commands;
  private final LocalEvents events;

  public WorkOrderService(
      WorkOrderStore store,
      DeviceQueryPort devices,
      WorkFaults gate,
      WorkOperations operations,
      UserAssignmentPort users,
      ObjectMapper json,
      @Value("${capyiot.workorders.command-fingerprint-key}") String key) {
    this.store = store;
    this.devices = devices;
    this.gate = gate;
    this.operations = operations;
    this.users = users;
    this.json = json;
    commands = new CommandJournal(store.db, store.tx, json, key, true);
    events = new LocalEvents(store.db, json, "workorder");
  }

  private Map<String, Object> row(long id) {
    var rows = store.db.queryForList("SELECT * FROM work_order WHERE id=?", id);
    if (rows.isEmpty()) throw ApiProblem.missing();
    return rows.getFirst();
  }

  private List<String> deviceIds(long id) {
    return store.db.queryForList(
        "SELECT device_id FROM work_order_device_item WHERE work_order_id=? ORDER BY item_id",
        String.class,
        id);
  }

  private Map<String, Object> require(AuthorizationContext actor, String permission, long id) {
    var order = row(id);
    var ids = deviceIds(id);
    if (ids.isEmpty()) throw ApiProblem.missing();
    devices.requireVisibleDevices(actor, permission, ids);
    return order;
  }

  @Override
  public Set<String> assignedDeviceIds(String user, String park, String relation) {
    String column =
        switch (relation) {
          case "REPAIRER" -> "assigned_repairer_id";
          case "ACCEPTOR" -> "designated_acceptor_id";
          default -> throw ApiProblem.invalid("履职关系无效");
        };
    return new HashSet<>(
        store.db.queryForList(
            "SELECT DISTINCT i.device_id FROM work_order_device_item i JOIN work_order w ON"
                + " w.id=i.work_order_id WHERE w.campus_id=? AND w."
                + column
                + "=?",
            String.class,
            park,
            user));
  }

  @Override
  public List<OrderRef> forIncident(String id) {
    return store.db.query(
        "SELECT id,work_order_no,incident_id,status,version FROM work_order WHERE incident_id=?"
            + " ORDER BY id",
        (rs, n) ->
            new OrderRef(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)),
        id);
  }

  @Override
  public Optional<OrderRef> forReservation(String id) {
    return store
        .db
        .query(
            "SELECT w.id,w.work_order_no,w.incident_id,w.status,w.version FROM work_order w JOIN"
                + " work_order_request r ON r.order_id=w.id WHERE r.reservation_id=?",
            (rs, n) ->
                new OrderRef(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getLong(5)),
            id)
        .stream()
        .findFirst();
  }

  private record Checked(
      List<DeviceQueryPort.DeviceRef> refs,
      String park,
      io.capyiot.contract.internal.FaultApi.IncidentRef incident,
      String priority) {}

  private Checked checked(AuthorizationContext actor, Create c) {
    actor.require("workorder.create");
    if (c.deviceItems() == null
        || c.deviceItems().isEmpty()
        || c.deviceItems().stream().filter(i -> i.deviceRole() == DeviceRole.MAIN).count() != 1)
      throw ApiProblem.invalid("工单必须且只能有一个 MAIN 设备");
    var ids = c.deviceItems().stream().map(DeviceItem::deviceId).toList();
    if (new HashSet<>(ids).size() != ids.size()) throw ApiProblem.invalid("维修设备不能重复");
    var refs = devices.requireVisibleDevices(actor, "workorder.create", ids);
    String park = refs.getFirst().parkId();
    if (refs.stream().anyMatch(d -> !d.parkId().equals(park) || d.assetStatus().equals("RETIRED")))
      throw ApiProblem.invalid("维修设备必须同园区且未退役");
    if (c.sourceType() == Source.INCIDENT && (c.incidentId() == null || c.incidentId().isBlank())
        || c.sourceType() == Source.MANUAL && c.incidentId() != null)
      throw ApiProblem.invalid("工单来源与 Incident 不一致");
    io.capyiot.contract.internal.FaultApi.IncidentRef incident =
        c.sourceType() == Source.INCIDENT ? gate.reference(c.incidentId()) : null;
    if (incident != null && !incident.parkId().equals(park))
      throw ApiProblem.invalid("工单与故障不在同一园区");
    String priority =
        c.priority() == null ? incident == null ? null : incident.priority() : c.priority().name();
    if (priority == null) throw ApiProblem.invalid("独立工单必须指定优先级");
    if (incident != null && !priority.equals(incident.priority())) {
      actor.require("incident.priority.change");
      io.capyiot.workflow.work.ApiProblem.requireReason(c.priorityReason());
    }
    if (incident != null && !Set.of("OPEN", "PROCESSING").contains(incident.status()))
      throw ApiProblem.conflict("故障尚未确认或已结束，不可创建维修工单");
    return new Checked(refs, park, incident, priority);
  }

  public Map<String, Object> validateDraft(AuthorizationContext actor, Create c) {
    actor.require("agent.draft.prepare");
    var valid = checked(actor, c);
    return Map.of("parkId", valid.park(), "priority", valid.priority());
  }

  public JsonNode commandReceipt(AuthorizationContext actor, AgentDelegation.Binding binding) {
    actor.require("agent.draft.confirm");
    actor.require("workorder.create");
    var rows =
        store.db.queryForList(
            "SELECT * FROM work_order_request WHERE idempotency_key=? AND actor_user_id=? AND"
                + " agent_confirmation_id=? AND agent_draft_id=? AND agent_draft_revision=? AND"
                + " agent_request_hash=? AND command_type='WorkOrderCreated'",
            binding.idempotencyKey(),
            actor.userId(),
            binding.confirmationId(),
            binding.draftId(),
            binding.draftRevision(),
            binding.requestHash());
    if (rows.isEmpty()) {
      var pending =
          store.db.queryForList(
              "SELECT * FROM work_order_operation WHERE actor_user_id=? AND idempotency_key=? AND"
                  + " agent_confirmation_id=? AND agent_draft_id=? AND agent_draft_revision=? AND"
                  + " agent_request_hash=?",
              actor.userId(),
              binding.idempotencyKey(),
              binding.confirmationId(),
              binding.draftId(),
              binding.draftRevision(),
              binding.requestHash());
      if (pending.size() != 1) throw ApiProblem.missing();
      var operation = pending.getFirst();
      var ids =
          store.db.queryForList(
              "SELECT device_id FROM work_order_operation_device WHERE operation_id=?",
              String.class,
              operation.get("operation_id"));
      devices.requireVisibleDevices(actor, "workorder.create", ids);
      if ("ABORTED".equals(operation.get("state")))
        throw new ApiProblem(
            409,
            "WORKORDER_OPERATION_ABORTED",
            "该确认操作已终止",
            Map.of("operationId", operation.get("operation_id")));
      throw new ApiProblem(
          503,
          "WORKORDER_OPERATION_PENDING",
          "原确认结果待核查，请保留原确认标识",
          Map.of("operationId", operation.get("operation_id")));
    }
    var receipt = rows.getFirst();
    require(actor, "workorder.create", ((Number) receipt.get("order_id")).longValue());
    try {
      return json.readTree(receipt.get("result_snapshot").toString());
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalStateException("Invalid receipt");
    }
  }

  public JsonNode create(AuthorizationContext actor, String key, String requestId, Create c) {
    actor.require("workorder.create");
    if (actor.sessionId().startsWith("delegation:")) actor.require("agent.draft.confirm");
    var previous = operations.existing(actor, key, "WorkOrderCreated", c);
    if (previous.isPresent()) {
      var result = operations.committed(previous.get());
      if (result.isPresent()) {
        require(
            actor, "workorder.create", Long.parseLong(result.get().path("workOrderId").asText()));
        return result.get();
      }
    }
    var valid = checked(actor, c);
    var refs = valid.refs();
    String park = valid.park(), priority = valid.priority();
    var operation =
        operations.prepare(
            actor,
            park,
            key,
            "WorkOrderCreated",
            c,
            c.incidentId(),
            null,
            requestId,
            refs.stream().map(DeviceQueryPort.DeviceRef::deviceId).toList());
    try {
      var reserved = operations.reserve(operation);
      var result =
          operations.execute(
              reserved,
              actor,
              c,
              () -> {
                String provisional = "WO-" + UUID.randomUUID();
                store.db.update(
                    "INSERT INTO"
                        + " work_order(campus_id,source_alarm_id,source_business_key,source_alarm_no,title,priority,status,created_by,work_order_no,source_type,incident_id,description,work_category,expected_result,due_at,last_reservation_id)"
                        + " VALUES(?,NULL,?,NULL,?,?,'OPEN',?,?,?,?,?,?,?,?,?)",
                    park,
                    "V1-" + UUID.randomUUID(),
                    c.title(),
                    priority,
                    actor.userId(),
                    provisional,
                    c.sourceType().name(),
                    c.incidentId(),
                    c.description(),
                    c.workCategory(),
                    c.expectedResult(),
                    DbTime.sql(c.dueAt()),
                    reserved.reservation());
                long id = store.db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                String number = "WO-%06d".formatted(id);
                store.db.update("UPDATE work_order SET work_order_no=? WHERE id=?", number, id);
                Map<String, DeviceQueryPort.DeviceRef> snapshots = new HashMap<>();
                refs.forEach(d -> snapshots.put(d.deviceId(), d));
                for (var item : c.deviceItems()) {
                  var d = snapshots.get(item.deviceId());
                  store.db.update(
                      "INSERT INTO"
                          + " work_order_device_item(item_id,work_order_id,device_id,asset_code_snapshot,device_name_snapshot,device_type_snapshot,area_id_snapshot,device_role,issue_description)"
                          + " VALUES(?,?,?,?,?,?,?,?,?)",
                      UUID.randomUUID().toString(),
                      id,
                      d.deviceId(),
                      d.assetCode(),
                      d.name(),
                      d.deviceType(),
                      d.areaId(),
                      item.deviceRole().name(),
                      item.issueDescription());
                }
                store.db.update(
                    "UPDATE work_order SET priority_reason=? WHERE id=?", c.priorityReason(), id);
                history(actor, id, 0, "CREATE", null, "OPEN", c.priorityReason(), requestId);
                events.append(
                    "WorkOrderCreated",
                    Long.toString(id),
                    park,
                    actor,
                    Map.of(
                        "workOrderId",
                        Long.toString(id),
                        "sourceType",
                        c.sourceType().name(),
                        "incidentId",
                        c.incidentId() == null ? "" : c.incidentId(),
                        "status",
                        "OPEN",
                        "aggregateVersion",
                        0));
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("workOrderId", Long.toString(id));
                created.put("workOrderNo", number);
                created.put("sourceType", c.sourceType().name());
                created.put("incidentId", c.incidentId());
                created.put("status", "OPEN");
                created.put("priority", priority);
                created.put("version", 0);
                created.put("createdAt", Instant.now());
                created.put("created", true);
                return created;
              });
      operations.notifyResult(reserved);
      return result;
    } catch (RuntimeException failure) {
      return operations.afterFailure(operation, failure);
    }
  }

  public Map<String, Object> list(
      AuthorizationContext actor,
      String deviceId,
      String incidentId,
      String status,
      String priority,
      String repairer,
      String acceptor,
      Instant from,
      Instant to,
      int page,
      int size) {
    Set<String> visible = devices.visibleDeviceIds(actor, "workorder.read");
    var rows =
        store.db.queryForList("SELECT * FROM work_order ORDER BY updated_at DESC,id DESC").stream()
            .filter(
                order -> {
                  var ids = deviceIds(((Number) order.get("id")).longValue());
                  return !ids.isEmpty()
                      && visible.containsAll(ids)
                      && (deviceId == null || ids.contains(deviceId));
                })
            .filter(o -> incidentId == null || incidentId.equals(o.get("incident_id")))
            .filter(o -> status == null || status.equals(o.get("status")))
            .filter(o -> priority == null || priority.equals(o.get("priority")))
            .filter(o -> repairer == null || repairer.equals(o.get("assigned_repairer_id")))
            .filter(o -> acceptor == null || acceptor.equals(o.get("designated_acceptor_id")))
            .filter(o -> from == null || !DbTime.instant(o.get("created_at")).isBefore(from))
            .filter(o -> to == null || DbTime.instant(o.get("created_at")).isBefore(to))
            .map(this::summary)
            .toList();
    return Views.page(rows, page, size);
  }

  private Map<String, Object> summary(Map<String, Object> raw) {
    var view = Views.row(raw);
    long id = ((Number) raw.get("id")).longValue();
    view.remove("id");
    view.put("workOrderId", Long.toString(id));
    view.put("parkId", view.remove("campusId"));
    view.keySet()
        .retainAll(
            Set.of(
                "workOrderId",
                "workOrderNo",
                "parkId",
                "sourceType",
                "incidentId",
                "title",
                "description",
                "workCategory",
                "expectedResult",
                "priority",
                "priorityReason",
                "status",
                "assignedRepairerId",
                "designatedAcceptorId",
                "workCycleNo",
                "dueAt",
                "completionSummary",
                "cancelReason",
                "assignedAt",
                "acceptedAt",
                "startedAt",
                "submittedAt",
                "cancelledAt",
                "closedAt",
                "createdAt",
                "updatedAt",
                "createdBy",
                "version",
                "lastAcceptanceId"));
    view.put(
        "deviceItems",
        store
            .db
            .queryForList(
                "SELECT"
                    + " item_id,device_id,device_role,asset_code_snapshot,device_name_snapshot,device_type_snapshot,area_id_snapshot,monitoring_point_id,issue_description"
                    + " FROM work_order_device_item WHERE work_order_id=? ORDER BY"
                    + " device_role,item_id",
                id)
            .stream()
            .map(Views::row)
            .toList());
    return view;
  }

  public Map<String, Object> detail(AuthorizationContext actor, long id) {
    var order = require(actor, "workorder.read", id);
    var view = summary(order);
    view.put(
        "repairRecords",
        store
            .db
            .queryForList(
                "SELECT * FROM repair_record WHERE work_order_id=? ORDER BY created_at,record_id",
                id)
            .stream()
            .map(Views::row)
            .toList());
    view.put(
        "acceptanceRecords",
        store
            .db
            .queryForList(
                "SELECT * FROM acceptance_record WHERE work_order_id=? ORDER BY work_cycle_no", id)
            .stream()
            .map(Views::row)
            .toList());
    view.put(
        "statusHistory",
        store
            .db
            .queryForList(
                "SELECT * FROM work_order_history WHERE order_id=? ORDER BY order_version", id)
            .stream()
            .map(Views::row)
            .toList());
    return view;
  }

  public JsonNode assign(
      AuthorizationContext actor, String key, long id, Assign c, boolean reassign) {
    var order = require(actor, reassign ? "workorder.reassign" : "workorder.assign", id);
    var refs = deviceIds(id).stream().map(devices::device).toList();
    if (c.assignedRepairerId().equals(c.designatedAcceptorId()))
      throw ApiProblem.invalid("维修人和验收人必须不同");
    users.requireRoleForDevices(c.assignedRepairerId(), "REPAIR_TECH", refs);
    users.requireRoleForDevices(c.designatedAcceptorId(), "ACCEPTANCE_STAFF", refs);
    if (reassign) io.capyiot.workflow.work.ApiProblem.requireReason(c.reason());
    return mutate(
        actor,
        key,
        id,
        reassign ? "WorkOrderReassigned" : "WorkOrderAssigned",
        reassign ? "workorder.reassign" : "workorder.assign",
        c,
        c.expectedVersion(),
        o -> {
          allowed(o, reassign ? Set.of("ASSIGNED", "ACCEPTED") : Set.of("OPEN"));
          store.db.update(
              "UPDATE work_order SET"
                  + " assigned_repairer_id=?,designated_acceptor_id=?,assigned_at=CURRENT_TIMESTAMP(3),accepted_at=NULL,status='ASSIGNED'"
                  + " WHERE id=?",
              c.assignedRepairerId(),
              c.designatedAcceptorId(),
              id);
          return "ASSIGNED";
        },
        c.reason());
  }

  public JsonNode accept(AuthorizationContext actor, String key, long id, Version c) {
    return mutate(
        actor,
        key,
        id,
        "WorkOrderAccepted",
        "workorder.repair.accept",
        c,
        c.expectedVersion(),
        o -> {
          repairer(actor, o);
          allowed(o, Set.of("ASSIGNED"));
          store.db.update(
              "UPDATE work_order SET accepted_at=CURRENT_TIMESTAMP(3),status='ACCEPTED' WHERE id=?",
              id);
          return "ACCEPTED";
        },
        null);
  }

  public JsonNode decline(AuthorizationContext actor, String key, long id, Reason c) {
    return mutate(
        actor,
        key,
        id,
        "WorkOrderDeclined",
        "workorder.repair.accept",
        c,
        c.expectedVersion(),
        o -> {
          repairer(actor, o);
          allowed(o, Set.of("ASSIGNED"));
          store.db.update(
              "UPDATE work_order SET"
                  + " assigned_repairer_id=NULL,designated_acceptor_id=NULL,status='OPEN' WHERE"
                  + " id=?",
              id);
          return "OPEN";
        },
        c.reason());
  }

  public JsonNode start(AuthorizationContext actor, String key, long id, Version c) {
    return mutate(
        actor,
        key,
        id,
        "WorkOrderStarted",
        "workorder.repair.start",
        c,
        c.expectedVersion(),
        o -> {
          repairer(actor, o);
          allowed(o, Set.of("ACCEPTED", "RETURNED"));
          store.db.update(
              "UPDATE work_order SET"
                  + " work_cycle_no=work_cycle_no+?,started_at=CURRENT_TIMESTAMP(3),completion_summary=NULL,status='IN_PROGRESS'"
                  + " WHERE id=?",
              o.get("status").equals("RETURNED") ? 1 : 0,
              id);
          return "IN_PROGRESS";
        },
        null);
  }

  public JsonNode repair(AuthorizationContext actor, String key, long id, Repair c) {
    validateEvidence(actor, id, c.evidenceReferenceIds(), "workorder.repair.record");
    return mutate(
        actor,
        key,
        id,
        "WorkOrderRepairRecorded",
        "workorder.repair.record",
        c,
        c.expectedVersion(),
        o -> {
          repairer(actor, o);
          allowed(o, Set.of("IN_PROGRESS"));
          if (!Set.of("DIAGNOSIS", "ACTION", "RESULT", "NOTE").contains(c.recordType()))
            throw ApiProblem.invalid("维修记录类型无效");
          store.db.update(
              "INSERT INTO"
                  + " repair_record(record_id,work_order_id,work_cycle_no,operator_id,record_type,content,evidence_refs_json,recorded_at)"
                  + " VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3))",
              UUID.randomUUID().toString(),
              id,
              o.get("work_cycle_no"),
              actor.userId(),
              c.recordType(),
              c.content(),
              serialize(c.evidenceReferenceIds() == null ? List.of() : c.evidenceReferenceIds()));
          return "IN_PROGRESS";
        },
        null);
  }

  public JsonNode submit(AuthorizationContext actor, String key, long id, Completion c) {
    validateEvidence(actor, id, c.evidenceReferenceIds(), "workorder.repair.submit");
    return mutate(
        actor,
        key,
        id,
        "WorkOrderCompletionSubmitted",
        "workorder.repair.submit",
        c,
        c.expectedVersion(),
        o -> {
          repairer(actor, o);
          allowed(o, Set.of("IN_PROGRESS"));
          var ids = deviceIds(id);
          var results = c.deviceResults().stream().map(DeviceResult::deviceId).toList();
          if (results.size() != new HashSet<>(results).size()
              || !new HashSet<>(ids).equals(new HashSet<>(results)))
            throw ApiProblem.invalid("必须逐项提交所有维修设备的处置结果");
          store.db.update(
              "INSERT INTO"
                  + " repair_record(record_id,work_order_id,work_cycle_no,operator_id,record_type,content,evidence_refs_json,device_results_json,recorded_at)"
                  + " VALUES(?,?,?,?,'RESULT',?,?,?,CURRENT_TIMESTAMP(3))",
              UUID.randomUUID().toString(),
              id,
              o.get("work_cycle_no"),
              actor.userId(),
              c.completionSummary(),
              serialize(c.evidenceReferenceIds() == null ? List.of() : c.evidenceReferenceIds()),
              serialize(c.deviceResults()));
          store.db.update(
              "UPDATE work_order SET"
                  + " completion_summary=?,submitted_at=CURRENT_TIMESTAMP(3),status='VERIFYING'"
                  + " WHERE id=?",
              c.completionSummary(),
              id);
          return "VERIFYING";
        },
        null);
  }

  public JsonNode acceptance(AuthorizationContext actor, String key, long id, Acceptance c) {
    String permission =
        c.result() == AcceptanceResult.PASS
            ? "workorder.acceptance.pass"
            : "workorder.acceptance.reject";
    validateEvidence(actor, id, c.evidenceReferenceIds(), permission);
    return mutate(
        actor,
        key,
        id,
        c.result() == AcceptanceResult.PASS ? "WorkOrderClosed" : "WorkOrderReturned",
        permission,
        c,
        c.expectedVersion(),
        o -> {
          allowed(o, Set.of("VERIFYING"));
          if (!actor.userId().equals(o.get("designated_acceptor_id"))
              || actor.userId().equals(o.get("assigned_repairer_id"))) throw ApiProblem.forbidden();
          if (c.result() == AcceptanceResult.PASS
              && c.checkItems().stream().anyMatch(check -> !check.passed()))
            throw ApiProblem.invalid("验收通过时所有检查项必须通过");
          String acceptance = UUID.randomUUID().toString();
          store.db.update(
              "INSERT INTO"
                  + " acceptance_record(acceptance_id,work_order_id,work_cycle_no,acceptor_id,result,comment,check_items_json,evidence_refs_json,accepted_at)"
                  + " VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3))",
              acceptance,
              id,
              o.get("work_cycle_no"),
              actor.userId(),
              c.result().name(),
              c.comment(),
              serialize(c.checkItems()),
              serialize(c.evidenceReferenceIds() == null ? List.of() : c.evidenceReferenceIds()));
          String next = c.result() == AcceptanceResult.PASS ? "CLOSED" : "RETURNED";
          store.db.update(
              "UPDATE work_order SET status=?,last_acceptance_id=?,closed_at=CASE WHEN ?='CLOSED'"
                  + " THEN CURRENT_TIMESTAMP(3) ELSE NULL END WHERE id=?",
              next,
              acceptance,
              next,
              id);
          return next;
        },
        c.comment());
  }

  public JsonNode cancel(AuthorizationContext actor, String key, long id, Reason c) {
    return mutate(
        actor,
        key,
        id,
        "WorkOrderCancelled",
        "workorder.cancel",
        c,
        c.expectedVersion(),
        o -> {
          allowed(o, Set.of("OPEN", "ASSIGNED", "ACCEPTED", "IN_PROGRESS", "RETURNED"));
          store.db.update(
              "UPDATE work_order SET"
                  + " status='CANCELLED',cancel_reason=?,cancelled_at=CURRENT_TIMESTAMP(3) WHERE"
                  + " id=?",
              c.reason(),
              id);
          return "CANCELLED";
        },
        c.reason());
  }

  public JsonNode reopen(
      AuthorizationContext actor, String key, String requestId, long id, Reason c) {
    var order = require(actor, "workorder.reopen", id);
    if (!order.get("source_type").equals("INCIDENT")) throw ApiProblem.conflict("独立或历史工单不可重开");
    String park = order.get("campus_id").toString();
    var command = Map.of("id", id, "body", c);
    var previous = operations.existing(actor, key, "WorkOrderReopened", command);
    if (previous.isPresent()) {
      var result = operations.committed(previous.get());
      if (result.isPresent()) return result.get();
    }
    var refs = deviceIds(id).stream().map(devices::device).toList();
    if (Objects.equals(order.get("assigned_repairer_id"), order.get("designated_acceptor_id")))
      throw ApiProblem.invalid("维修与验收人员必须不同");
    users.requireRoleForDevices(
        Objects.toString(order.get("assigned_repairer_id"), ""), "REPAIR_TECH", refs);
    users.requireRoleForDevices(
        Objects.toString(order.get("designated_acceptor_id"), ""), "ACCEPTANCE_STAFF", refs);
    var operation =
        operations.prepare(
            actor,
            park,
            key,
            "WorkOrderReopened",
            command,
            order.get("incident_id").toString(),
            id,
            requestId,
            deviceIds(id));
    try {
      var reserved = operations.reserve(operation);
      var result =
          operations.execute(
              reserved,
              actor,
              command,
              () -> {
                var current =
                    store.db.queryForMap("SELECT * FROM work_order WHERE id=? FOR UPDATE", id);
                Views.version(current, c.expectedVersion());
                allowed(current, Set.of("CLOSED"));
                store.db.update(
                    "UPDATE work_order SET"
                        + " status='ASSIGNED',work_cycle_no=work_cycle_no+1,closed_at=NULL,completion_summary=NULL,last_reservation_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP(3)"
                        + " WHERE id=?",
                    reserved.reservation(),
                    id);
                history(
                    actor,
                    id,
                    c.expectedVersion() + 1,
                    "WorkOrderReopened",
                    "CLOSED",
                    "ASSIGNED",
                    c.reason(),
                    requestId);
                events.append(
                    "WorkOrderReopened",
                    Long.toString(id),
                    park,
                    actor,
                    Map.of(
                        "workOrderId",
                        Long.toString(id),
                        "sourceType",
                        "INCIDENT",
                        "incidentId",
                        order.get("incident_id"),
                        "status",
                        "ASSIGNED",
                        "aggregateVersion",
                        c.expectedVersion() + 1));
                return Map.of(
                    "workOrderId",
                    Long.toString(id),
                    "status",
                    "ASSIGNED",
                    "version",
                    c.expectedVersion() + 1);
              });
      operations.notifyResult(reserved);
      return result;
    } catch (RuntimeException failure) {
      return operations.afterFailure(operation, failure);
    }
  }

  private JsonNode mutate(
      AuthorizationContext actor,
      String key,
      long id,
      String type,
      String permission,
      Object command,
      long expected,
      Function<Map<String, Object>, String> mutation,
      String reason) {
    var before = require(actor, permission, id);
    String park = before.get("campus_id").toString();
    store.db.update("INSERT IGNORE INTO command_lock(scope_id) VALUES(?)", park);
    var result =
        commands.execute(
            park,
            key,
            actor.userId(),
            type,
            Map.of("id", id, "body", command),
            () -> {
              var o = store.db.queryForMap("SELECT * FROM work_order WHERE id=? FOR UPDATE", id);
              if (o.get("source_type").equals("LEGACY_ALARM"))
                throw ApiProblem.conflict("历史工单只读，请创建新工单");
              Views.version(o, expected);
              String next = mutation.apply(o);
              store.db.update(
                  "UPDATE work_order SET version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE"
                      + " id=?",
                  id);
              history(
                  actor, id, expected + 1, type, o.get("status").toString(), next, reason, null);
              events.append(
                  type,
                  Long.toString(id),
                  park,
                  actor,
                  Map.of(
                      "workOrderId",
                      Long.toString(id),
                      "sourceType",
                      o.get("source_type"),
                      "incidentId",
                      Objects.toString(o.get("incident_id"), ""),
                      "status",
                      next,
                      "aggregateVersion",
                      expected + 1));
              return Map.of(
                  "workOrderId", Long.toString(id), "status", next, "version", expected + 1);
            });
    return result;
  }

  public Map<String, Object> maintenance(
      AuthorizationContext actor, String device, int page, int size) {
    return maintenance(actor, device, null, null, page, size);
  }

  public Map<String, Object> maintenance(
      AuthorizationContext actor, String device, Instant from, Instant to, int page, int size) {
    if (from != null && to != null && !from.isBefore(to)) throw ApiProblem.invalid("时间范围无效");
    devices.requireVisibleDevices(actor, "workorder.read", List.of(device));
    var result =
        new LinkedHashMap<>(
            list(actor, device, null, null, null, null, null, from, to, page, size));
    @SuppressWarnings("unchecked")
    var items = (List<Map<String, Object>>) result.get("items");
    result.put(
        "items",
        items.stream()
            .map(item -> detail(actor, Long.parseLong(item.get("workOrderId").toString())))
            .toList());
    return result;
  }

  private void history(
      AuthorizationContext actor,
      long id,
      long version,
      String action,
      String from,
      String to,
      String reason,
      String requestId) {
    String correlation = null;
    var attributes =
        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    if (attributes
        instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
      var req = servlet.getRequest();
      if (requestId == null)
        requestId = Objects.toString(req.getHeader("X-Request-Id"), req.getRequestId());
      correlation = Objects.toString(req.getHeader("X-Correlation-Id"), requestId);
    }
    store.db.update(
        "INSERT INTO"
            + " work_order_history(order_id,order_version,action,from_status,to_status,actor,remark,request_id,correlation_id)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        id,
        version,
        action.length() > 32 ? action.substring(0, 32) : action,
        from,
        to,
        actor.userId(),
        reason,
        requestId,
        correlation);
  }

  private static void allowed(Map<String, Object> order, Set<String> states) {
    if (!states.contains(order.get("status"))) throw ApiProblem.conflict("当前工单状态不允许此操作");
  }

  private static void repairer(AuthorizationContext actor, Map<String, Object> order) {
    if (!actor.userId().equals(order.get("assigned_repairer_id"))) throw ApiProblem.forbidden();
  }

  private void validateEvidence(
      AuthorizationContext actor, long id, List<String> references, String permission) {
    require(actor, permission, id);
    if (references == null || references.isEmpty()) return;
    if (new HashSet<>(references).size() != references.size()) throw ApiProblem.invalid("证据引用不能重复");
    var targets = deviceIds(id);
    for (String reference : references) {
      boolean resolved =
          devices instanceof WorkDevices nativeDevices
              && nativeDevices.evidence(reference, targets);
      if (!resolved) resolved = gate.evidence(reference, targets);
      if (!resolved) throw ApiProblem.missing();
    }
  }

  private String serialize(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }
}
