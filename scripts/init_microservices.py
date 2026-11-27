"""Prepare an ignored candidate bundle. Never starts Docker or changes a database.

Upgrade mode only reads the named source containers to export existing signing rings.
Run with the Agent's locked Python environment (cryptography and PyJWT).
"""

import argparse
import ipaddress
import json
import os
import re
import secrets
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

from microservices_common import (
    APPS,
    containers,
    name,
    private_path,
    read_env,
    run,
    sha,
    write_json,
)


def save(path, data, *, private=False):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.exists():
        return  # Existing credentials, signing keys and CA are never silently rotated.
    with path.open("xb") as output:
        output.write(data.encode() if isinstance(data, str) else data)
    # Container secrets are read-only file mounts to different non-root UIDs. Parent stays 0700.
    path.chmod(0o600 if private else 0o644)


def jwk_ring(path):
    import jwt
    from cryptography.hazmat.primitives.asymmetric import rsa

    if path.exists():
        keys = json.loads(path.read_text(encoding="utf-8"))["keys"]
        if not keys or "d" not in keys[0]:
            raise ValueError("An existing signing ring has no active private key")
        return
    key = jwt.algorithms.RSAAlgorithm.to_jwk(
        rsa.generate_private_key(public_exponent=65537, key_size=3072), as_dict=True
    )
    key.update(kid=uuid.uuid4().hex, alg="RS256", use="sig")
    save(path, json.dumps({"keys": [key]}))


def delegation_ring(path):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa

    if path.exists():
        data = json.loads(path.read_text(encoding="utf-8"))
        if not any(
            k["kid"] == data["activeKid"] and "privateKey" in k for k in data["keys"]
        ):
            raise ValueError("Existing delegation ring is incomplete")
        return
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    identity = uuid.uuid4().hex
    save(
        path,
        json.dumps(
            {
                "activeKid": identity,
                "keys": [
                    {
                        "kid": identity,
                        "privateKey": key.private_bytes(
                            serialization.Encoding.PEM,
                            serialization.PrivateFormat.PKCS8,
                            serialization.NoEncryption(),
                        ).decode(),
                        "publicKey": key.public_key()
                        .public_bytes(
                            serialization.Encoding.PEM,
                            serialization.PublicFormat.SubjectPublicKeyInfo,
                        )
                        .decode(),
                    }
                ],
            }
        ),
    )


def provision_tls(bundle, password, legacy_tls=None):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives.serialization import pkcs12
    from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

    now = datetime.now(timezone.utc)
    ca_key_file, ca_file = bundle / "pki/ca.key", bundle / "pki/ca.pem"
    if ca_key_file.exists() != ca_file.exists():
        raise ValueError(
            "Partial CA found; inspect it instead of replacing the trust anchor"
        )
    if not ca_file.exists():
        key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        subject = x509.Name(
            [x509.NameAttribute(NameOID.COMMON_NAME, "CapyIoT Internal CA")]
        )
        cert = (
            x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(subject)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(minutes=5))
            .not_valid_after(now + timedelta(days=3650))
            .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
            .add_extension(
                x509.KeyUsage(
                    False, False, False, False, False, True, True, None, None
                ),
                critical=True,
            )
            .sign(key, hashes.SHA256())
        )
        save(
            ca_key_file,
            key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            ),
            private=True,
        )
        save(ca_file, cert.public_bytes(serialization.Encoding.PEM))
    ca_key = serialization.load_pem_private_key(ca_key_file.read_bytes(), password=None)
    ca = x509.load_pem_x509_certificate(ca_file.read_bytes())
    for service in (*APPS, "edge"):
        folder = bundle / "pki" / service
        cert_file, key_file, store = (
            folder / "server.pem",
            folder / "server.key",
            folder / "server.p12",
        )
        if service == "edge" and legacy_tls:
            for origin, target in (
                ("ca.pem", folder / "ca.pem"),
                ("server.pem", cert_file),
                ("server.key", key_file),
            ):
                source = legacy_tls / origin
                if not source.is_file():
                    raise ValueError("Original edge certificate material is required")
                save(target, source.read_bytes())
            continue
        if cert_file.exists() != key_file.exists():
            raise ValueError("Partial leaf certificate found; no replacement was made")
        if not cert_file.exists():
            key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
            dns = (
                [service, "localhost"]
                if service != "edge"
                else ["frontend", "emqx", "localhost"]
            )
            cert = (
                x509.CertificateBuilder()
                .subject_name(
                    x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, dns[0])])
                )
                .issuer_name(ca.subject)
                .public_key(key.public_key())
                .serial_number(x509.random_serial_number())
                .not_valid_before(now - timedelta(minutes=5))
                .not_valid_after(now + timedelta(days=365))
                .add_extension(
                    x509.SubjectAlternativeName(
                        [
                            *(x509.DNSName(host) for host in dns),
                            x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
                        ]
                    ),
                    False,
                )
                .add_extension(x509.BasicConstraints(ca=False, path_length=None), True)
                .add_extension(
                    x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), False
                )
                .sign(ca_key, hashes.SHA256())
            )
            save(
                key_file,
                key.private_bytes(
                    serialization.Encoding.PEM,
                    serialization.PrivateFormat.PKCS8,
                    serialization.NoEncryption(),
                ),
            )
            save(cert_file, cert.public_bytes(serialization.Encoding.PEM))
        key = serialization.load_pem_private_key(key_file.read_bytes(), password=None)
        cert = x509.load_pem_x509_certificate(cert_file.read_bytes())
        save(
            store,
            pkcs12.serialize_key_and_certificates(
                service.encode(),
                key,
                cert,
                [ca],
                serialization.BestAvailableEncryption(password.encode()),
            ),
        )
        if service == "edge":
            save(folder / "ca.pem", ca_file.read_bytes())


def export_rings(bundle, source_project, import_keys):
    original = containers(source_project) if not import_keys else []
    for service, filename in (
        ("gateway", "user-keys.json"),
        ("core-service", "device-keys.json"),
        ("agent-service", "agent-keys.json"),
    ):
        target = bundle / "keys" / filename
        if target.exists():
            continue
        if import_keys:
            source = import_keys / filename
            if not source.is_file():
                raise ValueError(
                    "All three original signing rings are required for an upgrade"
                )
            save(target, source.read_bytes())
        else:
            candidates = [c for c in original if c["service"] == service]
            if len(candidates) != 1:
                raise ValueError(
                    "Cannot identify original signing owner; export rings explicitly"
                )
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            run(
                [
                    "docker",
                    "cp",
                    candidates[0]["id"] + ":/keys/" + filename,
                    str(target),
                ]
            )
            target.chmod(0o644)


def initialize(args):
    project = name(args.project)
    bundle = private_path(args.bundle)
    bundle.mkdir(parents=True, exist_ok=True, mode=0o700)
    bundle.chmod(0o700)
    env_file = bundle / "deployment.env"
    existing = read_env(env_file) if env_file.exists() else {}
    previous = (
        json.loads((bundle / "manifest.json").read_text(encoding="utf-8"))
        if (bundle / "manifest.json").exists()
        else None
    )
    if previous and (previous["project"] != project or previous["mode"] != args.mode):
        raise ValueError("An existing bundle cannot change project or upgrade mode")
    if previous:
        for relative, expected in previous["files"].items():
            path = (bundle / relative).resolve()
            if (
                bundle not in path.parents
                or not path.is_file()
                or sha(path) != expected
            ):
                raise ValueError(
                    "Previously provisioned material is missing or changed; do not silently replace it"
                )
    legacy = read_env(args.legacy_env) if args.mode == "upgrade" else {}
    if args.mode == "upgrade" and (not args.source_project or not args.legacy_tls):
        raise ValueError(
            "Upgrade requires the explicit source project, legacy env and edge TLS directory"
        )
    prefix = name(args.volume_prefix or project)
    if args.mode == "upgrade" and not args.volume_prefix:
        raise ValueError(
            "Upgrade must explicitly reuse the source physical volume prefix"
        )
    if existing and (
        existing["MS_VOLUME_PREFIX"] != prefix
        or existing["MS_MODEL_MODE"] != args.model_mode
    ):
        raise ValueError(
            "Existing volume or model configuration cannot be silently changed"
        )
    # Only a fresh installation may mint new database credentials and login password hashes.
    if args.mode == "fresh":
        from init_env import password_hash

        admin, operator = secrets.token_urlsafe(18), secrets.token_urlsafe(18)
        defaults = {
            "MYSQL_ROOT_PASSWORD": secrets.token_hex(24),
            **{
                domain + "_DB_PASSWORD": secrets.token_hex(24)
                for domain in ("DEVICE", "ALARM", "WORKFLOW", "PLATFORM", "AGENT")
            },
            "EMQX_API_KEY": secrets.token_hex(12),
            "EMQX_API_SECRET": secrets.token_hex(32),
            "EMQX_NODE_COOKIE": secrets.token_hex(24),
            "GRAFANA_ADMIN_PASSWORD": secrets.token_hex(24),
            "DEMO_ADMIN_PASSWORD": admin,
            "DEMO_OPERATOR_PASSWORD": operator,
            "DEMO_ADMIN_PASSWORD_HASH": password_hash(admin),
            "DEMO_OPERATOR_PASSWORD_HASH": password_hash(operator),
        }
    else:
        defaults = legacy
    if args.mode == "fresh":
        for role in ("admin", "operator"):
            plain = bundle / "secrets" / ("demo-" + role + "-password")
            hashed = bundle / "secrets" / ("demo-" + role + "-password-hash")
            if plain.exists() != hashed.exists():
                raise ValueError(
                    "Partial fresh login credentials; recover the original pair"
                )
    required = (
        "MYSQL_ROOT_PASSWORD",
        "DEVICE_DB_PASSWORD",
        "ALARM_DB_PASSWORD",
        "WORKFLOW_DB_PASSWORD",
        "PLATFORM_DB_PASSWORD",
        "AGENT_DB_PASSWORD",
        "EMQX_API_KEY",
        "EMQX_API_SECRET",
        "EMQX_NODE_COOKIE",
        "GRAFANA_ADMIN_PASSWORD",
        "DEMO_ADMIN_PASSWORD_HASH",
        "DEMO_OPERATOR_PASSWORD_HASH",
    )
    for key in required:
        if (
            not defaults.get(key)
            and not (bundle / "secrets" / key.lower().replace("_", "-")).exists()
        ):
            raise ValueError("Original credential missing: " + key)
        save(bundle / "secrets" / key.lower().replace("_", "-"), defaults.get(key, ""))
    for domain in ("device", "alarm", "workflow", "platform", "agent"):
        value = (bundle / "secrets" / (domain + "-db-password")).read_text(
            encoding="utf-8"
        )
        if not value or any(ord(char) < 32 for char in value):
            raise ValueError(
                "Original database credential is empty or cannot be represented as one secret line"
            )
        if args.mode == "fresh" and not re.fullmatch(r"[a-fA-F0-9]{32,128}", value):
            raise ValueError(
                "Fresh database secrets must match the initializer format; existing account passwords are preserved in upgrade mode"
            )
    fingerprint_key = legacy.get("CORE_SERVICE_KEY")
    for domain in ("DEVICE", "FAULT", "WORKFLOW", "PLATFORM"):
        value = legacy.get(domain + "_COMMAND_FINGERPRINT_KEY") or fingerprint_key
        path = bundle / "secrets" / (domain.lower() + "-fingerprint-key")
        if args.mode == "upgrade" and not value and not path.exists():
            raise ValueError("Original command fingerprint key is required")
        save(path, value or secrets.token_urlsafe(48))
    save(
        bundle / "secrets/session-proxy-key",
        legacy.get("SESSION_PROXY_KEY") or secrets.token_urlsafe(48),
    )
    save(bundle / "secrets/tls-password", secrets.token_urlsafe(32))
    for key in ("DEMO_ADMIN_PASSWORD", "DEMO_OPERATOR_PASSWORD"):
        if defaults.get(key):
            save(
                bundle / "secrets" / key.lower().replace("_", "-"),
                defaults[key],
                private=True,
            )
    model_key = (
        "offline-fixture-only"
        if args.model_mode == "offline"
        else legacy.get("DEEPSEEK_API_KEY") or os.environ.get("DEEPSEEK_API_KEY")
    )
    if not model_key and not (bundle / "secrets/deepseek-api-key").exists():
        raise ValueError(
            "Real model mode needs a locally configured DEEPSEEK_API_KEY; never send it in chat"
        )
    save(bundle / "secrets/deepseek-api-key", model_key or "")
    if args.mode == "upgrade":
        export_rings(bundle, name(args.source_project), args.import_keys)
    for ring in ("user-keys.json", "device-keys.json"):
        jwk_ring(bundle / "keys" / ring)
    delegation_ring(bundle / "keys/agent-keys.json")
    for app in APPS:
        jwk_ring(bundle / "keys" / (app + ".json"))
    provision_tls(
        bundle,
        (bundle / "secrets/tls-password").read_text(encoding="utf-8"),
        args.legacy_tls if args.mode == "upgrade" else None,
    )
    save(
        bundle / "secrets/emqx-api-keys",
        (bundle / "secrets/emqx-api-key").read_text(encoding="utf-8")
        + ":"
        + (bundle / "secrets/emqx-api-secret").read_text(encoding="utf-8")
        + "\n",
    )
    values = {
        "MS_PROJECT": project,
        "MS_BUNDLE": bundle.as_posix(),
        "MS_VOLUME_PREFIX": prefix,
        "MS_WRITERS_ENABLED": "false",
        "MS_MODEL_MODE": args.model_mode,
        "MS_HTTP_PORT": "28088",
        "MS_HTTPS_PORT": "28443",
        "MS_MQTT_PORT": "28883",
        "MS_PROMETHEUS_PORT": "29095",
        "MS_GRAFANA_PORT": "23000",
        "MS_JAEGER_PORT": "26686",
        "MS_IMAGE_TAG": "candidate",
        "MS_PLATFORM_BOOTSTRAP": "true" if args.mode == "fresh" else "false",
        "DEEPSEEK_MODEL": "offline-fixture"
        if args.model_mode == "offline"
        else legacy.get("DEEPSEEK_MODEL") or os.environ.get("DEEPSEEK_MODEL", ""),
        "DEEPSEEK_BASE_URL": legacy.get(
            "DEEPSEEK_BASE_URL", "https://api.deepseek.com"
        ),
        "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT": "",
        "AGENT_MODEL_TIMEOUT_SECONDS": legacy.get("AGENT_MODEL_TIMEOUT_SECONDS", "30"),
        "AGENT_RUN_OUTPUT_TOKEN_BUDGET": legacy.get(
            "AGENT_RUN_OUTPUT_TOKEN_BUDGET", "8192"
        ),
    }
    values.update(existing)
    if not values["DEEPSEEK_MODEL"]:
        raise ValueError("DEEPSEEK_MODEL is required in real model mode")
    if not env_file.exists():
        save(
            env_file,
            "\n".join(k + "=" + v for k, v in values.items()) + "\n",
            private=True,
        )
    save(bundle / "video-devices.json", "[]", private=True)
    files = {
        p.relative_to(bundle).as_posix(): sha(p)
        for folder in ("keys", "secrets", "pki")
        for p in (bundle / folder).rglob("*")
        if p.is_file()
    }
    write_json(
        bundle / "manifest.json",
        {
            "status": "READY",
            "project": project,
            "mode": args.mode,
            "sourceProject": args.source_project,
            "volumePrefix": prefix,
            "modelMode": args.model_mode,
            "files": files,
            "initializedAt": datetime.now(timezone.utc).isoformat(),
        },
    )
    print(
        "Prepared private candidate bundle; no containers, databases or existing credentials were changed: "
        + str(bundle)
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--mode", choices=("fresh", "upgrade"), required=True)
    parser.add_argument("--model-mode", choices=("offline", "real"), default="offline")
    parser.add_argument("--source-project")
    parser.add_argument("--volume-prefix")
    parser.add_argument("--legacy-env", type=Path)
    parser.add_argument("--legacy-tls", type=Path)
    parser.add_argument("--import-keys", type=Path)
    args = parser.parse_args()
    if args.mode == "upgrade" and not args.legacy_env:
        parser.error("Upgrade requires --legacy-env")
    try:
        initialize(args)
    except (ValueError, RuntimeError, OSError, KeyError):
        raise SystemExit(
            "Candidate initialization stopped; check required source material and bundle consistency. No credential was intentionally replaced."
        ) from None


if __name__ == "__main__":
    main()
