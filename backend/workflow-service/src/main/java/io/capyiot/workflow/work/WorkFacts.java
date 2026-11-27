package io.capyiot.workflow.work;

import io.capyiot.contract.internal.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Service-only authority reads. No user/device/fault client is injected. */
@Component
final class WorkFacts {
  private final WorkOrderStore store;

  WorkFacts(WorkOrderStore store) {
    this.store = store;
  }

  record Cursor(String incidentId, long orderAfter, String operationAfter) {}

  WorkOrderApi.IncidentOrderState incident(String incident, String cursor) {
    return store.read.execute(
        s -> {
          var start = decode(incident, cursor);
          var rows =
              store.db.queryForList(
                  "SELECT id,work_order_no,status,version FROM work_order WHERE incident_id=? AND"
                      + " id>? ORDER BY id LIMIT 101",
                  incident,
                  start.orderAfter());
          var pending =
              store.db.queryForList(
                  "SELECT operation_id FROM work_order_operation WHERE incident_id=? AND"
                      + " operation_id>? AND state"
                      + " IN('PREPARED','RESERVED','RECONCILIATION_REQUIRED') ORDER BY operation_id"
                      + " LIMIT 101",
                  String.class,
                  incident,
                  start.operationAfter());
          var orders =
              rows.stream()
                  .limit(100)
                  .map(
                      r ->
                          new WorkOrderApi.OrderRef(
                              r.get("id").toString(),
                              r.get("work_order_no").toString(),
                              WorkOrderApi.OrderStatus.valueOf(r.get("status").toString()),
                              ((Number) r.get("version")).longValue()))
                  .toList();
          var operations = pending.stream().limit(100).toList();
          String next = null;
          if (rows.size() > 100 || pending.size() > 100) {
            long after =
                orders.isEmpty()
                    ? start.orderAfter()
                    : Long.parseLong(orders.getLast().workOrderId());
            String op = operations.isEmpty() ? start.operationAfter() : operations.getLast();
            next = encode(new Cursor(incident, after, op));
          }
          return new WorkOrderApi.IncidentOrderState(
              incident, orders, operations, true, Instant.now(), next);
        });
  }

  WorkOrderApi.AssignmentResult assigned(WorkOrderApi.AssignmentRequest request) {
    String column =
        request.relation() == PlatformApi.AssignmentRelation.REPAIRER
            ? "assigned_repairer_id"
            : "designated_acceptor_id";
    var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(store.db);
    var ids =
        named.queryForList(
            "SELECT DISTINCT i.device_id FROM work_order_device_item i JOIN work_order w ON"
                + " w.id=i.work_order_id WHERE w.campus_id=:park AND w."
                + column
                + "=:user AND i.device_id IN (:ids) ORDER BY i.device_id",
            Map.of("park", request.parkId(), "user", request.userId(), "ids", request.deviceIds()),
            String.class);
    return new WorkOrderApi.AssignmentResult(ids, Instant.now());
  }

  private Cursor decode(String incident, String value) {
    if (value == null) return new Cursor(incident, 0, "");
    try {
      if (value.length() > 256) throw new IllegalArgumentException();
      String raw =
          new String(Base64.getUrlDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
      var fields = raw.split("\\|", -1);
      if (fields.length != 3
          || !fields[0].equals(incident)
          || !fields[1].matches("[0-9]{1,19}")
          || !fields[2].matches("[A-Za-z0-9_-]{0,64}")) throw new IllegalArgumentException();
      long order = Long.parseLong(fields[1]);
      if (order < 0) throw new IllegalArgumentException();
      return new Cursor(incident, order, fields[2]);
    } catch (RuntimeException invalid) {
      throw ApiProblem.invalid("工单权威分页游标无效");
    }
  }

  private String encode(Cursor cursor) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            (cursor.incidentId() + "|" + cursor.orderAfter() + "|" + cursor.operationAfter())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
