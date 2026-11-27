package io.capyiot.workflow.work;

import io.capyiot.contract.internal.*;

interface WorkFaults {
  FaultApi.IncidentRef reference(String id);

  FaultApi.Reservation reserve(WorkOrderApi.CommandBinding binding);

  FaultApi.Reservation query(String operation);

  void commit(String reservation, FaultApi.Commit command);

  void abort(String reservation, FaultApi.AbortAcknowledgement command);

  boolean evidence(String id, java.util.List<String> devices);
}
