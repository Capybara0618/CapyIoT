package io.capyiot.alarm.fault;

import com.fasterxml.jackson.databind.*;
import io.capyiot.contract.internal.*;
import io.capyiot.service.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Local locks fence decisions; every authority call happens before or between local transactions.
 */
@Service
final class FaultGate {
  private final FaultStore store;
  private final DeviceQueryPort scope;
  private final FaultDevices devices;
  private final FaultOrders orders;
  private final FaultProgress progress;
  private final CommandJournal commands;
  private final LocalEvents events;
  private final io.micrometer.core.instrument.MeterRegistry meters;

  FaultGate(
      FaultStore store,
      DeviceQueryPort scope,
      FaultDevices devices,
      FaultOrders orders,
      FaultProgress progress,
      ObjectMapper json,
      io.micrometer.core.instrument.MeterRegistry meters,
      @org.springframework.beans.factory.annotation.Value(
              "${capyiot.faults.command-fingerprint-key}")
          String key) {
    this.meters = meters;
    this.store = store;
    this.scope = scope;
    this.devices = devices;
    this.orders = orders;
    this.progress = progress;
    commands = new CommandJournal(store.db, store.tx, json, key);
    events = new LocalEvents(store.db, json, "fault");
  }

  Map<String, Object> incident(String id) {
    var rows = store.db.queryForList("SELECT * FROM incident WHERE incident_id=?", id);
    if (rows.isEmpty()) throw ApiProblem.missing();
    return rows.getFirst();
  }

  List<String> deviceIds(String id) {
    return store.db.queryForList(
        "SELECT DISTINCT a.device_id FROM incident_anomaly_link l JOIN anomaly a ON"
            + " a.anomaly_id=l.anomaly_id WHERE l.incident_id=? AND l.unlinked_at IS NULL AND"
            + " a.device_id IS NOT NULL ORDER BY a.device_id",
        String.class,
        id);
  }

  FaultApi.IncidentRef reference(AuthorizationContext actor, String id, String permission) {
    var i = incident(id);
    var ids = deviceIds(id);
    if (ids.isEmpty()) throw ApiProblem.missing();
    scope.requireVisibleDevices(actor, permission, ids);
    return new FaultApi.IncidentRef(
        id,
        i.get("park_id").toString(),
        i.get("status").toString(),
        i.get("priority").toString(),
        Views.version(i));
  }

  private Map<String, Object> lock(String id) {
    String park = incident(id).get("park_id").toString();
    store.db.update("INSERT IGNORE INTO command_lock(scope_id) VALUES(?)", park);
    store.db.queryForObject(
        "SELECT scope_id FROM command_lock WHERE scope_id=? FOR UPDATE", String.class, park);
    return store.db.queryForMap("SELECT * FROM incident WHERE incident_id=? FOR UPDATE", id);
  }

  private Map<String, Object> row(String reservation) {
    var rows =
        store.db.queryForList(
            "SELECT * FROM incident_work_order_reservation WHERE reservation_id=?"
                + (org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive()
                    ? " FOR UPDATE"
                    : ""),
            reservation);
    if (rows.isEmpty()) throw ApiProblem.missing();
    return rows.getFirst();
  }

  private WorkOrderApi.CommandBinding binding(Map<String, Object> r) {
    if (r.get("operation_id") == null)
      throw new ApiProblem(409, "LEGACY_RESERVATION_UNRESOLVED", "历史预约缺少操作日志绑定，保留待对账");
    return new WorkOrderApi.CommandBinding(
        r.get("operation_id").toString(),
        r.get("incident_id").toString(),
        WorkOrderApi.Operation.valueOf(r.get("operation_type").toString()),
        r.get("actor_user_id").toString(),
        r.get("idempotency_key").toString(),
        r.get("request_hash").toString());
  }

  private void match(Map<String, Object> row, WorkOrderApi.CommandBinding expected) {
    if (!binding(row).equals(expected))
      throw new ApiProblem(409, "IDEMPOTENCY_CONFLICT", "预约命令绑定冲突");
  }

  private FaultApi.Reservation view(Map<String, Object> r) {
    return new FaultApi.Reservation(
        r.get("reservation_id").toString(),
        FaultApi.ReservationState.valueOf(r.get("status").toString()),
        binding(r),
        Objects.toString(r.get("work_order_id"), null),
        Instant.now());
  }

  FaultApi.Reservation reserve(
      AuthorizationContext actor, String id, WorkOrderApi.CommandBinding b) {
    if (!id.equals(b.incidentId())
        || !actor.userId().equals(b.actorUserId())
        || actor.sessionId().startsWith("delegation:")
            && b.operation() != WorkOrderApi.Operation.CREATE) throw ApiProblem.forbidden();
    reference(
        actor,
        id,
        b.operation() == WorkOrderApi.Operation.CREATE ? "workorder.create" : "workorder.reopen");
    var intent = orders.operation(b.operationId());
    if (!b.equals(intent.binding()) || intent.state() == WorkOrderApi.OperationState.UNKNOWN)
      throw unavailable();
    return store.tx.execute(
        s -> {
          var locked = lock(id);
          var prior =
              store.db.queryForList(
                  "SELECT * FROM incident_work_order_reservation WHERE operation_id=? OR"
                      + " (incident_id=? AND idempotency_key=?) FOR UPDATE",
                  b.operationId(),
                  id,
                  b.idempotencyKey());
          if (!prior.isEmpty()) {
            if (prior.size() != 1) throw ApiProblem.conflict("预约绑定冲突");
            match(prior.getFirst(), b);
            return view(prior.getFirst());
          }
          if (intent.state() == WorkOrderApi.OperationState.ABORTED
              || intent.state() == WorkOrderApi.OperationState.COMMITTED)
            throw ApiProblem.conflict("操作已终结，不能取得新预约");
          var i = locked;
          var gate =
              store.db.queryForMap(
                  "SELECT * FROM incident_work_order_gate WHERE incident_id=? FOR UPDATE", id);
          if (!"OPEN".equals(gate.get("gate_status"))
              || !Set.of("OPEN", "PROCESSING", "PENDING_CLOSE").contains(i.get("status"))
              || b.operation() == WorkOrderApi.Operation.CREATE
                  && "PENDING_CLOSE".equals(i.get("status")))
            throw ApiProblem.conflict("故障门禁不允许新建或重开");
          String reservation = UUID.randomUUID().toString();
          store.db.update(
              "INSERT INTO"
                  + " incident_work_order_reservation(reservation_id,incident_id,request_id,idempotency_key,actor_user_id,request_hash,operation_type,operation_id)"
                  + " VALUES(?,?,?,?,?,?,?,?)",
              reservation,
              id,
              b.operationId(),
              b.idempotencyKey(),
              b.actorUserId(),
              b.requestHash(),
              b.operation().name(),
              b.operationId());
          store.db.update(
              "UPDATE incident_work_order_gate SET"
                  + " version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE incident_id=?",
              id);
          if (b.operation() == WorkOrderApi.Operation.REOPEN
              && "PENDING_CLOSE".equals(i.get("status")))
            store.db.update(
                "UPDATE incident SET"
                    + " status='PROCESSING',version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE"
                    + " incident_id=?",
                id);
          progress.pending(i.get("park_id").toString(), Instant.now());
          events.append(
              "IncidentReservationCreated",
              id,
              i.get("park_id").toString(),
              actor,
              Map.of(
                  "operationId",
                  b.operationId(),
                  "reservationId",
                  reservation,
                  "operation",
                  b.operation().name()));
          return view(row(reservation));
        });
  }

  FaultApi.Reservation reservation(String id) {
    return view(row(id));
  }

  void markUncertain(String id) {
    uncertain(row(id), "CORE_RESULT_UNCONFIRMED");
  }

  void legacyCommit(String id, String workOrder) {
    var r = row(id);
    var b = binding(r);
    var actual = orders.operation(b.operationId());
    if (actual.workOrderVersion() == null) throw unavailable();
    commit(id, new FaultApi.Commit(b, workOrder, actual.workOrderVersion()));
  }

  FaultApi.Reservation query(String operation) {
    var rows =
        store.db.queryForList(
            "SELECT * FROM incident_work_order_reservation WHERE operation_id=?", operation);
    if (rows.isEmpty()) throw ApiProblem.missing();
    return view(rows.getFirst());
  }

  FaultApi.Reservation commit(String id, FaultApi.Commit c) {
    var r = row(id);
    match(r, c.binding());
    var authority = orders.operation(c.binding().operationId());
    if (authority.state() != WorkOrderApi.OperationState.COMMITTED
        || !c.binding().equals(authority.binding())
        || !id.equals(authority.reservationId())
        || !c.workOrderId().equals(authority.workOrderId())
        || !Objects.equals(c.workOrderVersion(), authority.workOrderVersion())) throw unavailable();
    long order;
    try {
      order = Long.parseLong(c.workOrderId());
      if (order < 1) throw new NumberFormatException();
    } catch (NumberFormatException invalid) {
      throw ApiProblem.invalid("工单标识无效");
    }
    return store.tx.execute(
        s -> {
          lock(c.binding().incidentId());
          var current = row(id);
          match(current, c.binding());
          if ("RELEASED".equals(current.get("status"))) throw ApiProblem.conflict("已释放的预约不能提交");
          if ("COMMITTED".equals(current.get("status"))) {
            if (!c.workOrderId().equals(Objects.toString(current.get("work_order_id")))
                || current.get("work_order_version") != null
                    && ((Number) current.get("work_order_version")).longValue()
                        != c.workOrderVersion()) throw ApiProblem.conflict("提交工单绑定冲突");
            return view(current);
          }
          store.db.update(
              "UPDATE incident_work_order_reservation SET"
                  + " status='COMMITTED',work_order_id=?,work_order_version=?,version=version+1,last_reconciled_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3),failure_reason=NULL"
                  + " WHERE reservation_id=?",
              order,
              c.workOrderVersion(),
              id);
          store.db.update(
              "UPDATE incident SET"
                  + " status='PROCESSING',version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE"
                  + " incident_id=? AND status IN('OPEN','PENDING_CLOSE')",
              c.binding().incidentId());
          progress.pending(
              incident(c.binding().incidentId()).get("park_id").toString(), Instant.now());
          events.append(
              "IncidentReservationCommitted",
              c.binding().incidentId(),
              incident(c.binding().incidentId()).get("park_id").toString(),
              null,
              Map.of(
                  "operationId",
                  c.binding().operationId(),
                  "reservationId",
                  id,
                  "workOrderId",
                  c.workOrderId()));
          return view(row(id));
        });
  }

  FaultApi.Reservation abort(String id, FaultApi.AbortAcknowledgement c) {
    var r = row(id);
    match(r, c.binding());
    var authority = orders.operation(c.binding().operationId());
    if (!authority.provesAbort(c.binding())
        || authority.reservationId() != null && !id.equals(authority.reservationId()))
      throw unavailable();
    return store.tx.execute(
        s -> {
          lock(c.binding().incidentId());
          var current = row(id);
          match(current, c.binding());
          if ("COMMITTED".equals(current.get("status"))) throw ApiProblem.conflict("已提交的预约不能释放");
          if (!"RELEASED".equals(current.get("status"))) {
            store.db.update(
                "UPDATE incident_work_order_reservation SET"
                    + " status='RELEASED',failure_reason='AUTHORITY_ABORTED',version=version+1,last_reconciled_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE reservation_id=?",
                id);
            progress.pending(
                incident(c.binding().incidentId()).get("park_id").toString(), Instant.now());
            events.append(
                "IncidentReservationReleased",
                c.binding().incidentId(),
                incident(c.binding().incidentId()).get("park_id").toString(),
                null,
                Map.of(
                    "operationId",
                    c.binding().operationId(),
                    "reservationId",
                    id,
                    "reason",
                    "AUTHORITY_ABORTED"));
          }
          return view(row(id));
        });
  }

  void reconcile(String incident) {
    for (var r :
        store.db.queryForList(
            "SELECT * FROM incident_work_order_reservation WHERE incident_id=? AND status"
                + " IN('RESERVED','RECONCILIATION_REQUIRED') AND"
                + " next_reconcile_at<=CURRENT_TIMESTAMP(3) ORDER BY reservation_id LIMIT 20",
            incident)) reconcileRow(r);
  }

  void reconcilePending() {
    for (var r :
        store.db.queryForList(
            "SELECT * FROM incident_work_order_reservation WHERE status"
                + " IN('RESERVED','RECONCILIATION_REQUIRED') AND"
                + " next_reconcile_at<=CURRENT_TIMESTAMP(3) ORDER BY"
                + " next_reconcile_at,reservation_id LIMIT 20")) reconcileRow(r);
  }

  private void reconcileRow(Map<String, Object> r) {
    try {
      if (r.get("operation_id") == null) r = bindLegacy(r);
      var b = binding(r);
      var actual = orders.operation(b.operationId());
      if (actual.state() == WorkOrderApi.OperationState.COMMITTED)
        commit(
            r.get("reservation_id").toString(),
            new FaultApi.Commit(b, actual.workOrderId(), actual.workOrderVersion()));
      else if (actual.provesAbort(b))
        abort(r.get("reservation_id").toString(), new FaultApi.AbortAcknowledgement(b, "权威操作已终止"));
      else uncertain(r, "AUTHORITY_PENDING");
    } catch (RuntimeException error) {
      uncertain(r, error instanceof ServiceFailure f ? f.code() : "RECONCILIATION_REQUIRED");
    }
  }

  private Map<String, Object> bindLegacy(Map<String, Object> old) {
    var actual = orders.legacy(old.get("reservation_id").toString());
    var b = actual.binding();
    if (b == null
        || actual.state() != WorkOrderApi.OperationState.COMMITTED
        || !b.incidentId().equals(old.get("incident_id"))
        || !b.actorUserId().equals(old.get("actor_user_id"))
        || !b.idempotencyKey().equals(old.get("idempotency_key"))
        || !b.requestHash().equals(old.get("request_hash"))
        || !b.operation().name().equals(old.get("operation_type"))
        || !actual.reservationId().equals(old.get("reservation_id"))
        || old.get("work_order_id") != null
            && !old.get("work_order_id").toString().equals(actual.workOrderId()))
      throw ApiProblem.conflict("历史预约缺少可核实的提交证明");
    return store.tx.execute(
        s -> {
          lock(b.incidentId());
          var current = row(old.get("reservation_id").toString());
          if (current.get("operation_id") != null) {
            match(current, b);
            return current;
          }
          store.db.update(
              "UPDATE incident_work_order_reservation SET"
                  + " operation_id=?,updated_at=CURRENT_TIMESTAMP(3) WHERE reservation_id=? AND"
                  + " operation_id IS NULL",
              b.operationId(),
              actual.reservationId());
          return row(actual.reservationId());
        });
  }

  void uncertain(Map<String, Object> r, String code) {
    store.tx.executeWithoutResult(
        s -> {
          lock(r.get("incident_id").toString());
          var prior = row(r.get("reservation_id").toString());
          int changed =
              store.db.update(
                  "UPDATE incident_work_order_reservation SET"
                      + " status='RECONCILIATION_REQUIRED',reconcile_attempts=reconcile_attempts+1,failure_reason=?,last_reconciled_at=CURRENT_TIMESTAMP(3),next_reconcile_at=TIMESTAMPADD(SECOND,LEAST(300,5*POW(2,LEAST(reconcile_attempts,6))),CURRENT_TIMESTAMP(3)),updated_at=CURRENT_TIMESTAMP(3)"
                      + " WHERE reservation_id=? AND status"
                      + " IN('RESERVED','RECONCILIATION_REQUIRED')",
                  code,
                  r.get("reservation_id"));
          if (changed > 0 && "RESERVED".equals(prior.get("status")))
            events.append(
                "IncidentReservationUncertain",
                r.get("incident_id").toString(),
                incident(r.get("incident_id").toString()).get("park_id").toString(),
                null,
                Map.of("reservationId", r.get("reservation_id"), "reason", code));
        });
  }

  record Fence(String incident, String attempt, long version, long incidentVersion) {}

  Fence freeze(String id, long expected) {
    return store.tx.execute(
        s -> {
          var i = lock(id);
          Views.version(i, expected);
          if (!Set.of("OPEN", "PROCESSING", "PENDING_CLOSE").contains(i.get("status")))
            throw ApiProblem.conflict("故障不允许关闭");
          var gate =
              store.db.queryForMap(
                  "SELECT * FROM incident_work_order_gate WHERE incident_id=? FOR UPDATE", id);
          if (!"OPEN".equals(gate.get("gate_status"))) throw ApiProblem.conflict("关闭已在进行中");
          String attempt = UUID.randomUUID().toString();
          long version = Views.version(gate) + 1;
          store.db.update(
              "UPDATE incident_work_order_gate SET"
                  + " gate_status='CLOSING',close_attempt_id=?,closing_at=CURRENT_TIMESTAMP(3),version=?,updated_at=CURRENT_TIMESTAMP(3)"
                  + " WHERE incident_id=?",
              attempt,
              version,
              id);
          events.append(
              "IncidentCloseStarted",
              id,
              i.get("park_id").toString(),
              null,
              Map.of("closeAttemptId", attempt, "gateVersion", version));
          return new Fence(id, attempt, version, expected);
        });
  }

  boolean restore(Fence fence) {
    return store.tx.execute(
        s -> {
          lock(fence.incident());
          boolean restored =
              store.db.update(
                      "UPDATE incident_work_order_gate SET"
                          + " gate_status='OPEN',close_attempt_id=NULL,closing_at=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP(3)"
                          + " WHERE incident_id=? AND gate_status='CLOSING' AND close_attempt_id=?"
                          + " AND version=?",
                      fence.incident(),
                      fence.attempt(),
                      fence.version())
                  == 1;
          if (restored)
            events.append(
                "IncidentCloseCancelled",
                fence.incident(),
                incident(fence.incident()).get("park_id").toString(),
                null,
                Map.of("closeAttemptId", fence.attempt(), "gateVersion", fence.version()));
          return restored;
        });
  }

  JsonNode close(AuthorizationContext actor, String key, String id, FaultModels.Close c) {
    var ref = reference(actor, id, "incident.close");
    var command = Map.of("id", id, "body", c);
    var prior = commands.replay(ref.parkId(), key, actor.userId(), "IncidentClosed", command);
    if (prior.isPresent()) return prior.get();
    var fence = freeze(id, c.expectedVersion());
    try {
      reconcile(id);
      unresolved(id);
      var authority = orders.state(id);
      if (!authority.allTerminal()) throw ApiProblem.conflict("工单或未决操作尚未终结");
      var ids = deviceIds(id);
      if (ids.isEmpty()) throw ApiProblem.conflict("故障缺少设备证据");
      var snapshots = new ArrayList<DeviceApi.RuntimeResult>();
      for (int i = 0; i < ids.size(); i += 100)
        snapshots.add(devices.runtime(ids.subList(i, Math.min(i + 100, ids.size()))));
      return commands.execute(
          ref.parkId(),
          key,
          actor.userId(),
          "IncidentClosed",
          command,
          () -> {
            var current = lock(id);
            Views.version(current, fence.incidentVersion());
            var gate =
                store.db.queryForMap(
                    "SELECT * FROM incident_work_order_gate WHERE incident_id=? FOR UPDATE", id);
            if (!"CLOSING".equals(gate.get("gate_status"))
                || !fence.attempt().equals(gate.get("close_attempt_id"))
                || Views.version(gate) != fence.version()) throw ApiProblem.conflict("关闭操作已失效");
            unresolved(id);
            progress.ready(ref.parkId(), snapshots);
            if (Boolean.TRUE.equals(
                store.db.queryForObject(
                    "SELECT COUNT(*)>0 FROM anomaly a JOIN incident_anomaly_link l ON"
                        + " l.anomaly_id=a.anomaly_id WHERE l.incident_id=? AND l.unlinked_at IS"
                        + " NULL AND a.status='ACTIVE'",
                    Boolean.class,
                    id))) throw ApiProblem.conflict("仍有活动异常");
            if (store.db.update(
                    "UPDATE incident SET"
                        + " status='CLOSED',closed_at=CURRENT_TIMESTAMP(3),closed_by=?,resolved_disposition=?,primary_correlation_key=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP(3)"
                        + " WHERE incident_id=? AND version=?",
                    actor.userId(),
                    c.resolvedDisposition(),
                    id,
                    fence.incidentVersion())
                != 1) throw ApiProblem.conflict("故障版本已改变");
            if (store.db.update(
                    "UPDATE incident_work_order_gate SET"
                        + " gate_status='CLOSED',terminal_reason=?,version=version+1,updated_at=CURRENT_TIMESTAMP(3)"
                        + " WHERE incident_id=? AND gate_status='CLOSING' AND close_attempt_id=?"
                        + " AND version=?",
                    c.reason(),
                    id,
                    fence.attempt(),
                    fence.version())
                != 1) throw ApiProblem.conflict("关闭操作已失效");
            events.append(
                "IncidentClosed",
                id,
                ref.parkId(),
                actor,
                Map.of(
                    "reason",
                    c.reason(),
                    "resolvedDisposition",
                    c.resolvedDisposition(),
                    "closeAttemptId",
                    fence.attempt(),
                    "gateVersion",
                    fence.version()));
            return Map.of("incidentId", id, "status", "CLOSED", "version", c.expectedVersion() + 1);
          });
    } catch (RuntimeException failure) {
      meters
          .counter(
              "capyiot.fault.close.failures",
              "reason",
              failure instanceof ApiProblem p
                  ? p.code()
                  : failure instanceof ServiceFailure f ? f.code() : "CLOSE_FAILED")
          .increment();
      restore(fence);
      throw failure;
    }
  }

  private void unresolved(String id) {
    if (Boolean.TRUE.equals(
        store.db.queryForObject(
            "SELECT COUNT(*)>0 FROM incident_work_order_reservation WHERE incident_id=? AND status"
                + " IN('RESERVED','RECONCILIATION_REQUIRED')",
            Boolean.class,
            id))) throw ApiProblem.conflict("仍有未决预约");
  }

  void recoverClosingGates() {
    for (var row :
        store.db.queryForList(
            "SELECT g.* FROM incident_work_order_gate g JOIN incident i ON"
                + " i.incident_id=g.incident_id WHERE g.gate_status='CLOSING' AND"
                + " g.closing_at<TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP(3)) AND i.status NOT"
                + " IN('CLOSED','REJECTED') ORDER BY g.closing_at LIMIT 20")) {
      var fence =
          new Fence(
              row.get("incident_id").toString(),
              row.get("close_attempt_id").toString(),
              Views.version(row),
              0);
      restore(fence);
    }
  }

  private ServiceFailure unavailable() {
    return ServiceFailure.unavailable("WORKORDER_AUTHORITY_UNCONFIRMED", ServiceId.WORKFLOW, false);
  }
}
