package io.capyiot.contract.internal;

import java.time.Instant;
import java.util.List;

public final class WorkOrderApi {
    private WorkOrderApi() {}
    public enum Operation { CREATE, REOPEN }
    public enum OperationState { PREPARED, RESERVED, COMMITTED, ABORTED, RECONCILIATION_REQUIRED, UNKNOWN }
    public enum OrderStatus { OPEN, ASSIGNED, ACCEPTED, IN_PROGRESS, VERIFYING, RETURNED, CLOSED, CANCELLED;
        public boolean terminal() { return this==CLOSED || this==CANCELLED; }
    }

    public record CommandBinding(String operationId, String incidentId, Operation operation, String actorUserId,
                                 String idempotencyKey, String requestHash) {
        public CommandBinding {
            ContractChecks.id(operationId); ContractChecks.optionalId(incidentId); ContractChecks.required(operation);
            ContractChecks.id(actorUserId);
            if(idempotencyKey==null || !idempotencyKey.matches("[\\x21-\\x7e]{1,128}"))
                throw new IllegalArgumentException("Invalid idempotency key");
            if(requestHash==null || !requestHash.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid request fingerprint");
        }
    }

    /** UNKNOWN is explicitly non-authoritative. A missing operation is never proof of rollback. */
    public record OperationResult(String operationId, OperationState state, CommandBinding binding,
                                  String reservationId, String workOrderId, Long workOrderVersion, Instant checkedAt) {
        public OperationResult {
            ContractChecks.id(operationId); ContractChecks.required(state); ContractChecks.required(checkedAt);
            ContractChecks.optionalId(reservationId); ContractChecks.optionalId(workOrderId);
            if(state!=OperationState.UNKNOWN && (binding==null || !binding.operationId().equals(operationId)))
                throw new IllegalArgumentException("Missing operation binding");
            if(binding!=null && !binding.operationId().equals(operationId))throw new IllegalArgumentException("Mismatched operation");
            if(workOrderVersion!=null)ContractChecks.version(workOrderVersion);
            if(state==OperationState.COMMITTED && (binding.incidentId()!=null && reservationId==null || workOrderId==null || workOrderVersion==null))
                throw new IllegalArgumentException("Incomplete committed operation");
        }
        public boolean provesAbort(CommandBinding expected) {
            return state==OperationState.ABORTED && expected.equals(binding);
        }
    }

    public record OrderRef(String workOrderId, String workOrderNo, OrderStatus status, long version) {
        public OrderRef {
            ContractChecks.id(workOrderId); ContractChecks.text(workOrderNo,64);
            ContractChecks.required(status); ContractChecks.version(version);
        }
    }
    public record IncidentOrdersPageRequest(String cursor){public IncidentOrdersPageRequest {ContractChecks.text(cursor,256);}}
    public record IncidentOrderState(String incidentId, List<OrderRef> orders, List<String> pendingOperationIds,
                                     boolean complete, Instant checkedAt, String nextCursor) {
        public IncidentOrderState {
            ContractChecks.id(incidentId); orders=ContractChecks.batch(orders,true);
            pendingOperationIds=ContractChecks.ids(pendingOperationIds,true); ContractChecks.required(checkedAt);
            if(nextCursor!=null)ContractChecks.text(nextCursor,256);
        }
        public boolean allTerminal() {
            return complete && nextCursor==null && pendingOperationIds.isEmpty() && orders.stream().allMatch(o->o.status().terminal());
        }
    }
    public record AssignmentRequest(String userId, String parkId, PlatformApi.AssignmentRelation relation,
                                    List<String> deviceIds) {
        public AssignmentRequest {
            ContractChecks.id(userId); ContractChecks.id(parkId); ContractChecks.required(relation);
            deviceIds=ContractChecks.ids(deviceIds,false);
        }
    }
    public record AssignmentResult(List<String> assignedDeviceIds, Instant checkedAt) {
        public AssignmentResult { assignedDeviceIds=ContractChecks.ids(assignedDeviceIds,true); ContractChecks.required(checkedAt); }
    }
}
