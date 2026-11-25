from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.tool_schemas import ID


class DeviceItem(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    deviceId: ID
    deviceRole: Literal["MAIN", "RELATED"]
    issueDescription: str = Field(min_length=1, max_length=2000)


class PrepareWorkOrder(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    sourceType: Literal["INCIDENT", "MANUAL"]
    incidentId: ID | None = None
    deviceItems: list[DeviceItem] = Field(min_length=1, max_length=100)
    title: str = Field(min_length=1, max_length=160)
    description: str = Field(min_length=1, max_length=2000)
    workCategory: str = Field(default="设备故障维修", min_length=1, max_length=64)
    priority: Literal["P1", "P2", "P3", "P4"] | None = None
    priorityReason: str | None = Field(default=None, max_length=1000)
    expectedResult: str = Field(min_length=1, max_length=1000)
    evidenceReferenceIds: list[ID] = Field(default_factory=list, max_length=20)

    @model_validator(mode="after")
    def business_shape(self):
        ids = [item.deviceId for item in self.deviceItems]
        if len(set(ids)) != len(ids) or sum(i.deviceRole == "MAIN" for i in self.deviceItems) != 1:
            raise ValueError("Exactly one MAIN and unique devices required")
        if (self.sourceType == "INCIDENT") != (self.incidentId is not None):
            raise ValueError("Incident source mismatch")
        if self.sourceType == "MANUAL" and self.priority is None:
            raise ValueError("Manual work orders require a priority")
        if len(set(self.evidenceReferenceIds)) != len(self.evidenceReferenceIds):
            raise ValueError("Duplicate evidence")
        return self

    def command(self):
        return self.model_dump(mode="json", exclude={"evidenceReferenceIds"})


class DraftDecision(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    draftRevision: int = Field(ge=1)
    contentHash: str = Field(pattern=r"^[a-f0-9]{64}$")


class DraftRejection(DraftDecision):
    reason: str | None = Field(default=None, max_length=1000)
