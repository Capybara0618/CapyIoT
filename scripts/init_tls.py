# Historical Core deployment helper. Native deployments use init_microservices.py and microservices_control.py.
"""Provision local development CA/server certificates. Secrets stay under ignored var/."""

import os
import shutil
import subprocess
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    target = root / "var" / "tls"
    target.mkdir(parents=True, exist_ok=True)
    openssl = shutil.which("openssl")
    if not openssl:
        raise SystemExit("OpenSSL is required to provision local TLS certificates")
    config = target / "openssl.cnf"
    config.write_text("[req]\ndistinguished_name=dn\n[dn]\n", encoding="utf-8")

    def run(*args):
        result = subprocess.run([openssl, *args], cwd=target, capture_output=True, text=True)
        if result.returncode:
            raise SystemExit("Local certificate provisioning failed: " + result.stderr[-500:])

    if not (target / "ca.pem").exists():
        run("req", "-config", "openssl.cnf", "-x509", "-newkey", "rsa:3072", "-nodes", "-sha256", "-days", "3650",
            "-subj", "/CN=CapyIoT Local Development CA", "-keyout", "ca.key", "-out", "ca.pem")
    if not (target / "server.pem").exists():
        (target / "server.ext").write_text(
            "subjectAltName=DNS:localhost,DNS:emqx,DNS:frontend,DNS:gateway,DNS:core-service,IP:127.0.0.1\n"
            "extendedKeyUsage=serverAuth\nkeyUsage=digitalSignature,keyEncipherment\n", encoding="utf-8")
        run("req", "-config", "openssl.cnf", "-new", "-newkey", "rsa:3072", "-nodes", "-subj", "/CN=localhost",
            "-keyout", "server.key", "-out", "server.csr")
        run("x509", "-req", "-in", "server.csr", "-CA", "ca.pem", "-CAkey", "ca.key",
            "-CAcreateserial", "-out", "server.pem", "-days", "365", "-sha256", "-extfile", "server.ext")
    # Broker and Nginx run with different unprivileged container users. Their read-only mounts
    # are inside an isolated local Compose network; CA private key is never mounted.
    for name in ("server.key", "server.pem", "ca.pem"):
        os.chmod(target / name, 0o644)
    os.chmod(target / "ca.key", 0o600)
    print("Local TLS certificates ready under var/tls. Trust ca.pem for local HTTPS/MQTTS.")


if __name__ == "__main__":
    main()
