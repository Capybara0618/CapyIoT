package io.capyiot.workflow.work;

import io.micrometer.core.instrument.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class WorkMetrics {
  private final WorkOrderStore store;
  private final Map<String, AtomicReference<Double>> values = new LinkedHashMap<>();

  WorkMetrics(WorkOrderStore store, MeterRegistry meters) {
    this.store = store;
    for (String name :
        List.of(
            "operations.pending",
            "operations.oldest.seconds",
            "notifications.pending",
            "outbox.oldest.seconds")) {
      var value = new AtomicReference<>(Double.NaN);
      values.put(name, value);
      Gauge.builder("capyiot.workorder." + name, value, v -> v.get()).register(meters);
    }
  }

  @Scheduled(fixedDelay = 10000)
  void tick() {
    try {
      set(
          "operations.pending",
          "SELECT COUNT(*) FROM work_order_operation WHERE state"
              + " IN('PREPARED','RESERVED','RECONCILIATION_REQUIRED')");
      set(
          "operations.oldest.seconds",
          "SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,CURRENT_TIMESTAMP(3))),0) FROM"
              + " work_order_operation WHERE state"
              + " IN('PREPARED','RESERVED','RECONCILIATION_REQUIRED')");
      set(
          "notifications.pending",
          "SELECT COUNT(*) FROM work_order_operation WHERE state IN('COMMITTED','ABORTED') AND"
              + " notification_status='PENDING'");
      set(
          "outbox.oldest.seconds",
          "SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,CURRENT_TIMESTAMP(3))),0) FROM"
              + " outbox_event WHERE status<>'SENT'");
    } catch (org.springframework.dao.DataAccessException unavailable) {
      values.values().forEach(v -> v.set(Double.NaN));
    }
  }

  private void set(String name, String sql) {
    values.get(name).set(store.db.queryForObject(sql, Double.class));
  }
}
