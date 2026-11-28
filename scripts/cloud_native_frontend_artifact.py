"""Package the platform-independent static output already built by the frontend check."""

import hashlib
import json
import shutil
from pathlib import Path

from microservices_common import ROOT, private_path, sha, write_json


def inventory(folder):
    folder=Path(folder).resolve()
    files={}
    for file in sorted(folder.rglob("*")):
        if file.is_symlink(): raise ValueError("Static artifacts cannot contain symlinks")
        if file.is_file(): files[file.relative_to(folder).as_posix()]=sha(file)
    if "index.html" not in files or not any(p.endswith(".js") for p in files):
        raise ValueError("A complete static frontend build is required")
    return files


def source_hash():
    digest=hashlib.sha256()
    for file in sorted((ROOT/"frontend/src").rglob("*")):
        if file.is_file(): digest.update(file.relative_to(ROOT).as_posix().encode()+file.read_bytes())
    digest.update((ROOT/"frontend/package-lock.json").read_bytes())
    return digest.hexdigest()


def attest(proof, build_log):
    proof=private_path(proof);build_log=private_path(build_log)
    if proof.exists(): raise ValueError("Artifact proof already exists")
    if "built in" not in build_log.read_text(encoding="utf-8",errors="replace"):
        raise ValueError("Successful frontend build log is required")
    write_json(proof,{"sourceHash":source_hash(),"files":inventory(ROOT/"frontend/dist"),
                      "buildLogSha256":sha(build_log),"buildLog":str(build_log)})


def package(proof, context):
    proof=private_path(proof);context=private_path(context)
    value=json.loads(proof.read_text())
    if value["sourceHash"] != source_hash() or value["files"] != inventory(ROOT/"frontend/dist") or sha(private_path(value["buildLog"])) != value["buildLogSha256"]:
        raise ValueError("Frontend source, output or checked build evidence changed")
    context.mkdir(parents=True,exist_ok=False,mode=0o700)
    shutil.copytree(ROOT/"frontend/dist",context/"dist")
    shutil.copyfile(ROOT/"frontend/infra/nginx.microservices.conf",context/"nginx.conf")
    shutil.copyfile(ROOT/"frontend/infra/19-session-secret.envsh",context/"session-secret.envsh")
    shutil.copyfile(ROOT/"frontend/Dockerfile.runtime",context/"Dockerfile")
    return context,sha(proof)
