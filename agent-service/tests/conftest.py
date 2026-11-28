import pytest

from app.config import Settings


@pytest.fixture
def settings(tmp_path):
    import json

    import jwt
    from cryptography.hazmat.primitives.asymmetric import rsa

    key = jwt.algorithms.RSAAlgorithm.to_jwk(
        rsa.generate_private_key(public_exponent=65537, key_size=2048), as_dict=True
    )
    key.update(kid="service-test", alg="RS256", use="sig")
    key_file = tmp_path / "service-keys.json"
    key_file.write_text(json.dumps({"keys": [key]}), encoding="utf-8")
    from app.delegation import DelegationSigner
    delegation_file = tmp_path / "delegation-keys.json"
    DelegationSigner(str(delegation_file), generate=True)
    return Settings(
        db_host="127.0.0.1",
        db_password="test-password",
        platform_base_url="https://platform-fixture",
        device_base_url="https://device-fixture",
        fault_base_url="https://fault-fixture",
        workflow_base_url="https://workflow-fixture",
        service_key_file=str(key_file),
        delegation_key_file=str(delegation_file),
        internal_http_test_only=True,
        jwks_url="https://gateway-fixture/jwks",
        DEEPSEEK_API_KEY="offline-fixture-only",
        DEEPSEEK_MODEL="offline-fixture",
        DEEPSEEK_BASE_URL="https://model-fixture.invalid",
    )
