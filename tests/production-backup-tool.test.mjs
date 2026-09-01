import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
  chmod,
  mkdtemp,
  mkdir,
  opendir,
  readFile,
  rm,
  stat,
  symlink,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  assertEligibilityFreshness,
  REPOSITORY_SENTINEL,
  REPOSITORY_SENTINEL_CONTENT,
  parseBackupEligibility,
  parseBackupManifest,
  run,
} from "../scripts/rhaomi-backup-tool.mjs";

const SOURCE_SHA = "a".repeat(40);
const TARGET_SHA = "b".repeat(40);
const SOURCE_DIGEST = `sha256:${"c".repeat(64)}`;

test("release eligibility와 manifest freshness가 strict 24시간 local RPO를 강제한다", () => {
  const now = Date.parse("2026-09-01T12:00:00Z");
  assert.doesNotThrow(() =>
    assertEligibilityFreshness(
      "2026-08-31T12:00:00.000001Z",
      "2026-09-01T11:59:59.999999Z",
      now,
    ),
  );
  for (const [createdAt, verifiedAt] of [
    ["2026-08-31T12:00:00Z", "2026-09-01T11:00:00Z"],
    ["2026-09-01T11:00:00Z", "2026-08-31T12:00:00Z"],
    ["2026-09-01T12:00:00.000001Z", "2026-09-01T11:00:00Z"],
    ["2026-09-01T11:00:00Z", "2026-09-01T12:00:00.000001Z"],
    ["2026-09-01T11:00:00Z", "2026-02-31T00:00:00Z"],
  ]) {
    assert.throws(
      () => assertEligibilityFreshness(createdAt, verifiedAt, now),
      /BACKUP_ELIGIBILITY_INVALID|BACKUP_CONTRACT_INVALID/u,
    );
  }
});

async function makeFixtureRemovable(directory) {
  await chmod(directory, 0o700);
  const handle = await opendir(directory);
  for await (const entry of handle) {
    if (entry.isDirectory()) {
      await makeFixtureRemovable(join(directory, entry.name));
    }
  }
}

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), "rhaomi-backup-tool-"));
  t.after(async () => {
    await makeFixtureRemovable(root);
    await rm(root, { recursive: true, force: true });
  });
  const repository = join(root, "repository");
  const media = join(root, "media");
  const state = join(root, "state");
  const restore = join(root, "restore");
  await Promise.all([
    mkdir(join(repository, "sets"), { recursive: true, mode: 0o700 }),
    mkdir(media, { mode: 0o700 }),
    mkdir(state, { mode: 0o700 }),
    mkdir(restore, { mode: 0o700 }),
  ]);
  await writeFile(join(repository, REPOSITORY_SENTINEL), REPOSITORY_SENTINEL_CONTENT, {
    mode: 0o600,
  });
  const environment = {
    RHAOMI_BACKUP_REPOSITORY_ROOT: repository,
    RHAOMI_BACKUP_MEDIA_ROOT: media,
    RHAOMI_BACKUP_DEPLOY_STATE_ROOT: state,
    RHAOMI_BACKUP_RESTORE_MEDIA_ROOT: restore,
  };
  return { root, repository, media, state, restore, environment };
}

async function completeSet(context, setId, startedAt, purpose = "scheduled") {
  await run(["begin", setId], context.environment);
  await run(["capture-media", setId], context.environment);
  const incomplete = join(context.repository, "sets", `.incomplete-${setId}`);
  await writeFile(join(incomplete, "postgres.dump"), `PGDMP-synthetic-dump-${setId}`, {
    mode: 0o600,
  });
  return run(
    ["finalize", setId, purpose, startedAt, SOURCE_SHA, SOURCE_DIGEST, "9"],
    context.environment,
  );
}

async function rewriteEligibilityCreatedAt(context, createdAt) {
  const evidencePath = join(context.state, "backup-eligibility.json");
  const evidence = JSON.parse(await readFile(evidencePath, "utf8"));
  evidence.createdAt = createdAt;
  const evidenceBytes = `${JSON.stringify(evidence, null, 2)}\n`;
  await chmod(evidencePath, 0o600);
  await writeFile(evidencePath, evidenceBytes, { mode: 0o600 });
  const evidenceSha256 = createHash("sha256").update(evidenceBytes).digest("hex");
  await writeFile(
    join(context.state, "backup-eligible.env"),
    `schemaVersion=1\nstatus=eligible\nreleaseSha=${TARGET_SHA}\nevidenceSha256=${evidenceSha256}\n`,
    { mode: 0o600 },
  );
}

async function rewriteManifestVerifiedAt(context, setId, verifiedAt) {
  const setPath = join(context.repository, "sets", setId);
  const manifestPath = join(setPath, "backup-manifest.json");
  await chmod(setPath, 0o700);
  await chmod(manifestPath, 0o600);
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  manifest.startedAt = "2000-01-01T00:00:00Z";
  manifest.completedAt = "2000-01-01T00:00:01Z";
  manifest.verifiedAt = verifiedAt;
  const manifestBytes = `${JSON.stringify(manifest, null, 2)}\n`;
  await writeFile(manifestPath, manifestBytes, { mode: 0o600 });
  await chmod(manifestPath, 0o400);
  await chmod(setPath, 0o500);

  const evidencePath = join(context.state, "backup-eligibility.json");
  const evidence = JSON.parse(await readFile(evidencePath, "utf8"));
  evidence.backupManifestSha256 = createHash("sha256").update(manifestBytes).digest("hex");
  evidence.createdAt = new Date().toISOString();
  const evidenceBytes = `${JSON.stringify(evidence, null, 2)}\n`;
  await writeFile(evidencePath, evidenceBytes, { mode: 0o600 });
  const evidenceSha256 = createHash("sha256").update(evidenceBytes).digest("hex");
  await writeFile(
    join(context.state, "backup-eligible.env"),
    `schemaVersion=1\nstatus=eligible\nreleaseSha=${TARGET_SHA}\nevidenceSha256=${evidenceSha256}\n`,
    { mode: 0o600 },
  );
}

test("incomplete에서 full-read 검증을 거친 set만 atomic complete·eligibility·restore authority가 된다", async (t) => {
  const context = await fixture(t);
  const setId = "20260831T183000Z-111111111111";
  await mkdir(join(context.media, "nested"), { mode: 0o700 });
  await writeFile(join(context.media, "nested", "master-a.png"), "media-A", {
    mode: 0o600,
  });

  await run(["begin", setId], context.environment);
  await assert.rejects(
    run(["verify", setId, "full-read"], context.environment),
    /BACKUP_SET_INVALID/u,
  );
  await run(["capture-media", setId], context.environment);
  await writeFile(
    join(context.repository, "sets", `.incomplete-${setId}`, "postgres.dump"),
    "PGDMP-database-A",
    { mode: 0o600 },
  );
  const completed = await run(
    [
      "finalize",
      setId,
      "on-demand",
      "2026-08-31T18:30:00.123456Z",
      SOURCE_SHA,
      SOURCE_DIGEST,
      "9",
    ],
    context.environment,
  );
  assert.equal(completed.status, "complete");
  assert.equal(completed.fileCount, 1);
  assert.match(completed.manifestSha256, /^[0-9a-f]{64}$/u);

  const verified = await run(["verify", setId, "full-read"], context.environment);
  assert.equal(verified.manifestSha256, completed.manifestSha256);
  const eligibility = await run(
    ["issue-eligibility", setId, TARGET_SHA],
    context.environment,
  );
  assert.equal(eligibility.status, "eligible");
  assert.deepEqual(
    await run(["verify-eligibility", TARGET_SHA], context.environment),
    eligibility,
  );

  const restored = await run(["restore-media", setId], context.environment);
  assert.equal(restored.fileCount, 1);
  assert.equal(await readFile(join(context.restore, "nested", "master-a.png"), "utf8"), "media-A");
  const evidence = JSON.parse(
    await readFile(join(context.state, "backup-eligibility.json"), "utf8"),
  );
  assert.equal(evidence.backupManifestSha256, completed.manifestSha256);
  assert.equal(evidence.targetReleaseSha, TARGET_SHA);
  assert.equal(evidence.status, "eligible");
});

test("dump/media corruption과 stale target 또는 evidence hash mismatch를 fail-closed한다", async (t) => {
  const context = await fixture(t);
  const setId = "20260830T183000Z-222222222222";
  await writeFile(join(context.media, "master.png"), "media-A", { mode: 0o600 });
  await completeSet(context, setId, "2026-08-30T18:30:00Z", "on-demand");
  await run(["issue-eligibility", setId, TARGET_SHA], context.environment);

  await assert.rejects(
    run(["verify-eligibility", "d".repeat(40)], context.environment),
    /BACKUP_ELIGIBILITY_INVALID/u,
  );
  const compatibility = join(context.state, "backup-eligible.env");
  await chmod(compatibility, 0o600);
  await writeFile(
    compatibility,
    `schemaVersion=1\nstatus=eligible\nreleaseSha=${TARGET_SHA}\nevidenceSha256=${"0".repeat(64)}\n`,
  );
  await assert.rejects(
    run(["verify-eligibility", TARGET_SHA], context.environment),
    /BACKUP_ELIGIBILITY_INVALID/u,
  );

  const mediaPath = join(context.repository, "sets", setId, "media", "master.png");
  await chmod(mediaPath, 0o600);
  await writeFile(mediaPath, "media-B");
  await assert.rejects(
    run(["verify", setId, "full-read"], context.environment),
    /BACKUP_CONTRACT_INVALID/u,
  );
});

test("같은 target SHA의 오래된 eligibility를 새 predeploy evidence로 오인하지 않는다", async (t) => {
  const context = await fixture(t);
  const setId = "20260901T120000Z-777777777777";
  await writeFile(join(context.media, "master.png"), "media-A", { mode: 0o600 });
  await completeSet(context, setId, new Date(Date.now() - 1_000).toISOString(), "on-demand");
  await run(["issue-eligibility", setId, TARGET_SHA], context.environment);

  await rewriteEligibilityCreatedAt(context, "2000-01-01T00:00:00Z");

  await assert.rejects(
    run(["verify-eligibility", TARGET_SHA], context.environment),
    /BACKUP_ELIGIBILITY_INVALID/u,
  );
});

test("fresh eligibility가 오래된 referenced manifest를 release backup으로 승격하지 않는다", async (t) => {
  const context = await fixture(t);
  const setId = "20260901T120100Z-888888888888";
  await writeFile(join(context.media, "master.png"), "media-A", { mode: 0o600 });
  await completeSet(context, setId, new Date(Date.now() - 1_000).toISOString(), "on-demand");
  await run(["issue-eligibility", setId, TARGET_SHA], context.environment);

  await rewriteManifestVerifiedAt(context, setId, "2000-01-01T00:00:02Z");

  await assert.rejects(
    run(["verify-eligibility", TARGET_SHA], context.environment),
    /BACKUP_ELIGIBILITY_INVALID/u,
  );
});

test("media symlink·special indirection과 non-canonical manifest/evidence를 거부한다", async (t) => {
  const context = await fixture(t);
  const setId = "20260829T183000Z-333333333333";
  await writeFile(join(context.root, "outside"), "private", { mode: 0o600 });
  await symlink(join(context.root, "outside"), join(context.media, "linked-master"));
  await run(["begin", setId], context.environment);
  await assert.rejects(
    run(["capture-media", setId], context.environment),
    /BACKUP_CONTRACT_INVALID/u,
  );

  assert.throws(
    () =>
      parseBackupManifest({
        schemaVersion: 1,
        backupSetId: setId,
        backupPurpose: "scheduled",
        startedAt: "2026-02-31T00:00:00Z",
        completedAt: "2026-03-01T00:00:00Z",
        verifiedAt: "2026-03-01T00:00:00Z",
        sourceReleaseSha: SOURCE_SHA,
        sourceImageDigest: SOURCE_DIGEST,
        sourceFlywayVersion: "9",
        postgresDump: { relativePath: "../postgres.dump", sha256: "a".repeat(64), sizeBytes: 1 },
        media: { fileCount: 0, totalSizeBytes: 0, treeSha256: "b".repeat(64), files: [] },
        sameHostFailureDomain: true,
      }),
    /BACKUP_CONTRACT_INVALID/u,
  );
  assert.throws(
    () =>
      parseBackupEligibility({
        schemaVersion: 1,
        targetReleaseSha: TARGET_SHA,
        backupSetId: setId,
        backupManifestSha256: "a".repeat(64),
        sourceReleaseSha: SOURCE_SHA,
        sourceImageDigest: SOURCE_DIGEST,
        sourceFlywayVersion: "9",
        createdAt: "2026-09-01T24:00:00Z",
        status: "eligible",
      }),
    /BACKUP_CONTRACT_INVALID/u,
  );
});

test("retention은 verified set 3개와 on-demand를 보호하고 incomplete가 있으면 apply를 차단한다", async (t) => {
  const context = await fixture(t);
  await writeFile(join(context.media, "master.png"), "media-A", { mode: 0o600 });
  const setIds = [];
  for (let index = 1; index <= 12; index += 1) {
    const day = String(index).padStart(2, "0");
    const setId = `202608${day}T183000Z-${String(index).padStart(12, "0")}`;
    setIds.push(setId);
    await completeSet(
      context,
      setId,
      `2026-08-${day}T18:30:00Z`,
      index === 1 ? "on-demand" : "scheduled",
    );
  }
  const incompleteId = "20260813T183000Z-999999999999";
  await run(["begin", incompleteId], context.environment);
  const blocked = await run(["retention-plan"], context.environment);
  assert.equal(blocked.applyAllowed, false);
  assert.equal(blocked.incompleteBackupSetCount, 1);
  await assert.rejects(
    run(["retention-apply"], context.environment),
    /BACKUP_RETENTION_BLOCKED/u,
  );

  await rm(join(context.repository, "sets", `.incomplete-${incompleteId}`), {
    recursive: true,
  });
  const newestMedia = join(
    context.repository,
    "sets",
    setIds.at(-1),
    "media",
    "master.png",
  );
  await chmod(newestMedia, 0o600);
  await writeFile(newestMedia, "media-B");
  await assert.rejects(
    run(["retention-plan"], context.environment),
    /BACKUP_CONTRACT_INVALID/u,
  );
  await writeFile(newestMedia, "media-A");
  await chmod(newestMedia, 0o400);
  const plan = await run(["retention-plan"], context.environment);
  assert.equal(plan.applyAllowed, true);
  assert(plan.protectedBackupSetIds.includes(setIds[0]), "on-demand set은 보호되어야 합니다.");
  assert(plan.deleteBackupSetIds.length > 0, "오래된 scheduled set이 삭제 후보여야 합니다.");
  const deletionCandidate = join(
    context.repository,
    "sets",
    plan.deleteBackupSetIds[0],
  );
  assert.equal((await stat(deletionCandidate)).mode & 0o777, 0o500);
  const applied = await run(["retention-apply"], context.environment);
  assert.equal(applied.status, "applied");
  await assert.rejects(stat(deletionCandidate), { code: "ENOENT" });
  assert.deepEqual(applied.deleteBackupSetIds, []);
  assert(applied.verifiedBackupSetCount >= 3);
});
