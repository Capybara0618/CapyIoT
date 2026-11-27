"""Independent Agent command identity. Neither human nor delegated JWTs are persisted."""

import json
import os
import time
import uuid
from pathlib import Path

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa


class DelegationSigner:
    def __init__(self, path, generate=False):
        self.path = Path(path)
        if not self.path.exists() and generate:
            self.rotate()
        self.load()

    def load(self):
        data = json.loads(self.path.read_text(encoding="utf-8"))
        if not 1 <= len(data["keys"]) <= 3:
            raise ValueError("Invalid Agent signing key set")
        active = next(k for k in data["keys"] if k["kid"] == data["activeKid"])
        private = serialization.load_pem_private_key(active["privateKey"].encode(), password=None)
        if not isinstance(private, rsa.RSAPrivateKey) or private.key_size < 2048:
            raise ValueError("Agent signing key must be RSA 2048 or greater")
        return data, active, private

    def rotate(self):
        previous = self.load()[0] if self.path.exists() else {"keys": []}
        private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        entry = {
            "kid": uuid.uuid4().hex,
            "privateKey": private.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            ).decode(),
            "publicKey": private.public_key()
            .public_bytes(
                serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
            )
            .decode(),
        }
        # Keep overlap public keys; retired private keys are removed from the active file.
        keys = [entry] + [
            {"kid": k["kid"], "publicKey": k["publicKey"]} for k in previous["keys"][:2]
        ]
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_name(self.path.name + "." + uuid.uuid4().hex + ".tmp")
        try:
            with temp.open("x", encoding="utf-8") as output:
                os.chmod(temp, 0o600)
                json.dump({"activeKid": entry["kid"], "keys": keys}, output)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temp, self.path)
        finally:
            temp.unlink(missing_ok=True)

    def jwks(self):
        data, _, _ = self.load()
        result = []
        for entry in data["keys"]:
            public = serialization.load_pem_public_key(entry["publicKey"].encode())
            key = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(public))
            result.append({**key, "kid": entry["kid"], "alg": "RS256", "use": "sig"})
        return {"keys": result}

    def issue(self, command):
        _, active, private = self.load()
        stamp = int(time.time())
        return jwt.encode(
            {
                "iss": "capyiot-agent-service",
                "sub": "capyiot-agent-service",
                "aud": "capyiot-workflow",
                "iat": stamp,
                "nbf": stamp,
                "exp": stamp + 60,
                "jti": uuid.uuid4().hex,
                "actorUserId": command["actorUserId"],
                "confirmationId": command["confirmationId"],
                "draftId": command["draftId"],
                "draftRevision": command["draftRevision"],
                "requestHash": command["requestHash"],
                "idempotencyKey": command["idempotencyKey"],
                "command": "CREATE_WORK_ORDER",
            },
            private,
            algorithm="RS256",
            headers={"kid": active["kid"]},
        )


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(
        description="Create/rotate the local Agent signing key; no key is printed"
    )
    parser.add_argument("--file", required=True)
    parser.add_argument("--rotate", action="store_true")
    args = parser.parse_args()
    signer = DelegationSigner(args.file, generate=True)
    if args.rotate:
        signer.rotate()
    print("Agent signing key file is ready; key material was not printed.")
