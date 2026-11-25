from typing import Annotated

from fastapi import APIRouter, Depends, Header, Query, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field
from starlette.concurrency import run_in_threadpool

from app.auth import current_principal
from app.core_client import Principal
from app.draft_schemas import DraftDecision, DraftRejection
from app.errors import ServiceError
from app.schemas import (
    ConversationHistory,
    ConversationPage,
    ConversationView,
    DraftView,
    Envelope,
    RunView,
)

router = APIRouter(prefix="/api/v1/agent")
User = Annotated[Principal, Depends(current_principal)]


class ConversationCreate(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    title: str = Field(default="运维对话", min_length=1, max_length=200)


class MessageCreate(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    content: str = Field(min_length=1, max_length=40000)


def ok(request, data):
    return {"code": "OK", "message": "成功", "data": data, "traceId": request.state.correlation_id}


@router.post("/conversations", status_code=201, response_model=Envelope[ConversationView])
async def create_conversation(body: ConversationCreate, user: User, request: Request):
    result = await run_in_threadpool(
        request.app.state.repository.create_conversation, user.user_id, body.title
    )
    return ok(request, result)


@router.get("/conversations", response_model=Envelope[ConversationPage])
async def conversations(
    user: User, request: Request, page: int = Query(1, ge=1), size: int = Query(20, ge=1, le=50)
):
    return ok(
        request,
        await run_in_threadpool(
            request.app.state.repository.conversations, user.user_id, page, size
        ),
    )


@router.get("/conversations/{conversation_id}", response_model=Envelope[ConversationHistory])
async def history(
    conversation_id: str,
    user: User,
    request: Request,
    after: int = Query(0, ge=0),
    size: int = Query(20, ge=1, le=50),
    before: int | None = Query(None, ge=1),
    latest: bool = False,
):
    if after and (before is not None or latest) or latest and before is not None:
        raise ServiceError("INVALID_ARGUMENT", 400, "历史游标方向不能混用")
    return ok(
        request,
        await request.app.state.runtime.history(
            user, conversation_id, request.state.correlation_id, after, size, before, latest
        ),
    )


@router.post("/conversations/{conversation_id}/messages")
async def message(
    conversation_id: str,
    body: MessageCreate,
    user: User,
    request: Request,
    idempotency_key: Annotated[str, Header(pattern=r"^[A-Za-z0-9_-]{1,128}$")],
):
    if len(body.content) > request.app.state.settings.max_input_chars:
        raise ServiceError("INVALID_ARGUMENT", 400, "消息超出输入上限")
    if "text/event-stream" not in request.headers.get("Accept", ""):
        raise ServiceError("INVALID_ARGUMENT", 400, "消息请求必须接受 text/event-stream")
    run_id, created = await run_in_threadpool(
        request.app.state.repository.start_run,
        user.user_id,
        conversation_id,
        body.content,
        idempotency_key,
        request.state.correlation_id,
    )
    if not created:
        view = await request.app.state.runtime.visible_run(
            user, run_id, request.state.correlation_id
        )
        if view["status"] in {"CREATED", "PLANNING", "RUNNING"}:
            raise ServiceError(
                "RUN_ALREADY_IN_PROGRESS",
                409,
                "请求仍在运行",
                {"existingRunId": run_id, "statusUrl": "/api/v1/agent/runs/" + run_id},
            )
        return Envelope[RunView].model_validate(ok(request, view)).model_dump(mode="json")
    return StreamingResponse(
        request.app.state.runtime.stream(request, user, run_id, conversation_id, body.content),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.get("/runs/{run_id}", response_model=Envelope[RunView])
async def run(run_id: str, user: User, request: Request):
    return ok(
        request,
        await request.app.state.runtime.visible_run(user, run_id, request.state.correlation_id),
    )


@router.post("/runs/{run_id}/cancel", response_model=Envelope[RunView])
async def cancel(run_id: str, user: User, request: Request):
    await request.app.state.runtime.cancel(user, run_id)
    return ok(
        request,
        await request.app.state.runtime.visible_run(user, run_id, request.state.correlation_id),
    )


@router.get("/drafts/{draft_id}", response_model=Envelope[DraftView])
async def draft(draft_id: str, user: User, request: Request):
    return ok(
        request,
        await request.app.state.drafts.visible(user, draft_id, request.state.correlation_id),
    )


@router.post("/drafts/{draft_id}/confirm", status_code=202, response_model=Envelope[DraftView])
async def confirm_draft(
    draft_id: str,
    body: DraftDecision,
    user: User,
    request: Request,
    idempotency_key: Annotated[str, Header(pattern=r"^[A-Za-z0-9_-]{1,128}$")],
):
    if not request.app.state.settings.dispatch_enabled:
        raise ServiceError("DISPATCH_DISABLED", 503, "工单派发未启用，请联系管理员")
    return ok(
        request,
        await request.app.state.drafts.decision(
            user, draft_id, body, "CONFIRM", idempotency_key, request.state.correlation_id
        ),
    )


@router.post("/drafts/{draft_id}/reject", response_model=Envelope[DraftView])
async def reject_draft(
    draft_id: str,
    body: DraftRejection,
    user: User,
    request: Request,
    idempotency_key: Annotated[str, Header(pattern=r"^[A-Za-z0-9_-]{1,128}$")],
):
    return ok(
        request,
        await request.app.state.drafts.decision(
            user, draft_id, body, "REJECT", idempotency_key, request.state.correlation_id
        ),
    )
