package io.capyiot.contract.internal;

import java.time.Instant;
import java.util.List;

public final class FaultApi {
    private FaultApi() {}
    public enum ReservationState { RESERVED, COMMITTED, RELEASED, RECONCILIATION_REQUIRED, UNKNOWN }
    public record IncidentRef(String incidentId, String parkId, String status, String priority, long version) {
        public IncidentRef {
            ContractChecks.id(incidentId); ContractChecks.id(parkId); ContractChecks.text(status,32);
            ContractChecks.text(priority,8); ContractChecks.version(version);
        }
    }
    public record Reserve(WorkOrderApi.CommandBinding binding) {
        public Reserve { ContractChecks.required(binding); }
    }
    public record Reservation(String reservationId, ReservationState state, WorkOrderApi.CommandBinding binding,
                              String workOrderId, Instant checkedAt) {
        public Reservation {
            ContractChecks.id(reservationId); ContractChecks.required(state); ContractChecks.required(binding);
            ContractChecks.optionalId(workOrderId); ContractChecks.required(checkedAt);
            if(state==ReservationState.COMMITTED && workOrderId==null)throw new IllegalArgumentException("Missing committed order");
        }
    }
    public record Commit(WorkOrderApi.CommandBinding binding, String workOrderId, long workOrderVersion) {
        public Commit { ContractChecks.required(binding); ContractChecks.id(workOrderId); ContractChecks.version(workOrderVersion); }
    }
    /** The receiver must independently query workflow authority before accepting this acknowledgement. */
    public record AbortAcknowledgement(WorkOrderApi.CommandBinding binding, String reason) {
        public AbortAcknowledgement { ContractChecks.required(binding); ContractChecks.text(reason,1000); }
    }
    public record CloseAttempt(String incidentId, String closeAttemptId, long gateVersion, long expectedIncidentVersion) {
        public CloseAttempt {
            ContractChecks.id(incidentId); ContractChecks.id(closeAttemptId);
            ContractChecks.version(gateVersion); ContractChecks.version(expectedIncidentVersion);
        }
    }
    public record Health(String deviceId, String healthStatus, long sourceVersion) {
        public Health { ContractChecks.id(deviceId); ContractChecks.text(healthStatus,32); ContractChecks.version(sourceVersion); }
    }
    public record HealthResult(List<Health> devices, Instant checkedAt, boolean complete) {
        public HealthResult { devices=ContractChecks.batch(devices,true); ContractChecks.required(checkedAt); }
    }
    public record EvidenceRequest(String evidenceReferenceId, List<String> workOrderDeviceIds) {
        public EvidenceRequest { ContractChecks.id(evidenceReferenceId); workOrderDeviceIds=ContractChecks.ids(workOrderDeviceIds,false); }
    }
    public record EvidenceResult(boolean visible, boolean related, String sourceId, Instant checkedAt) {
        public EvidenceResult { ContractChecks.optionalId(sourceId); ContractChecks.required(checkedAt); }
    }
}
