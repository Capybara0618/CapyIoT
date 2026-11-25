import asyncio
import logging
import re
import time
import uuid
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.agent import OpsAgent
from app.api import router
from app.auth import UserAuthenticator
from app.config import Settings, load_settings
from app.core_client import CoreApiClient
from app.database import Database
from app.errors import ServiceError
from app.repository import Repository
from app.runtime import QueryRuntime
from app.tools import ToolRunner

LOG = logging.getLogger("capyiot.agent")


def create_app(settings: Settings | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app):
        logging.basicConfig(
            level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s"
        )
        LOG.setLevel(logging.INFO)
        config = settings or load_settings()
        app.state.settings = config
        app.state.database = Database(config)
        app.state.http = httpx.AsyncClient(
            timeout=config.http_timeout_seconds,
            trust_env=False,
            follow_redirects=False,
            limits=httpx.Limits(max_connections=20),
        )
        app.state.model_health = None
        app.state.core = CoreApiClient(app.state.http, config.core_base_url)
        app.state.auth = UserAuthenticator(app.state.http, config, app.state.core)
        app.state.repository = Repository(app.state.database.sessions)
        app.state.tools = ToolRunner(config, app.state.repository)
        app.state.ops_agent = OpsAgent(config, tools=app.state.tools.tools())
        app.state.runtime = QueryRuntime(
            config,
            app.state.repository,
            app.state.tools,
            app.state.core,
            app.state.auth,
            app.state.ops_agent,
        )
        logging.getLogger("httpx").setLevel(logging.WARNING)
        logging.getLogger("httpcore").setLevel(logging.WARNING)
        try:
            yield
        finally:
            await app.state.runtime.close()
            await app.state.ops_agent.close()
            await app.state.http.aclose()
            await asyncio.to_thread(app.state.database.close)

    app = FastAPI(
        title="CapyIoT Agent",
        version="0.1.0",
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url="/api/v1/agent/openapi",
    )
    app.include_router(router)

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        proposed = request.headers.get("X-Correlation-Id", "")
        request.state.correlation_id = (
            proposed if re.fullmatch(r"[A-Za-z0-9_-]{1,64}", proposed) else str(uuid.uuid4())
        )
        start = time.monotonic()
        try:
            response = await call_next(request)
        except Exception as error:
            # Exception messages may contain provider payloads, credentials or SQL; never log them.
            LOG.error(
                "request_failed correlation=%s category=%s",
                request.state.correlation_id,
                type(error).__name__,
            )
            response = JSONResponse(
                status_code=500,
                content={
                    "code": "INTERNAL_ERROR",
                    "message": "服务内部错误",
                    "details": {},
                    "traceId": request.state.correlation_id,
                },
            )
        response.headers["X-Correlation-Id"] = request.state.correlation_id
        LOG.info(
            "request_completed correlation=%s method=%s status=%s latency_ms=%s",
            request.state.correlation_id,
            request.method,
            response.status_code,
            round((time.monotonic() - start) * 1000),
        )
        return response

    @app.exception_handler(ServiceError)
    async def service_error(request, error):
        return JSONResponse(
            status_code=error.status,
            content={
                "code": error.code,
                "message": error.message,
                "details": error.details,
                "traceId": request.state.correlation_id,
            },
        )

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, _):
        return JSONResponse(
            status_code=400,
            content={
                "code": "INVALID_ARGUMENT",
                "message": "请求参数无效",
                "details": {},
                "traceId": request.state.correlation_id,
            },
        )

    @app.get("/health/live")
    def live():
        return {"status": "UP", "service": "agent-service"}

    @app.get("/health/ready")
    async def ready(request: Request):
        state, components = request.app.state, {}
        try:
            await asyncio.to_thread(state.database.ready)
            components["mysql"] = "UP"
        except Exception:
            components["mysql"] = "DOWN"
        try:
            response = await state.http.get(
                state.settings.core_base_url.rstrip("/") + "/actuator/health/readiness"
            )
            components["core"] = "UP" if response.status_code == 200 else "DOWN"
        except httpx.HTTPError:
            components["core"] = "DOWN"
        # Metadata check only: health probes never generate billable completions.
        cached = state.model_health
        if not cached or time.monotonic() - cached[0] > 15:
            model_status = "DOWN"
            try:
                response = await state.http.get(
                    state.settings.deepseek_base_url.rstrip("/") + "/models",
                    headers={
                        "Authorization": "Bearer "
                        + state.settings.deepseek_api_key.get_secret_value()
                    },
                )
                if response.status_code == 200 and any(
                    m.get("id") == state.settings.deepseek_model
                    for m in response.json().get("data", [])
                ):
                    model_status = "UP"
            except (httpx.HTTPError, ValueError, TypeError, AttributeError):
                pass
            state.model_health = time.monotonic(), model_status
        components["model"] = state.model_health[1]
        healthy = all(value == "UP" for value in components.values())
        return JSONResponse(
            status_code=200 if healthy else 503,
            content={"status": "UP" if healthy else "DEGRADED", "components": components},
        )

    return app


app = create_app()
