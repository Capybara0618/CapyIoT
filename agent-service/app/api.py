from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, ConfigDict, Field
from starlette.concurrency import run_in_threadpool

from app.auth import current_principal
from app.core_client import Principal

router = APIRouter(prefix="/api/v1/agent")
User = Annotated[Principal, Depends(current_principal)]


class ConversationCreate(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    title: str = Field(default="运维对话", min_length=1, max_length=200)


def ok(request, data):
    return {"code": "OK", "message": "成功", "data": data, "traceId": request.state.correlation_id}


@router.post("/conversations", status_code=201)
async def create_conversation(body: ConversationCreate, user: User, request: Request):
    result = await run_in_threadpool(request.app.state.repository.create_conversation, user.user_id, body.title)
    return ok(request, result)


@router.get("/conversations")
async def conversations(user: User, request: Request, page: int = Query(1, ge=1), size: int = Query(20, ge=1, le=50)):
    return ok(request, await run_in_threadpool(request.app.state.repository.conversations, user.user_id, page, size))


@router.get("/conversations/{conversation_id}")
async def history(conversation_id: str, user: User, request: Request,
                  after: int = Query(0, ge=0), size: int = Query(20, ge=1, le=50)):
    return ok(request, await run_in_threadpool(request.app.state.repository.history, user.user_id, conversation_id, after, size))
