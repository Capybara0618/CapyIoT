package io.capyiot.alarm.fault;

import io.micrometer.core.instrument.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class FaultMetrics {
  private final FaultStore store;
  private final Map<String, AtomicReference<Double>> values = new LinkedHashMap<>();
  private final Counter failures;

  FaultMetrics(FaultStore store, MeterRegistry meters) {
    this.store = store;
    failures = meters.counter("capyiot.fault.metrics.failures");
    for (String name :
        List.of(
            "reservations.pending",
            "reservations.oldest.seconds",
            "reviews.pending",
            "messages.blocked",
            "gates.closing",
            "outbox.oldest.seconds")) {
      var value = new AtomicReference<>(Double.NaN);
      values.put(name, value);
      Gauge.builder("capyiot.fault." + name, value, v -> v.get()).register(meters);
    }
  }

  @Scheduled(fixedDelay = 10000)
  void refresh() {
    try {
      set(
          "reservations.pending",
          "SELECT COUNT(*) FROM incident_work_order_reservation WHERE status"
              + " IN('RESERVED','RECONCILIATION_REQUIRED')");
      set(
          "reservations.oldest.seconds",
          "SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,CURRENT_TIMESTAMP(3))),0) FROM"
              + " incident_work_order_reservation WHERE status"
              + " IN('RESERVED','RECONCILIATION_REQUIRED')");
      set("reviews.pending", "SELECT COUNT(*) FROM fault_pending_review");
      set(
          "messages.blocked",
          "SELECT COUNT(*) FROM fault_consumer_blocker WHERE resolved_at IS NULL");
      set(
          "gates.closing",
          "SELECT COUNT(*) FROM incident_work_order_gate WHERE gate_status='CLOSING'");
      set(
          "outbox.oldest.seconds",
          "SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,CURRENT_TIMESTAMP(3))),0) FROM"
              + " outbox_event WHERE status<>'SENT'");
    } catch (org.springframework.dao.DataAccessException unavailable) {
      values.values().forEach(v -> v.set(Double.NaN));
      failures.increment();
    }
  }

  private void set(String name, String sql) {
    values.get(name).set(store.db.queryForObject(sql, Double.class));
  }
}
