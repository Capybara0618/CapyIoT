import asyncio
import logging
from dataclasses import dataclass, field
from pathlib import Path

from openai import AsyncOpenAI
from pydantic import BaseModel, ConfigDict, Field
from pydantic_ai import Agent, ModelRetry, RunContext, Tool, UsageLimits
from pydantic_ai.exceptions import ModelHTTPError, UnexpectedModelBehavior, UsageLimitExceeded
from pydantic_ai.models import Model
from pydantic_ai.models.openai import OpenAIChatModel, OpenAIChatModelSettings
from pydantic_ai.providers.deepseek import DeepSeekProvider

from app.config import Settings
from app.core_client import CoreApiClient, Principal
from app.errors import ServiceError

ALLOWLIST = frozenset({"query_devices", "get_device_detail", "get_device_status_history", "query_anomalies",
    "get_incident_detail", "query_incidents", "get_topology_context", "query_work_orders",
    "get_maintenance_history", "prepare_work_order"})


class Claim(BaseModel):
    model_config = ConfigDict(extra="forbid")
    statement: str = Field(min_length=1, max_length=2000)
    evidenceIds: list[str] = Field(min_length=1, max_length=20)


class Answer(BaseModel):
    model_config = ConfigDict(extra="forbid")
    facts: list[Claim] = Field(max_length=20)
    inferences: list[Claim] = Field(max_length=20)
    recommendations: list[str] = Field(max_length=20)
    unknowns: list[str] = Field(max_length=20)


@dataclass
class AgentDeps:
    principal: Principal
    core: CoreApiClient
    run_id: str
    correlation_id: str
    evidence_ids: set[str] = field(default_factory=set)


class OpsAgent:
    def __init__(self, settings: Settings, *, model: Model | None = None, tools: tuple[Tool, ...] = ()):
        self.settings = settings
        if any(tool.name not in ALLOWLIST for tool in tools) or len({tool.name for tool in tools}) != len(tools):
            raise ValueError("Only explicitly registered CapyIoT business tools are allowed")
        self.provider = None
        if model is None:
            # Dedicated provider client; never share the Core user's token or its HTTP pool.
            sdk = AsyncOpenAI(api_key=settings.deepseek_api_key.get_secret_value(),
                              base_url=settings.deepseek_base_url, max_retries=0,
                              timeout=settings.http_timeout_seconds)
            self.provider = DeepSeekProvider(openai_client=sdk)
            model = OpenAIChatModel(settings.deepseek_model, provider=self.provider)
        prompt = Path(__file__).with_name("prompts").joinpath("system.md").read_text(encoding="utf-8")
        self.agent = Agent(model, deps_type=AgentDeps, output_type=Answer, instructions=prompt,
                           tools=tools, retries=1, instrument=False,
                           model_settings=OpenAIChatModelSettings(thinking=False,
                               max_tokens=settings.max_output_tokens, parallel_tool_calls=False))
        self.registered_tools = frozenset(tool.name for tool in tools)
        self.slots = asyncio.BoundedSemaphore(settings.max_concurrent_runs)
        logging.getLogger("openai").setLevel(logging.WARNING)

        @self.agent.output_validator
        def verify_evidence(ctx: RunContext[AgentDeps], output: Answer):
            for claim in (*output.facts, *output.inferences):
                if not set(claim.evidenceIds).issubset(ctx.deps.evidence_ids):
                    raise ModelRetry("证据引用不存在或尚未授权查询，请移除该陈述并说明未知信息")
            if not output.facts and not output.unknowns:
                raise ModelRetry("缺少已核查事实时必须说明未知信息")
            return output

    async def run(self, content: str, deps: AgentDeps) -> Answer:
        deps.principal.require("agent.use")
        if not content.strip() or len(content) > self.settings.max_input_chars:
            raise ServiceError("INVALID_ARGUMENT", 400, "消息为空或超出输入上限")
        try:
            async with asyncio.timeout(self.settings.run_timeout_seconds):
                async with self.slots:
                    result = await self.agent.run(content, deps=deps, usage_limits=UsageLimits(
                        request_limit=self.settings.max_model_requests,
                        tool_calls_limit=self.settings.max_tool_calls,
                        output_tokens_limit=self.settings.max_output_tokens))
                    return result.output
        except TimeoutError:
            raise ServiceError("MODEL_TIMEOUT", 504, "模型运行超时，请稍后重试") from None
        except UsageLimitExceeded:
            raise ServiceError("RUN_BUDGET_EXCEEDED", 409, "本次运行已达到预算上限") from None
        except UnexpectedModelBehavior:
            raise ServiceError("MODEL_INVALID_OUTPUT", 502, "模型未返回可核查的有效回答") from None
        except ModelHTTPError:
            raise ServiceError("MODEL_UNAVAILABLE", 503, "模型服务暂不可用") from None

    async def close(self):
        if self.provider is not None:
            await self.provider.client.close()
