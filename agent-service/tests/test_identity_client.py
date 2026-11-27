import time

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

from app.auth import UserAuthenticator
from app.business_client import BusinessApiClient, Principal
from app.errors import ServiceError
from app.service_identity import ServiceIdentity


@pytest.fixture
def identity():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public = jwt.algorithms.RSAAlgorithm.to_jwk(key.public_key(), as_dict=True)
    public.update(kid="key-one", alg="RS256", use="sig")
    claims = {
        "iss": "capyiot-gateway",
        "aud": "capyiot-api",
        "sub": "user-one",
        "sid": "session-one",
        "jti": "token-one",
        "authz_ver": 1,
        "iat": int(time.time()),
        "exp": int(time.time()) + 300,
    }
    return key, public, claims


async def test_identity_rechecks_current_authorization_and_caches_only_keys(settings, identity):
    key, public, claims = identity
    public.pop("alg")  # Nimbus Gateway publishes a valid RSA JWK without this optional field.
    calls, active = [], True

    async def transport(request):
        calls.append(request.url.path)
        if request.url.host == "gateway-fixture":
            return httpx.Response(200, json={"keys": [public]})
        if not active:
            return httpx.Response(401, json={"message": "private backend diagnostic"})
        return httpx.Response(
            200,
            json={
                "code": "OK",
                "data": {
                    "userId": "user-one",
                    "status": "ACTIVE",
                    "sessionId": "session-one",
                    "authorizationVersion": 1,
                    "permissionCodes": ["agent.use"],
                },
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        auth = UserAuthenticator(
            http,
            settings,
            BusinessApiClient(http, settings, ServiceIdentity(settings.service_key_file)),
        )
        token = jwt.encode(claims, key, algorithm="RS256", headers={"kid": "key-one"})
        assert (await auth.authenticate(token, "correlation")).user_id == "user-one"
        active = False
        with pytest.raises(ServiceError) as error:
            await auth.authenticate(token, "correlation")
        assert error.value.status == 401
        assert calls.count("/jwks") == 1
        assert calls.count("/internal/v1/identity/context") == 2
        assert token not in repr(error.value)


@pytest.mark.parametrize(
    "change",
    [
        {"aud": "capyiot-emqx"},
        {"iss": "device-service"},
        {"sid": None},
        {"authz_ver": True},
        {"exp": 1},
        {"sub": "../other"},
    ],
)
async def test_invalid_claims_are_rejected(settings, identity, change):
    key, public, claims = identity
    claims.update(change)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json={"keys": [public]}))
    ) as http:
        auth = UserAuthenticator(
            http,
            settings,
            BusinessApiClient(http, settings, ServiceIdentity(settings.service_key_file)),
        )
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
        auth = UserAuthenticator(
            http,
            settings,
            BusinessApiClient(http, settings, ServiceIdentity(settings.service_key_file)),
        )
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


@pytest.mark.parametrize("status", [400, 401, 403, 404, 409, 503])
async def test_core_normalizes_errors_without_raw_payload(status, settings):
    calls = []

    def transport(request):
        calls.append(request)
        return httpx.Response(status, json={"message": "password=private-sql-diagnostic"})

    user = Principal("u", "s", 1, frozenset({"agent.use", "device.read"}), "user-secret-token")
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        with pytest.raises(ServiceError) as error:
            await BusinessApiClient(
                http, settings, ServiceIdentity(settings.service_key_file)
            ).read("query_devices", user, "trace")
    assert error.value.status == status
    assert len(calls) == (2 if status >= 500 else 1)
    assert "private" not in str(error.value)


async def test_transport_get_retries_but_post_preserves_unknown_outcome(settings):
    calls = []

    def transport(request):
        calls.append(request)
        raise httpx.ReadTimeout("sensitive provider error", request=request)

    user = Principal(
        "u",
        "s",
        1,
        frozenset({"agent.use", "device.read", "agent.draft.confirm", "workorder.create"}),
        "secret-token",
    )
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        business = BusinessApiClient(http, settings, ServiceIdentity(settings.service_key_file))
        with pytest.raises(ServiceError) as error:
            await business.read("query_devices", user, "trace")
        assert error.value.code == "UPSTREAM_TIMEOUT" and len(calls) == 2
        calls.clear()
        with pytest.raises(ServiceError) as error:
            await business.create_work_order(user, "trace", {"title": "repair"}, "stable-key")
        assert error.value.code == "COMMAND_OUTCOME_UNKNOWN" and len(calls) == 1
        assert calls[0].headers["Idempotency-Key"] == "stable-key"


async def test_route_and_scope_parameters_cannot_be_injected(settings):
    user = Principal("u", "s", 1, frozenset({"agent.use", "device.read"}), "token")
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: pytest.fail("No HTTP request is allowed"))
    ) as http:
        business = BusinessApiClient(http, settings, ServiceIdentity(settings.service_key_file))
        for operation, filters in [
            ("http://attacker/", {}),
            ("query_devices", {"parkId": "other"}),
            ("query_devices", {"size": 51}),
        ]:
            with pytest.raises(ServiceError):
                await business.read(operation, user, "trace", filters=filters)


@pytest.mark.parametrize("changes", [{"alg": "RS512"}, {"alg": "HS256"}, {"use": "enc"}])
async def test_conflicting_jwk_metadata_is_rejected(settings, identity, changes):
    _, public, _ = identity
    public.update(changes)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json={"keys": [public]}))
    ) as http:
        auth = UserAuthenticator(
            http,
            settings,
            BusinessApiClient(http, settings, ServiceIdentity(settings.service_key_file)),
        )
        with pytest.raises(ServiceError):
            await auth.key("key-one")


@pytest.mark.parametrize("status,body", [(500, b"private-diagnostic"), (200, b"invalid-json")])
async def test_write_server_error_or_invalid_reply_preserves_unknown_outcome(
    settings, status, body
):
    calls = []

    def transport(request):
        calls.append(request)
        return httpx.Response(status, content=body)

    user = Principal(
        "u", "s", 1, frozenset({"agent.draft.confirm", "workorder.create"}), "private-token"
    )
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        with pytest.raises(ServiceError) as error:
            await BusinessApiClient(
                http, settings, ServiceIdentity(settings.service_key_file)
            ).create_work_order(user, "trace", {"title": "repair"}, "same-key")
        assert error.value.code == "COMMAND_OUTCOME_UNKNOWN"
        assert len(calls) == 1 and calls[0].headers["Idempotency-Key"] == "same-key"


@pytest.mark.parametrize(
    "operation,identity,owner",
    [
        ("query_devices", None, "device-fixture"),
        ("get_device_detail", "device-1", "device-fixture"),
        ("get_device_status_history", "device-1", "device-fixture"),
        ("get_topology_context", None, "device-fixture"),
        ("query_anomalies", None, "fault-fixture"),
        ("query_incidents", None, "fault-fixture"),
        ("get_incident_detail", "incident-1", "fault-fixture"),
        ("query_work_orders", None, "workflow-fixture"),
        ("get_maintenance_history", "device-1", "workflow-fixture"),
    ],
)
async def test_nine_reads_use_fixed_business_owners(settings, operation, identity, owner):
    requests = []

    def transport(request):
        requests.append(request)
        return httpx.Response(200, json={"code": "OK", "data": {}})

    principal = Principal(
        "user",
        "session",
        1,
        frozenset(
            {
                "agent.use",
                "device.read",
                "topology.read",
                "anomaly.read",
                "incident.read",
                "workorder.read",
            }
        ),
        "opaque-user-token",
    )
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        await BusinessApiClient(http, settings).read(
            operation, principal, "trace", identity=identity
        )
    assert len(requests) == 1 and requests[0].url.host == owner
    assert "X-Service-Authorization" not in requests[0].headers


async def test_identity_service_jwt_binds_exact_body_actor_and_deadline(settings):
    import hashlib

    signer = ServiceIdentity(settings.service_key_file)
    requests = []

    def transport(request):
        requests.append(request)
        return httpx.Response(
            200,
            json={
                "code": "OK",
                "data": {
                    "userId": "user",
                    "sessionId": "session",
                    "authorizationVersion": 1,
                    "permissionCodes": ["agent.use"],
                },
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        await BusinessApiClient(http, settings, signer).current_user("actor.jwt.token", "trace")
    request = requests[0]
    claims = jwt.decode(
        request.headers["X-Service-Authorization"][7:],
        jwt.PyJWK.from_dict(signer.jwks()["keys"][0]).key,
        algorithms=["RS256"],
        issuer="capyiot-service/agent-service",
        audience="capyiot-service/platform-service",
    )
    assert request.url.host == "platform-fixture" and request.method == "POST"
    assert request.url.path == claims["path"] == "/internal/v1/identity/context"
    assert claims["operation"] == "IDENTITY_CONTEXT" and claims["scope"] == "identity.read"
    assert claims["bodyDigest"] == hashlib.sha256(request.content).hexdigest()
    assert claims["actorDigest"] == hashlib.sha256(b"actor.jwt.token").hexdigest()
    assert claims["deadline"] == int(request.headers["X-CapyIoT-Deadline"])
    assert claims["deadline"] <= (claims["iat"] + 10) * 1000
    assert not {"d", "p", "q", "dp", "dq", "qi"} & signer.jwks()["keys"][0].keys()


async def test_platform_context_must_match_the_current_session(settings, identity):
    key, public, claims = identity

    def transport(request):
        if request.url.host == "gateway-fixture":
            return httpx.Response(200, json={"keys": [public]})
        return httpx.Response(
            200,
            json={
                "code": "OK",
                "data": {
                    "userId": "user-one",
                    "sessionId": "different-session",
                    "authorizationVersion": 1,
                    "permissionCodes": ["agent.use"],
                },
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        auth = UserAuthenticator(
            http,
            settings,
            BusinessApiClient(http, settings, ServiceIdentity(settings.service_key_file)),
        )
        token = jwt.encode(claims, key, algorithm="RS256", headers={"kid": "key-one"})
        with pytest.raises(ServiceError) as error:
            await auth.authenticate(token, "trace")
        assert error.value.status == 401


@pytest.mark.parametrize(
    "status,code", [(409, "WORKORDER_OPERATION_ABORTED"), (503, "WORKORDER_OPERATION_PENDING")]
)
async def test_workflow_operation_state_is_safe_and_not_flattened(settings, status, code):
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                status,
                json={
                    "code": code,
                    "message": "private-diagnostic",
                    "details": {"operationId": "operation-1", "sql": "secret"},
                },
            )
        )
    ) as http:
        with pytest.raises(ServiceError) as error:
            await BusinessApiClient(http, settings).command_receipt(
                {"idempotencyKey": "key-1", "commandId": "command-1"}, "delegate"
            )
        assert error.value.code == code
        assert error.value.details == {"operationId": "operation-1"}
        assert "private" not in error.value.message
