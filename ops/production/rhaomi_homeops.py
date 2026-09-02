#!/usr/bin/python3

from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import stat
import subprocess
import time
from typing import Callable, Sequence


PRODUCTION_ROOT = Path("/private/var/lib/rhaomi")
COMPATIBILITY_NAME = "homeops-compatibility.json"
MAX_EVENT_BYTES = 16 * 1024
MAX_STATUS_BYTES = 4 * 1024
LOCAL_RPO_SECONDS = 24 * 60 * 60
FIXED_PATH = "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
HASH_PATTERN = re.compile(r"[0-9a-f]{64}")
DIGEST_PATTERN = re.compile(r"sha256:[0-9a-f]{64}")
SET_ID_PATTERN = re.compile(r"[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}")
FAILURE_CODE_PATTERN = re.compile(r"[A-Z][A-Z0-9_]{0,63}")
PROJECT_PATTERN = re.compile(r"[a-z0-9][a-z0-9_-]{0,62}")
CONTAINER_ID_PATTERN = re.compile(r"[0-9a-f]{64}")
INSTANT_PATTERN = re.compile(
    r"(\d{4})-(\d{2})-(\d{2})T([01]\d|2[0-3]):([0-5]\d):([0-5]\d)(?:\.(\d{1,6}))?Z"
)

DEPLOYMENT_FIELDS = [
    "eventKey",
    "project",
    "environment",
    "branch",
    "commitSha",
    "imageTag",
    "previousCommitSha",
    "status",
    "startedAt",
    "finishedAt",
    "failureStage",
    "failureSummary",
    "actor",
    "workflowRunId",
    "workflowRunUrl",
    "rollback",
]
BACKUP_FIELDS = [
    "eventKey",
    "project",
    "databaseType",
    "logicalLocation",
    "status",
    "startedAt",
    "finishedAt",
    "sizeBytes",
    "expiresAt",
    "failureSummary",
    "restoreTestedAt",
    "restoreTestStatus",
]
COMPATIBILITY_FIELDS = {
    "schemaVersion",
    "homeOpsCommit",
    "reporterRelativePath",
    "reporterSha256",
    "deploymentRequestSha256",
    "backupRequestSha256",
    "deploymentFields",
    "deploymentStatuses",
    "backupFields",
    "backupStatuses",
    "managedLabel",
    "managedValue",
    "writableBindOrVolumeControl",
    "monitoringRequest",
}
MONITORING_REQUEST_FIELDS = {
    "methods",
    "expectedStatusMinimum",
    "expectedStatusMaximum",
    "timeoutMsMinimum",
    "timeoutMsMaximum",
    "intervalSecondsMinimum",
    "intervalSecondsMaximum",
    "failureThresholdMinimum",
    "failureThresholdMaximum",
    "recoveryThresholdMinimum",
    "recoveryThresholdMaximum",
    "productionThresholds",
    "notificationEnabled",
}
MONITORING_INTEGER_FIELDS = {
    "expectedStatusMinimum",
    "expectedStatusMaximum",
    "timeoutMsMinimum",
    "timeoutMsMaximum",
    "intervalSecondsMinimum",
    "intervalSecondsMaximum",
    "failureThresholdMinimum",
    "failureThresholdMaximum",
    "recoveryThresholdMinimum",
    "recoveryThresholdMaximum",
}
ELIGIBILITY_FIELDS = {
    "schemaVersion",
    "targetReleaseSha",
    "backupSetId",
    "backupManifestSha256",
    "sourceReleaseSha",
    "sourceImageDigest",
    "sourceFlywayVersion",
    "createdAt",
    "status",
}


class HomeOpsContractError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class RecoveryError(HomeOpsContractError):
    def __init__(
        self,
        code: str,
        *,
        service: str | None = None,
        pre_health: str = "UNKNOWN",
        post_health: str = "UNKNOWN",
        restart_count: int = 0,
    ):
        super().__init__(code)
        self.service = service
        self.pre_health = pre_health
        self.post_health = post_health
        self.restart_count = restart_count


def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def format_instant(value: dt.datetime) -> str:
    return value.astimezone(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def strict_instant(value: str) -> dt.datetime:
    if not isinstance(value, str) or INSTANT_PATTERN.fullmatch(value) is None:
        raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise HomeOpsContractError("HOMEOPS_EVENT_INVALID") from error
    if parsed.tzinfo is None:
        raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
    return parsed.astimezone(dt.timezone.utc)


def canonical_json(value: object) -> bytes:
    return (json.dumps(value, separators=(",", ":"), ensure_ascii=True) + "\n").encode("utf-8")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def exact_object(value: object, fields: set[str], code: str) -> dict[str, object]:
    if not isinstance(value, dict) or set(value) != fields:
        raise HomeOpsContractError(code)
    return value


def require_regular_file(path: Path, modes: set[int], code: str) -> os.stat_result:
    try:
        details = path.lstat()
    except OSError as error:
        raise HomeOpsContractError(code) from error
    if (
        not stat.S_ISREG(details.st_mode)
        or stat.S_ISLNK(details.st_mode)
        or details.st_uid != os.getuid()
        or stat.S_IMODE(details.st_mode) not in modes
    ):
        raise HomeOpsContractError(code)
    return details


def require_private_directory(path: Path, code: str) -> None:
    try:
        details = path.lstat()
    except OSError as error:
        raise HomeOpsContractError(code) from error
    if (
        not stat.S_ISDIR(details.st_mode)
        or stat.S_ISLNK(details.st_mode)
        or details.st_uid != os.getuid()
        or stat.S_IMODE(details.st_mode) not in {0o700, 0o710, 0o740, 0o750}
    ):
        raise HomeOpsContractError(code)


def load_compatibility(app_root: Path) -> dict[str, object]:
    path = app_root / COMPATIBILITY_NAME
    require_regular_file(path, {0o640, 0o644}, "HOMEOPS_AUTHORITY_INVALID")
    try:
        value = exact_object(
            json.loads(path.read_text(encoding="utf-8")),
            COMPATIBILITY_FIELDS,
            "HOMEOPS_AUTHORITY_INVALID",
        )
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise HomeOpsContractError("HOMEOPS_AUTHORITY_INVALID") from error
    monitoring = exact_object(
        value["monitoringRequest"],
        MONITORING_REQUEST_FIELDS,
        "HOMEOPS_AUTHORITY_INVALID",
    )
    if (
        type(value["schemaVersion"]) is not int
        or value["schemaVersion"] != 1
        or value["homeOpsCommit"] != "0a8ce9090c76f5ad7afba19ca896e923b96b0cbf"
        or value["reporterRelativePath"]
        != "Server/apps/homeops/runtime-config/current/scripts/report-homeops-event.py"
        or value["reporterSha256"]
        != "7a7ea2f7597efdc0d174775f28626dac7d330bddd638446fd0fe0f4e0f3acf9c"
        or value["deploymentRequestSha256"]
        != "737f49a2fd5398501f6bcb18ea7935f1e17438338e23388cf93c6b1d2a42fbc5"
        or value["backupRequestSha256"]
        != "a77610337bf2763b50c00786e8a897018dac9c107e78af827f2caeffc45dade4"
        or value["deploymentFields"] != DEPLOYMENT_FIELDS
        or value["deploymentStatuses"]
        != ["REQUESTED", "RUNNING", "SUCCESS", "FAILED", "ROLLED_BACK", "CANCELLED"]
        or value["backupFields"] != BACKUP_FIELDS
        or value["backupStatuses"] != ["RUNNING", "SUCCESS", "FAILED", "INCOMPLETE"]
        or value["managedLabel"] != "homeops.managed"
        or value["managedValue"] != "true"
        or value["writableBindOrVolumeControl"] != "DENIED"
        or any(type(monitoring[field]) is not int for field in MONITORING_INTEGER_FIELDS)
        or monitoring["methods"] != ["GET", "HEAD"]
        or monitoring["expectedStatusMinimum"] != 100
        or monitoring["expectedStatusMaximum"] != 599
        or monitoring["timeoutMsMinimum"] != 100
        or monitoring["timeoutMsMaximum"] != 60_000
        or monitoring["intervalSecondsMinimum"] != 5
        or monitoring["intervalSecondsMaximum"] != 86_400
        or monitoring["failureThresholdMinimum"] != 1
        or monitoring["failureThresholdMaximum"] != 100
        or monitoring["recoveryThresholdMinimum"] != 1
        or monitoring["recoveryThresholdMaximum"] != 100
        or monitoring["productionThresholds"] != "PROVISIONING_REQUIRED"
        or monitoring["notificationEnabled"] is not False
    ):
        raise HomeOpsContractError("HOMEOPS_AUTHORITY_INVALID")
    return value


def lifecycle_event_key(kind: str, identity: str, started_at: str) -> str:
    digest = hashlib.sha256(f"{kind}\0{identity}\0{started_at}".encode("utf-8")).hexdigest()
    return f"rhaomi:{kind}:{digest[:32]}"


def build_deployment_event(
    status: str,
    release_sha: str,
    image_digest: str,
    started_at: str,
    finished_at: str | None,
    failure_code: str | None,
) -> dict[str, object]:
    if status not in {"RUNNING", "SUCCESS", "FAILED"}:
        raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
    if SHA_PATTERN.fullmatch(release_sha) is None or DIGEST_PATTERN.fullmatch(image_digest) is None:
        raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
    started = strict_instant(started_at)
    finished = strict_instant(finished_at) if finished_at is not None else None
    if status == "RUNNING":
        if finished is not None or failure_code is not None:
            raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
    else:
        if finished is None or finished < started:
            raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
        if status == "FAILED":
            if failure_code is None or FAILURE_CODE_PATTERN.fullmatch(failure_code) is None:
                raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
        elif failure_code is not None:
            raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
    return {
        "eventKey": lifecycle_event_key("deployment", release_sha, started_at),
        "project": "rhaomi",
        "environment": "production",
        "branch": "main",
        "commitSha": release_sha,
        "imageTag": image_digest,
        "previousCommitSha": None,
        "status": status,
        "startedAt": started_at,
        "finishedAt": finished_at,
        "failureStage": "rhaomi-deploy" if status == "FAILED" else None,
        "failureSummary": failure_code,
        "actor": "rhaomi-fixed-entrypoint",
        "workflowRunId": None,
        "workflowRunUrl": None,
        "rollback": False,
    }


def build_backup_event(
    status: str,
    backup_set_id: str,
    started_at: str,
    finished_at: str | None,
    failure_code: str | None,
    size_bytes: int | None = None,
) -> dict[str, object]:
    if status not in {"RUNNING", "SUCCESS", "FAILED", "INCOMPLETE"}:
        raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
    if SET_ID_PATTERN.fullmatch(backup_set_id) is None:
        raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
    started = strict_instant(started_at)
    finished = strict_instant(finished_at) if finished_at is not None else None
    if size_bytes is not None and (isinstance(size_bytes, bool) or size_bytes < 0):
        raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
    if status == "RUNNING":
        if finished is not None or failure_code is not None:
            raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
    else:
        if finished is None or finished < started:
            raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
        if status in {"FAILED", "INCOMPLETE"}:
            if failure_code is None or FAILURE_CODE_PATTERN.fullmatch(failure_code) is None:
                raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
        elif failure_code is not None:
            raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
    return {
        "eventKey": lifecycle_event_key("backup", backup_set_id, started_at),
        "project": "rhaomi",
        "databaseType": "postgresql",
        "logicalLocation": backup_set_id,
        "status": status,
        "startedAt": started_at,
        "finishedAt": finished_at,
        "sizeBytes": size_bytes,
        "expiresAt": None,
        "failureSummary": failure_code,
        "restoreTestedAt": None,
        "restoreTestStatus": None,
    }


ReporterRunner = Callable[[Sequence[str], bytes], int]


def default_reporter_runner(arguments: Sequence[str], payload: bytes) -> int:
    try:
        completed = subprocess.run(
            list(arguments),
            input=payload,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env={"PATH": FIXED_PATH, "LANG": "C", "LC_ALL": "C"},
            timeout=5,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return 1
    return completed.returncode


def report_event(
    kind: str,
    event: dict[str, object],
    app_root: Path,
    *,
    account_home: Path | None = None,
    reporter_runner: ReporterRunner = default_reporter_runner,
) -> str:
    if kind not in {"deployments", "backups"}:
        raise HomeOpsContractError("HOMEOPS_EVENT_INVALID")
    compatibility = load_compatibility(app_root)
    try:
        home = account_home or Path(pwd.getpwuid(os.getuid()).pw_dir)
    except (KeyError, OSError):
        return "FAILED"
    reporter = home / str(compatibility["reporterRelativePath"])
    try:
        details = reporter.lstat()
    except FileNotFoundError:
        return "NOT_CONFIGURED"
    except OSError:
        return "FAILED"
    try:
        reporter_hash = sha256_file(reporter)
    except OSError:
        return "FAILED"
    if (
        not stat.S_ISREG(details.st_mode)
        or stat.S_ISLNK(details.st_mode)
        or details.st_uid != os.getuid()
        or stat.S_IMODE(details.st_mode) != 0o700
        or reporter_hash != compatibility["reporterSha256"]
    ):
        return "FAILED"
    payload = canonical_json(event)
    if len(payload) > MAX_EVENT_BYTES:
        return "FAILED"
    return "RETAINED" if reporter_runner(("/usr/bin/python3", str(reporter), kind), payload) == 0 else "FAILED"


CommandRunner = Callable[[Sequence[str]], str]


def command_environment(app_root: Path) -> dict[str, str]:
    return {
        "PATH": FIXED_PATH,
        "LANG": "C",
        "LC_ALL": "C",
        "DOCKER_CONFIG": str(app_root / "docker"),
    }


def default_command_runner(app_root: Path) -> CommandRunner:
    environment = command_environment(app_root)

    def run(arguments: Sequence[str]) -> str:
        timeout_seconds = 45 if "restart" in arguments else 10
        try:
            completed = subprocess.run(
                list(arguments),
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                env=environment,
                timeout=timeout_seconds,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise HomeOpsContractError("RHAOMI_STATUS_UNAVAILABLE") from error
        if completed.returncode != 0:
            raise HomeOpsContractError("RHAOMI_STATUS_UNAVAILABLE")
        return completed.stdout.strip()

    return run


def fixed_environment_value(path: Path, name: str, code: str) -> str:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as error:
        raise HomeOpsContractError(code) from error
    prefix = f"{name}="
    values = [line[len(prefix) :] for line in lines if line.startswith(prefix)]
    if len(values) != 1:
        raise HomeOpsContractError(code)
    return values[0]


def validate_host_inventory(root: Path) -> tuple[Path, list[str], str]:
    app_root = root / "app"
    for directory in (root, app_root, root / "state", root / "state" / "deploy", root / "state" / "locks"):
        require_private_directory(directory, "RHAOMI_HOST_INVALID")
    compose_file = app_root / "compose.production.yaml"
    environment_file = app_root / "production.env"
    docker_root = app_root / "docker"
    require_regular_file(compose_file, {0o640, 0o644}, "RHAOMI_HOST_INVALID")
    require_regular_file(environment_file, {0o600}, "RHAOMI_HOST_INVALID")
    require_private_directory(docker_root, "RHAOMI_HOST_INVALID")
    require_regular_file(docker_root / "config.json", {0o600}, "RHAOMI_HOST_INVALID")
    project = fixed_environment_value(
        environment_file,
        "RHAOMI_PRODUCTION_COMPOSE_PROJECT",
        "RHAOMI_HOST_INVALID",
    )
    if PROJECT_PATTERN.fullmatch(project) is None:
        raise HomeOpsContractError("RHAOMI_HOST_INVALID")
    compose = [
        "docker",
        "compose",
        "--project-directory",
        str(app_root),
        "--env-file",
        str(environment_file),
        "--file",
        str(compose_file),
    ]
    return app_root, compose, project


def normalized_health(state: str, health: str) -> str:
    if state != "running":
        return "DOWN"
    if health == "healthy":
        return "UP"
    if health in {"unhealthy", "starting"}:
        return "DOWN"
    return "UNKNOWN"


def service_snapshot(service: str, compose: list[str], runner: CommandRunner) -> dict[str, str | None]:
    identifier = runner((*compose, "ps", "--all", "--quiet", service))
    if not identifier:
        return {
            "state": "missing",
            "health": "DOWN",
            "identifier": None,
            "imageId": None,
            "imageReference": None,
        }
    if "\n" in identifier or CONTAINER_ID_PATTERN.fullmatch(identifier) is None:
        raise HomeOpsContractError("RHAOMI_STATUS_INVALID")
    state = runner(("docker", "inspect", "--format", "{{.State.Status}}", identifier))
    health = runner(
        (
            "docker",
            "inspect",
            "--format",
            "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}",
            identifier,
        )
    )
    image_id = runner(("docker", "inspect", "--format", "{{.Image}}", identifier))
    image_reference = runner(("docker", "inspect", "--format", "{{.Config.Image}}", identifier))
    if state not in {"running", "exited", "created", "dead", "restarting"}:
        state = "unknown"
    if DIGEST_PATTERN.fullmatch(image_id) is None:
        raise HomeOpsContractError("RHAOMI_STATUS_INVALID")
    return {
        "state": state,
        "health": normalized_health(state, health),
        "identifier": identifier,
        "imageId": image_id,
        "imageReference": image_reference,
    }


def public_service(snapshot: dict[str, str | None]) -> dict[str, str]:
    return {"state": str(snapshot["state"]), "health": str(snapshot["health"])}


def lock_present(lock: Path) -> bool:
    try:
        details = lock.lstat()
    except FileNotFoundError:
        return False
    except OSError as error:
        raise HomeOpsContractError("RHAOMI_STATUS_INVALID") from error
    if not stat.S_ISDIR(details.st_mode) or stat.S_ISLNK(details.st_mode):
        raise HomeOpsContractError("RHAOMI_STATUS_INVALID")
    return True


def eligibility_status(root: Path, now: dt.datetime) -> dict[str, object]:
    path = root / "state" / "deploy" / "backup-eligibility.json"
    if not path.exists():
        return {
            "eligibilityPresent": False,
            "backupSetId": None,
            "targetReleaseSha": None,
            "freshnessStatus": "missing",
            "ageSeconds": None,
        }
    try:
        require_regular_file(path, {0o600}, "RHAOMI_STATUS_INVALID")
        value = exact_object(
            json.loads(path.read_text(encoding="utf-8")),
            ELIGIBILITY_FIELDS,
            "RHAOMI_STATUS_INVALID",
        )
        if (
            type(value["schemaVersion"]) is not int
            or value["schemaVersion"] != 1
            or value["status"] != "eligible"
            or not isinstance(value["targetReleaseSha"], str)
            or SHA_PATTERN.fullmatch(value["targetReleaseSha"]) is None
            or not isinstance(value["backupSetId"], str)
            or SET_ID_PATTERN.fullmatch(value["backupSetId"]) is None
            or not isinstance(value["backupManifestSha256"], str)
            or HASH_PATTERN.fullmatch(value["backupManifestSha256"]) is None
            or not isinstance(value["sourceReleaseSha"], str)
            or SHA_PATTERN.fullmatch(value["sourceReleaseSha"]) is None
            or not isinstance(value["sourceImageDigest"], str)
            or DIGEST_PATTERN.fullmatch(value["sourceImageDigest"]) is None
            or value["sourceFlywayVersion"] != "9"
            or not isinstance(value["createdAt"], str)
        ):
            raise HomeOpsContractError("RHAOMI_STATUS_INVALID")
        created = strict_instant(value["createdAt"])
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, HomeOpsContractError):
        return {
            "eligibilityPresent": True,
            "backupSetId": None,
            "targetReleaseSha": None,
            "freshnessStatus": "invalid",
            "ageSeconds": None,
        }
    age = int((now - created).total_seconds())
    if age < 0:
        freshness = "future"
        bounded_age = None
    elif age >= LOCAL_RPO_SECONDS:
        freshness = "stale"
        bounded_age = min(age, 315_360_000)
    else:
        freshness = "fresh"
        bounded_age = age
    return {
        "eligibilityPresent": True,
        "backupSetId": value["backupSetId"],
        "targetReleaseSha": value["targetReleaseSha"],
        "freshnessStatus": freshness,
        "ageSeconds": bounded_age,
    }


def collect_status(
    root: Path,
    *,
    runner: CommandRunner | None = None,
    now: dt.datetime | None = None,
) -> dict[str, object]:
    current_time = (now or utc_now()).astimezone(dt.timezone.utc)
    app_root, compose, _project = validate_host_inventory(root)
    command_runner = runner or default_command_runner(app_root)
    snapshots = {
        "web": service_snapshot("rhaomi-web", compose, command_runner),
        "backend": service_snapshot("backend", compose, command_runner),
        "publisher": service_snapshot("publisher", compose, command_runner),
        "postgres": service_snapshot("postgres", compose, command_runner),
    }
    backend = snapshots["backend"]
    publisher = snapshots["publisher"]
    same_image = (
        backend["identifier"] is not None
        and publisher["identifier"] is not None
        and backend["imageId"] == publisher["imageId"]
        and backend["imageReference"] == publisher["imageReference"]
    )
    release_sha = None
    image_digest = None
    if same_image:
        reference = str(backend["imageReference"])
        match = re.fullmatch(r"ghcr[.]io/xxh3898/rhaomi@(sha256:[0-9a-f]{64})", reference)
        if match is not None:
            image_digest = match.group(1)
            candidate = command_runner(
                (
                    "docker",
                    "image",
                    "inspect",
                    "--format",
                    '{{index .Config.Labels "org.opencontainers.image.revision"}}',
                    str(backend["imageId"]),
                )
            )
            if SHA_PATTERN.fullmatch(candidate) is not None:
                release_sha = candidate
    public_available = False
    if snapshots["web"]["state"] == "running" and snapshots["web"]["health"] == "UP":
        try:
            command_runner(
                (
                    *compose,
                    "exec",
                    "--no-TTY",
                    "rhaomi-web",
                    "wget",
                    "-qO",
                    "/dev/null",
                    "http://127.0.0.1:8080/",
                )
            )
            public_available = True
        except HomeOpsContractError:
            public_available = False
    result = {
        "schemaVersion": 1,
        "checkedAt": format_instant(current_time),
        "project": "rhaomi",
        "environment": "production",
        "release": {
            "releaseSha": release_sha,
            "imageDigest": image_digest,
            "backendPublisherSameImage": same_image,
        },
        "services": {name: public_service(snapshot) for name, snapshot in snapshots.items()},
        "publicWeb": {"available": public_available},
        "operation": {"deployBackupLockPresent": lock_present(root / "state" / "locks" / "rhaomi-deploy.lock")},
        "backup": eligibility_status(root, current_time),
    }
    if len(canonical_json(result)) > MAX_STATUS_BYTES:
        raise HomeOpsContractError("RHAOMI_STATUS_INVALID")
    return result


def status_failure(code: str, now: dt.datetime | None = None) -> dict[str, object]:
    safe_code = code if FAILURE_CODE_PATTERN.fullmatch(code) else "RHAOMI_STATUS_INVALID"
    return {
        "schemaVersion": 1,
        "checkedAt": format_instant(now or utc_now()),
        "project": "rhaomi",
        "environment": "production",
        "status": "error",
        "code": safe_code,
    }


def compose_identity(
    service: str,
    compose: list[str],
    expected_project: str,
    runner: CommandRunner,
) -> dict[str, str]:
    identifier = runner((*compose, "ps", "--all", "--quiet", service))
    if CONTAINER_ID_PATTERN.fullmatch(identifier) is None:
        raise RecoveryError("RECOVERY_TARGET_UNAVAILABLE", service=service)
    state = runner(("docker", "inspect", "--format", "{{.State.Status}}", identifier))
    if state != "running":
        raise RecoveryError("RECOVERY_TARGET_UNAVAILABLE", service=service, pre_health="DOWN")
    image_id = runner(("docker", "inspect", "--format", "{{.Image}}", identifier))
    image_reference = runner(("docker", "inspect", "--format", "{{.Config.Image}}", identifier))
    project = runner(
        (
            "docker",
            "inspect",
            "--format",
            '{{index .Config.Labels "com.docker.compose.project"}}',
            identifier,
        )
    )
    live_service = runner(
        (
            "docker",
            "inspect",
            "--format",
            '{{index .Config.Labels "com.docker.compose.service"}}',
            identifier,
        )
    )
    health = runner(
        (
            "docker",
            "inspect",
            "--format",
            "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}",
            identifier,
        )
    )
    if (
        DIGEST_PATTERN.fullmatch(image_id) is None
        or not image_reference
        or project != expected_project
        or live_service != service
    ):
        raise RecoveryError("RECOVERY_TARGET_INVALID", service=service)
    return {
        "identifier": identifier,
        "imageId": image_id,
        "imageReference": image_reference,
        "health": normalized_health(state, health),
    }


def acquire_recovery_lock(root: Path, token: str) -> tuple[Path, Path]:
    lock = root / "state" / "locks" / "rhaomi-deploy.lock"
    owner = lock / "owner"
    try:
        lock.mkdir(mode=0o700)
    except FileExistsError as error:
        raise RecoveryError("RECOVERY_LOCKED") from error
    except OSError as error:
        raise RecoveryError("RECOVERY_LOCK_INVALID") from error
    try:
        descriptor = os.open(owner, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(f"{token}\n")
            output.flush()
            os.fsync(output.fileno())
    except OSError as error:
        try:
            lock.rmdir()
        except OSError:
            pass
        raise RecoveryError("RECOVERY_LOCK_INVALID") from error
    return lock, owner


def release_recovery_lock(lock: Path, owner: Path, token: str) -> None:
    try:
        require_regular_file(owner, {0o600}, "RECOVERY_LOCK_RELEASE_FAILED")
        if owner.read_text(encoding="utf-8") != f"{token}\n":
            raise HomeOpsContractError("RECOVERY_LOCK_RELEASE_FAILED")
        owner.unlink()
        lock.rmdir()
    except (OSError, UnicodeDecodeError, HomeOpsContractError) as error:
        raise RecoveryError("RECOVERY_LOCK_RELEASE_FAILED") from error


def recovery_audit(
    *,
    service: str | None,
    started_at: dt.datetime,
    finished_at: dt.datetime,
    pre_health: str,
    post_health: str,
    restart_count: int,
    status: str,
    code: str,
) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "startedAt": format_instant(started_at),
        "finishedAt": format_instant(finished_at),
        "service": service if service in {"rhaomi-web", "backend"} else None,
        "operation": "restart",
        "preHealth": pre_health,
        "postHealth": post_health,
        "restartCount": restart_count,
        "status": status,
        "code": code,
    }


def recover_service(
    root: Path,
    operation: str,
    service: str,
    *,
    runner: CommandRunner | None = None,
    clock: Callable[[], dt.datetime] = utc_now,
    sleeper: Callable[[float], None] = time.sleep,
    maximum_attempts: int = 120,
) -> tuple[dict[str, object], int]:
    started = clock().astimezone(dt.timezone.utc)
    if operation != "restart" or service not in {"rhaomi-web", "backend"}:
        audit = recovery_audit(
            service=service if service in {"rhaomi-web", "backend"} else None,
            started_at=started,
            finished_at=clock(),
            pre_health="UNKNOWN",
            post_health="UNKNOWN",
            restart_count=0,
            status="failed",
            code="RECOVERY_INPUT_INVALID",
        )
        return audit, 1
    lock: Path | None = None
    owner: Path | None = None
    token = f"recovery:{os.getpid()}:{started.strftime('%Y%m%dT%H%M%SZ')}"
    pre_health = "UNKNOWN"
    post_health = "UNKNOWN"
    restart_count = 0
    preserve_lock = False
    code = "RECOVERY_FAILED"
    try:
        app_root, compose, expected_project = validate_host_inventory(root)
        command_runner = runner or default_command_runner(app_root)
        lock, owner = acquire_recovery_lock(root, token)
        before = compose_identity(service, compose, expected_project, command_runner)
        pre_health = before["health"]
        restart_count = 1
        try:
            command_runner((*compose, "restart", "--timeout", "30", service))
        except HomeOpsContractError as error:
            preserve_lock = True
            raise RecoveryError(
                "RECOVERY_RESTART_UNCONFIRMED",
                service=service,
                pre_health=pre_health,
                restart_count=restart_count,
            ) from error
        for attempt in range(maximum_attempts):
            try:
                after = compose_identity(service, compose, expected_project, command_runner)
            except RecoveryError:
                after = None
            if after is not None:
                if (
                    after["identifier"] != before["identifier"]
                    or after["imageId"] != before["imageId"]
                    or after["imageReference"] != before["imageReference"]
                ):
                    raise RecoveryError(
                        "RECOVERY_IDENTITY_CHANGED",
                        service=service,
                        pre_health=pre_health,
                        restart_count=restart_count,
                    )
                post_health = after["health"]
                if post_health == "UP":
                    code = "RECOVERY_APPLIED"
                    break
            if attempt + 1 < maximum_attempts:
                sleeper(1)
        else:
            raise RecoveryError(
                "RECOVERY_POST_HEALTH_FAILED",
                service=service,
                pre_health=pre_health,
                post_health=post_health,
                restart_count=restart_count,
            )
        release_recovery_lock(lock, owner, token)
        lock = None
        owner = None
        return (
            recovery_audit(
                service=service,
                started_at=started,
                finished_at=clock(),
                pre_health=pre_health,
                post_health=post_health,
                restart_count=restart_count,
                status="success",
                code=code,
            ),
            0,
        )
    except RecoveryError as error:
        code = error.code
        pre_health = error.pre_health if error.pre_health != "UNKNOWN" else pre_health
        post_health = error.post_health if error.post_health != "UNKNOWN" else post_health
        restart_count = max(restart_count, error.restart_count)
    except HomeOpsContractError as error:
        code = error.code
    except Exception:
        code = "RECOVERY_FAILED"
    finally:
        if lock is not None and owner is not None and not preserve_lock:
            try:
                release_recovery_lock(lock, owner, token)
            except RecoveryError:
                code = "RECOVERY_LOCK_RELEASE_FAILED"
    return (
        recovery_audit(
            service=service,
            started_at=started,
            finished_at=clock(),
            pre_health=pre_health,
            post_health=post_health,
            restart_count=restart_count,
            status="failed",
            code=code,
        ),
        1,
    )
