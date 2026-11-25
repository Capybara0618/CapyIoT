from datetime import UTC, datetime
from typing import Annotated, Generic, Literal, TypeVar

from pydantic import BaseModel, PlainSerializer

UtcTime = Annotated[
    datetime,
    PlainSerializer(lambda value: value.replace(tzinfo=UTC).isoformat().replace("+00:00", "Z")),
]
T = TypeVar("T")


class Envelope(BaseModel, Generic[T]):
    code: Literal["OK"]
    message: str
    data: T
    traceId: str


class ConversationView(BaseModel):
    conversationId: str
    title: str
    status: Literal["ACTIVE", "ARCHIVED"]
    createdAt: UtcTime
    lastMessageAt: UtcTime | None
    version: int


class ConversationPage(BaseModel):
    items: list[ConversationView]
    total: int
    page: int
    size: int


class MessageView(BaseModel):
    messageId: str
    sequence: int
    messageSeq: int
    role: Literal["USER", "ASSISTANT"]
    content: str | None
    contentStatus: str
    createdAt: UtcTime
    runId: str | None


class ConversationHistory(ConversationView):
    messages: list[MessageView]


class RunView(BaseModel):
    runId: str
    conversationId: str
    status: str
    startedAt: UtcTime
    finishedAt: UtcTime | None
    answerMessageId: str | None
    answer: dict | None
    toolCallCount: int
    draftId: str | None
    executionStatus: str
    errorCode: str | None
    version: int
    contentStatus: str
    evidenceReferences: list[dict]


class ExecutionView(BaseModel):
    executionId: str
    status: str
    workOrderId: str | None
    workOrderNo: str | None
    errorCode: str | None
    updatedAt: UtcTime | None


class DraftView(BaseModel):
    draftId: str
    runId: str
    revision: int
    contentHash: str
    status: str
    sourceType: str
    actionType: str
    payload: dict
    relatedEvidenceReferences: list[dict]
    summary: str
    expiresAt: UtcTime
    confirmedAt: UtcTime | None
    execution: ExecutionView | None
