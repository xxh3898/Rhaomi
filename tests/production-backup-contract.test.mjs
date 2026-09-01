import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));

async function source(path) {
  return readFile(join(projectRoot, path), "utf8");
}

test("backup manifest V1과 release eligibility가 exact hash chain을 고정한다", async () => {
  const tool = await source("scripts/rhaomi-backup-tool.mjs");
  for (const field of [
    "schemaVersion",
    "backupSetId",
    "backupPurpose",
    "startedAt",
    "completedAt",
    "verifiedAt",
    "sourceReleaseSha",
    "sourceImageDigest",
    "sourceFlywayVersion",
    "postgresDump",
    "media",
    "sameHostFailureDomain",
  ]) {
    assert.ok(tool.includes(`"${field}"`));
  }
  for (const field of [
    "targetReleaseSha",
    "backupManifestSha256",
    "createdAt",
    "status",
  ]) {
    assert.ok(tool.includes(`"${field}"`));
  }
  assert.match(tool, /input\.schemaVersion !== 1/u);
  assert.match(tool, /input\.sameHostFailureDomain !== true/u);
  assert.match(tool, /dumpHeader\.toString\("ascii"\) !== "PGDMP"/u);
  assert.match(tool, /verifySetDirectory\(completeSetPath\(root, setId\), "full-read"\)/u);
  assert.match(tool, /rename\(incomplete, complete\)/u);
  assert.match(tool, /evidenceSha256=\$\{evidenceSha256\}/u);
  assert.match(tool, /verify-eligibility/u);
  assert.doesNotMatch(tool, /Number\(.*(?:Revision|Generation)|parseInt/u);
});

test("fixed backup entrypoint가 repository CLI 없이 shared lock과 writer-quiescent capture를 수행한다", async () => {
  const [wrapper, core] = await Promise.all([
    source("ops/production/backup-rhaomi.sh"),
    source("ops/production/backup-rhaomi-core.sh"),
  ]);
  assert.match(wrapper, /backup_rhaomi \/private\/var\/lib\/rhaomi/u);
  assert.doesNotMatch(wrapper, /\$\{?RHAOMI_BACKUP_ROOT|eval/u);
  assert.match(core, /RHAOMI_BACKUP_REPOSITORY_ROOT=/u);
  assert.match(core, /command -v docker >\/dev\/null/u);
  assert.match(core, /command -v docker-compose/u);
  assert.match(core, /docker-compose \\\n/u);
  assert.match(core, /production\.env/u);
  assert.match(core, /rhaomi-deploy\.lock/u);
  assert.match(core, /stop --timeout 30 backend publisher/u);
  assert.match(core, /verify_backup_writer_quiescence/u);
  assert.match(core, /pg_dump --format=custom --no-password/u);
  assert.match(core, /pg_restore --list/u);
  assert.match(core, /capture-media/u);
  assert.match(core, /restore_backup_writers/u);
  assert.match(core, /backup_tool finalize/u);
  assert.match(core, /transition_backup_validation_media_state capture/u);
  assert.match(core, /transition_backup_validation_media_state runtime/u);
  const stopIndex = core.indexOf("stop --timeout 30 backend publisher");
  const quiescenceIndex = core.indexOf("verify_backup_writer_quiescence", stopIndex);
  const capturePermissionIndex = core.indexOf(
    "transition_backup_validation_media_state capture",
    quiescenceIndex,
  );
  const captureMediaIndex = core.indexOf('backup_tool capture-media "$backup_set_id"');
  const writerRecoveryIndex = core.indexOf("restore_backup_writers ||");
  const finalizeIndex = core.indexOf("backup_tool finalize");
  assert(
    stopIndex >= 0 &&
      quiescenceIndex > stopIndex &&
      capturePermissionIndex > quiescenceIndex &&
      captureMediaIndex > capturePermissionIndex &&
      writerRecoveryIndex > captureMediaIndex &&
      finalizeIndex > writerRecoveryIndex,
  );
  assert.match(core, /backup_lock_preserve=true/u);
  for (const mode of [
    "scheduled",
    "on-demand",
    "predeploy",
    "structural-check",
    "full-read-check",
    "retention-dry-run",
    "retention-apply",
  ]) {
    assert.ok(core.includes(mode));
  }
  assert.doesNotMatch(core, /source .*production\.env|eval|down -v|volume prune|image prune/u);
});

test("backup tool production profile과 validation-only permission service가 fail-closed 경계를 유지한다", async () => {
  const [compose, overlay, dockerfile, permissionHelper, verifierEntrypoint] = await Promise.all([
    source("compose.production.yaml"),
    source("compose.production.validation.yaml"),
    source("backend/Dockerfile.production"),
    source("scripts/rhaomi-backup-media-permissions.sh"),
    source("scripts/rhaomi-backup-verifier.sh"),
  ]);
  const block = compose.match(/\n  backup-tool:\n([\s\S]*?)\n  backup-verifier:/u)?.[1];
  assert.ok(block);
  assert.match(block, /profiles: \["production-backup"\]/u);
  assert.match(block, /image: \$\{RHAOMI_PRODUCTION_IMAGE:/u);
  assert.match(block, /network_mode: none/u);
  assert.match(block, /read_only: true/u);
  assert.match(block, /cap_drop: \["ALL"\]/u);
  assert.match(block, /source: \$\{RHAOMI_BACKUP_REPOSITORY_ROOT:/u);
  assert.match(block, /target: \/var\/lib\/rhaomi\/media[\s\S]*read_only: true/u);
  assert.match(block, /target: \/var\/lib\/rhaomi\/deploy-state/u);
  assert.doesNotMatch(block, /POSTGRES_PASSWORD|BUILD_SERVICE_TOKEN|docker\.sock|ports:/u);
  const verifierBlock = compose.match(/\n  backup-verifier:\n([\s\S]*?)\n  postgres:/u)?.[1];
  assert.ok(verifierBlock);
  assert.match(verifierBlock, /profiles: \["production-backup"\]/u);
  assert.match(verifierBlock, /image: \$\{RHAOMI_PRODUCTION_IMAGE:/u);
  assert.match(verifierBlock, /read_only: true/u);
  assert.match(verifierBlock, /cap_drop: \["ALL"\]/u);
  assert.match(verifierBlock, /no-new-privileges:true/u);
  assert.match(verifierBlock, /network_mode: none/u);
  assert.match(verifierBlock, /entrypoint: \["\/usr\/local\/bin\/rhaomi-backup-verifier"\]/u);
  assert.match(
    verifierBlock,
    /target: \/var\/lib\/rhaomi\/backup-repository[\s\S]*read_only: true/u,
  );
  assert.match(
    verifierBlock,
    /target: \/var\/lib\/rhaomi\/deploy-state[\s\S]*read_only: true/u,
  );
  assert.doesNotMatch(
    verifierBlock,
    /RHAOMI_BACKUP_MEDIA_ROOT|POSTGRES_PASSWORD|BUILD_SERVICE_TOKEN|docker\.sock|ports:|tmpfs:/u,
  );
  assert.match(overlay, /RHAOMI_BACKUP_RESTORE_MEDIA_ROOT/u);
  assert.match(overlay, /backup-verifier:[\s\S]*read_only: true/u);
  assert.match(dockerfile, /COPY scripts\/rhaomi-backup-tool\.mjs/u);
  assert.match(dockerfile, /COPY --chmod=0555 scripts\/rhaomi-backup-verifier\.sh/u);
  assert.match(
    dockerfile,
    /COPY --chmod=0555 scripts\/rhaomi-backup-media-permissions\.sh/u,
  );
  const permissionBlock = overlay.match(/\n  backup-permission:\n([\s\S]*?)\n  postgres:/u)?.[1];
  assert.ok(permissionBlock);
  assert.match(permissionBlock, /profiles: \["production-backup"\]/u);
  assert.match(permissionBlock, /user: "0:0"/u);
  assert.match(permissionBlock, /cap_drop: \["ALL"\]/u);
  assert.match(permissionBlock, /cap_add: \["CHOWN", "DAC_OVERRIDE", "FOWNER"\]/u);
  assert.match(permissionBlock, /network_mode: none/u);
  assert.match(permissionBlock, /target: \/var\/lib\/rhaomi\/media-permissions/u);
  assert.doesNotMatch(permissionBlock, /POSTGRES_PASSWORD|BUILD_SERVICE_TOKEN|docker\.sock|ports:/u);
  assert.match(permissionHelper, /media_root=\/var\/lib\/rhaomi\/media-permissions/u);
  for (const action of ["runtime", "capture", "assert-runtime", "assert-capture"]) {
    assert.match(permissionHelper, new RegExp(`\\n  ${action}\\)`, "u"));
  }
  assert.match(permissionHelper, /apply_tree_state 0 "\$host_gid" 0750 0640/u);
  assert.match(permissionHelper, /apply_tree_state "\$host_uid" "\$host_gid" 0700 0600/u);
  assert.match(permissionHelper, /! -type d ! -type f/u);
  assert.doesNotMatch(permissionHelper, /eval|\/private\/var\/lib\/rhaomi/u);
  assert.match(verifierEntrypoint, /^#!\/bin\/sh/u);
  assert.match(verifierEntrypoint, /\[ "\$1" = verify-eligibility \]/u);
  assert.match(verifierEntrypoint, /\^\[0-9a-f\]\{40\}\$/u);
  assert.match(verifierEntrypoint, /exec \/usr\/local\/bin\/node/u);
  assert.doesNotMatch(verifierEntrypoint, /eval|RHAOMI_BACKUP_MEDIA_ROOT/u);
});

test("deploy backup gate가 target·evidence hash·complete manifest를 writer mutation 전에 재검증한다", async () => {
  const core = await source("ops/production/deploy-rhaomi-core.sh");
  const envelopeIndex = core.indexOf("validate_backup_eligibility_envelope");
  const pullIndex = core.indexOf("pull_and_verify_release_image", envelopeIndex);
  const fullReadIndex = core.indexOf("validate_backup_eligibility_full_read", pullIndex);
  const writerIndex = core.indexOf("writer_maintenance_active=true", fullReadIndex);
  assert(
    envelopeIndex >= 0 &&
      pullIndex > envelopeIndex &&
      fullReadIndex > pullIndex &&
      writerIndex > fullReadIndex,
  );
  assert.match(core, /backup-eligibility\.json/u);
  assert.match(core, /openssl dgst -sha256/u);
  assert.match(core, /backup-verifier verify-eligibility "\$release_sha"/u);
  assert.match(core, /RHAOMI_BACKUP_REPOSITORY_ROOT/u);
  assert.match(core, /\.rhaomi-backup-repository/u);
});

test("tracked macOS schedule source는 매일 03:30 fixed scheduled mode만 실행한다", async () => {
  const plist = await source("ops/production/com.rhaomi.backup.plist");
  assert.match(plist, /<string>com\.rhaomi\.backup<\/string>/u);
  assert.match(plist, /\/private\/var\/lib\/rhaomi\/app\/bin\/backup-rhaomi\.sh/u);
  assert.match(plist, /<string>--mode<\/string>[\s\S]*<string>scheduled<\/string>/u);
  assert.match(plist, /<key>Hour<\/key>\s*<integer>3<\/integer>/u);
  assert.match(plist, /<key>Minute<\/key>\s*<integer>30<\/integer>/u);
  assert.doesNotMatch(plist, /RunAtLoad|KeepAlive|Program<\/key>\s*<string>\/(?:bin|usr\/bin)\/(?:sh|bash)/u);
});

test("task validator가 A backup/B mutation/fresh restore A와 persistence를 executable contract로 고정한다", async () => {
  const [validator, control, orchestrator, nextBuild] = await Promise.all([
    source("scripts/validate-production-backup.sh"),
    source("scripts/validate-production-backup-control.sh"),
    source("src/publication-release/orchestrator.mts"),
    source("src/publication-release/next-build.mts"),
  ]);
  assert.match(validator, /seed_source_a/u);
  assert.match(validator, /mutate_source_to_b/u);
  assert.match(validator, /verify_restored_a/u);
  assert.match(validator, /pg_restore[\s\S]*--exit-on-error/u);
  assert.match(validator, /schema-validate/u);
  assert.match(validator, /wait_for_static_publication/u);
  assert.match(validator, /restart postgres/u);
  assert.match(validator, /down --remove-orphans/u);
  assert.match(validator, /docker volume inspect/u);
  assert.match(validator, /sourceVolumeRetained=true/u);
  assert.match(validator, /restoreVolumeRetained=true/u);
  assert.match(validator, /sameHostFailureDomain/u);
  assert.match(validator, /backup-verifier verify-eligibility "\$git_head"/u);
  assert.match(orchestrator, /\.rhaomi-publication-work/u);
  assert.match(nextBuild, /relative\(input\.sourceRoot, input\.workspaceRoot\)/u);
  assert.match(validator, /state\/publisher\/build-workspace/u);
  assert.match(validator, /prepare_runtime_bind_ownership "\$source_root"/u);
  assert.match(validator, /restore_runtime_bind_ownership "\$source_root"/u);
  assert.equal(
    [...validator.matchAll(/restore_runtime_bind_ownership "\$source_root"/gu)].length,
    1,
  );
  assert.match(validator, /quiesce_source_writers_for_host_mutation/u);
  assert.match(validator, /verify_validation_writer_quiescence/u);
  assert.match(
    validator,
    /transition_validation_media_state "\$source_root" "\$source_project" capture/u,
  );
  assert.match(
    validator,
    /transition_validation_media_state "\$restore_root" "\$restore_project" assert-capture/u,
  );
  assert.equal(
    [...validator.matchAll(/prepare_runtime_bind_ownership "\$restore_root"/gu)].length,
    2,
  );
  assert.equal(
    [...validator.matchAll(/restore_runtime_bind_ownership "\$restore_root"/gu)].length,
    2,
  );
  assert.match(validator, /prepare_validation_compose_cli "\$source_root\/app\/docker"/u);
  assert.match(
    validator,
    /DOCKER_CONFIG=\$validation_docker_config docker compose version/u,
  );
  assert.match(validator, /validation_cli_root="\$validation_parent\/validation-cli"/u);
  assert.match(validator, /'exec docker compose "\$@"'/u);
  assert.match(validator, /command -v docker-compose/u);
  assert.match(
    validator,
    /chown -R 0:0[\s\S]*\/validation\/state\/publisher/u,
  );
  assert.match(validator, /chown -R "\$2:\$3" \/validation\/state\/deploy/u);
  assert.match(
    validator,
    /chown -R "0:\$3"[\s\S]*\/validation\/state\/locks[\s\S]*\/validation\/state\/publisher/u,
  );
  assert.match(validator, /chmod 0700 \/validation\/state\/deploy/u);
  assert.match(validator, /chmod 0770 \/validation\/state\/locks/u);
  assert.match(
    validator,
    /chmod 0750[\s\S]*\/validation\/state\/publisher[\s\S]*\/validation\/state\/publisher\/build-workspace/u,
  );
  assert.doesNotMatch(validator, /chown -R "0:\$3" \/validation\/data\/media/u);
  assert.match(
    validator,
    /chown -R "\$2:\$3"[\s\S]*\/validation\/state\/publisher/u,
  );
  assert.match(validator, /sourceCapturePermissionState/u);
  assert.match(validator, /sourceRuntimeRecoveryPermissionState/u);
  assert.match(validator, /finalHostPermissionState/u);
  assert.match(validator, /dockerImageDeletion/u);
  assert.match(control, /capture-failure/u);
  assert.match(control, /runtime-permission-failure/u);
  assert.match(control, /permissionFailureLockHold/u);
  assert.match(control, /restart-failure/u);
  assert.match(control, /contention/u);
  assert.match(control, /secretLeakCount/u);
  for (const content of [validator, control]) {
    assert.doesNotMatch(
      content,
      /docker (?:volume|image) (?:rm|prune)|docker system prune|down -v/u,
    );
  }
});

test("Hosted Validate는 3-job을 유지하며 Backend exact-head image로 D-IMP-4 evidence를 만든다", async () => {
  const workflow = await source(".github/workflows/validate.yml");
  const jobs = [...workflow.matchAll(/^  [a-z0-9-]+:\s*$/gmu)].map((match) =>
    match[0].trim(),
  );
  assert.deepEqual(jobs, ["frontend:", "backend:", "compose-smoke:"]);
  assert.match(workflow, /scripts\/validate-production-backup-control\.sh/u);
  assert.match(workflow, /scripts\/validate-production-backup\.sh/u);
  assert.match(workflow, /production-backup-evidence/u);
  assert.match(workflow, /55-application-consistent-restore-gate/u);
  assert.doesNotMatch(workflow, /packages:\s*write/u);
});
