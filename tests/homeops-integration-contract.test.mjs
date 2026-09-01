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
  assert.equal(contract.homeOpsCommit, "f3845396bd4d6bf677d1d8bf6bbcb82113851c14");
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
  assert.equal(contract.monitoringRequest.productionThresholds, "PROVISIONING_REQUIRED");
  assert.equal(contract.monitoringRequest.notificationEnabled, false);
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
  assert.match(workflow, /homeops-integration-evidence-\$\{\{ github\.event\.pull_request\.head\.sha \}\}/u);
  assert.match(workflow, /57-homeops-integration-boundary/u);
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
