import time

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

from app.auth import UserAuthenticator
from app.core_client import CoreApiClient, Principal
from app.errors import ServiceError


@pytest.fixture
def identity():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public = jwt.algorithms.RSAAlgorithm.to_jwk(key.public_key(), as_dict=True)
    public.update(kid="key-one", alg="RS256", use="sig")
    claims = {"iss": "capyiot-gateway", "aud": "capyiot-api", "sub": "user-one", "sid": "session-one",
              "jti": "token-one", "authz_ver": 1, "iat": int(time.time()), "exp": int(time.time())+300}
    return key, public, claims


async def test_identity_rechecks_current_authorization_and_caches_only_keys(settings, identity):
    key, public, claims = identity
    calls, active = [], True
    async def transport(request):
        calls.append(request.url.path)
        if request.url.host == "gateway-fixture":
            return httpx.Response(200, json={"keys": [public]})
        if not active:
            return httpx.Response(401, json={"message": "private backend diagnostic"})
        return httpx.Response(200, json={"code": "OK", "data": {"userId": "user-one", "status": "ACTIVE",
                             "authorizationVersion": 1, "permissionCodes": ["agent.use"]}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        auth = UserAuthenticator(http, settings, CoreApiClient(http, settings.core_base_url))
        token = jwt.encode(claims, key, algorithm="RS256", headers={"kid": "key-one"})
        assert (await auth.authenticate(token, "correlation")).user_id == "user-one"
        active = False
        with pytest.raises(ServiceError) as error:
            await auth.authenticate(token, "correlation")
        assert error.value.status == 401
        assert calls.count("/jwks") == 1
        assert calls.count("/api/v1/auth/me") == 2
        assert token not in repr(error.value)


@pytest.mark.parametrize("change", [{"aud": "capyiot-emqx"}, {"iss": "device-service"}, {"sid": None},
                                   {"authz_ver": True}, {"exp": 1}, {"sub": "../other"}])
async def test_invalid_claims_are_rejected(settings, identity, change):
    key, public, claims = identity
    claims.update(change)
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, json={"keys": [public]}))) as http:
        auth = UserAuthenticator(http, settings, CoreApiClient(http, settings.core_base_url))
        token = jwt.encode(claims, key, algorithm="RS256", headers={"kid": "key-one"})
        with pytest.raises(ServiceError) as error:
            await auth.authenticate(token, "test")
        assert error.value.status == 401


async def test_rotation_overlap_and_failed_refresh_do_not_extend_key_lifetime(settings, identity):
    _, public, _ = identity
    second_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    second = jwt.algorithms.RSAAlgorithm.to_jwk(second_key.public_key(), as_dict=True)
    second.update(kid="key-two", alg="RS256", use="sig")
    keys, online = [public], True
    def transport(_):
        return httpx.Response(200, json={"keys": keys}) if online else httpx.Response(503)
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        auth = UserAuthenticator(http, settings, CoreApiClient(http, settings.core_base_url))
        await auth.key("key-one")
        keys = [public, second]
        auth.last_refresh = float("-inf")
        await auth.key("key-two")
        assert len(auth.keys) == 2
        keys = [second]
        auth.expires = 0
        with pytest.raises(ServiceError) as error:
            await auth.key("key-one")
        assert error.value.status == 401
        online, auth.expires = False, 0
        with pytest.raises(ServiceError) as error:
            await auth.key("key-two")
        assert error.value.status == 503


@pytest.mark.parametrize("status", [400,401,403,404,409,503])
async def test_core_normalizes_errors_without_raw_payload(status, settings):
    calls = []
    def transport(request):
        calls.append(request)
        return httpx.Response(status, json={"message": "password=private-sql-diagnostic"})
    user = Principal("u", "s", 1, frozenset({"agent.use", "device.read"}), "user-secret-token")
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        with pytest.raises(ServiceError) as error:
            await CoreApiClient(http, settings.core_base_url).read("query_devices", user, "trace")
    assert error.value.status == status
    assert len(calls) == (2 if status >= 500 else 1)
    assert "private" not in str(error.value)


async def test_transport_get_retries_but_post_preserves_unknown_outcome(settings):
    calls = []
    def transport(request):
        calls.append(request)
        raise httpx.ReadTimeout("sensitive provider error", request=request)
    user = Principal("u", "s", 1, frozenset({"agent.use", "device.read", "agent.draft.confirm", "workorder.create"}), "secret-token")
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        core = CoreApiClient(http, settings.core_base_url)
        with pytest.raises(ServiceError) as error:
            await core.read("query_devices", user, "trace")
        assert error.value.code == "UPSTREAM_TIMEOUT" and len(calls) == 2
        calls.clear()
        with pytest.raises(ServiceError) as error:
            await core.create_work_order(user, "trace", {"title": "repair"}, "stable-key")
        assert error.value.code == "COMMAND_OUTCOME_UNKNOWN" and len(calls) == 1
        assert calls[0].headers["Idempotency-Key"] == "stable-key"


async def test_route_and_scope_parameters_cannot_be_injected(settings):
    user = Principal("u", "s", 1, frozenset({"agent.use", "device.read"}), "token")
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: pytest.fail("No HTTP request is allowed"))) as http:
        core = CoreApiClient(http, settings.core_base_url)
        for operation, filters in [("http://attacker/", {}), ("query_devices", {"parkId": "other"}), ("query_devices", {"size": 51})]:
            with pytest.raises(ServiceError):
                await core.read(operation, user, "trace", filters=filters)
