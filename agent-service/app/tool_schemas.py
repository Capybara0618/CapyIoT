"""Fixed input contracts and allowlisted projections; never pass raw Core payloads to a model."""

from datetime import UTC, datetime, timedelta
from typing import Annotated, Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

ID = Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")]
Text = Annotated[str, Field(max_length=1000)]


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True, str_strip_whitespace=True)


class Pagination(Input):
    page: int = Field(default=1, ge=1, le=10000, strict=True)
    size: int = Field(default=20, ge=1, le=50, strict=True)


class TimeRange(Input):
    from_: datetime | None = Field(default=None, alias="from")
    to: datetime | None = None

    @field_validator("from_", "to", mode="before")
    @classmethod
    def timestamp_input(cls, value):
        if value is not None and not isinstance(value, (str, datetime)):
            raise ValueError("时间须为 RFC3339 UTC 字符串")
        return value

    @model_validator(mode="after")
    def utc_range(self):
        for value in (self.from_, self.to):
            if value and (value.tzinfo is None or value.utcoffset() != timedelta(0)):
                raise ValueError("时间须为 RFC3339 UTC")
        if self.from_ and self.to and self.from_ >= self.to:
            raise ValueError("时间范围无效")
        return self

    def bounded(self, days):
        limit = timedelta(days=days)
        if self.from_ or self.to:
            start = self.from_ or self.to - limit
            end = self.to or min(datetime.now(UTC), start + limit)
            if end <= start or end - start > limit:
                raise ValueError("时间查询超出跨度上限")
            return {"from": start.isoformat(), "to": end.isoformat()}
        return {}


class QueryDevices(Pagination):
    keyword: str | None = Field(default=None, min_length=1, max_length=100)
    deviceType: Literal["IPC", "NVR", "SWITCH"] | None = None
    areaId: ID | None = None
    monitoringPointId: ID | None = None
    assetStatus: Literal["REGISTERED", "IN_SERVICE", "OUT_OF_SERVICE", "RETIRED"] | None = None
    connectivityStatus: Literal["UNKNOWN", "ONLINE", "OFFLINE"] | None = None
    operatingMode: Literal["UNKNOWN", "NORMAL", "LOW_POWER"] | None = None
    dataFreshness: Literal["NO_DATA", "FRESH", "STALE"] | None = None


class DeviceIdentity(Input):
    deviceId: ID


class StatusHistory(TimeRange, Pagination, DeviceIdentity):
    stateDimension: Literal["CONNECTIVITY", "OPERATING_MODE", "DATA_FRESHNESS"] | None = None


class QueryAnomalies(TimeRange, Pagination):
    deviceId: ID | None = None
    anomalyType: str | None = Field(default=None, max_length=64, pattern=r"^[A-Z_]+$")
    severity: Literal["WARNING", "FAULT"] | None = None
    status: Literal["ACTIVE", "RECOVERED", "DISMISSED"] | None = None


class IncidentIdentity(Input):
    incidentId: ID


class QueryIncidents(TimeRange, Pagination):
    deviceId: ID | None = None
    areaId: ID | None = None
    status: (
        Literal["PENDING_REVIEW", "OPEN", "PROCESSING", "PENDING_CLOSE", "CLOSED", "REJECTED"]
        | None
    ) = None
    priority: Literal["P1", "P2", "P3", "P4"] | None = None


class TopologyQuery(Input):
    deviceId: ID | None = None
    monitoringPointId: ID | None = None
    asOf: datetime | None = None
    depth: int = Field(default=2, ge=1, le=3, strict=True)

    @field_validator("asOf", mode="before")
    @classmethod
    def timestamp_input(cls, value):
        return TimeRange.timestamp_input(value)

    @model_validator(mode="after")
    def target(self):
        if not self.deviceId and not self.monitoringPointId:
            raise ValueError("必须指定设备或点位")
        if self.asOf and (self.asOf.tzinfo is None or self.asOf.utcoffset() != timedelta(0)):
            raise ValueError("时间须为 RFC3339 UTC")
        return self


class QueryOrders(TimeRange, Pagination):
    deviceId: ID | None = None
    incidentId: ID | None = None
    status: (
        Literal[
            "OPEN",
            "ASSIGNED",
            "ACCEPTED",
            "IN_PROGRESS",
            "VERIFYING",
            "RETURNED",
            "CLOSED",
            "CANCELLED",
        ]
        | None
    ) = None
    priority: Literal["P1", "P2", "P3", "P4"] | None = None
    assignedRepairerId: ID | None = None
    designatedAcceptorId: ID | None = None


class MaintenanceQuery(TimeRange, Pagination):
    deviceId: ID | None = None
    workOrderId: Annotated[str, Field(pattern=r"^[1-9][0-9]{0,18}$")] | None = None

    @model_validator(mode="after")
    def target(self):
        if not self.deviceId and not self.workOrderId:
            raise ValueError("必须指定设备或工单")
        return self


class Projection(BaseModel):
    model_config = ConfigDict(extra="ignore")


class Device(Projection):
    deviceId: ID
    assetCode: Text = ""
    name: Text = ""
    deviceType: str = "UNKNOWN"
    areaId: str | None = None
    areaName: Text | None = None
    assetStatus: str = "UNKNOWN"
    connectivityStatus: str = "UNKNOWN"
    operatingMode: str = "UNKNOWN"
    dataFreshness: str = "NO_DATA"
    healthStatus: str = "UNKNOWN"
    version: int = 0
    stateVersion: int = 0
    lastHeartbeatReceivedAt: datetime | None = None
    lastBusinessDataReceivedAt: datetime | None = None
    updatedAt: datetime | None = None


class Binding(Projection):
    monitoringPointId: ID
    ipcDeviceId: ID
    validFrom: datetime | None = None
    validTo: datetime | None = None


class Relation(Projection):
    relationId: ID
    relationType: str
    sourceDeviceId: ID
    targetDeviceId: ID
    upstreamPortRef: str | None = None
    poePort: str | None = None
    channelNo: int | None = None
    validFrom: datetime | None = None
    validTo: datetime | None = None


class DeviceDetail(Device):
    monitoringPointBindings: list[Binding] = Field(default_factory=list)
    networkDependencies: list[Relation] = Field(default_factory=list)
    powerDependencies: list[Relation] = Field(default_factory=list)
    videoChannels: list[Relation] = Field(default_factory=list)
    completeness: str = "UNKNOWN"


class StateChange(Projection):
    historyId: int
    stateDimension: str
    oldValue: str | None = None
    newValue: str
    sourceEventId: str | None = None
    occurredAt: datetime | None = None
    receivedAt: datetime | None = None
    streamKey: str | None = None
    streamSequence: int | None = None
    stateVersion: int


class Anomaly(Projection):
    anomalyId: ID
    deviceId: ID | None = None
    anomalyType: str
    resourceKey: str | None = None
    status: str
    severity: Literal["WARNING", "FAULT"]
    version: int
    firstObservedAt: datetime | None = None
    lastObservedAt: datetime | None = None
    recoveredAt: datetime | None = None


class Incident(Projection):
    incidentId: ID
    incidentNo: str
    title: Text
    status: str
    priority: str
    rootCauseStatus: str
    candidateRootDeviceId: str | None = None
    confirmedRootCauseText: Text | None = None
    version: int
    openedAt: datetime | None = None
    closedAt: datetime | None = None
    completeness: str = "UNKNOWN"


class AnomalyLink(Projection):
    anomalyId: ID
    deviceId: str | None = None
    relationKind: str | None = None


class EvidenceSource(Projection):
    evidenceId: ID
    sourceContext: str
    sourceType: str
    sourceId: str
    occurredAt: datetime | None = None
    receivedAt: datetime | None = None


class IncidentDetail(Incident):
    anomalyLinks: list[AnomalyLink] = Field(default_factory=list)
    evidenceReferences: list[EvidenceSource] = Field(default_factory=list)


class Order(Projection):
    workOrderId: str
    workOrderNo: str
    title: Text
    status: str
    priority: str
    sourceType: str
    incidentId: str | None = None
    version: int
    createdAt: datetime | None = None
    updatedAt: datetime | None = None
    completionSummary: Text | None = None


class DeviceItem(Projection):
    deviceId: ID
    deviceRole: str
    issueDescription: Text


class Repair(Projection):
    recordId: str
    recordType: str
    content: Text
    recordedAt: datetime | None = None


class Acceptance(Projection):
    acceptanceId: str
    result: str
    comment: Text
    acceptedAt: datetime | None = None


class Maintenance(Order):
    deviceItems: list[DeviceItem] = Field(default_factory=list)
    repairRecords: list[Repair] = Field(default_factory=list)
    acceptanceRecords: list[Acceptance] = Field(default_factory=list)


T = TypeVar("T")


class Page(Projection, Generic[T]):
    items: list[T]
    total: int
    page: int
    size: int


class Topology(Projection):
    targetId: str
    asOf: datetime
    nodes: list[Device]
    relations: list[Relation]
    completeness: str
    unknownRelations: bool = True
    configurationStatus: str = "UNKNOWN"


class EvidenceView(BaseModel):
    evidenceId: str
    sourceContext: str
    sourceType: Literal["TOOL_RESULT"] = "TOOL_RESULT"
    sourceId: str
    retrievedAt: str
    sourceVersion: int | None = None
    sourceMode: Literal["CORE_API"] = "CORE_API"
    freshnessStatus: str
    summary: str
    sourceObjects: list[dict] = Field(default_factory=list)


class ToolResult(BaseModel, Generic[T]):
    toolName: str
    schemaVersion: int = 1
    status: Literal["SUCCEEDED", "EMPTY", "REJECTED", "FAILED", "TIMED_OUT"]
    data: T | None = None
    evidenceReferences: list[EvidenceView] = Field(default_factory=list)
    completeness: Literal["COMPLETE", "INCOMPLETE", "UNKNOWN"] = "UNKNOWN"
    freshnessStatus: str = "UNKNOWN"
    authorization: Literal["CURRENT_USER_SCOPE", "DENIED"] = "CURRENT_USER_SCOPE"
    truncated: bool = False
    errorCode: str | None = None
    safeMessage: str | None = None
