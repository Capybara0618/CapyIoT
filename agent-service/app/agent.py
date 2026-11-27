import asyncio
import logging
import re
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path

from openai import AsyncOpenAI
from pydantic import BaseModel, ConfigDict, Field
from pydantic_ai import (
    Agent,
    InstructionCapabilityPart,
    ModelRetry,
    OperatorSource,
    OperatorText,
    RunContext,
    Tool,
    UsageLimits,
)
from pydantic_ai.capabilities import AbstractCapability
from pydantic_ai.exceptions import (
    ModelAPIError,
    ModelRequestTimeout,
    ModelTimeoutError,
    OutputTokenLimitExceeded,
    UnexpectedModelBehavior,
    UsageLimitExceeded,
)
from pydantic_ai.models import Model
from pydantic_ai.models.openai import OpenAIChatModel, OpenAIChatModelSettings
from pydantic_ai.providers.deepseek import DeepSeekProvider
from pydantic_ai.usage import RunUsage

from app.business_client import BusinessApiClient, Principal
from app.config import Settings
from app.errors import ServiceError

ALLOWLIST = frozenset(
    {
        "query_devices",
        "get_device_detail",
        "get_device_status_history",
        "query_anomalies",
        "get_incident_detail",
        "query_incidents",
        "get_topology_context",
        "query_work_orders",
        "get_maintenance_history",
        "prepare_work_order",
    }
)


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


async def no_event(_event, _data):
    pass


@dataclass
class AgentDeps:
    principal: Principal
    business: BusinessApiClient
    run_id: str
    correlation_id: str
    evidence_ids: set[str] = field(default_factory=set)
    incomplete: bool = False
    emit: Callable[[str, dict], Awaitable[None]] = field(default=no_event, repr=False)
    confirmed_root_evidence: set[str] = field(default_factory=set)


@dataclass(kw_only=True)
class BudgetNotice(InstructionCapabilityPart, namespace="capyiot_budget"):
    pass


@dataclass
class QueryBudget(AbstractCapability[AgentDeps]):
    """Reserve a final model response instead of advertising queries until the hard stop."""

    response_token_limit: int = 4096

    @staticmethod
    def remaining(ctx):
        limits = ctx.usage_limits
        if limits is None:
            return None, None, None
        return (
            None if limits.request_limit is None else limits.request_limit - ctx.usage.requests,
            None
            if limits.tool_calls_limit is None
            else limits.tool_calls_limit - ctx.usage.tool_calls,
            None
            if limits.output_tokens_limit is None
            else limits.output_tokens_limit - ctx.usage.output_tokens,
        )

    def closing(self, ctx):
        requests, tools, tokens = self.remaining(ctx)
        return (
            requests is not None
            and requests <= 1
            or tools is not None
            and tools <= 0
            or tokens is not None
            and tokens <= self.response_token_limit
        )

    async def prepare_tools(self, ctx, tool_defs):
        return [] if self.closing(ctx) else tool_defs

    async def before_model_request(self, ctx, request_context):
        requests, tools, tokens = self.remaining(ctx)
        if self.closing(ctx) or requests is not None and requests <= 3:
            ctx.enqueue(
                BudgetNotice(
                    content=OperatorText(
                        f"运行剩余预算：模型响应 {requests} 次，业务工具 {tools} 次，输出 Token {tokens}。"
                        "请及时完成结构化最终回答；查询工具不可用时必须使用已有证据作答，缺失或未查询信息放入 unknowns，"
                        "不要重复查询相同事实或宣称未经查询的结论。"
                    ),
                    source=OperatorSource(),
                    scope="request",
                )
            )
        return request_context


class OpsAgent:
    def __init__(
        self,
        settings: Settings,
        *,
        model: Model | None = None,
        tools: tuple[Tool, ...] = (),
        provider: DeepSeekProvider | None = None,
    ):
        self.settings = settings
        if any(tool.name not in ALLOWLIST for tool in tools) or len(
            {tool.name for tool in tools}
        ) != len(tools):
            raise ValueError("Only explicitly registered CapyIoT business tools are allowed")
        self.provider = None
        if model is None:
            # Dedicated provider client; never share the business user's token or its HTTP pool.
            self.provider = provider or DeepSeekProvider(
                openai_client=AsyncOpenAI(
                    api_key=settings.deepseek_api_key.get_secret_value(),
                    base_url=settings.deepseek_base_url,
                    max_retries=0,
                    timeout=settings.model_timeout_seconds,
                )
            )
            model = OpenAIChatModel(settings.deepseek_model, provider=self.provider)
        prompt = (
            Path(__file__).with_name("prompts").joinpath("system.md").read_text(encoding="utf-8")
        )
        self.agent = Agent(
            model,
            deps_type=AgentDeps,
            output_type=Answer,
            instructions=prompt,
            tools=tools,
            retries=1,
            capabilities=[QueryBudget(response_token_limit=settings.max_output_tokens)],
            model_settings=OpenAIChatModelSettings(
                thinking=False, max_tokens=settings.max_output_tokens, parallel_tool_calls=False
            ),
        )
        self.agent.instrument = False
        self.registered_tools = frozenset(tool.name for tool in tools)
        self.slots = asyncio.BoundedSemaphore(settings.max_concurrent_runs)
        logging.getLogger("openai").setLevel(logging.WARNING)
        logging.getLogger("httpx2").setLevel(logging.WARNING)

        @self.agent.output_validator
        def verify_evidence(ctx: RunContext[AgentDeps], output: Answer):
            statements = [claim.statement for claim in (*output.facts, *output.inferences)]
            for statement in [*statements, *output.recommendations, *output.unknowns]:
                if re.search(
                    r"已(?:经)?创建.*(?:正式工单|维修工单(?!草稿))|工单.*创建成功|成功创建.*正式工单",
                    statement,
                ):
                    raise ModelRetry(
                        "本次工具只能生成草稿，正式工单尚未创建；请说明需要用户审阅确认"
                    )
            for claim in (*output.facts, *output.inferences):
                if not set(claim.evidenceIds).issubset(ctx.deps.evidence_ids):
                    logging.getLogger("capyiot.agent").info(
                        "answer_rejected run=%s reason=UNKNOWN_EVIDENCE", ctx.deps.run_id
                    )
                    raise ModelRetry("证据引用不存在或尚未授权查询，请移除该陈述并说明未知信息")
                if re.search(
                    r"已确认.*根因|已证实.*根因|确定根因|根因是|确认为.*根因|\bCONFIRMED\b",
                    claim.statement,
                ) and not set(claim.evidenceIds).intersection(ctx.deps.confirmed_root_evidence):
                    logging.getLogger("capyiot.agent").info(
                        "answer_rejected run=%s reason=UNCONFIRMED_ROOT", ctx.deps.run_id
                    )
                    raise ModelRetry("只能描述候选根因或推断，未取得后端 CONFIRMED 根因证据")
            if not output.facts and not output.unknowns:
                raise ModelRetry("缺少已核查事实时必须说明未知信息")
            return output

    async def run(self, content: str, deps: AgentDeps, *, history=None) -> Answer:
        deps.principal.require("agent.use")
        if not content.strip() or len(content) > self.settings.max_input_chars:
            raise ServiceError("INVALID_ARGUMENT", 400, "消息为空或超出输入上限")
        usage = RunUsage()
        try:
            async with asyncio.timeout(self.settings.run_timeout_seconds):
                async with self.slots:
                    result = await self.agent.run(
                        content,
                        deps=deps,
                        message_history=history,
                        usage=usage,
                        usage_limits=UsageLimits(
                            request_limit=self.settings.max_model_requests,
                            tool_calls_limit=self.settings.max_tool_calls,
                            output_tokens_limit=self.settings.run_output_token_budget,
                        ),
                    )
                    return result.output
        except (TimeoutError, ModelRequestTimeout, ModelTimeoutError):
            raise ServiceError("MODEL_TIMEOUT", 504, "模型运行超时，请稍后重试") from None
        except UsageLimitExceeded:
            logging.getLogger("capyiot.agent").info(
                "run_budget_exceeded run=%s requests=%s tool_calls=%s output_tokens=%s",
                deps.run_id,
                usage.requests,
                usage.tool_calls,
                usage.output_tokens,
            )
            raise ServiceError("RUN_BUDGET_EXCEEDED", 409, "本次运行已达到预算上限") from None
        except OutputTokenLimitExceeded:
            logging.getLogger("capyiot.agent").info(
                "answer_rejected run=%s reason=OUTPUT_TRUNCATED", deps.run_id
            )
            raise ServiceError(
                "MODEL_OUTPUT_TOO_LONG", 502, "模型回答超出长度限制，请缩小查询范围"
            ) from None
        except UnexpectedModelBehavior as error:
            logging.getLogger("capyiot.agent").info(
                "answer_rejected run=%s reason=INVALID_OUTPUT category=%s requests=%s output_tokens=%s",
                deps.run_id,
                type(error).__name__,
                usage.requests,
                usage.output_tokens,
            )
            raise ServiceError("MODEL_INVALID_OUTPUT", 502, "模型未返回可核查的有效回答") from None
        except ModelAPIError:
            raise ServiceError("MODEL_UNAVAILABLE", 503, "模型服务暂不可用") from None

    async def close(self):
        if self.provider is not None:
            await self.provider.client.close()
