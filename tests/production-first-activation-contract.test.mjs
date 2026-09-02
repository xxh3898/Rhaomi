import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));

async function source(path) {
  return readFile(join(projectRoot, path), "utf8");
}

test("first activation은 fixed root와 명시적 two-phase mode만 허용한다", async () => {
  const [wrapper, core, workflow] = await Promise.all([
    source("ops/production/first-activate-rhaomi.sh"),
    source("ops/production/first-activate-rhaomi-core.sh"),
    source(".github/workflows/production-release.yml"),
  ]);

  assert.match(wrapper, /first_activate_rhaomi \/private\/var\/lib\/rhaomi "\$@"/u);
  assert.match(wrapper, /production-lifecycle-core\.sh/u);
  assert.doesNotMatch(wrapper, /RHAOMI_FIRST_ACTIVATION_ROOT|eval/u);
  assert.match(core, /case "\$first_activation_mode" in bootstrap \| accept-recovery/u);
  assert.doesNotMatch(
    core,
    /--force(?:\s|$)|--skip-backup(?:\s|$)|\bskip_backup\b|\beval\b/u,
  );

  assert.match(workflow, /deployment_mode:[\s\S]*type: choice[\s\S]*- steady-state[\s\S]*- first-activation/u);
  assert.match(workflow, /inputs\.deployment_mode == 'steady-state'/u);
  assert.match(workflow, /inputs\.deployment_mode == 'first-activation'/u);
  assert.match(workflow, /first-activate-rhaomi\.sh \\\n\s+--mode bootstrap/u);
  assert.match(workflow, /backup-rhaomi\.sh \\\n\s+--mode first-activation/u);
  assert.match(workflow, /first-activate-rhaomi\.sh \\\n\s+--mode accept-recovery/u);
  assert.doesNotMatch(workflow, /deployment_mode.*(?:auto|detect|infer)/iu);
});

test("verified-empty 판정 뒤 durable bootstrap state를 mutation보다 먼저 기록한다", async () => {
  const core = await source("ops/production/first-activate-rhaomi-core.sh");
  for (const authority of [
    "public/current",
    "public/previous",
    "public/releases",
    "data/media",
    "state/publisher",
    "backup_repository/sets",
    "com.docker.compose.project",
    "_postgres-data",
  ]) {
    assert.ok(core.includes(authority), `${authority} empty authority 검증`);
  }
  assert.match(core, /state\/deploy[\s\S]*find .* -mindepth 1/u);
  assert.match(core, /first_activation_require_owned_private_directory "\$first_activation_root\/state\/deploy"/u);
  assert.match(core, /FIRST_ACTIVATION_STATE_UNKNOWN/u);

  const emptyIndex = core.indexOf("verify_first_activation_empty");
  const verifiedEvidenceIndex = core.indexOf("record_verified_empty_evidence", emptyIndex);
  const bootstrapEvidenceIndex = core.indexOf("record_bootstrap_evidence RUNNING", verifiedEvidenceIndex);
  const stateIndex = core.indexOf("FIRST_ACTIVATION_BOOTSTRAPPING", bootstrapEvidenceIndex);
  const mutationIndex = core.indexOf("first_activation_mutation_started=true", stateIndex);
  const pullIndex = core.indexOf("pull_and_verify_first_activation_image", mutationIndex);
  assert.ok(
    emptyIndex >= 0 &&
      verifiedEvidenceIndex > emptyIndex &&
      bootstrapEvidenceIndex > verifiedEvidenceIndex &&
      stateIndex > bootstrapEvidenceIndex &&
      mutationIndex > stateIndex &&
      pullIndex > mutationIndex,
  );
  assert.match(core, /migration[\s\S]*verify_first_activation_flyway[\s\S]*schema-validate/u);
  assert.match(core, /verify_first_activation_private_runtime/u);
  assert.match(core, /RECOVERY_ACCEPTANCE_REQUIRED/u);
  assert.match(core, /publicIngressActivated": false/u);
});

test("recovery acceptance가 full-read와 isolated restore 뒤에만 STEADY_STATE를 기록한다", async () => {
  const [core, compose, verifier, lifecycle] = await Promise.all([
    source("ops/production/first-activate-rhaomi-core.sh"),
    source("compose.production.first-activation.yaml"),
    source("scripts/rhaomi-backup-verifier.sh"),
    source("ops/production/production-lifecycle-core.sh"),
  ]);

  const requiredIndex = core.indexOf("RECOVERY_ACCEPTANCE_REQUIRED");
  const fullReadIndex = core.indexOf("verify-backup-set", requiredIndex);
  const databaseRestoreIndex = core.indexOf("restore_first_activation_database", fullReadIndex);
  const mediaRestoreIndex = core.indexOf("restore_first_activation_media", databaseRestoreIndex);
  const flywayIndex = core.indexOf("verify_recovery_flyway", mediaRestoreIndex);
  const rowIndex = core.indexOf("verify_recovered_empty_content", flywayIndex);
  const publicationIndex = core.indexOf("wait_for_recovery_publication", rowIndex);
  const smokeIndex = core.indexOf("first-activation-smoke", publicationIndex);
  const quiescenceIndex = core.indexOf("stop_first_activation_recovery", smokeIndex);
  const steadyIndex = core.indexOf("STEADY_STATE", quiescenceIndex);
  assert.ok(
    requiredIndex >= 0 &&
      fullReadIndex > requiredIndex &&
      databaseRestoreIndex > fullReadIndex &&
      mediaRestoreIndex > databaseRestoreIndex &&
      flywayIndex > mediaRestoreIndex &&
      rowIndex > flywayIndex &&
      publicationIndex > rowIndex &&
      smokeIndex > publicationIndex &&
      quiescenceIndex > smokeIndex &&
      steadyIndex > quiescenceIndex,
  );

  assert.match(verifier, /verify-backup-set/u);
  assert.match(verifier, /set -- verify "\$2" full-read/u);
  assert.match(compose, /first-activation-backup-verifier:[\s\S]*read_only: true[\s\S]*network_mode: none/u);
  assert.match(compose, /first-activation-postgres:[\s\S]*\/var\/lib\/postgresql:rw,noexec,nosuid/u);
  assert.match(compose, /first-activation-media-restore:[\s\S]*backup-repository[\s\S]*read_only: true/u);
  assert.match(compose, /first-activation-static:/u);
  assert.doesNotMatch(compose, /ports:/u);
  assert.equal((compose.match(/internal: true/gu) ?? []).length, 3);
  assert.match(lifecycle, /evidenceSha256/u);
  assert.match(lifecycle, /mktemp[\s\S]*mv/u);
  assert.match(lifecycle, /first-activation-recovery\.json/u);
});

test("steady-state backup과 deploy는 durable lifecycle state 없이는 진행하지 않는다", async () => {
  const [backup, deploy, validator] = await Promise.all([
    source("ops/production/backup-rhaomi-core.sh"),
    source("ops/production/deploy-rhaomi-core.sh"),
    source("scripts/validate-production-first-activation.sh"),
  ]);

  assert.match(backup, /rhaomi_lifecycle_require_state STEADY_STATE/u);
  assert.match(backup, /RECOVERY_ACCEPTANCE_REQUIRED "\$target_release_sha"/u);
  assert.match(backup, /--mode first-activation|first-activation/u);
  assert.match(backup, /backup_tool verify "\$backup_set_id" full-read/u);
  assert.match(backup, /first-activation-backup\.env/u);
  assert.match(deploy, /validate_deploy_lifecycle/u);
  assert.match(deploy, /rhaomi_lifecycle_require_state STEADY_STATE/u);

  for (const evidence of [
    "verifiedEmptyMatrix=verified",
    "partialBootstrapReentry=blocked",
    "recoveryAcceptanceFailures=blocked",
    "recoveryComposeRender=verified",
    "steadyStateTransition=verified-once",
    "productionPathMutation=0",
    "dockerVolumeDeletion=0",
    "dockerImageDeletion=0",
  ]) {
    assert.ok(validator.includes(evidence));
  }
});
