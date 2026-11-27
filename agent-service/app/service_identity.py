"""Service identity is separate from human and confirmed-command credentials.

Interoperates with Java ServiceTokenVerifier. Only the fixed identity query is exposed;
neither model arguments nor HTTP input can select an internal operation or destination.
"""

import hashlib
import json
import re
import time
import uuid
from pathlib import Path

import jwt


class ServiceIdentity:
    def __init__(self, path):
        self.path = Path(path)
        self._load()

    def _load(self):
        try:
            if self.path.stat().st_size > 65536:
                raise ValueError()
            keys = json.loads(self.path.read_text(encoding="utf-8"))["keys"]
            if not isinstance(keys, list) or not 1 <= len(keys) <= 8:
                raise ValueError()
            ids = set()
            for entry in keys:
                kid = entry.get("kid")
                if (
                    entry.get("kty") != "RSA"
                    or entry.get("alg") not in {None, "RS256"}
                    or entry.get("use", "sig") != "sig"
                    or not isinstance(kid, str)
                    or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", kid)
                    or kid in ids
                ):
                    raise ValueError()
                key = jwt.algorithms.RSAAlgorithm.from_jwk(json.dumps(entry))
                if key.key_size < 2048:
                    raise ValueError()
                ids.add(kid)
            if "d" not in keys[0]:
                raise ValueError()
            return keys
        except (OSError, ValueError, KeyError, TypeError, jwt.PyJWTError):
            raise RuntimeError("Service signing key ring unavailable") from None

    def jwks(self):
        return {
            "keys": [
                {k: v for k, v in entry.items() if k in {"kty", "kid", "n", "e", "alg", "use"}}
                for entry in self._load()
            ]
        }

    def headers(self, actor, body, deadline):
        entry = self._load()[0]
        stamp = int(time.time())
        token = jwt.encode(
            {
                "iss": "capyiot-service/agent-service",
                "sub": "agent-service",
                "aud": "capyiot-service/platform-service",
                "iat": stamp,
                "exp": stamp + 60,
                "jti": str(uuid.uuid4()),
                "operation": "IDENTITY_CONTEXT",
                "scope": "identity.read",
                "method": "POST",
                "path": "/internal/v1/identity/context",
                "bodyDigest": hashlib.sha256(body).hexdigest(),
                "actorDigest": hashlib.sha256(actor.encode("ascii")).hexdigest(),
                "deadline": deadline,
            },
            jwt.algorithms.RSAAlgorithm.from_jwk(json.dumps(entry)),
            algorithm="RS256",
            headers={"kid": entry["kid"], "typ": "JWT"},
        )
        return {"X-Service-Authorization": "Bearer " + token, "X-CapyIoT-Deadline": str(deadline)}

    def __repr__(self):
        return "ServiceIdentity[REDACTED]"
