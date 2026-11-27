package io.capyiot.alarm.fault;

import io.capyiot.contract.internal.WorkOrderApi;

interface FaultOrders {
  record State(
      String incidentId,
      java.util.List<WorkOrderApi.OrderRef> orders,
      java.util.List<String> pendingOperationIds,
      boolean complete,
      java.time.Instant checkedAt) {
    boolean allTerminal() {
      return complete
          && pendingOperationIds.isEmpty()
          && orders.stream().allMatch(o -> o.status().terminal());
    }
  }

  State state(String incident);

  WorkOrderApi.OperationResult operation(String operation);
}
