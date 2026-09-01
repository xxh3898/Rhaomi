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

test("backup tool production profile은 same image·no network·private mounts만 사용한다", async () => {
  const [compose, overlay, dockerfile] = await Promise.all([
    source("compose.production.yaml"),
    source("compose.production.validation.yaml"),
    source("backend/Dockerfile.production"),
  ]);
  const block = compose.match(/\n  backup-tool:\n([\s\S]*?)\n  postgres:/u)?.[1];
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
  assert.match(overlay, /RHAOMI_BACKUP_RESTORE_MEDIA_ROOT/u);
  assert.match(dockerfile, /COPY scripts\/rhaomi-backup-tool\.mjs/u);
});

test("deploy backup gate가 target·evidence hash·complete manifest를 writer mutation 전에 재검증한다", async () => {
  const core = await source("ops/production/deploy-rhaomi-core.sh");
  const pullIndex = core.indexOf("pull_and_verify_release_image");
  const eligibilityIndex = core.indexOf("validate_backup_eligibility", pullIndex);
  const writerIndex = core.indexOf("writer_maintenance_active=true", eligibilityIndex);
  assert(pullIndex >= 0 && eligibilityIndex > pullIndex && writerIndex > eligibilityIndex);
  assert.match(core, /backup-eligibility\.json/u);
  assert.match(core, /openssl dgst -sha256/u);
  assert.match(core, /verify-eligibility "\$release_sha"/u);
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
  assert.match(orchestrator, /\.rhaomi-publication-work/u);
  assert.match(nextBuild, /relative\(input\.sourceRoot, input\.workspaceRoot\)/u);
  assert.match(validator, /state\/publisher\/build-workspace/u);
  assert.match(control, /capture-failure/u);
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
