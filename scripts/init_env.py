"""Create local-only credentials. Existing configuration is preserved."""

import base64
import hashlib
import secrets
from pathlib import Path


def password_hash(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 310_000, 32)
    return (salt + digest).hex()


def main() -> None:
    destination = Path(__file__).resolve().parents[1] / ".env"
    admin_password = secrets.token_urlsafe(18)
    operator_password = secrets.token_urlsafe(18)
    values = {
        "MYSQL_ROOT_PASSWORD": secrets.token_hex(24),
        "DEVICE_DB_PASSWORD": secrets.token_hex(24),
        "ALARM_DB_PASSWORD": secrets.token_hex(24),
        "WORKFLOW_DB_PASSWORD": secrets.token_hex(24),
        "JWT_SECRET": base64.b64encode(secrets.token_bytes(48)).decode(),
        "DEMO_ADMIN_PASSWORD": admin_password,
        "DEMO_ADMIN_PASSWORD_HASH": password_hash(admin_password),
        "DEMO_OPERATOR_PASSWORD": operator_password,
        "DEMO_OPERATOR_PASSWORD_HASH": password_hash(operator_password),
        "GRAFANA_ADMIN_PASSWORD": secrets.token_urlsafe(24),
        "EMQX_NODE_COOKIE": secrets.token_hex(24),
        "DEVICE_JWT_SECRET": base64.b64encode(secrets.token_bytes(48)).decode(),
    }
    try:
        with destination.open("x", encoding="utf-8", newline="\n") as config:
            config.write("# Generated local credentials. Keep this file private.\n")
            for key, value in values.items():
                config.write(f"{key}={value}\n")
    except FileExistsError:
        existing = {line.split("=", 1)[0] for line in destination.read_text(encoding="utf-8").splitlines() if "=" in line and not line.startswith("#")}
        missing = {key: value for key, value in values.items() if key not in existing}
        with destination.open("a", encoding="utf-8", newline="\n") as config:
            for key, value in missing.items():
                config.write(f"{key}={value}\n")
        print(f"Existing credentials preserved. Added {len(missing)} new configuration keys.")
        return
    print("Created .env. Read DEMO_ADMIN_PASSWORD or DEMO_OPERATOR_PASSWORD there to log in.")


if __name__ == "__main__":
    main()
