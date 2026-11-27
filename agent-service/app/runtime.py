"""One user-initiated, bounded read-only Run per POST stream; no worker or task bus."""

import asyncio
import json
import logging
from datetime import UTC, datetime

from pydantic_ai.messages import ModelRequest, ModelResponse, TextPart, UserPromptPart
from starlette.concurrency import run_in_threadpool

from app.agent import AgentDeps
from app.errors import ServiceError

LOG = logging.getLogger("capyiot.agent.runtime")


class QueryRuntime:
    def __init__(self, settings, repository, tools, business, auth, agent):
        self.settings, self.repository, self.tools = settings, repository, tools
        self.business, self.auth, self.agent = business, auth, agent
        self.tasks = {}

    async def visible_run(self, principal, run_id, correlation):
        view = await run_in_threadpool(self.repository.run_view, principal.user_id, run_id)
        if view["answer"] is not None:
            evidence = await run_in_threadpool(self.repository.evidence, principal.user_id, run_id)
            try:
                async with asyncio.timeout(min(self.settings.run_timeout_seconds, 20)):
                    visible = await self.tools.revalidate(
                        evidence, principal, self.business, correlation
                    )
            except TimeoutError:
                visible = False
            if visible:
                view["evidenceReferences"] = [
                    {
                        key: value
                        for key, value in row.items()
                        if key not in {"arguments", "authorizationVersion", "resultHash"}
                    }
                    for row in evidence
                ]
                view["contentStatus"] = "VISIBLE"
            else:
                view["answer"], view["contentStatus"] = None, "REDACTED_BY_CURRENT_AUTHORIZATION"
                view["evidenceReferences"] = []
        else:
            view["contentStatus"], view["evidenceReferences"] = "VISIBLE", []
        return view

    async def history(
        self, principal, conversation_id, correlation, after, size, before=None, latest=False
    ):
        view = await run_in_threadpool(
            self.repository.history, principal.user_id, conversation_id, after, size, before, latest
        )
        # One authorization recheck per Run, shared by the user/assistant pair.
        limit = asyncio.Semaphore(3)

        async def current(run_id):
            async with limit:
                return run_id, await self.visible_run(principal, run_id, correlation)

        ids = {message["runId"] for message in view["messages"] if message["runId"]}
        runs = dict(await asyncio.gather(*(current(run_id) for run_id in ids)))
        for message in view["messages"]:
            message["messageSeq"] = message["sequence"]
            run = runs.get(message["runId"])
            message["run"] = run
            if message["role"] == "ASSISTANT" and run:
                message["content"] = (
                    json.dumps(run["answer"], ensure_ascii=False) if run["answer"] else None
                )
                message["contentStatus"] = run["contentStatus"]
        return view

    async def context(self, principal, conversation_id, run_id, correlation):
        rows = await run_in_threadpool(
            self.repository.recent_messages,
            principal.user_id,
            conversation_id,
            self.settings.max_context_messages,
        )
        messages, remaining = [], self.settings.max_context_chars
        # Budget the most recent context first, then preserve its chronological order.
        for row in reversed(rows):
            if row["runId"] == run_id:
                continue
            text = row["content"]
            if row["role"] == "ASSISTANT":
                if not row["runId"]:
                    continue
                previous = await self.visible_run(principal, row["runId"], correlation)
                if previous["answer"] is None:
                    continue
                text = json.dumps(previous["answer"], ensure_ascii=False)
            if len(text) > remaining:
                break
            remaining -= len(text)
            messages.append(
                ModelRequest(parts=[UserPromptPart(text)])
                if row["role"] == "USER"
                else ModelResponse(parts=[TextPart(text)])
            )
        return list(reversed(messages))

    async def cancel(self, principal, run_id):
        view = await run_in_threadpool(self.repository.run_view, principal.user_id, run_id)
        if view["status"] in {"CREATED", "RUNNING", "PLANNING"}:
            await run_in_threadpool(
                self.repository.finish_run,
                principal.user_id,
                run_id,
                "CANCELLED",
                None,
                "USER_CANCELLED",
            )
            task = self.tasks.get(run_id)
            if task:
                task.cancel()

    async def stream(self, request, principal, run_id, conversation_id, content):
        queue = asyncio.Queue(maxsize=64)

        async def emit(name, data):
            await queue.put((name, data))

        async def execute():
            try:
                await run_in_threadpool(
                    self.repository.begin_run,
                    principal.user_id,
                    run_id,
                    self.settings.run_timeout_seconds,
                )
                await emit(
                    "step.started",
                    {"stepType": "QUERY_ANALYSIS", "summary": "查询当前授权范围内的业务证据"},
                )
                async with asyncio.timeout(self.settings.run_timeout_seconds):
                    current = await self.auth.authenticate(
                        principal.token, request.state.correlation_id
                    )
                    history = await self.context(
                        current, conversation_id, run_id, request.state.correlation_id
                    )
                    deps = AgentDeps(
                        current, self.business, run_id, request.state.correlation_id, emit=emit
                    )
                    answer = await self.agent.run(content, deps, history=history)
                    # Recheck the session before returning content; every tool has its own service authorization too.
                    await self.auth.authenticate(principal.token, request.state.correlation_id)
                    serialized = answer.model_dump(mode="json")
                    status = "PARTIAL_SUCCEEDED" if deps.incomplete else "SUCCEEDED"
                    message = await run_in_threadpool(
                        self.repository.finish_run, current.user_id, run_id, status, serialized
                    )
                    terminal = await run_in_threadpool(
                        self.repository.run_view, current.user_id, run_id
                    )
                    if terminal["status"] == "CANCELLED":
                        await emit("run.cancelled", {})
                        return
                    # Only validated structured output is streamed; hidden reasoning/raw tool payloads never leave.
                    await emit(
                        "answer.delta", {"contentDelta": json.dumps(serialized, ensure_ascii=False)}
                    )
                    if terminal["draftId"]:
                        draft = await run_in_threadpool(
                            self.repository.draft, current.user_id, terminal["draftId"]
                        )
                        if draft["status"] == "PENDING_CONFIRMATION":
                            await emit(
                                "draft.ready",
                                {
                                    "draftId": draft["draftId"],
                                    "draftRevision": draft["revision"],
                                    "draftStatus": draft["status"],
                                    "contentHash": draft["contentHash"],
                                    "draftSummary": draft["summary"],
                                },
                            )
                    await emit(
                        "run.completed", {"status": terminal["status"], "answerMessageId": message}
                    )
            except asyncio.CancelledError:
                await run_in_threadpool(
                    self.repository.finish_run,
                    principal.user_id,
                    run_id,
                    "CANCELLED",
                    None,
                    "CLIENT_DISCONNECTED_OR_CANCELLED",
                )
                raise
            except (ServiceError, TimeoutError) as error:
                code = error.code if isinstance(error, ServiceError) else "RUN_TIMEOUT"
                await run_in_threadpool(
                    self.repository.finish_run,
                    principal.user_id,
                    run_id,
                    "TIMED_OUT" if "TIMEOUT" in code or "BUDGET" in code else "FAILED",
                    None,
                    code,
                )
                await emit(
                    "run.failed",
                    {
                        "errorCode": code,
                        "safeMessage": "查询未完成，请查看运行状态；不能据此判断没有异常",
                    },
                )
            except Exception as error:
                LOG.error(
                    "run_failed correlation=%s run=%s category=%s",
                    request.state.correlation_id,
                    run_id,
                    type(error).__name__,
                )
                await run_in_threadpool(
                    self.repository.finish_run,
                    principal.user_id,
                    run_id,
                    "FAILED",
                    None,
                    "INTERNAL_ERROR",
                )
                await emit(
                    "run.failed",
                    {"errorCode": "INTERNAL_ERROR", "safeMessage": "运行失败，请稍后重试"},
                )

        task = asyncio.create_task(execute())
        self.tasks[run_id] = task
        sequence = 0

        def event(name, values):
            nonlocal sequence
            sequence += 1
            payload = {
                **values,
                "runId": run_id,
                "seq": sequence,
                "occurredAt": datetime.now(UTC).isoformat(),
            }
            return f"id: {sequence}\nevent: {name}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"

        try:
            yield event("run.started", {"conversationId": conversation_id})
            while True:
                if await request.is_disconnected():
                    break
                try:
                    name, values = await asyncio.wait_for(queue.get(), timeout=2)
                except TimeoutError:
                    if task.done():
                        view = await run_in_threadpool(
                            self.repository.run_view, principal.user_id, run_id
                        )
                        yield event(
                            "run.cancelled" if view["status"] == "CANCELLED" else "run.failed", {}
                        )
                        break
                    yield ": keep-alive\n\n"
                    continue
                yield event(name, values)
                if name in {"run.completed", "run.failed", "run.cancelled"}:
                    break
        finally:
            if not task.done():
                task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
            # The socket may close immediately after run.started, before execute() enters its try block.
            # A read-only Run must still become terminal; completed Runs remain unchanged.
            await asyncio.shield(
                run_in_threadpool(
                    self.repository.finish_run,
                    principal.user_id,
                    run_id,
                    "CANCELLED",
                    None,
                    "STREAM_ENDED_BEFORE_COMPLETION",
                )
            )
            self.tasks.pop(run_id, None)

    async def close(self):
        tasks = list(self.tasks.values())
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
