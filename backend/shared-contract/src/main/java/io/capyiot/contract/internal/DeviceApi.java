package io.capyiot.contract.internal;

import java.time.Instant;
import java.util.List;

public final class DeviceApi {
    private DeviceApi() {}
    public record Batch(List<String> deviceIds) {
        public Batch { deviceIds=ContractChecks.ids(deviceIds,false); }
    }
    public record VisiblePageRequest(String permission,String afterDeviceId) {
        public VisiblePageRequest {ContractChecks.permission(permission);ContractChecks.optionalId(afterDeviceId);}
    }
    public record VisiblePage(List<String> deviceIds,String nextCursor,Instant checkedAt) {
        public VisiblePage {deviceIds=ContractChecks.ids(deviceIds,true);ContractChecks.optionalId(nextCursor);ContractChecks.required(checkedAt);}
    }
    public record NetworkPageRequest(String parkId,Instant asOf,String afterRelationId) {
        public NetworkPageRequest {ContractChecks.id(parkId);ContractChecks.required(asOf);ContractChecks.optionalId(afterRelationId);}
    }
    public record NetworkPage(List<Relation> relations,String nextCursor,Instant asOf) {
        public NetworkPage {relations=ContractChecks.batch(relations,true);ContractChecks.optionalId(nextCursor);ContractChecks.required(asOf);}
    }
    public record IngestionIdentity(String clientId) {
        public IngestionIdentity {if(clientId==null||!clientId.matches("capyiot-ingestion-[A-Za-z0-9_-]{1,48}"))throw new IllegalArgumentException("Invalid ingestion client ID");}
    }
    public record IngestionCredential(String token,Instant expiresAt) {
        public IngestionCredential {ContractChecks.text(token,16384);ContractChecks.required(expiresAt);}
        @Override public String toString(){return "IngestionCredential[REDACTED]";}
    }
    public record SessionContext(String deviceId,String parkId,String deviceType,String source,String sessionId,
                                 long sessionEpoch,long credentialVersion,Instant expiresAt,String monitoringPointId) {
        public SessionContext {ContractChecks.id(deviceId);ContractChecks.id(parkId);ContractChecks.text(deviceType,32);ContractChecks.text(source,64);
            ContractChecks.id(sessionId);ContractChecks.version(sessionEpoch);ContractChecks.version(credentialVersion);ContractChecks.required(expiresAt);ContractChecks.optionalId(monitoringPointId);}
    }
    public record Resource(String deviceId, String parkId, String areaId, String assetCode, String deviceType,
                           String name, String assetStatus, List<String> ancestorAreaIds, long version) {
        public Resource {
            ContractChecks.id(deviceId); ContractChecks.id(parkId); ContractChecks.optionalId(areaId);
            ContractChecks.text(assetCode,64); ContractChecks.text(deviceType,32); ContractChecks.text(name,200);
            ContractChecks.text(assetStatus,32); ancestorAreaIds=ContractChecks.ids(ancestorAreaIds,true);
            ContractChecks.version(version);
        }
    }
    public record ScopeRequest(String permission, List<String> deviceIds) {
        public ScopeRequest { ContractChecks.permission(permission); deviceIds=ContractChecks.ids(deviceIds,false); }
    }
    /** All requested IDs must be present and authorized, otherwise return 403/404, never a partial success. */
    public record ScopeResult(List<Resource> devices, Instant checkedAt) {
        public ScopeResult { devices=ContractChecks.batch(devices,false); ContractChecks.required(checkedAt); }
    }
    public record ResourceValidation(String parkId, List<String> areaIds) {
        public ResourceValidation { ContractChecks.id(parkId); areaIds=ContractChecks.ids(areaIds,true); }
    }
    public record AuditScopeRequest(String parkId,List<String> deviceIds,List<String> areaIds) {
        public AuditScopeRequest {
            ContractChecks.id(parkId);deviceIds=ContractChecks.ids(deviceIds,true);areaIds=ContractChecks.ids(areaIds,true);
        }
    }
    public record AuditScopeResult(boolean visible,Instant checkedAt) {
        public AuditScopeResult {ContractChecks.required(checkedAt);}
    }
    public record ResourceValidity(String parkId, boolean parkExists, boolean allAreasBelong,
                                   long topologyVersion, Instant checkedAt) {
        public ResourceValidity { ContractChecks.id(parkId); ContractChecks.version(topologyVersion); ContractChecks.required(checkedAt); }
    }
    public record TopologyRequest(String parkId, List<String> deviceIds, Instant asOf) {
        public TopologyRequest { ContractChecks.id(parkId); deviceIds=ContractChecks.ids(deviceIds,false); ContractChecks.required(asOf); }
    }
    public record Relation(String relationId, String type, String sourceDeviceId, String targetDeviceId,
                           String port, Integer channel, Instant validFrom, Instant validTo, long version) {
        public Relation {
            ContractChecks.id(relationId); ContractChecks.text(type,32); ContractChecks.id(sourceDeviceId);
            ContractChecks.id(targetDeviceId); ContractChecks.required(validFrom); ContractChecks.version(version);
            if (validTo != null && validTo.isBefore(validFrom)) throw new IllegalArgumentException("Invalid relation validity");
        }
    }
    public record TopologyResult(List<Resource> devices, List<Relation> relations, Instant asOf,
                                 long topologyVersion, boolean complete) {
        public TopologyResult {
            devices=ContractChecks.batch(devices,true); relations=List.copyOf(relations);
            if (relations.size()>1000) throw new IllegalArgumentException("Too many topology relations");
            ContractChecks.required(asOf); ContractChecks.version(topologyVersion);
        }
    }
    /** Relevant persisted facts include pending Outbox events; heartbeat-only versions are excluded. */
    public record ObservationPosition(String eventId, String streamKey, long sessionEpoch, long sequence) {
        public ObservationPosition {
            ContractChecks.id(eventId); ContractChecks.text(streamKey,255);
            ContractChecks.version(sessionEpoch); ContractChecks.version(sequence);
        }
    }
    public record RuntimeState(String deviceId, String connectivityStatus, String operatingMode,
                               String dataFreshness, long stateVersion, Instant lastHeartbeatReceivedAt,
                               Instant lastBusinessDataReceivedAt, List<ObservationPosition> requiredFaultPositions) {
        public RuntimeState {
            ContractChecks.id(deviceId); ContractChecks.text(connectivityStatus,32); ContractChecks.text(operatingMode,32);
            ContractChecks.text(dataFreshness,32); ContractChecks.version(stateVersion);
            requiredFaultPositions=List.copyOf(requiredFaultPositions);
            if(requiredFaultPositions.size()>1000)throw new IllegalArgumentException("Too many observation positions");
        }
    }
    public record RuntimeResult(List<RuntimeState> devices, Instant checkedAt, boolean complete) {
        public RuntimeResult { devices=ContractChecks.batch(devices,true); ContractChecks.required(checkedAt); }
    }
}
