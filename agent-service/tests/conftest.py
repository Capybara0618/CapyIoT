import pytest

from app.config import Settings


@pytest.fixture
def settings():
    return Settings(db_host="127.0.0.1", db_password="test-password",
                    core_base_url="http://core-fixture:8085", jwks_url="http://gateway-fixture/jwks",
                    DEEPSEEK_API_KEY="offline-fixture-only", DEEPSEEK_MODEL="offline-fixture")
