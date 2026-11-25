import asyncio
import re
import time

import httpx
import jwt
from fastapi import Request

from app.core_client import CoreApiClient, Principal
from app.errors import ServiceError, unavailable


class UserAuthenticator:
    def __init__(self, http, settings, core: CoreApiClient):
        self.http, self.settings, self.core = http, settings, core
        self.keys, self.expires, self.last_refresh = {}, 0, float("-inf")
        self.lock = asyncio.Lock()

    async def key(self, kid):
        async with self.lock:
            current = time.monotonic()
            expired = current >= self.expires
            if expired or (kid not in self.keys and current - self.last_refresh >= 5):
                self.last_refresh = current
                try:
                    response = await self.http.get(self.settings.jwks_url)
                    if response.status_code != 200 or len(response.content) > 65536:
                        raise ValueError("Invalid JWKS response")
                    entries = response.json()["keys"]
                    if not isinstance(entries, list) or not 1 <= len(entries) <= 16:
                        raise ValueError("Invalid key set")
                    keys = {}
                    for entry in entries:
                        identity = entry.get("kid")
                        if (
                            not isinstance(identity, str)
                            or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", identity)
                            or identity in keys
                        ):
                            raise ValueError("Invalid key id")
                        if (
                            entry.get("kty") != "RSA"
                            or entry.get("alg") not in {None, "RS256"}
                            or entry.get("use", "sig") != "sig"
                            or "d" in entry
                        ):
                            raise ValueError("Invalid signing key")
                        # Gateway's RSA JWK may omit optional alg; JWT verification stays RS256-only.
                        key = jwt.PyJWK.from_dict(entry, algorithm="RS256").key
                        if key.key_size < 2048:
                            raise ValueError("Invalid RSA size")
                        keys[identity] = key
                    self.keys, self.expires = keys, current + self.settings.jwks_ttl_seconds
                except (
                    httpx.HTTPError,
                    ValueError,
                    KeyError,
                    TypeError,
                    AttributeError,
                    jwt.PyJWTError,
                ):
                    # A failed refresh never extends the old keys' trust window.
                    if expired:
                        raise unavailable("IDENTITY_PROVIDER_UNAVAILABLE") from None
                    raise ServiceError("UNAUTHENTICATED", 401, "登录凭证无效") from None
            if kid not in self.keys:
                raise ServiceError("UNAUTHENTICATED", 401, "登录凭证无效")
            return self.keys[kid]

    async def authenticate(self, token, correlation):
        try:
            header = jwt.get_unverified_header(token)
            kid = header.get("kid")
            if (
                header.get("alg") != "RS256"
                or not isinstance(kid, str)
                or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", kid)
            ):
                raise ValueError("Invalid token header")
            key = await self.key(kid)
            claims = jwt.decode(
                token,
                key,
                algorithms=["RS256"],
                audience="capyiot-api",
                issuer="capyiot-gateway",
                options={"require": ["iss", "sub", "aud", "iat", "exp", "jti", "sid", "authz_ver"]},
            )
            for name in ("sub", "sid", "jti"):
                if not isinstance(claims[name], str) or not re.fullmatch(
                    r"[A-Za-z0-9_-]{1,64}", claims[name]
                ):
                    raise ValueError("Invalid identity claim")
            if type(claims["authz_ver"]) is not int or claims["authz_ver"] < 0:
                raise ValueError("Invalid authorization version")
            if (
                type(claims["iat"]) is not int
                or type(claims["exp"]) is not int
                or claims["exp"] <= claims["iat"]
            ):
                raise ValueError("Invalid token time")
        except (jwt.PyJWTError, ValueError, KeyError, TypeError):
            raise ServiceError("UNAUTHENTICATED", 401, "登录凭证无效") from None
        # Never cache user grants/session state. Core revalidates every request, including histories.
        user = await self.core.current_user(token, correlation)
        if (
            user.userId != claims["sub"]
            or user.status != "ACTIVE"
            or user.authorizationVersion != claims["authz_ver"]
        ):
            raise ServiceError("UNAUTHENTICATED", 401, "登录已失效，请重新登录")
        principal = Principal(
            user.userId,
            claims["sid"],
            user.authorizationVersion,
            frozenset(user.permissionCodes),
            token,
        )
        principal.require("agent.use")
        return principal


async def current_principal(request: Request) -> Principal:
    authorization = request.headers.get("Authorization", "")
    if not authorization.startswith("Bearer ") or not 1 <= len(authorization[7:]) <= 16384:
        raise ServiceError("UNAUTHENTICATED", 401, "请先登录")
    return await request.app.state.auth.authenticate(
        authorization[7:], request.state.correlation_id
    )
