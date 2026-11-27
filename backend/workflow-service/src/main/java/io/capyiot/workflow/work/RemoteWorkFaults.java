package io.capyiot.workflow.work;

import io.capyiot.contract.internal.*;
import io.capyiot.service.*;

final class RemoteWorkFaults implements WorkFaults {
  private final InternalHttpClient client;

  RemoteWorkFaults(InternalHttpClient client) {
    this.client = client;
  }

  private <T> T call(
      InternalEndpoint op, boolean actor, Object body, Class<T> result, String... ids) {
    WorkDevices.outside();
    return client.call(
        op,
        actor ? RequestCalls.actor() : null,
        body,
        result,
        RequestCalls.budget(),
        RequestCalls.correlation(),
        ids);
  }

  public FaultApi.IncidentRef reference(String id) {
    var ref = call(InternalEndpoint.INCIDENT_REFERENCE, true, null, FaultApi.IncidentRef.class, id);
    if (!id.equals(ref.incidentId())) throw invalid();
    return ref;
  }

  public FaultApi.Reservation reserve(WorkOrderApi.CommandBinding b) {
    var result =
        call(
            InternalEndpoint.RESERVATION_CREATE,
            true,
            new FaultApi.Reserve(b),
            FaultApi.Reservation.class,
            b.incidentId());
    if (!b.equals(result.binding())
        || result.state() == FaultApi.ReservationState.RELEASED
        || result.state() == FaultApi.ReservationState.UNKNOWN) throw invalid();
    return result;
  }

  public FaultApi.Reservation query(String operation) {
    var result =
        call(
            InternalEndpoint.RESERVATION_QUERY, false, null, FaultApi.Reservation.class, operation);
    if (!operation.equals(result.binding().operationId())) throw invalid();
    return result;
  }

  public void commit(String id, FaultApi.Commit c) {
    var r = call(InternalEndpoint.RESERVATION_COMMIT, false, c, FaultApi.Reservation.class, id);
    if (!id.equals(r.reservationId())
        || !c.binding().equals(r.binding())
        || r.state() != FaultApi.ReservationState.COMMITTED
        || !c.workOrderId().equals(r.workOrderId())) throw invalid();
  }

  public void abort(String id, FaultApi.AbortAcknowledgement c) {
    var r = call(InternalEndpoint.RESERVATION_ABORT, false, c, FaultApi.Reservation.class, id);
    if (!id.equals(r.reservationId())
        || !c.binding().equals(r.binding())
        || r.state() != FaultApi.ReservationState.RELEASED) throw invalid();
  }

  public boolean evidence(String id, java.util.List<String> devices) {
    var r =
        call(
            InternalEndpoint.EVIDENCE_RESOLVE,
            true,
            new FaultApi.EvidenceRequest(id, devices),
            FaultApi.EvidenceResult.class);
    return r.visible() && r.related();
  }

  private ServiceFailure invalid() {
    return ServiceFailure.unavailable("FAULT_AUTHORITY_INVALID_RESPONSE", ServiceId.FAULT, false);
  }
}
