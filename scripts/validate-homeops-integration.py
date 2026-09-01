#!/usr/bin/python3

from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from unittest.mock import patch


REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "ops" / "production"))

from rhaomi_homeops import (  # noqa: E402
    BACKUP_FIELDS,
    DEPLOYMENT_FIELDS,
    HomeOpsContractError,
    build_backup_event,
    build_deployment_event,
    canonical_json,
    collect_status,
    load_compatibility,
    recover_service,
    report_event,
)


FIXED_NOW = dt.datetime(2026, 9, 1, 12, 0, 0, tzinfo=dt.timezone.utc)
RELEASE_SHA = "a" * 40
IMAGE_DIGEST = f"sha256:{'b' * 64}"
IMAGE_ID = f"sha256:{'c' * 64}"
SECRET_MARKER = "synthetic-homeops-secret-must-not-escape"
HOMEOPS_PRODUCTION_COMMIT = "0a8ce9090c76f5ad7afba19ca896e923b96b0cbf"
HOMEOPS_PRODUCTION_TREE = "f8f77091383931f36dc96aa35242193bb5ab1f01"
HOMEOPS_AGENT_ARTIFACT_DIGEST = (
    "sha256:305c0f216bf00097ae8532b33991aed99e752669a32956b85eebfbf7351bcf4b"
)

EXPECTED_ACTIVATION_PREFLIGHT = {
    "schemaVersion": 1,
    "overallProductionReadiness": "HOLD",
    "productionCompatibilityAuthority": {
        "homeOpsBranch": "main",
        "homeOpsCommit": HOMEOPS_PRODUCTION_COMMIT,
        "compatibilityFile": "ops/production/homeops-compatibility.json",
    },
    "sourceImplementationEvidence": {
        "status": "LOCAL_CI_VERIFIED",
        "homeOpsBranch": "dev",
        "homeOpsCommit": "e4d5c59841e30fdc20bf1ce55fa419ac3f766a13",
        "homeOpsTree": "f8f77091383931f36dc96aa35242193bb5ab1f01",
        "pullRequest": 120,
        "postMergeValidateRun": 33527901223,
    },
    "productionReleaseEvidence": {
        "status": "RELEASED_AND_DEPLOYED",
        "homeOpsBranch": "main",
        "homeOpsCommit": HOMEOPS_PRODUCTION_COMMIT,
        "homeOpsTree": HOMEOPS_PRODUCTION_TREE,
        "pullRequest": 122,
        "publishAndDeployRun": 33569523762,
        "agentArtifactDigest": HOMEOPS_AGENT_ARTIFACT_DIGEST,
    },
    "automaticRecoveryPolicy": {
        "mappings": [
            {
                "monitorSignal": "PUBLIC_HTTPS_STATUS",
                "expectedStatusAuthority": "MONITORED_SERVICE_EXPECTED_STATUS",
                "failureThreshold": 3,
                "target": "rhaomi-web",
                "action": "RESTART",
                "initialState": "DISABLED",
            }
        ],
        "unmappedTargets": ["backend"],
        "cooldownSeconds": 1800,
        "noAutoRetryOutcomes": ["FAILED", "OUTCOME_UNKNOWN"],
    },
    "releaseOrder": [
        "HOMEOPS_RELEASE",
        "LIVE_COMPATIBILITY_REVALIDATION",
        "RHAOMI_RELEASE_PROVISIONING",
    ],
    "activationSequence": [
        "VERIFY_HOMEOPS_V14_PROVISIONING",
        "CREATE_DISABLED_WEB_MAPPING",
        "VERIFY_RHAOMI_FIXED_INVENTORY",
        "ROLLOUT_HOMEOPS_AGENT",
        "VERIFY_FRESH_RECOVERY_CAPABILITY",
        "VERIFY_READ_ONLY_END_TO_END_COMPATIBILITY",
        "EXPLICIT_MAPPING_ENABLE_APPROVAL",
        "CONTROLLED_SINGLE_RECOVERY_DRILL_APPROVAL",
        "VERIFY_POST_HEALTH_AUDIT_ACTIVITY",
        "OBSERVATION_WINDOW",
    ],
    "productionState": {
        "homeOpsRelease": "COMPLETED",
        "homeOpsApplicationDeploy": "COMPLETED",
        "rhaomiRelease": "NOT_RUN",
        "v14ProductionMigration": "APPLIED",
        "agentArtifact": "PUBLISHED",
        "webMapping": "NOT_CREATED",
        "backendMapping": "ABSENT",
        "agentRollout": "NOT_RUN",
        "mappingEnable": "NOT_RUN",
        "restartDrill": "NOT_RUN",
        "notificationActivation": "NOT_RUN",
    },
    "privateEvidenceRequired": [
        "HOMEOPS_DATABASE_IDENTITY",
        "MONITORED_SERVICE_IDENTITY",
        "RHAOMI_FIXED_INVENTORY_IDENTITY",
        "HOMEOPS_AGENT_CURRENT_AND_ROLLBACK_IDENTITY",
        "RHAOMI_RUNTIME_IDENTITY",
        "BACKUP_RESTORE_ELIGIBILITY",
    ],
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def write(path: Path, contents: str, mode: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(contents, encoding="utf-8")
    path.chmod(mode)


def prepare_root(parent: Path) -> Path:
    root = parent / "root"
    for directory in (
        root,
        root / "app",
        root / "app" / "docker",
        root / "state",
        root / "state" / "deploy",
        root / "state" / "locks",
    ):
        directory.mkdir(parents=True, exist_ok=True)
        directory.chmod(0o700)
    write(root / "app" / "compose.production.yaml", "services: {}\n", 0o644)
    write(
        root / "app" / "production.env",
        "RHAOMI_PRODUCTION_COMPOSE_PROJECT=rhaomi-production\n"
        f"RHAOMI_POSTGRES_PASSWORD={SECRET_MARKER}\n",
        0o600,
    )
    write(root / "app" / "docker" / "config.json", "{}\n", 0o600)
    write(
        root / "state" / "deploy" / "backup-eligibility.json",
        json.dumps(
            {
                "schemaVersion": 1,
                "targetReleaseSha": RELEASE_SHA,
                "backupSetId": "20260901T110000Z-111111111111",
                "backupManifestSha256": "d" * 64,
                "sourceReleaseSha": RELEASE_SHA,
                "sourceImageDigest": IMAGE_DIGEST,
                "sourceFlywayVersion": "9",
                "createdAt": "2026-09-01T11:00:00Z",
                "status": "eligible",
            },
            indent=2,
        )
        + "\n",
        0o600,
    )
    return root


class FakeDocker:
    def __init__(
        self,
        *,
        post_health_failure: bool = False,
        identity_change: bool = False,
        project: str = "rhaomi-production",
    ):
        self.services = {
            "rhaomi-web": "1" * 64,
            "backend": "2" * 64,
            "publisher": "3" * 64,
            "postgres": "4" * 64,
        }
        self.reverse = {identifier: service for service, identifier in self.services.items()}
        self.post_health_failure = post_health_failure
        self.identity_change = identity_change
        self.project = project
        self.restart_count = 0
        self.restarted_service: str | None = None
        self.commands: list[tuple[str, ...]] = []

    def __call__(self, arguments):
        command = tuple(str(value) for value in arguments)
        self.commands.append(command)
        if command[0:2] == ("docker", "compose"):
            if "ps" in command:
                service = command[-1]
                identifier = self.services.get(service, "")
                if self.identity_change and self.restart_count and service == self.restarted_service:
                    new_identifier = "5" * 64
                    self.reverse[new_identifier] = service
                    return new_identifier
                return identifier
            if "restart" in command:
                self.restart_count += 1
                self.restarted_service = command[-1]
                return ""
            if "exec" in command:
                return "synthetic public html"
        if command[0:2] == ("docker", "inspect"):
            template = command[3]
            identifier = command[4]
            service = self.reverse.get(identifier)
            if service is None:
                raise HomeOpsContractError("RHAOMI_STATUS_UNAVAILABLE")
            if template == "{{.State.Status}}":
                return "running"
            if template == "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}":
                if self.post_health_failure and service == self.restarted_service:
                    return "unhealthy"
                return "healthy" if service != "publisher" else "none"
            if template == "{{.Image}}":
                return IMAGE_ID
            if template == "{{.Config.Image}}":
                return f"ghcr.io/xxh3898/rhaomi@{IMAGE_DIGEST}"
            if template == '{{index .Config.Labels "com.docker.compose.project"}}':
                return self.project
            if template == '{{index .Config.Labels "com.docker.compose.service"}}':
                return service
        if command[0:3] == ("docker", "image", "inspect"):
            return RELEASE_SHA
        raise HomeOpsContractError("RHAOMI_STATUS_UNAVAILABLE")


def validate_compatibility() -> dict[str, object]:
    compatibility = load_compatibility(REPO_ROOT / "ops" / "production")
    require(compatibility["deploymentFields"] == DEPLOYMENT_FIELDS, "deployment fields drift")
    require(compatibility["backupFields"] == BACKUP_FIELDS, "backup fields drift")
    require(compatibility["managedLabel"] == "homeops.managed", "managed label drift")
    require(compatibility["writableBindOrVolumeControl"] == "DENIED", "mount policy drift")
    return compatibility


def validate_activation_preflight_value(
    preflight: object,
    compatibility: dict[str, object],
    encoded: bytes,
) -> dict[str, object]:
    require(type(preflight) is dict, "activation preflight must be an object")
    require(type(preflight["schemaVersion"]) is int, "preflight schemaVersion type drift")
    require(preflight["overallProductionReadiness"] == "HOLD", "production readiness drift")
    require(
        type(preflight["sourceImplementationEvidence"]["pullRequest"]) is int,
        "preflight pull request type drift",
    )
    require(
        type(preflight["sourceImplementationEvidence"]["postMergeValidateRun"]) is int,
        "preflight run id type drift",
    )
    release_evidence = preflight["productionReleaseEvidence"]
    require(type(release_evidence) is dict, "production release evidence type drift")
    require(
        type(release_evidence["pullRequest"]) is int,
        "production release pull request type drift",
    )
    require(
        type(release_evidence["publishAndDeployRun"]) is int,
        "production release run id type drift",
    )
    require(
        release_evidence["status"] == "RELEASED_AND_DEPLOYED",
        "HomeOps release evidence drift",
    )
    require(
        release_evidence["homeOpsCommit"] == HOMEOPS_PRODUCTION_COMMIT,
        "HomeOps release commit drift",
    )
    require(
        release_evidence["homeOpsTree"] == HOMEOPS_PRODUCTION_TREE,
        "HomeOps release tree drift",
    )
    require(
        release_evidence["agentArtifactDigest"] == HOMEOPS_AGENT_ARTIFACT_DIGEST,
        "HomeOps Agent artifact digest drift",
    )
    policy = preflight["automaticRecoveryPolicy"]
    mapping = policy["mappings"][0]
    monitoring_request = compatibility["monitoringRequest"]
    require(type(monitoring_request) is dict, "monitoring request contract type drift")
    require(
        set(monitoring_request)
        == {
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
        },
        "monitoring request capability drift",
    )
    require(monitoring_request["methods"] == ["GET", "HEAD"], "monitoring methods drift")
    require(
        monitoring_request["expectedStatusMinimum"] == 100
        and monitoring_request["expectedStatusMaximum"] == 599,
        "expected status contract drift",
    )
    require(
        not any(
            marker in str(field).lower()
            for field in monitoring_request
            for marker in ("keyword", "body", "content")
        ),
        "unsupported response matcher entered monitoring contract",
    )
    require(
        not any(
            marker in str(field).lower()
            for field in mapping
            for marker in ("keyword", "body", "content")
        ),
        "unsupported response matcher entered recovery policy",
    )
    require(mapping["monitorSignal"] == "PUBLIC_HTTPS_STATUS", "web signal drift")
    require(
        mapping["expectedStatusAuthority"] == "MONITORED_SERVICE_EXPECTED_STATUS",
        "expected status authority drift",
    )
    require(type(mapping["failureThreshold"]) is int, "web failure threshold type drift")
    require(
        monitoring_request["failureThresholdMinimum"]
        <= mapping["failureThreshold"]
        <= monitoring_request["failureThresholdMaximum"],
        "web failure threshold exceeds monitoring contract",
    )
    require(mapping["failureThreshold"] == 3, "web failure threshold policy drift")
    require(mapping["target"] == "rhaomi-web", "web recovery target drift")
    require(mapping["action"] == "RESTART", "web recovery action drift")
    require(mapping["initialState"] == "DISABLED", "web mapping initial state drift")
    require(policy["unmappedTargets"] == ["backend"], "backend mapping authority drift")
    require(type(policy["cooldownSeconds"]) is int, "cooldown type drift")
    require(policy["cooldownSeconds"] == 1800, "cooldown policy drift")
    require(
        policy["noAutoRetryOutcomes"] == ["FAILED", "OUTCOME_UNKNOWN"],
        "no-auto-retry policy drift",
    )
    require(
        preflight["productionCompatibilityAuthority"]["homeOpsCommit"]
        == compatibility["homeOpsCommit"],
        "production compatibility authority drift",
    )
    require(
        release_evidence["homeOpsCommit"] == compatibility["homeOpsCommit"],
        "production release evidence and compatibility authority drift",
    )
    require(
        preflight["sourceImplementationEvidence"]["homeOpsTree"]
        == release_evidence["homeOpsTree"],
        "released tree differs from reviewed source tree",
    )
    production_state = preflight["productionState"]
    require(production_state["homeOpsRelease"] == "COMPLETED", "HomeOps release state drift")
    require(
        production_state["homeOpsApplicationDeploy"] == "COMPLETED",
        "HomeOps application deploy state drift",
    )
    require(
        production_state["v14ProductionMigration"] == "APPLIED",
        "HomeOps V14 production state drift",
    )
    require(production_state["agentArtifact"] == "PUBLISHED", "Agent artifact state drift")
    require(production_state["agentRollout"] == "NOT_RUN", "Agent rollout state drift")
    require(production_state["webMapping"] == "NOT_CREATED", "web mapping state drift")
    require(production_state["backendMapping"] == "ABSENT", "backend mapping state drift")
    require(production_state["mappingEnable"] == "NOT_RUN", "mapping enable state drift")
    require(production_state["restartDrill"] == "NOT_RUN", "restart drill state drift")
    require(
        production_state["notificationActivation"] == "NOT_RUN",
        "notification activation state drift",
    )
    require(production_state["rhaomiRelease"] == "NOT_RUN", "Rhaomi release state drift")
    require(preflight == EXPECTED_ACTIVATION_PREFLIGHT, "activation preflight contract drift")
    require(SECRET_MARKER.encode() not in encoded, "secret marker leaked into preflight")
    require(b"/private/" not in encoded and b"/Users/" not in encoded, "private path leaked")
    return {
        "overallProductionReadiness": preflight["overallProductionReadiness"],
        "productionAuthorityPinnedToCurrentMain": True,
        "sourceEvidenceSeparated": True,
        "homeOpsProductionCommit": release_evidence["homeOpsCommit"],
        "homeOpsRelease": production_state["homeOpsRelease"],
        "homeOpsApplicationDeploy": production_state["homeOpsApplicationDeploy"],
        "v14ProductionMigration": production_state["v14ProductionMigration"],
        "agentArtifact": production_state["agentArtifact"],
        "agentArtifactDigest": release_evidence["agentArtifactDigest"],
        "agentRollout": production_state["agentRollout"],
        "webMonitorSignal": mapping["monitorSignal"],
        "webExpectedStatusAuthority": mapping["expectedStatusAuthority"],
        "keywordBodyMatcherSupported": False,
        "webFailureThreshold": mapping["failureThreshold"],
        "webTarget": mapping["target"],
        "backendMapping": preflight["productionState"]["backendMapping"],
        "cooldownSeconds": policy["cooldownSeconds"],
        "noAutoRetryOutcomes": policy["noAutoRetryOutcomes"],
        "mappingEnableCount": 0,
        "actualRestartOrDrillCount": 0,
        "secretMarkerCount": 0,
        "privatePathCount": 0,
    }


def set_nested(value: object, path: tuple[object, ...], replacement: object) -> None:
    target = value
    for segment in path[:-1]:
        target = target[segment]  # type: ignore[index]
    target[path[-1]] = replacement  # type: ignore[index]


def validate_activation_preflight_regressions(compatibility: dict[str, object]) -> dict[str, bool]:
    cases = (
        ("production pin", ("productionCompatibilityAuthority", "homeOpsCommit"), "0" * 40),
        ("keyword matcher", ("automaticRecoveryPolicy", "mappings", 0, "keywordMatcher"), "SUPPORTED"),
        ("failure threshold", ("automaticRecoveryPolicy", "mappings", 0, "failureThreshold"), 2),
        ("web target", ("automaticRecoveryPolicy", "mappings", 0, "target"), "backend"),
        ("backend mapping", ("productionState", "backendMapping"), "PRESENT"),
        ("cooldown", ("automaticRecoveryPolicy", "cooldownSeconds"), 1799),
        ("no-auto-retry", ("automaticRecoveryPolicy", "noAutoRetryOutcomes"), ["FAILED"]),
        ("Agent rollout", ("productionState", "agentRollout"), "PUBLISHED"),
        ("mapping enable", ("productionState", "mappingEnable"), "COMPLETED"),
        ("restart drill", ("productionState", "restartDrill"), "COMPLETED"),
        ("readiness", ("overallProductionReadiness",), "READY"),
    )
    for label, path, replacement in cases:
        candidate = copy.deepcopy(EXPECTED_ACTIVATION_PREFLIGHT)
        set_nested(candidate, path, replacement)
        encoded = canonical_json(candidate)
        try:
            validate_activation_preflight_value(candidate, compatibility, encoded)
        except AssertionError:
            continue
        raise AssertionError(f"{label} drift was accepted")
    return {
        "productionPinDriftRejected": True,
        "policyDriftRejected": True,
        "activationStateDriftRejected": True,
        "readinessDriftRejected": True,
    }


def validate_activation_preflight(compatibility: dict[str, object]) -> dict[str, object]:
    preflight_path = REPO_ROOT / "ops" / "production" / "homeops-activation-preflight.json"
    try:
        encoded = preflight_path.read_bytes()
        preflight = json.loads(encoded)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AssertionError("activation preflight is unreadable") from error
    evidence = validate_activation_preflight_value(preflight, compatibility, encoded)
    evidence.update(validate_activation_preflight_regressions(compatibility))
    return evidence


def validate_events(compatibility: dict[str, object], temporary: Path) -> dict[str, object]:
    started = "2026-09-01T11:00:00.123456Z"
    finished = "2026-09-01T11:01:00.123456Z"
    deployment_running = build_deployment_event(
        "RUNNING", RELEASE_SHA, IMAGE_DIGEST, started, None, None
    )
    deployment_success = build_deployment_event(
        "SUCCESS", RELEASE_SHA, IMAGE_DIGEST, started, finished, None
    )
    deployment_failed = build_deployment_event(
        "FAILED", RELEASE_SHA, IMAGE_DIGEST, started, finished, "DEPLOY_TEST_FAILED"
    )
    backup_running = build_backup_event(
        "RUNNING", "20260901T110000Z-111111111111", started, None, None
    )
    backup_success = build_backup_event(
        "SUCCESS", "20260901T110000Z-111111111111", started, finished, None
    )
    backup_failed = build_backup_event(
        "FAILED",
        "20260901T110000Z-111111111111",
        started,
        finished,
        "BACKUP_TEST_FAILED",
    )
    require(list(deployment_running) == DEPLOYMENT_FIELDS, "deployment shape mismatch")
    require(list(backup_running) == BACKUP_FIELDS, "backup shape mismatch")
    require(
        deployment_running["eventKey"]
        == deployment_success["eventKey"]
        == deployment_failed["eventKey"],
        "deployment lifecycle event key changed",
    )
    require(
        backup_running["eventKey"] == backup_success["eventKey"] == backup_failed["eventKey"],
        "backup lifecycle event key changed",
    )
    require(deployment_success["startedAt"] == started, "deployment startedAt drift")
    require(backup_success["startedAt"] == started, "backup startedAt drift")
    combined = b"".join(
        canonical_json(value)
        for value in (
            deployment_running,
            deployment_success,
            deployment_failed,
            backup_running,
            backup_success,
            backup_failed,
        )
    )
    require(SECRET_MARKER.encode() not in combined, "secret marker leaked into payload")
    require(b"/private/" not in combined and b"/Users/" not in combined, "private path leaked")

    for field, replacement in (
        ("homeOpsCommit", "0" * 40),
        ("reporterSha256", "0" * 64),
        ("deploymentRequestSha256", "0" * 64),
        ("backupRequestSha256", "0" * 64),
    ):
        invalid_compatibility_root = temporary / f"invalid-compatibility-{field}"
        invalid_compatibility = dict(compatibility)
        invalid_compatibility[field] = replacement
        write(
            invalid_compatibility_root / "homeops-compatibility.json",
            json.dumps(invalid_compatibility) + "\n",
            0o644,
        )
        try:
            load_compatibility(invalid_compatibility_root)
        except HomeOpsContractError:
            continue
        raise AssertionError(f"drifted HomeOps {field} authority was accepted")

    for invalid_call in (
        lambda: build_deployment_event("SUCCESS", "bad", IMAGE_DIGEST, started, finished, None),
        lambda: build_deployment_event("UNKNOWN", RELEASE_SHA, IMAGE_DIGEST, started, finished, None),
        lambda: build_deployment_event("FAILED", RELEASE_SHA, IMAGE_DIGEST, started, finished, None),
        lambda: build_backup_event("SUCCESS", "bad", started, finished, None),
        lambda: build_backup_event("FAILED", "20260901T110000Z-111111111111", started, finished, "bad path"),
    ):
        try:
            invalid_call()
        except HomeOpsContractError:
            pass
        else:
            raise AssertionError("invalid HomeOps event was accepted")

    account_home = temporary / "account"
    reporter = account_home / str(compatibility["reporterRelativePath"])
    write(reporter, "# synthetic reporter; network disabled\n", 0o700)
    captured: list[tuple[tuple[str, ...], bytes]] = []

    def retained(arguments, payload):
        captured.append((tuple(arguments), payload))
        return 0

    with patch("rhaomi_homeops.sha256_file", return_value=compatibility["reporterSha256"]):
        retained_outcome = report_event(
            "deployments",
            deployment_running,
            REPO_ROOT / "ops" / "production",
            account_home=account_home,
            reporter_runner=retained,
        )
        failed_outcome = report_event(
            "backups",
            backup_running,
            REPO_ROOT / "ops" / "production",
            account_home=account_home,
            reporter_runner=lambda _arguments, _payload: 1,
        )
    require(retained_outcome == "RETAINED", "transient retained outcome drift")
    require(failed_outcome == "FAILED", "local spool failure outcome drift")
    require(len(captured) == 1, "unexpected actual reporter invocation")
    require(captured[0][0][-1] == "deployments", "reporter mode drift")
    require(json.loads(captured[0][1]) == deployment_running, "reporter payload drift")
    with patch("rhaomi_homeops.sha256_file", side_effect=OSError("synthetic")):
        require(
            report_event(
                "deployments",
                deployment_running,
                REPO_ROOT / "ops" / "production",
                account_home=account_home,
                reporter_runner=retained,
            )
            == "FAILED",
            "reporter hash failure was not bounded",
        )
    shutil.rmtree(account_home)
    not_configured = report_event(
        "deployments",
        deployment_running,
        REPO_ROOT / "ops" / "production",
        account_home=account_home,
        reporter_runner=lambda _arguments, _payload: 99,
    )
    require(not_configured == "NOT_CONFIGURED", "missing reporter did not fail closed")
    return {
        "deploymentExactShape": True,
        "backupExactShape": True,
        "lifecycleEventKeyStable": True,
        "retainedTransientSeparated": True,
        "localSpoolFailureSeparated": True,
        "productionPinDriftRejected": True,
        "reporterHashDriftRejected": True,
        "deploymentRequestHashDriftRejected": True,
        "backupRequestHashDriftRejected": True,
        "actualHomeOpsNetworkCalls": 0,
        "actualHomeOpsSecretReads": 0,
    }


def validate_status(temporary: Path) -> dict[str, object]:
    root = prepare_root(temporary / "status")
    fake = FakeDocker()
    result = collect_status(root, runner=fake, now=FIXED_NOW)
    encoded = canonical_json(result)
    require(len(encoded) <= 4096, "status output is unbounded")
    require(result["project"] == "rhaomi", "status project drift")
    require(result["environment"] == "production", "status environment drift")
    require(result["release"]["releaseSha"] == RELEASE_SHA, "release SHA missing")
    require(result["release"]["imageDigest"] == IMAGE_DIGEST, "image digest missing")
    require(result["release"]["backendPublisherSameImage"] is True, "image equality drift")
    require(result["publicWeb"]["available"] is True, "public web status drift")
    require(result["backup"]["freshnessStatus"] == "fresh", "backup freshness drift")
    require(SECRET_MARKER.encode() not in encoded, "status leaked secret marker")
    require(str(root).encode() not in encoded, "status leaked validation path")
    require(b"/private/" not in encoded and b"/Users/" not in encoded, "status leaked private path")
    require(all(".Config.Env" not in " ".join(command) for command in fake.commands), "Docker Env inspected")
    require(
        any(
            "wget" in command and "-qO" in command and "/dev/null" in command
            for command in fake.commands
        ),
        "public status probe captured response content",
    )
    lock = root / "state" / "locks" / "rhaomi-deploy.lock"
    lock.mkdir(mode=0o700)
    locked = collect_status(root, runner=FakeDocker(), now=FIXED_NOW)
    require(locked["operation"]["deployBackupLockPresent"] is True, "lock signal missing")
    lock.rmdir()

    invalid_root = prepare_root(temporary / "status-invalid-eligibility")
    eligibility = invalid_root / "state" / "deploy" / "backup-eligibility.json"
    invalid_value = json.loads(eligibility.read_text(encoding="utf-8"))
    invalid_value["targetReleaseSha"] = int("1" * 40)
    write(eligibility, json.dumps(invalid_value) + "\n", 0o600)
    invalid = collect_status(invalid_root, runner=FakeDocker(), now=FIXED_NOW)
    require(invalid["backup"]["freshnessStatus"] == "invalid", "numeric identity accepted")

    invalid_manifest_root = prepare_root(temporary / "status-invalid-manifest")
    invalid_manifest_path = invalid_manifest_root / "state" / "deploy" / "backup-eligibility.json"
    invalid_manifest = json.loads(invalid_manifest_path.read_text(encoding="utf-8"))
    invalid_manifest["backupManifestSha256"] = "not-a-sha256"
    write(invalid_manifest_path, json.dumps(invalid_manifest) + "\n", 0o600)
    invalid_manifest_status = collect_status(
        invalid_manifest_root, runner=FakeDocker(), now=FIXED_NOW
    )
    require(
        invalid_manifest_status["backup"]["freshnessStatus"] == "invalid",
        "invalid backup manifest authority accepted",
    )
    return {
        "schemaVersion": result["schemaVersion"],
        "boundedBytes": len(encoded),
        "secretMarkerCount": encoded.count(SECRET_MARKER.encode()),
        "privatePathCount": encoded.count(b"/private/") + encoded.count(b"/Users/"),
        "dockerEnvironmentInspectCount": 0,
        "lockSignalVerified": True,
    }


def directory_digest(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(value for value in root.rglob("*") if value.is_file()):
        digest.update(str(path.relative_to(root)).encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()


def validate_recovery(temporary: Path) -> dict[str, object]:
    successful = {}
    for service in ("rhaomi-web", "backend"):
        root = prepare_root(temporary / f"recovery-{service}")
        before = directory_digest(root)
        fake = FakeDocker()
        result, status = recover_service(
            root,
            "restart",
            service,
            runner=fake,
            clock=lambda: FIXED_NOW,
            sleeper=lambda _seconds: None,
            maximum_attempts=2,
        )
        require(status == 0 and result["status"] == "success", f"{service} recovery failed")
        require(result["restartCount"] == 1 and fake.restart_count == 1, "restart count drift")
        require(result["postHealth"] == "UP", "post health drift")
        require(not (root / "state" / "locks" / "rhaomi-deploy.lock").exists(), "own lock leaked")
        require(directory_digest(root) == before, "recovery changed persistent files")
        joined = "\n".join(" ".join(command) for command in fake.commands)
        require(" compose " in f" {joined} ", "compose was not used")
        require(" down " not in f" {joined} " and " up " not in f" {joined} ", "down/up used")
        require(" pull " not in f" {joined} " and " rm " not in f" {joined} ", "image mutation used")
        successful[service] = True

    invalid_root = prepare_root(temporary / "recovery-invalid")
    invalid_fake = FakeDocker()
    for operation, service in (
        ("restart", "publisher"),
        ("restart", "postgres"),
        ("start", "rhaomi-web"),
        ("restart", "unknown"),
    ):
        result, status = recover_service(invalid_root, operation, service, runner=invalid_fake)
        require(status == 1 and result["restartCount"] == 0, "invalid recovery mutated service")
    require(invalid_fake.restart_count == 0, "protected recovery restarted a service")

    wrong_project_root = prepare_root(temporary / "recovery-wrong-project")
    wrong_project_fake = FakeDocker(project="other-project")
    wrong_project_result, wrong_project_status = recover_service(
        wrong_project_root,
        "restart",
        "backend",
        runner=wrong_project_fake,
    )
    require(wrong_project_status == 1, "wrong Compose project returned success")
    require(wrong_project_result["code"] == "RECOVERY_TARGET_INVALID", "project code drift")
    require(wrong_project_fake.restart_count == 0, "wrong Compose project restarted service")

    locked_root = prepare_root(temporary / "recovery-locked")
    lock = locked_root / "state" / "locks" / "rhaomi-deploy.lock"
    lock.mkdir(mode=0o700)
    locked_fake = FakeDocker()
    locked_result, locked_status = recover_service(
        locked_root, "restart", "rhaomi-web", runner=locked_fake
    )
    require(locked_status == 1 and locked_result["code"] == "RECOVERY_LOCKED", "lock fail-close drift")
    require(locked_fake.restart_count == 0, "lock contention restarted service")

    failed_root = prepare_root(temporary / "recovery-post-failure")
    failed_fake = FakeDocker(post_health_failure=True)
    failed_result, failed_status = recover_service(
        failed_root,
        "restart",
        "backend",
        runner=failed_fake,
        sleeper=lambda _seconds: None,
        maximum_attempts=2,
    )
    require(failed_status == 1, "post-health failure returned success")
    require(failed_result["code"] == "RECOVERY_POST_HEALTH_FAILED", "post-health code drift")
    require(failed_result["restartCount"] == 1 and failed_fake.restart_count == 1, "failure retried restart")
    require(not (failed_root / "state" / "locks" / "rhaomi-deploy.lock").exists(), "failure own lock leaked")

    uncertain_root = prepare_root(temporary / "recovery-uncertain")
    uncertain_fake = FakeDocker()

    def uncertain_runner(arguments):
        if "restart" in arguments:
            uncertain_fake.restart_count += 1
            raise HomeOpsContractError("RHAOMI_STATUS_UNAVAILABLE")
        return uncertain_fake(arguments)

    uncertain_result, uncertain_status = recover_service(
        uncertain_root,
        "restart",
        "backend",
        runner=uncertain_runner,
        sleeper=lambda _seconds: None,
    )
    require(uncertain_status == 1, "uncertain restart returned success")
    require(uncertain_result["code"] == "RECOVERY_RESTART_UNCONFIRMED", "uncertain code drift")
    require(uncertain_result["restartCount"] == 1, "uncertain restart retried")
    require(
        (uncertain_root / "state" / "locks" / "rhaomi-deploy.lock").is_dir(),
        "uncertain physical restart released shared lock",
    )
    return {
        "webExactlyOneRestart": successful["rhaomi-web"],
        "backendExactlyOneRestart": successful["backend"],
        "protectedServiceMutationCount": 0,
        "wrongProjectMutationCount": 0,
        "lockContentionMutationCount": 0,
        "postHealthFalseSuccessCount": 0,
        "uncertainRestartLockReleaseCount": 0,
        "composeDownUpCount": 0,
        "imagePullOrDeleteCount": 0,
        "persistentFileMutationCount": 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--git-head", required=True)
    parser.add_argument("--evidence-dir", required=True)
    arguments = parser.parse_args()
    if len(arguments.git_head) != 40 or any(character not in "0123456789abcdef" for character in arguments.git_head):
        raise SystemExit("HOMEOPS_VALIDATION_INPUT_INVALID")
    evidence_dir = Path(arguments.evidence_dir).resolve()
    evidence_dir.mkdir(parents=True, exist_ok=True)
    if any(evidence_dir.iterdir()):
        raise SystemExit("HOMEOPS_VALIDATION_EVIDENCE_NOT_EMPTY")
    with tempfile.TemporaryDirectory(prefix="rhaomi-homeops-validation-") as raw_temporary:
        temporary = Path(raw_temporary)
        compatibility = validate_compatibility()
        activation_preflight_evidence = validate_activation_preflight(compatibility)
        event_evidence = validate_events(compatibility, temporary)
        status_evidence = validate_status(temporary)
        recovery_evidence = validate_recovery(temporary)
    evidence = {
        "contract": "rhaomi-homeops-d-imp-5b-preflight-v1",
        "gitHead": arguments.git_head,
        "homeOpsCommit": compatibility["homeOpsCommit"],
        "reporterSha256": compatibility["reporterSha256"],
        "activationPreflight": activation_preflight_evidence,
        "events": event_evidence,
        "status": status_evidence,
        "recovery": recovery_evidence,
        "productionPathMutation": 0,
        "homeOpsRepositoryMutation": 0,
        "dockerVolumeDeletion": 0,
        "dockerImageDeletion": 0,
    }
    write(evidence_dir / "homeops-integration-evidence.json", json.dumps(evidence, indent=2) + "\n", 0o600)
    print("HomeOps integration task validation: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
