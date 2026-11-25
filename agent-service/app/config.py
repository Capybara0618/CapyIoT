from functools import cached_property
from urllib.parse import urlsplit

from pydantic import Field, SecretStr, ValidationError, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy import URL


class DatabaseSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="AGENT_", extra="ignore", hide_input_in_errors=True
    )
    db_host: str = Field(min_length=1)
    db_port: int = Field(default=3306, ge=1, le=65535)
    db_password: SecretStr

    @cached_property
    def database_url(self) -> URL:
        if not self.db_password.get_secret_value():
            raise ValueError("Database credentials must be configured")
        return URL.create(
            "mysql+pymysql",
            username="agent_app",
            password=self.db_password.get_secret_value(),
            host=self.db_host,
            port=self.db_port,
            database="agent_db",
            query={"charset": "utf8mb4"},
        )


class Settings(DatabaseSettings):
    core_base_url: str
    jwks_url: str
    deepseek_api_key: SecretStr = Field(validation_alias="DEEPSEEK_API_KEY")
    deepseek_model: str = Field(min_length=1, max_length=100, validation_alias="DEEPSEEK_MODEL")
    deepseek_base_url: str = Field(
        default="https://api.deepseek.com", validation_alias="DEEPSEEK_BASE_URL"
    )
    allow_insecure_model_endpoint: bool = False
    http_timeout_seconds: float = Field(default=5, gt=0, le=30)
    run_timeout_seconds: float = Field(default=60, gt=0, le=180)
    max_model_requests: int = Field(default=4, ge=1, le=10)
    max_tool_calls: int = Field(default=10, ge=1, le=20)
    max_output_tokens: int = Field(default=2048, ge=128, le=8192)
    max_input_chars: int = Field(default=12000, ge=100, le=40000)
    max_concurrent_runs: int = Field(default=4, ge=1, le=16)
    jwks_ttl_seconds: int = Field(default=300, ge=5, le=600)

    @model_validator(mode="after")
    def validate_configuration(self):
        if not self.db_password.get_secret_value() or not self.deepseek_api_key.get_secret_value():
            raise ValueError("Database and model credentials must be configured")
        for value in (self.core_base_url, self.jwks_url, self.deepseek_base_url):
            parsed = urlsplit(value)
            if (
                parsed.scheme not in {"http", "https"}
                or not parsed.hostname
                or parsed.username
                or parsed.password
                or parsed.query
                or parsed.fragment
            ):
                raise ValueError(
                    "Configured upstream URLs must have a host and no credentials/query"
                )
        if (
            not self.allow_insecure_model_endpoint
            and urlsplit(self.deepseek_base_url).scheme != "https"
        ):
            raise ValueError("The model endpoint requires HTTPS")
        return self


def load_settings() -> Settings:
    try:
        return Settings()
    except ValidationError as error:
        fields = sorted(
            {
                ".".join(map(str, e["loc"])) or "configuration"
                for e in error.errors(include_input=False)
            }
        )
        raise RuntimeError("Invalid agent configuration: " + ", ".join(fields)) from None
