package io.capyiot.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Public read models also supply the schemas for map-based query projections. */
public final class ApiViews {
    private ApiViews(){}
    public record DeviceSummary(String deviceId,String assetCode,String deviceType,String name,String parkId,String areaId,String areaName,
        String assetStatus,String connectivityStatus,String operatingMode,String dataFreshness,String healthStatus,long version,long stateVersion,
        Instant lastHeartbeatReceivedAt,Instant lastBusinessDataReceivedAt,Instant updatedAt){}
    public record DeviceDetail(String deviceId,String assetCode,String deviceType,String name,String parkId,String areaId,String areaName,String assetStatus,
        String connectivityStatus,String operatingMode,String dataFreshness,String healthStatus,long version,long stateVersion,
        String manufacturer,String model,String serialNumber,String managementIp,String criticality,Map<String,Boolean> capabilities,
        Instant lastHeartbeatReceivedAt,Instant lastBusinessDataReceivedAt,Instant updatedAt,List<Binding> monitoringPointBindings,
        List<Relation> networkDependencies,List<Relation> powerDependencies,List<Relation> videoChannels,String completeness){}
    public record DevicePage(List<DeviceSummary> items,long total,int page,int size){}
    public record RegisteredDevice(DeviceSummary device,String deviceSecret,boolean secretAlreadyRevealed,boolean created){}
    public record Area(String areaId,String parkId,String parentAreaId,String areaType,String areaCode,String name,String status,long version){}
    public record Point(String monitoringPointId,String parkId,String areaId,String pointCode,String name,String description,String status,long version){}
    public record Binding(String bindingId,String monitoringPointId,String ipcDeviceId,Instant validFrom,Instant validTo,String reason,long version){}
    public record Relation(String relationId,String relationType,String sourceDeviceId,String targetDeviceId,String upstreamPortRef,String poePort,Integer channelNo,
        Instant validFrom,Instant validTo,String source,long version){}
    public record Topology(String targetId,Instant asOf,List<DeviceSummary> nodes,List<Relation> relations,String completeness,boolean unknownRelations,String configurationStatus){}
    public record StateChange(long historyId,String stateDimension,String oldValue,String newValue,String sourceEventId,Instant occurredAt,Instant receivedAt,
        String streamKey,Long streamSequence,long stateVersion){}
    public record StateHistoryPage(List<StateChange> items,long total,int page,int size){}
    public record Event(String eventId,String deviceId,String eventType,String streamKey,long sequence,long sessionEpoch,Instant occurredAt,Instant receivedAt,
        String source,String processingStatus,String traceId,Object payload){}
    public record EventPage(List<Event> items,long total,int page,int size){}
    public record Evidence(String evidenceId,String anomalyId,String sourceContext,String sourceType,String sourceId,String eventId,String evidenceType,
        Instant occurredAt,Instant receivedAt,Object summaryJson){}
    public record Anomaly(String anomalyId,String parkId,String deviceId,String subjectKind,String subjectId,String anomalyType,String resourceKey,String status,
        String severity,long version,Instant firstObservedAt,Instant lastObservedAt,Instant recoveredAt,String resolutionReason,List<Evidence> evidenceReferences){}
    public record AnomalyPage(List<Anomaly> items,long total,int page,int size){}
    public record Incident(String incidentId,String incidentNo,String parkId,String title,String status,String priority,String priorityReason,String rootCauseStatus,
        String candidateRootDeviceId,String confirmedRootCauseText,long version,Instant openedAt,Instant closedAt,Object impactSnapshot,
        List<Map<String,Object>> anomalyLinks,List<Evidence> evidenceReferences,List<Map<String,Object>> correlationCandidates,List<Map<String,Object>> workOrders,String completeness){}
    public record IncidentPage(List<Incident> items,long total,int page,int size){}
    public record DeviceItem(String itemId,String deviceId,String deviceRole,String assetCodeSnapshot,String deviceNameSnapshot,String deviceTypeSnapshot,String areaIdSnapshot,String issueDescription){}
    public record RepairRecord(String recordId,int workCycleNo,String operatorId,String recordType,String content,Object evidenceRefsJson,Object deviceResultsJson,Instant recordedAt){}
    public record AcceptanceRecord(String acceptanceId,int workCycleNo,String acceptorId,String result,String comment,Object checkItemsJson,Object evidenceRefsJson,Instant acceptedAt){}
    public record WorkOrder(String workOrderId,String workOrderNo,String parkId,String sourceType,String incidentId,String title,String description,String workCategory,
        String priority,String priorityReason,String status,long version,String assignedRepairerId,String designatedAcceptorId,int workCycleNo,String expectedResult,
        String completionSummary,Instant dueAt,Instant createdAt,Instant updatedAt,List<DeviceItem> deviceItems,List<RepairRecord> repairRecords,
        List<AcceptanceRecord> acceptanceRecords,List<Map<String,Object>> statusHistory){}
    public record WorkOrderPage(List<WorkOrder> items,long total,int page,int size){}
    public record CreatedWorkOrder(String workOrderId,String workOrderNo,String sourceType,String incidentId,String status,String priority,long version,Instant createdAt,boolean created){}
    public record AuditRecord(long auditId,String actorUserId,String actorName,String actionCode,String resourceType,String resourceId,String parkId,String result,String source,String reason,Instant createdAt){}
    public record AuditPage(List<AuditRecord> items,long total,int page,int size){}
    public record CommandResult(String deviceId,String areaId,String monitoringPointId,String bindingId,String dependencyId,String powerDependencyId,String associationId,
        String incidentId,String workOrderId,String status,Long version,boolean created,String rotationId,Long credentialVersion,Instant rotatedAt,String deviceSecret,boolean secretAlreadyRevealed){}
}
