package io.capyiot.workflow.work;

import com.fasterxml.jackson.databind.*;
import io.capyiot.contract.internal.*;
import io.capyiot.service.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Only local transactions decide COMMITTED/ABORTED. No network request holds an operation row lock.
 */
@Service
final class WorkOperations {
  record Intent(
      String id,
      String park,
      String key,
      String actor,
      String incident,
      WorkOrderApi.Operation kind,
      String commandType,
      String fingerprint,
      String protocolHash,
      String reservation,
      String state) {
    WorkOrderApi.CommandBinding binding() {
      return new WorkOrderApi.CommandBinding(id, incident, kind, actor, key, protocolHash);
    }
  }

  static final ThreadLocal<Intent> ACTIVE = new ThreadLocal<>();
  private final WorkOrderStore store;
  private final WorkFaults faults;
  private final ObjectMapper json;
  private final CommandJournal commands;
  private final io.micrometer.core.instrument.MeterRegistry meters;

  WorkOperations(
      WorkOrderStore store,
      WorkFaults faults,
      ObjectMapper json,
      io.micrometer.core.instrument.MeterRegistry meters,
      @org.springframework.beans.factory.annotation.Value(
              "${capyiot.workorders.command-fingerprint-key}")
          String key) {
    this.store = store;
    this.faults = faults;
    this.json = json;
    this.meters = meters;
    commands = new CommandJournal(store.db, store.tx, json, key, true);
  }

  private Intent intent(Map<String, Object> r) {
    return new Intent(
        r.get("operation_id").toString(),
        r.get("campus_id").toString(),
        r.get("idempotency_key").toString(),
        r.get("actor_user_id").toString(),
        (String) r.get("incident_id"),
        WorkOrderApi.Operation.valueOf(r.get("operation_type").toString()),
        r.get("command_type").toString(),
        r.get("request_hash").toString(),
        r.get("protocol_hash").toString(),
        (String) r.get("reservation_id"),
        r.get("state").toString());
  }

  Map<String, Object> row(String id) {
    var rows = store.db.queryForList("SELECT * FROM work_order_operation WHERE operation_id=?", id);
    if (rows.isEmpty()) throw ApiProblem.missing();
    return rows.getFirst();
  }

  private Map<String, Object> lock(Intent i) {
    store.db.update(
        "INSERT INTO command_lock(scope_id) VALUES(?) ON DUPLICATE KEY UPDATE"
            + " scope_id=VALUES(scope_id)",
        i.park());
    store.db.queryForObject(
        "SELECT scope_id FROM command_lock WHERE scope_id=? FOR UPDATE", String.class, i.park());
    return store.db.queryForMap(
        "SELECT * FROM work_order_operation WHERE operation_id=? FOR UPDATE", i.id());
  }

  Intent prepare(
      AuthorizationContext actor,
      String park,
      String key,
      String type,
      Object command,
      String incident,
      Long target,
      String request,
      java.util.List<String> devices) {
    if (key == null || !key.matches("[\\x21-\\x7e]{1,128}")) throw ApiProblem.invalid("幂等键无效");
    String hash =
        commands.fingerprint(Map.of("actor", actor.userId(), "type", type, "command", command));
    var delegation = delegation();
    String protocolHash = delegation == null ? hash : delegation.requestHash();
    return store.tx.execute(
        s -> {
          store.db.update(
              "INSERT INTO command_lock(scope_id) VALUES(?) ON DUPLICATE KEY UPDATE"
                  + " scope_id=VALUES(scope_id)",
              park);
          store.db.queryForObject(
              "SELECT scope_id FROM command_lock WHERE scope_id=? FOR UPDATE", String.class, park);
          var prior =
              store.db.queryForList(
                  "SELECT * FROM work_order_operation WHERE campus_id=? AND idempotency_key=? FOR"
                      + " UPDATE",
                  park,
                  key);
          if (!prior.isEmpty()) {
            var r = prior.getFirst();
            verify(r, actor, hash, type, delegation);
            return intent(r);
          }
          var receipt =
              store.db.queryForList(
                  "SELECT command_type FROM work_order_request WHERE campus_id=? AND"
                      + " idempotency_key=?",
                  park,
                  key);
          if (!receipt.isEmpty()) throw ApiProblem.conflict("原命令回执未能转换为操作记录，请保留原幂等键核查");
          String id = UUID.randomUUID().toString();
          store.db.update(
              "INSERT INTO"
                  + " work_order_operation(operation_id,campus_id,idempotency_key,actor_user_id,operation_type,command_type,incident_id,target_order_id,request_hash,protocol_hash,request_id,agent_confirmation_id,agent_draft_id,agent_draft_revision,agent_request_hash)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
              id,
              park,
              key,
              actor.userId(),
              type.equals("WorkOrderCreated") ? "CREATE" : "REOPEN",
              type,
              incident,
              target,
              hash,
              protocolHash,
              request,
              delegation == null ? null : delegation.confirmationId(),
              delegation == null ? null : delegation.draftId(),
              delegation == null ? null : delegation.draftRevision(),
              delegation == null ? null : delegation.requestHash());
          for (String device : new java.util.HashSet<>(devices))
            store.db.update(
                "INSERT INTO work_order_operation_device(operation_id,device_id) VALUES(?,?)",
                id,
                device);
          var prepared = row(id);
          operationEvent(prepared, "WorkOrderOperationPrepared", "INTENT_PERSISTED");
          return intent(prepared);
        });
  }

  private AgentDelegation.Binding delegation() {
    var a = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    return a instanceof org.springframework.web.context.request.ServletRequestAttributes r
            && r.getRequest().getAttribute("capyiot.delegation")
                instanceof AgentDelegation.Binding b
        ? b
        : null;
  }

  private void verify(
      Map<String, Object> r,
      AuthorizationContext actor,
      String hash,
      String type,
      AgentDelegation.Binding b) {
    if (!actor.userId().equals(r.get("actor_user_id"))
        || !hash.equals(r.get("request_hash"))
        || !type.equals(r.get("command_type")))
      throw new ApiProblem(409, "IDEMPOTENCY_CONFLICT", "幂等键已绑定其他命令");
    if (b == null && r.get("agent_confirmation_id") != null
        || b != null
            && (!b.confirmationId().equals(r.get("agent_confirmation_id"))
                || !b.draftId().equals(r.get("agent_draft_id"))
                || !(r.get("agent_draft_revision") instanceof Number rev)
                || rev.longValue() != b.draftRevision()
                || !b.requestHash().equals(r.get("agent_request_hash"))))
      throw AgentDelegation.denied();
  }

  Optional<Intent> existing(AuthorizationContext actor, String key, String type, Object command) {
    var rows =
        store.db.queryForList(
            "SELECT * FROM work_order_operation WHERE actor_user_id=? AND idempotency_key=?",
            actor.userId(),
            key);
    if (rows.isEmpty()) return Optional.empty();
    if (rows.size() != 1) throw ApiProblem.conflict("幂等键在多个园区存在，请按操作标识查询");
    var r = rows.getFirst();
    verify(
        r,
        actor,
        commands.fingerprint(Map.of("actor", actor.userId(), "type", type, "command", command)),
        type,
        delegation());
    return Optional.of(intent(r));
  }

  Optional<JsonNode> committed(Intent i) {
    var r = row(i.id());
    if ("COMMITTED".equals(r.get("state")))
      return Optional.of(parse(r.get("result_snapshot").toString()));
    if ("ABORTED".equals(r.get("state")))
      throw new ApiProblem(
          409, "WORKORDER_OPERATION_ABORTED", "该操作已终止，请查询原操作结果", Map.of("operationId", i.id()));
    return Optional.empty();
  }

  Intent reserve(Intent i) {
    committed(i);
    if (i.incident() == null) return i;
    var r = faults.reserve(i.binding());
    return store.tx.execute(
        s -> {
          var current = lock(i);
          if ("ABORTED".equals(current.get("state")))
            throw new ApiProblem(409, "WORKORDER_OPERATION_ABORTED", "该操作已终止");
          if ("COMMITTED".equals(current.get("state"))) return intent(current);
          if (current.get("reservation_id") != null
              && !r.reservationId().equals(current.get("reservation_id")))
            throw ApiProblem.conflict("预约结果绑定冲突");
          store.db.update(
              "UPDATE work_order_operation SET"
                  + " reservation_id=?,state='RESERVED',updated_at=CURRENT_TIMESTAMP(3),last_error=NULL"
                  + " WHERE operation_id=?",
              r.reservationId(),
              i.id());
          return intent(row(i.id()));
        });
  }

  JsonNode execute(Intent i, AuthorizationContext actor, Object command, Supplier<?> mutation) {
    return store.tx.execute(
        s -> {
          var current = lock(i);
          if ("COMMITTED".equals(current.get("state")))
            return parse(current.get("result_snapshot").toString());
          if ("ABORTED".equals(current.get("state")))
            throw new ApiProblem(409, "WORKORDER_OPERATION_ABORTED", "操作已终止，禁止迟到提交");
          if (i.incident() != null
              && (!"RESERVED".equals(current.get("state"))
                  || current.get("reservation_id") == null))
            throw ApiProblem.conflict("工单尚未取得故障预约");
          Intent active = intent(current);
          ACTIVE.set(active);
          try {
            var result =
                commands.execute(
                    i.park(), i.key(), actor.userId(), i.commandType(), command, mutation);
            long order = Long.parseLong(result.path("workOrderId").asText());
            long version = result.path("version").asLong();
            JsonNode snapshot = result.deepCopy();
            if (snapshot instanceof com.fasterxml.jackson.databind.node.ObjectNode object
                && i.kind() == WorkOrderApi.Operation.CREATE) object.put("created", false);
            store.db.update(
                "UPDATE work_order_operation SET"
                    + " state='COMMITTED',work_order_id=?,work_order_version=?,result_snapshot=?,notification_status=?,last_error=NULL,updated_at=CURRENT_TIMESTAMP(3),next_attempt_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE operation_id=?",
                order,
                version,
                snapshot.toString(),
                i.incident() == null ? "ACKNOWLEDGED" : "PENDING",
                i.id());
            store.db.update(
                "UPDATE work_order_request SET operation_id=? WHERE campus_id=? AND"
                    + " idempotency_key=?",
                i.id(),
                i.park(),
                i.key());
            return result;
          } finally {
            ACTIVE.remove();
          }
        });
  }

  void abort(Intent i, String reason) {
    store.tx.executeWithoutResult(
        s -> {
          var current = lock(i);
          if (Set.of("COMMITTED", "ABORTED").contains(current.get("state"))) return;
          store.db.update(
              "UPDATE work_order_operation SET"
                  + " state='ABORTED',last_error=?,notification_status=?,next_attempt_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3)"
                  + " WHERE operation_id=?",
              reason,
              i.incident() == null ? "ACKNOWLEDGED" : "PENDING",
              i.id());
          operationEvent(current, "WorkOrderOperationAborted", reason);
        });
  }

  void uncertain(Intent i, String reason) {
    store.tx.executeWithoutResult(
        status -> {
          var current = lock(i);
          int changed =
              store.db.update(
                  "UPDATE work_order_operation SET"
                      + " state='RECONCILIATION_REQUIRED',last_error=?,updated_at=CURRENT_TIMESTAMP(3)"
                      + " WHERE operation_id=? AND state"
                      + " IN('PREPARED','RESERVED','RECONCILIATION_REQUIRED')",
                  reason,
                  i.id());
          if (changed > 0 && !"RECONCILIATION_REQUIRED".equals(current.get("state")))
            operationEvent(current, "WorkOrderOperationUncertain", reason);
        });
  }

  JsonNode afterFailure(Intent i, RuntimeException failure) {
    try {
      var result = committed(i);
      if (result.isPresent()) return result.get();
    } catch (org.springframework.dao.DataAccessException unavailable) {
      throw pending(i);
    }
    RuntimeException outcome = failed(i, failure);
    try {
      var current = row(i.id());
      if ("COMMITTED".equals(current.get("state")))
        return parse(current.get("result_snapshot").toString());
    } catch (org.springframework.dao.DataAccessException unavailable) {
      throw pending(i);
    }
    throw outcome;
  }

  RuntimeException failed(Intent i, RuntimeException failure) {
    try {
      boolean rejected =
          failure instanceof ApiProblem p && p.status() < 500
              || failure instanceof ServiceFailure f
                  && f.outcome() == ServiceFailure.Outcome.REJECTED
                  && f.status() < 500
                  && f.status() != 429;
      if (rejected)
        abort(i, failure instanceof ApiProblem p ? p.code() : ((ServiceFailure) failure).code());
      else uncertain(i, failure instanceof ServiceFailure f ? f.code() : "COMMIT_UNCONFIRMED");
      var actual = row(i.id());
      if ("COMMITTED".equals(actual.get("state"))) return pending(i);
      if (rejected)
        return failure instanceof ApiProblem p
            ? new ApiProblem(p.status(), p.code(), p.getMessage(), Map.of("operationId", i.id()))
            : new ApiProblem(
                409,
                "WORKORDER_OPERATION_ABORTED",
                "该操作被权威服务拒绝，已阻止迟到提交",
                Map.of("operationId", i.id()));
      return pending(i);
    } catch (RuntimeException unconfirmed) {
      return pending(i);
    }
  }

  private ApiProblem pending(Intent i) {
    return new ApiProblem(
        503,
        "WORKORDER_OPERATION_PENDING",
        "操作结果待核查，请保留原幂等键并查询操作状态",
        Map.of("operationId", i.id()));
  }

  void notifyResult(Intent i) {
    try {
      var r = row(i.id());
      if (!Set.of("COMMITTED", "ABORTED").contains(r.get("state"))
          || "ACKNOWLEDGED".equals(r.get("notification_status"))) return;
      if (i.incident() != null) {
        String reservation = (String) r.get("reservation_id");
        if (reservation == null) {
          var remote = faults.query(i.id());
          if (!i.binding().equals(remote.binding())) throw ApiProblem.conflict("预约回查绑定冲突");
          reservation = remote.reservationId();
          final String found = reservation;
          store.tx.executeWithoutResult(
              s -> {
                var current = lock(i);
                if (current.get("reservation_id") != null
                    && !found.equals(current.get("reservation_id")))
                  throw ApiProblem.conflict("预约标识已改变");
                store.db.update(
                    "UPDATE work_order_operation SET reservation_id=? WHERE operation_id=?",
                    found,
                    i.id());
              });
        }
        if ("COMMITTED".equals(r.get("state")))
          faults.commit(
              reservation,
              new FaultApi.Commit(
                  i.binding(),
                  r.get("work_order_id").toString(),
                  ((Number) r.get("work_order_version")).longValue()));
        else faults.abort(reservation, new FaultApi.AbortAcknowledgement(i.binding(), "工单权威操作已终止"));
      }
      store.db.update(
          "UPDATE work_order_operation SET"
              + " notification_status='ACKNOWLEDGED',last_error=NULL,updated_at=CURRENT_TIMESTAMP(3)"
              + " WHERE operation_id=? AND state IN('COMMITTED','ABORTED')",
          i.id());
    } catch (RuntimeException failure) {
      meters.counter("capyiot.workorder.notification.failures").increment();
      try {
        store.db.update(
            "UPDATE work_order_operation SET"
                + " notification_attempts=notification_attempts+1,last_error=?,next_attempt_at=TIMESTAMPADD(SECOND,LEAST(300,5*POW(2,LEAST(notification_attempts,6))),CURRENT_TIMESTAMP(3)),updated_at=CURRENT_TIMESTAMP(3)"
                + " WHERE operation_id=? AND notification_status='PENDING'",
            failure instanceof ServiceFailure f ? f.code() : "NOTIFICATION_PENDING",
            i.id());
      } catch (org.springframework.dao.DataAccessException unavailable) {
        /* Committed business result remains successful. */
      }
    }
  }

  private void operationEvent(Map<String, Object> r, String type, String reason) {
    String id = UUID.randomUUID().toString();
    String operation = r.get("operation_id").toString();
    var ids =
        store.db.queryForList(
            "SELECT device_id FROM work_order_operation_device WHERE operation_id=?",
            String.class,
            operation);
    var event = new LinkedHashMap<String, Object>();
    event.put("eventId", id);
    event.put("eventType", type);
    event.put("schemaVersion", 1);
    event.put("sourceContext", "workorder");
    event.put("producer", "workflow-service");
    event.put("aggregateType", "WorkOrderOperation");
    event.put("aggregateId", operation);
    event.put("aggregateVersion", 0);
    event.put("parkId", r.get("campus_id"));
    event.put("deviceIds", ids);
    event.put("areaIds", List.of());
    event.put(
        "actor",
        Map.of("serviceId", "workflow-service", "userId", r.get("actor_user_id").toString()));
    event.put("occurredAt", Instant.now());
    event.put("body", Map.of("operationId", operation, "reason", reason));
    if (r.get("request_id") != null) event.put("requestId", r.get("request_id"));
    event.put("traceId", UUID.randomUUID().toString());
    try {
      store.db.update(
          "INSERT INTO"
              + " outbox_event(campus_id,aggregate_type,aggregate_id,event_type,event_id,topic,message_key,payload_json)"
              + " VALUES(?,'WorkOrderOperation',?,?,?,'capyiot.domain.workorder.v1',?,?)",
          r.get("campus_id"),
          operation,
          type,
          id,
          operation,
          json.writeValueAsString(event));
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  void recover() {
    for (var r :
        store.db.queryForList(
            "SELECT * FROM work_order_operation WHERE state"
                + " IN('PREPARED','RESERVED','RECONCILIATION_REQUIRED') AND"
                + " updated_at<TIMESTAMPADD(SECOND,-120,CURRENT_TIMESTAMP(3)) ORDER BY"
                + " updated_at,operation_id LIMIT 20")) abort(intent(r), "STALE_INTENT_ABORTED");
    for (var r :
        store.db.queryForList(
            "SELECT * FROM work_order_operation WHERE state IN('COMMITTED','ABORTED') AND"
                + " notification_status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(3) ORDER"
                + " BY next_attempt_at,operation_id LIMIT 20")) {
      RequestCalls.budget().check();
      notifyResult(intent(r));
    }
  }

  WorkOrderApi.OperationResult result(String id) {
    var rows = store.db.queryForList("SELECT * FROM work_order_operation WHERE operation_id=?", id);
    if (rows.isEmpty())
      return new WorkOrderApi.OperationResult(
          id, WorkOrderApi.OperationState.UNKNOWN, null, null, null, null, Instant.now());
    var r = rows.getFirst();
    var i = intent(r);
    return new WorkOrderApi.OperationResult(
        id,
        WorkOrderApi.OperationState.valueOf(i.state()),
        i.binding(),
        i.reservation(),
        Objects.toString(r.get("work_order_id"), null),
        r.get("work_order_version") == null
            ? null
            : ((Number) r.get("work_order_version")).longValue(),
        Instant.now());
  }

  WorkOrderApi.OperationResult legacy(String reservation) {
    var rows =
        store.db.queryForList(
            "SELECT operation_id FROM work_order_operation WHERE reservation_id=?",
            String.class,
            reservation);
    if (rows.size() != 1) throw ApiProblem.missing();
    return result(rows.getFirst());
  }

  private JsonNode parse(String value) {
    try {
      return json.readTree(value);
    } catch (Exception error) {
      throw new IllegalStateException("Invalid operation receipt");
    }
  }
}
