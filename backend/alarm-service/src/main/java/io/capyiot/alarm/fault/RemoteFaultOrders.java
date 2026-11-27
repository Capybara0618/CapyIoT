package io.capyiot.alarm.fault;

import io.capyiot.contract.internal.*;
import io.capyiot.service.*;
import java.time.Instant;
import java.util.*;

final class RemoteFaultOrders implements FaultOrders, WorkOrderQueryPort {
  private final InternalHttpClient client;

  RemoteFaultOrders(InternalHttpClient client) {
    this.client = client;
  }

  public WorkOrderApi.OperationResult operation(String id) {
    FaultDevices.outside();
    var value =
        client.call(
            InternalEndpoint.OPERATION_QUERY,
            null,
            null,
            WorkOrderApi.OperationResult.class,
            RequestCalls.budget(),
            RequestCalls.correlation(),
            id);
    if (!id.equals(value.operationId())) throw invalid();
    return value;
  }

  public FaultOrders.State state(String id) {
    FaultDevices.outside();
    var orders = new LinkedHashMap<String, WorkOrderApi.OrderRef>();
    var pending = new HashSet<String>();
    var cursors = new HashSet<String>();
    String cursor = null;
    int pages = 0;
    Instant checked = Instant.now();
    do {
      if (++pages > 100) throw invalid();
      var page =
          cursor == null
              ? client.call(
                  InternalEndpoint.INCIDENT_ORDER_STATE,
                  null,
                  null,
                  WorkOrderApi.IncidentOrderState.class,
                  RequestCalls.budget(),
                  RequestCalls.correlation(),
                  id)
              : client.call(
                  InternalEndpoint.INCIDENT_ORDER_PAGE,
                  null,
                  new WorkOrderApi.IncidentOrdersPageRequest(cursor),
                  WorkOrderApi.IncidentOrderState.class,
                  RequestCalls.budget(),
                  RequestCalls.correlation(),
                  id);
      if (!id.equals(page.incidentId()) || !page.complete()) throw invalid();
      for (var order : page.orders()) {
        try {
          if (Long.parseLong(order.workOrderId()) < 1) throw invalid();
        } catch (NumberFormatException bad) {
          throw invalid();
        }
        if (orders.putIfAbsent(order.workOrderId(), order) != null) throw invalid();
      }
      pending.addAll(page.pendingOperationIds());
      cursor = page.nextCursor();
      if (cursor != null && !cursors.add(cursor)) throw invalid();
      if (page.checkedAt().isBefore(checked)) checked = page.checkedAt();
    } while (cursor != null);
    return new FaultOrders.State(
        id, List.copyOf(orders.values()), pending.stream().sorted().toList(), true, checked);
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

  public WorkOrderApi.OperationResult legacy(String reservation) {
    FaultDevices.outside();
    var result =
        client.call(
            InternalEndpoint.LEGACY_RESERVATION_OPERATION,
            null,
            null,
            WorkOrderApi.OperationResult.class,
            RequestCalls.budget(),
            RequestCalls.correlation(),
            reservation);
    if (result.state() != WorkOrderApi.OperationState.COMMITTED
        || !reservation.equals(result.reservationId())) throw invalid();
    return result;
  }

  private ServiceFailure invalid() {
    return ServiceFailure.unavailable("WORKORDER_AUTHORITY_INCOMPLETE", ServiceId.WORKFLOW, false);
  }
}
