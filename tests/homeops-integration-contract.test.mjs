import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));

async function source(path) {
  return readFile(join(projectRoot, path), "utf8");
}

test("HomeOps compatibility snapshot이 current main DTO와 reporter authority를 고정한다", async () => {
  const contract = JSON.parse(await source("ops/production/homeops-compatibility.json"));
  assert.equal(contract.schemaVersion, 1);
  assert.equal(contract.homeOpsCommit, "0a8ce9090c76f5ad7afba19ca896e923b96b0cbf");
  assert.equal(
    contract.reporterSha256,
    "7a7ea2f7597efdc0d174775f28626dac7d330bddd638446fd0fe0f4e0f3acf9c",
  );
  assert.equal(
    contract.deploymentRequestSha256,
    "737f49a2fd5398501f6bcb18ea7935f1e17438338e23388cf93c6b1d2a42fbc5",
  );
  assert.equal(
    contract.backupRequestSha256,
    "a77610337bf2763b50c00786e8a897018dac9c107e78af827f2caeffc45dade4",
  );
  assert.deepEqual(contract.deploymentStatuses, [
    "REQUESTED",
    "RUNNING",
    "SUCCESS",
    "FAILED",
    "ROLLED_BACK",
    "CANCELLED",
  ]);
  assert.deepEqual(contract.backupStatuses, ["RUNNING", "SUCCESS", "FAILED", "INCOMPLETE"]);
  assert.equal(contract.managedLabel, "homeops.managed");
  assert.equal(contract.managedValue, "true");
  assert.equal(contract.writableBindOrVolumeControl, "DENIED");
  assert.deepEqual(Object.keys(contract.monitoringRequest), [
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
  ]);
  assert.deepEqual(contract.monitoringRequest.methods, ["GET", "HEAD"]);
  assert.equal(contract.monitoringRequest.expectedStatusMinimum, 100);
  assert.equal(contract.monitoringRequest.expectedStatusMaximum, 599);
  assert.equal(contract.monitoringRequest.failureThresholdMinimum, 1);
  assert.equal(contract.monitoringRequest.failureThresholdMaximum, 100);
  assert.doesNotMatch(JSON.stringify(contract.monitoringRequest), /keyword|body|content/iu);
  assert.equal(contract.monitoringRequest.productionThresholds, "PROVISIONING_REQUIRED");
  assert.equal(contract.monitoringRequest.notificationEnabled, false);
});

test("HomeOps live release evidence와 web-only production activation preflight를 고정한다", async () => {
  const [
    preflight,
    compatibility,
    adr,
    readiness,
    monitoring,
    deployment,
    backup,
    checklist,
    strategy,
    environment,
  ] =
    await Promise.all([
      source("ops/production/homeops-activation-preflight.json").then(JSON.parse),
      source("ops/production/homeops-compatibility.json").then(JSON.parse),
      source("docs/09-decisions/ADR-013-homeops-monitoring-recovery-boundary.md"),
      source("docs/07-operations/production-readiness.md"),
      source("docs/07-operations/monitoring-and-incident-response.md"),
      source("docs/07-operations/deployment.md"),
      source("docs/07-operations/backup-and-restore.md"),
      source("docs/08-quality/release-checklist.md"),
      source("docs/08-quality/test-strategy.md"),
      source("docs/04-architecture/environment-and-configuration.md"),
    ]);

  assert.deepEqual(Object.keys(preflight), [
    "schemaVersion",
    "overallProductionReadiness",
    "productionCompatibilityAuthority",
    "sourceImplementationEvidence",
    "productionReleaseEvidence",
    "automaticRecoveryPolicy",
    "releaseOrder",
    "activationSequence",
    "productionState",
    "privateEvidenceRequired",
  ]);
  assert.equal(preflight.schemaVersion, 1);
  assert.equal(preflight.overallProductionReadiness, "HOLD");
  assert.deepEqual(preflight.productionCompatibilityAuthority, {
    homeOpsBranch: "main",
    homeOpsCommit: "0a8ce9090c76f5ad7afba19ca896e923b96b0cbf",
    compatibilityFile: "ops/production/homeops-compatibility.json",
  });
  assert.equal(
    preflight.productionCompatibilityAuthority.homeOpsCommit,
    compatibility.homeOpsCommit,
  );
  assert.deepEqual(preflight.sourceImplementationEvidence, {
    status: "LOCAL_CI_VERIFIED",
    homeOpsBranch: "dev",
    homeOpsCommit: "e4d5c59841e30fdc20bf1ce55fa419ac3f766a13",
    homeOpsTree: "f8f77091383931f36dc96aa35242193bb5ab1f01",
    pullRequest: 120,
    postMergeValidateRun: 33527901223,
  });
  assert.notEqual(
    preflight.sourceImplementationEvidence.homeOpsCommit,
    compatibility.homeOpsCommit,
  );
  assert.deepEqual(preflight.productionReleaseEvidence, {
    status: "RELEASED_AND_DEPLOYED",
    homeOpsBranch: "main",
    homeOpsCommit: "0a8ce9090c76f5ad7afba19ca896e923b96b0cbf",
    homeOpsTree: "f8f77091383931f36dc96aa35242193bb5ab1f01",
    pullRequest: 122,
    publishAndDeployRun: 33569523762,
    agentArtifactDigest:
      "sha256:305c0f216bf00097ae8532b33991aed99e752669a32956b85eebfbf7351bcf4b",
  });
  assert.equal(preflight.productionReleaseEvidence.homeOpsCommit, compatibility.homeOpsCommit);
  assert.equal(
    preflight.productionReleaseEvidence.homeOpsTree,
    preflight.sourceImplementationEvidence.homeOpsTree,
  );
  assert.deepEqual(preflight.automaticRecoveryPolicy, {
    mappings: [
      {
        monitorSignal: "PUBLIC_HTTPS_STATUS",
        expectedStatusAuthority: "MONITORED_SERVICE_EXPECTED_STATUS",
        failureThreshold: 3,
        target: "rhaomi-web",
        action: "RESTART",
        initialState: "DISABLED",
      },
    ],
    unmappedTargets: ["backend"],
    cooldownSeconds: 1800,
    noAutoRetryOutcomes: ["FAILED", "OUTCOME_UNKNOWN"],
  });
  assert.deepEqual(preflight.releaseOrder, [
    "HOMEOPS_RELEASE",
    "LIVE_COMPATIBILITY_REVALIDATION",
    "RHAOMI_RELEASE_PROVISIONING",
  ]);
  assert.deepEqual(preflight.productionState, {
    homeOpsRelease: "COMPLETED",
    homeOpsApplicationDeploy: "COMPLETED",
    rhaomiRelease: "NOT_RUN",
    v14ProductionMigration: "APPLIED",
    agentArtifact: "PUBLISHED",
    webMapping: "NOT_CREATED",
    backendMapping: "ABSENT",
    agentRollout: "NOT_RUN",
    mappingEnable: "NOT_RUN",
    restartDrill: "NOT_RUN",
    notificationActivation: "NOT_RUN",
  });

  for (const document of [adr, monitoring]) {
    assert.match(document, /HomeOps release[^\n]*live compatibility[^\n]*Rhaomi release\/provisioning/u);
    assert.match(document, /public HTTPS[^\n]*(?:expected HTTP status|expectedStatus|HTTP status)[^\n]*3회[^\n]*rhaomi-web/iu);
    assert.match(document, /keyword[^\n]*(?:별도|future|IMPLEMENTATION_REQUIRED)[^\n]*(?:구현|재검토|enhancement)/iu);
    assert.match(document, /backend[^\n]*(?:unmapped|mapping 없음|매핑 없음|미매핑)/u);
    assert.match(document, /FAILED[^\n]*OUTCOME_UNKNOWN[^\n]*(?:자동 재실행 금지|no-auto-retry)/u);
  }
  for (const document of [adr, readiness, monitoring, deployment, backup, checklist, strategy]) {
    assert.doesNotMatch(document, /PUBLIC_HTTPS_KEYWORD/iu);
  }
  for (const document of [readiness, deployment, checklist, strategy]) {
    assert.match(document, /HomeOps release[^\n]*live compatibility[^\n]*Rhaomi release\/provisioning/u);
  }
  assert.match(readiness, /HomeOps monitoring \/ recovery[^\n]*`PROVISIONING_REQUIRED`/u);
  assert.match(deployment, /mapping enable[^\n]*restart\/drill[^\n]*(?:별도 승인|수행 금지)/iu);
  assert.match(backup, /recovery preflight[^\n]*shared deploy\/backup lock/u);
  for (const document of [adr, readiness, monitoring, deployment, checklist, strategy, environment]) {
    assert.match(document, /0a8ce9090c76f5ad7afba19ca896e923b96b0cbf/u);
    assert.match(document, /33569523762/u);
    assert.match(document, /Agent[^\n]*(?:artifact|아티팩트)[^\n]*(?:PUBLISHED|게시|publish)/iu);
    assert.match(document, /Agent[^\n]*(?:rollout|롤아웃)[^\n]*(?:NOT_RUN|미수행|수행하지)/iu);
  }
});

test("production entrypoint가 root·reporter·URL·command override를 받지 않는다", async () => {
  const [core, reporter, status, recovery] = await Promise.all([
    source("ops/production/rhaomi_homeops.py"),
    source("ops/production/report-rhaomi-event.py"),
    source("ops/production/status-rhaomi.py"),
    source("ops/production/recover-rhaomi-service.py"),
  ]);
  assert.match(core, /PRODUCTION_ROOT = Path\("\/private\/var\/lib\/rhaomi"\)/u);
  assert.match(core, /pwd\.getpwuid\(os\.getuid\(\)\)\.pw_dir/u);
  assert.match(core, /homeops-compatibility\.json/u);
  assert.match(core, /runtime-config\/current\/scripts\/report-homeops-event\.py/u);
  assert.doesNotMatch(
    `${reporter}\n${status}\n${recovery}`,
    /RHAOMI_(?:ROOT|REPORTER|URL|COMMAND)|HOMEOPS_(?:SECRET|URL|REPORTER)|eval|shell=True/u,
  );
  assert.doesNotMatch(core, /\.Config\.Env|Docker inspect Env|requests|urllib/u);
});

test("production Compose와 Hosted Validate가 web-only control·exact-head evidence를 고정한다", async () => {
  const [compose, workflow] = await Promise.all([
    source("compose.production.yaml"),
    source(".github/workflows/validate.yml"),
  ]);
  const managed = compose.match(/homeops\.managed:\s*"true"/gu) ?? [];
  assert.equal(managed.length, 1);
  assert.match(compose, /rhaomi-web:[\s\S]*homeops\.managed:\s*"true"/u);
  assert.doesNotMatch(
    compose.slice(compose.indexOf("\n  backend:")),
    /homeops\.managed/u,
  );
  assert.match(workflow, /validate-homeops-integration\.sh/u);
  assert.match(workflow, /59-homeops-activation-preflight/u);
  assert.match(workflow, /HomeOps D-IMP-5b source·activation preflight 계약 검증/u);
  assert.match(workflow, /homeops-activation-preflight-evidence-\$\{\{ github\.event\.pull_request\.head\.sha \}\}/u);
});

test("task validator가 status/event/recovery privacy와 fail-closed 계약을 실행한다", async (t) => {
  if (!existsSync("/usr/bin/python3")) {
    t.skip("Python 없는 frontend-only image에서는 별도 host/Backend gate가 validator를 실행합니다.");
    return;
  }
  const evidence = await mkdtemp(join(tmpdir(), "rhaomi-homeops-contract-"));
  t.after(() => rm(evidence, { recursive: true, force: true }));
  const result = spawnSync("/bin/sh", ["scripts/validate-homeops-integration.sh"], {
    cwd: projectRoot,
    encoding: "utf8",
    env: { ...process.env, RHAOMI_HOMEOPS_EVIDENCE_DIR: evidence },
  });
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  const summary = JSON.parse(await readFile(join(evidence, "homeops-integration-evidence.json"), "utf8"));
  assert.equal(summary.events.lifecycleEventKeyStable, true);
  assert.equal(summary.events.actualHomeOpsNetworkCalls, 0);
  assert.equal(summary.events.actualHomeOpsSecretReads, 0);
  assert.equal(summary.activationPreflight.overallProductionReadiness, "HOLD");
  assert.equal(
    summary.activationPreflight.homeOpsProductionCommit,
    "0a8ce9090c76f5ad7afba19ca896e923b96b0cbf",
  );
  assert.equal(summary.activationPreflight.homeOpsRelease, "COMPLETED");
  assert.equal(summary.activationPreflight.homeOpsApplicationDeploy, "COMPLETED");
  assert.equal(summary.activationPreflight.v14ProductionMigration, "APPLIED");
  assert.equal(summary.activationPreflight.agentArtifact, "PUBLISHED");
  assert.equal(
    summary.activationPreflight.agentArtifactDigest,
    "sha256:305c0f216bf00097ae8532b33991aed99e752669a32956b85eebfbf7351bcf4b",
  );
  assert.equal(summary.activationPreflight.agentRollout, "NOT_RUN");
  assert.equal(summary.activationPreflight.webMonitorSignal, "PUBLIC_HTTPS_STATUS");
  assert.equal(
    summary.activationPreflight.webExpectedStatusAuthority,
    "MONITORED_SERVICE_EXPECTED_STATUS",
  );
  assert.equal(summary.activationPreflight.keywordBodyMatcherSupported, false);
  assert.equal(summary.activationPreflight.webFailureThreshold, 3);
  assert.equal(summary.activationPreflight.webTarget, "rhaomi-web");
  assert.equal(summary.activationPreflight.backendMapping, "ABSENT");
  assert.equal(summary.activationPreflight.mappingEnableCount, 0);
  assert.equal(summary.activationPreflight.actualRestartOrDrillCount, 0);
  assert.equal(summary.activationPreflight.productionPinDriftRejected, true);
  assert.equal(summary.activationPreflight.policyDriftRejected, true);
  assert.equal(summary.activationPreflight.activationStateDriftRejected, true);
  assert.equal(summary.activationPreflight.readinessDriftRejected, true);
  assert.equal(summary.events.productionPinDriftRejected, true);
  assert.equal(summary.events.reporterHashDriftRejected, true);
  assert.equal(summary.events.deploymentRequestHashDriftRejected, true);
  assert.equal(summary.events.backupRequestHashDriftRejected, true);
  assert.equal(summary.status.secretMarkerCount, 0);
  assert.equal(summary.status.privatePathCount, 0);
  assert.equal(summary.status.dockerEnvironmentInspectCount, 0);
  assert.equal(summary.recovery.webExactlyOneRestart, true);
  assert.equal(summary.recovery.backendExactlyOneRestart, true);
  assert.equal(summary.recovery.protectedServiceMutationCount, 0);
  assert.equal(summary.recovery.wrongProjectMutationCount, 0);
  assert.equal(summary.recovery.lockContentionMutationCount, 0);
  assert.equal(summary.recovery.postHealthFalseSuccessCount, 0);
  assert.equal(summary.recovery.uncertainRestartLockReleaseCount, 0);
  assert.equal(summary.recovery.composeDownUpCount, 0);
  assert.equal(summary.productionPathMutation, 0);
  assert.equal(summary.homeOpsRepositoryMutation, 0);
  assert.equal(summary.dockerVolumeDeletion, 0);
  assert.equal(summary.dockerImageDeletion, 0);
});
