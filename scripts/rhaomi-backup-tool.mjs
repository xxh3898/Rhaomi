#!/usr/bin/env node

import { constants as fsConstants, createReadStream } from "node:fs";
import { createHash } from "node:crypto";
import {
  chmod,
  lstat,
  mkdir,
  open,
  opendir,
  readFile,
  realpath,
  rename,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import { basename, dirname, isAbsolute, join, normalize, relative, resolve, sep } from "node:path";
import { pathToFileURL } from "node:url";

const REPOSITORY_SENTINEL = ".rhaomi-backup-repository";
const REPOSITORY_SENTINEL_CONTENT = "rhaomi-backup-repository-v1\n";
const MANIFEST_NAME = "backup-manifest.json";
const DUMP_NAME = "postgres.dump";
const MEDIA_DIRECTORY = "media";
const ELIGIBILITY_NAME = "backup-eligibility.json";
const COMPATIBILITY_NAME = "backup-eligible.env";
const SET_ID_PATTERN = /^\d{8}T\d{6}Z-[0-9a-f]{12}$/u;
const SHA_PATTERN = /^[0-9a-f]{40}$/u;
const DIGEST_PATTERN = /^sha256:[0-9a-f]{64}$/u;
const HASH_PATTERN = /^[0-9a-f]{64}$/u;
const RELATIVE_PATH_PATTERN = /^(?!\/)(?!.*(?:^|\/)\.\.(?:\/|$))(?!.*\\)(?!.*[\u0000-\u001f\u007f]).+$/u;
const INSTANT_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T([01]\d|2[0-3]):([0-5]\d):([0-5]\d)(?:\.(\d{1,6}))?Z$/u;
const LOCAL_RPO_WINDOW_MICROSECONDS = 24n * 60n * 60n * 1_000_000n;
const MANIFEST_KEYS = [
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
];
const DUMP_KEYS = ["relativePath", "sha256", "sizeBytes"];
const MEDIA_KEYS = ["fileCount", "totalSizeBytes", "treeSha256", "files"];
const MEDIA_FILE_KEYS = ["relativePath", "sha256", "sizeBytes"];
const ELIGIBILITY_KEYS = [
  "schemaVersion",
  "targetReleaseSha",
  "backupSetId",
  "backupManifestSha256",
  "sourceReleaseSha",
  "sourceImageDigest",
  "sourceFlywayVersion",
  "createdAt",
  "status",
];

class BackupContractError extends Error {
  constructor(code) {
    super(code);
    this.name = "BackupContractError";
    this.code = code;
  }
}

function fail(code = "BACKUP_CONTRACT_INVALID") {
  throw new BackupContractError(code);
}

function exactKeys(value, keys) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) fail();
  const actual = Object.keys(value);
  if (actual.length !== keys.length || actual.some((key) => !keys.includes(key))) fail();
  return value;
}

function strictInstant(value) {
  if (typeof value !== "string") fail();
  const match = INSTANT_PATTERN.exec(value);
  if (match === null) fail();
  const parsed = new Date(value);
  if (
    !Number.isFinite(parsed.getTime()) ||
    parsed.getUTCFullYear() !== Number(match[1]) ||
    parsed.getUTCMonth() + 1 !== Number(match[2]) ||
    parsed.getUTCDate() !== Number(match[3]) ||
    parsed.getUTCHours() !== Number(match[4]) ||
    parsed.getUTCMinutes() !== Number(match[5]) ||
    parsed.getUTCSeconds() !== Number(match[6])
  ) {
    fail();
  }
  return value;
}

function instantEpochMicroseconds(value) {
  const checked = strictInstant(value);
  const match = INSTANT_PATTERN.exec(checked);
  if (match === null) fail();
  const wholeSecond = `${match[1]}-${match[2]}-${match[3]}T${match[4]}:${match[5]}:${match[6]}Z`;
  const epochMilliseconds = Date.parse(wholeSecond);
  if (!Number.isFinite(epochMilliseconds)) fail();
  const fractionalMicroseconds = BigInt((match[7] ?? "").padEnd(6, "0") || "0");
  return BigInt(epochMilliseconds) * 1_000n + fractionalMicroseconds;
}

export function assertEligibilityFreshness(
  eligibilityCreatedAt,
  manifestVerifiedAt,
  nowEpochMilliseconds = Date.now(),
) {
  if (!Number.isSafeInteger(nowEpochMilliseconds)) fail("BACKUP_ELIGIBILITY_INVALID");
  const nowMicroseconds = BigInt(nowEpochMilliseconds) * 1_000n;
  for (const instant of [eligibilityCreatedAt, manifestVerifiedAt]) {
    const age = nowMicroseconds - instantEpochMicroseconds(instant);
    if (age < 0n || age >= LOCAL_RPO_WINDOW_MICROSECONDS) {
      fail("BACKUP_ELIGIBILITY_INVALID");
    }
  }
}

function safeInteger(value) {
  if (!Number.isSafeInteger(value) || value < 0) fail();
  return value;
}

function matchedString(value, pattern) {
  if (typeof value !== "string" || !pattern.test(value)) fail();
  return value;
}

function relativePath(value) {
  const checked = matchedString(value, RELATIVE_PATH_PATTERN);
  if (normalize(checked).split(sep).join("/") !== checked || checked === ".") fail();
  return checked;
}

function parseMediaFile(value) {
  const input = exactKeys(value, MEDIA_FILE_KEYS);
  return {
    relativePath: relativePath(input.relativePath),
    sha256: matchedString(input.sha256, HASH_PATTERN),
    sizeBytes: safeInteger(input.sizeBytes),
  };
}

export function parseBackupManifest(value) {
  const input = exactKeys(value, MANIFEST_KEYS);
  if (input.schemaVersion !== 1 || input.sameHostFailureDomain !== true) fail();
  const dump = exactKeys(input.postgresDump, DUMP_KEYS);
  const media = exactKeys(input.media, MEDIA_KEYS);
  if (!Array.isArray(media.files)) fail();
  const files = media.files.map(parseMediaFile);
  for (let index = 1; index < files.length; index += 1) {
    if (files[index - 1].relativePath >= files[index].relativePath) fail();
  }
  const fileCount = safeInteger(media.fileCount);
  const totalSizeBytes = safeInteger(media.totalSizeBytes);
  if (
    fileCount !== files.length ||
    totalSizeBytes !== files.reduce((total, file) => total + file.sizeBytes, 0)
  ) {
    fail();
  }
  const startedAt = strictInstant(input.startedAt);
  const completedAt = strictInstant(input.completedAt);
  const verifiedAt = strictInstant(input.verifiedAt);
  if (
    Date.parse(completedAt) < Date.parse(startedAt) ||
    Date.parse(verifiedAt) < Date.parse(completedAt)
  ) {
    fail();
  }
  return {
    schemaVersion: 1,
    backupSetId: matchedString(input.backupSetId, SET_ID_PATTERN),
    backupPurpose:
      input.backupPurpose === "scheduled" || input.backupPurpose === "on-demand"
        ? input.backupPurpose
        : fail(),
    startedAt,
    completedAt,
    verifiedAt,
    sourceReleaseSha: matchedString(input.sourceReleaseSha, SHA_PATTERN),
    sourceImageDigest: matchedString(input.sourceImageDigest, DIGEST_PATTERN),
    sourceFlywayVersion:
      input.sourceFlywayVersion === "9" ? input.sourceFlywayVersion : fail(),
    postgresDump: {
      relativePath:
        dump.relativePath === DUMP_NAME ? dump.relativePath : fail(),
      sha256: matchedString(dump.sha256, HASH_PATTERN),
      sizeBytes: safeInteger(dump.sizeBytes),
    },
    media: {
      fileCount,
      totalSizeBytes,
      treeSha256: matchedString(media.treeSha256, HASH_PATTERN),
      files,
    },
    sameHostFailureDomain: true,
  };
}

export function parseBackupEligibility(value) {
  const input = exactKeys(value, ELIGIBILITY_KEYS);
  if (input.schemaVersion !== 1 || input.status !== "eligible") fail("BACKUP_ELIGIBILITY_INVALID");
  return {
    schemaVersion: 1,
    targetReleaseSha: matchedString(input.targetReleaseSha, SHA_PATTERN),
    backupSetId: matchedString(input.backupSetId, SET_ID_PATTERN),
    backupManifestSha256: matchedString(input.backupManifestSha256, HASH_PATTERN),
    sourceReleaseSha: matchedString(input.sourceReleaseSha, SHA_PATTERN),
    sourceImageDigest: matchedString(input.sourceImageDigest, DIGEST_PATTERN),
    sourceFlywayVersion:
      input.sourceFlywayVersion === "9" ? input.sourceFlywayVersion : fail(),
    createdAt: strictInstant(input.createdAt),
    status: "eligible",
  };
}

function nowInstant() {
  return new Date().toISOString();
}

function canonicalJson(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

async function sha256File(path) {
  const digest = createHash("sha256");
  const stream = createReadStream(path);
  for await (const chunk of stream) digest.update(chunk);
  return digest.digest("hex");
}

async function privateCanonicalDirectory(path) {
  if (!isAbsolute(path) || normalize(path) !== path || path === dirname(path)) fail("BACKUP_CONFIG_INVALID");
  const [physical, details] = await Promise.all([
    realpath(path).catch(() => fail("BACKUP_CONFIG_INVALID")),
    lstat(path).catch(() => fail("BACKUP_CONFIG_INVALID")),
  ]);
  if (physical !== path || !details.isDirectory() || details.isSymbolicLink()) {
    fail("BACKUP_CONFIG_INVALID");
  }
  if ((details.mode & 0o077) !== 0) fail("BACKUP_CONFIG_INVALID");
  return path;
}

async function repositoryRoot(environment = process.env) {
  const root = await privateCanonicalDirectory(
    environment.RHAOMI_BACKUP_REPOSITORY_ROOT ?? "",
  );
  const sentinelPath = join(root, REPOSITORY_SENTINEL);
  const sentinel = await lstat(sentinelPath).catch(() => fail("BACKUP_REPOSITORY_INVALID"));
  if (!sentinel.isFile() || sentinel.isSymbolicLink() || (sentinel.mode & 0o077) !== 0) {
    fail("BACKUP_REPOSITORY_INVALID");
  }
  if ((await readFile(sentinelPath, "utf8")) !== REPOSITORY_SENTINEL_CONTENT) {
    fail("BACKUP_REPOSITORY_INVALID");
  }
  const sets = join(root, "sets");
  const setsStat = await lstat(sets).catch(() => fail("BACKUP_REPOSITORY_INVALID"));
  if (!setsStat.isDirectory() || setsStat.isSymbolicLink() || (setsStat.mode & 0o077) !== 0) {
    fail("BACKUP_REPOSITORY_INVALID");
  }
  return root;
}

function incompleteSetPath(root, setId) {
  matchedString(setId, SET_ID_PATTERN);
  return join(root, "sets", `.incomplete-${setId}`);
}

function completeSetPath(root, setId) {
  matchedString(setId, SET_ID_PATTERN);
  return join(root, "sets", setId);
}

async function requireDirectory(path, code = "BACKUP_SET_INVALID") {
  const details = await lstat(path).catch(() => fail(code));
  if (!details.isDirectory() || details.isSymbolicLink()) fail(code);
  return details;
}

async function requireRegularFile(path, code = "BACKUP_SET_INVALID") {
  const details = await lstat(path).catch(() => fail(code));
  if (!details.isFile() || details.isSymbolicLink()) fail(code);
  return details;
}

async function listRegularFiles(root) {
  await requireDirectory(root);
  const files = [];
  async function visit(directory) {
    const handle = await opendir(directory).catch(() => fail());
    for await (const entry of handle) {
      const absolutePath = join(directory, entry.name);
      const details = await lstat(absolutePath).catch(() => fail());
      if (details.isSymbolicLink() || (!details.isDirectory() && !details.isFile())) fail();
      if (details.isDirectory()) {
        await visit(absolutePath);
      } else {
        const path = relative(root, absolutePath).split(sep).join("/");
        files.push({ absolutePath, relativePath: relativePath(path), sizeBytes: safeInteger(details.size) });
      }
    }
  }
  await visit(root);
  return files.sort((left, right) =>
    Buffer.from(left.relativePath).compare(Buffer.from(right.relativePath)),
  );
}

async function inventory(root, includeHashes) {
  const entries = [];
  for (const file of await listRegularFiles(root)) {
    entries.push({
      relativePath: file.relativePath,
      sha256: includeHashes ? await sha256File(file.absolutePath) : undefined,
      sizeBytes: file.sizeBytes,
    });
  }
  return entries;
}

function treeHash(files) {
  const digest = createHash("sha256");
  for (const file of files) {
    digest.update(file.relativePath, "utf8");
    digest.update("\0");
    digest.update(String(file.sizeBytes), "ascii");
    digest.update("\0");
    digest.update(file.sha256, "ascii");
    digest.update("\n");
  }
  return digest.digest("hex");
}

async function copyRegularTree(source, target, targetMustNotExist = true) {
  await privateCanonicalDirectory(source);
  const existingTarget = await lstat(target).catch(() => null);
  if (existingTarget === null) {
    await mkdir(target, { mode: 0o700 });
  } else {
    if (targetMustNotExist) fail("BACKUP_MEDIA_INVALID");
    await requireDirectory(target, "BACKUP_RESTORE_INVALID");
  }
  for (const file of await listRegularFiles(source)) {
    const destination = join(target, file.relativePath);
    await mkdir(dirname(destination), { recursive: true, mode: 0o700 });
    const sourceHandle = await open(
      file.absolutePath,
      fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW,
    ).catch(() => fail("BACKUP_MEDIA_INVALID"));
    try {
      const sourceStat = await sourceHandle.stat();
      if (!sourceStat.isFile() || sourceStat.size !== file.sizeBytes) fail("BACKUP_MEDIA_INVALID");
      const destinationHandle = await open(destination, "wx", 0o600).catch(() =>
        fail("BACKUP_MEDIA_INVALID"),
      );
      try {
        await destinationHandle.writeFile(await sourceHandle.readFile());
      } finally {
        await destinationHandle.close();
      }
    } finally {
      await sourceHandle.close();
    }
  }
}

async function beginSet(setId, environment) {
  const root = await repositoryRoot(environment);
  const incomplete = incompleteSetPath(root, setId);
  const complete = completeSetPath(root, setId);
  if (
    (await lstat(incomplete).catch(() => null)) !== null ||
    (await lstat(complete).catch(() => null)) !== null
  ) {
    fail("BACKUP_SET_EXISTS");
  }
  await mkdir(incomplete, { mode: 0o700 });
  return { status: "incomplete", backupSetId: setId };
}

async function captureMedia(setId, environment) {
  const root = await repositoryRoot(environment);
  const source = environment.RHAOMI_BACKUP_MEDIA_ROOT ?? "";
  const incomplete = incompleteSetPath(root, setId);
  await requireDirectory(incomplete);
  const target = join(incomplete, MEDIA_DIRECTORY);
  if ((await lstat(target).catch(() => null)) !== null) fail("BACKUP_MEDIA_INVALID");
  await copyRegularTree(source, target);
  const files = await inventory(target, true);
  return {
    status: "captured",
    backupSetId: setId,
    fileCount: files.length,
    totalSizeBytes: files.reduce((total, file) => total + file.sizeBytes, 0),
    treeSha256: treeHash(files),
  };
}

async function verifySetDirectory(setRoot, mode = "full-read") {
  if (mode !== "structural" && mode !== "full-read") fail("BACKUP_INPUT_INVALID");
  await requireDirectory(setRoot);
  const manifestPath = join(setRoot, MANIFEST_NAME);
  await requireRegularFile(manifestPath);
  const manifestBytes = await readFile(manifestPath);
  let parsed;
  try {
    parsed = parseBackupManifest(JSON.parse(manifestBytes.toString("utf8")));
  } catch (error) {
    if (error instanceof BackupContractError) throw error;
    fail();
  }
  if (
    basename(setRoot) !== parsed.backupSetId &&
    basename(setRoot) !== `.incomplete-${parsed.backupSetId}`
  ) {
    fail();
  }
  const dumpPath = join(setRoot, parsed.postgresDump.relativePath);
  const dumpStat = await requireRegularFile(dumpPath);
  if (dumpStat.size !== parsed.postgresDump.sizeBytes || dumpStat.size === 0) fail();
  const dumpHeaderHandle = await open(
    dumpPath,
    fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW,
  ).catch(() => fail());
  let dumpHeader;
  try {
    dumpHeader = Buffer.alloc(5);
    const { bytesRead } = await dumpHeaderHandle.read(dumpHeader, 0, 5, 0);
    if (bytesRead !== 5 || dumpHeader.toString("ascii") !== "PGDMP") fail();
  } finally {
    await dumpHeaderHandle.close();
  }
  const mediaRoot = join(setRoot, MEDIA_DIRECTORY);
  const actualFiles = await inventory(mediaRoot, mode === "full-read");
  if (actualFiles.length !== parsed.media.files.length) fail();
  for (let index = 0; index < actualFiles.length; index += 1) {
    const actual = actualFiles[index];
    const expected = parsed.media.files[index];
    if (
      actual.relativePath !== expected.relativePath ||
      actual.sizeBytes !== expected.sizeBytes ||
      (mode === "full-read" && actual.sha256 !== expected.sha256)
    ) {
      fail();
    }
  }
  if (mode === "full-read") {
    if ((await sha256File(dumpPath)) !== parsed.postgresDump.sha256) fail();
    if (treeHash(actualFiles) !== parsed.media.treeSha256) fail();
  }
  return {
    manifest: parsed,
    manifestSha256: sha256(manifestBytes),
  };
}

async function makeReadOnly(root) {
  const files = await listRegularFiles(root);
  for (const file of files) await chmod(file.absolutePath, 0o400);
  const directories = [];
  async function visit(directory) {
    const handle = await opendir(directory);
    for await (const entry of handle) {
      if (entry.isDirectory()) {
        const child = join(directory, entry.name);
        directories.push(child);
        await visit(child);
      }
    }
  }
  await visit(root);
  directories.sort((left, right) => right.length - left.length);
  for (const directory of directories) await chmod(directory, 0o500);
  await chmod(root, 0o500);
}

async function makeRemovable(root) {
  const directories = [root];
  async function visit(directory) {
    const handle = await opendir(directory).catch(() => fail("BACKUP_RETENTION_FAILED"));
    for await (const entry of handle) {
      const child = join(directory, entry.name);
      const details = await lstat(child).catch(() => fail("BACKUP_RETENTION_FAILED"));
      if (details.isSymbolicLink() || (!details.isDirectory() && !details.isFile())) {
        fail("BACKUP_RETENTION_FAILED");
      }
      if (details.isDirectory()) {
        directories.push(child);
        await visit(child);
      }
    }
  }
  await visit(root);
  directories.sort((left, right) => left.length - right.length);
  for (const directory of directories) await chmod(directory, 0o700);
}

async function finalizeSet(
  setId,
  purpose,
  startedAt,
  sourceReleaseSha,
  sourceImageDigest,
  sourceFlywayVersion,
  environment,
) {
  const root = await repositoryRoot(environment);
  const incomplete = incompleteSetPath(root, setId);
  const complete = completeSetPath(root, setId);
  await requireDirectory(incomplete);
  if ((await lstat(complete).catch(() => null)) !== null) fail("BACKUP_SET_EXISTS");
  const dumpPath = join(incomplete, DUMP_NAME);
  const dumpStat = await requireRegularFile(dumpPath);
  if (dumpStat.size < 5) fail("BACKUP_DUMP_INVALID");
  await chmod(dumpPath, 0o600);
  const mediaFiles = await inventory(join(incomplete, MEDIA_DIRECTORY), true);
  const completedAt = nowInstant();
  const manifest = parseBackupManifest({
    schemaVersion: 1,
    backupSetId: setId,
    backupPurpose: purpose,
    startedAt,
    completedAt,
    verifiedAt: completedAt,
    sourceReleaseSha,
    sourceImageDigest,
    sourceFlywayVersion,
    postgresDump: {
      relativePath: DUMP_NAME,
      sha256: await sha256File(dumpPath),
      sizeBytes: safeInteger(dumpStat.size),
    },
    media: {
      fileCount: mediaFiles.length,
      totalSizeBytes: mediaFiles.reduce((total, file) => total + file.sizeBytes, 0),
      treeSha256: treeHash(mediaFiles),
      files: mediaFiles,
    },
    sameHostFailureDomain: true,
  });
  await writeFile(join(incomplete, MANIFEST_NAME), canonicalJson(manifest), {
    encoding: "utf8",
    flag: "wx",
    mode: 0o600,
  });
  const stagingVerification = await verifySetDirectory(incomplete, "full-read");
  if (stagingVerification.manifest.backupSetId !== setId) fail();
  await makeReadOnly(incomplete);
  const incompleteStat = await stat(incomplete);
  const setsStat = await stat(dirname(incomplete));
  if (incompleteStat.dev !== setsStat.dev) fail("BACKUP_PROMOTION_INVALID");
  await rename(incomplete, complete).catch(() => fail("BACKUP_PROMOTION_INVALID"));
  const completeVerification = await verifySetDirectory(complete, "full-read");
  return {
    status: "complete",
    backupSetId: setId,
    manifestSha256: completeVerification.manifestSha256,
    fileCount: completeVerification.manifest.media.fileCount,
    totalSizeBytes: completeVerification.manifest.media.totalSizeBytes,
  };
}

async function verifySet(setId, mode, environment) {
  const root = await repositoryRoot(environment);
  const result = await verifySetDirectory(completeSetPath(root, setId), mode);
  return {
    status: "verified",
    mode,
    backupSetId: setId,
    manifestSha256: result.manifestSha256,
  };
}

async function deployStateRoot(environment) {
  return privateCanonicalDirectory(environment.RHAOMI_BACKUP_DEPLOY_STATE_ROOT ?? "");
}

async function atomicPrivateWrite(path, bytes) {
  const temporary = join(dirname(path), `.${basename(path)}.tmp-${process.pid}`);
  if ((await lstat(temporary).catch(() => null)) !== null) fail("BACKUP_EVIDENCE_INVALID");
  await writeFile(temporary, bytes, { encoding: "utf8", flag: "wx", mode: 0o600 });
  await chmod(temporary, 0o600);
  await rename(temporary, path).catch(() => fail("BACKUP_EVIDENCE_INVALID"));
}

async function issueEligibility(setId, targetReleaseSha, environment) {
  matchedString(targetReleaseSha, SHA_PATTERN);
  const root = await repositoryRoot(environment);
  const stateRoot = await deployStateRoot(environment);
  const verification = await verifySetDirectory(completeSetPath(root, setId), "full-read");
  const manifest = verification.manifest;
  const evidence = parseBackupEligibility({
    schemaVersion: 1,
    targetReleaseSha,
    backupSetId: setId,
    backupManifestSha256: verification.manifestSha256,
    sourceReleaseSha: manifest.sourceReleaseSha,
    sourceImageDigest: manifest.sourceImageDigest,
    sourceFlywayVersion: manifest.sourceFlywayVersion,
    createdAt: nowInstant(),
    status: "eligible",
  });
  const evidenceBytes = canonicalJson(evidence);
  const evidenceSha256 = sha256(evidenceBytes);
  await atomicPrivateWrite(join(stateRoot, ELIGIBILITY_NAME), evidenceBytes);
  await atomicPrivateWrite(
    join(stateRoot, COMPATIBILITY_NAME),
    `schemaVersion=1\nstatus=eligible\nreleaseSha=${targetReleaseSha}\nevidenceSha256=${evidenceSha256}\n`,
  );
  return { status: "eligible", targetReleaseSha, backupSetId: setId, evidenceSha256 };
}

async function verifyEligibility(targetReleaseSha, environment) {
  matchedString(targetReleaseSha, SHA_PATTERN);
  const root = await repositoryRoot(environment);
  const stateRoot = await deployStateRoot(environment);
  const evidencePath = join(stateRoot, ELIGIBILITY_NAME);
  const compatibilityPath = join(stateRoot, COMPATIBILITY_NAME);
  await Promise.all([
    requireRegularFile(evidencePath, "BACKUP_ELIGIBILITY_INVALID"),
    requireRegularFile(compatibilityPath, "BACKUP_ELIGIBILITY_INVALID"),
  ]);
  const [evidenceBytes, compatibility] = await Promise.all([
    readFile(evidencePath),
    readFile(compatibilityPath, "utf8"),
  ]);
  let evidence;
  try {
    evidence = parseBackupEligibility(JSON.parse(evidenceBytes.toString("utf8")));
  } catch (error) {
    if (error instanceof BackupContractError) throw error;
    fail("BACKUP_ELIGIBILITY_INVALID");
  }
  const expectedCompatibility =
    `schemaVersion=1\nstatus=eligible\nreleaseSha=${targetReleaseSha}\n` +
    `evidenceSha256=${sha256(evidenceBytes)}\n`;
  if (compatibility !== expectedCompatibility || evidence.targetReleaseSha !== targetReleaseSha) {
    fail("BACKUP_ELIGIBILITY_INVALID");
  }
  const verification = await verifySetDirectory(
    completeSetPath(root, evidence.backupSetId),
    "full-read",
  );
  if (
    verification.manifestSha256 !== evidence.backupManifestSha256 ||
    verification.manifest.sourceReleaseSha !== evidence.sourceReleaseSha ||
    verification.manifest.sourceImageDigest !== evidence.sourceImageDigest ||
    verification.manifest.sourceFlywayVersion !== evidence.sourceFlywayVersion
  ) {
    fail("BACKUP_ELIGIBILITY_INVALID");
  }
  assertEligibilityFreshness(evidence.createdAt, verification.manifest.verifiedAt);
  return {
    status: "eligible",
    targetReleaseSha,
    backupSetId: evidence.backupSetId,
    evidenceSha256: sha256(evidenceBytes),
  };
}

async function restoreMedia(setId, environment) {
  const root = await repositoryRoot(environment);
  const target = await privateCanonicalDirectory(
    environment.RHAOMI_BACKUP_RESTORE_MEDIA_ROOT ?? "",
  );
  const existing = await opendir(target).catch(() => fail("BACKUP_RESTORE_INVALID"));
  try {
    if ((await existing.read()) !== null) fail("BACKUP_RESTORE_INVALID");
  } finally {
    await existing.close();
  }
  const verification = await verifySetDirectory(completeSetPath(root, setId), "full-read");
  await copyRegularTree(
    join(completeSetPath(root, setId), MEDIA_DIRECTORY),
    target,
    false,
  );
  const restored = await inventory(target, true);
  if (treeHash(restored) !== verification.manifest.media.treeSha256) {
    fail("BACKUP_RESTORE_INVALID");
  }
  return {
    status: "restored",
    backupSetId: setId,
    fileCount: restored.length,
    treeSha256: treeHash(restored),
  };
}

function kstParts(instant) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date(instant));
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return { year: Number(values.year), month: Number(values.month), day: Number(values.day) };
}

function isoWeekKey(instant) {
  const { year, month, day } = kstParts(instant);
  const value = new Date(Date.UTC(year, month - 1, day));
  const weekday = value.getUTCDay() || 7;
  value.setUTCDate(value.getUTCDate() + 4 - weekday);
  const weekYear = value.getUTCFullYear();
  const yearStart = new Date(Date.UTC(weekYear, 0, 1));
  const week = Math.ceil(((value - yearStart) / 86_400_000 + 1) / 7);
  return `${weekYear}-W${String(week).padStart(2, "0")}`;
}

function retentionSelection(manifests) {
  const sorted = [...manifests].sort((left, right) =>
    right.manifest.startedAt.localeCompare(left.manifest.startedAt),
  );
  const protectedIds = new Set(
    sorted
      .filter(({ manifest }) => manifest.backupPurpose === "on-demand")
      .map(({ manifest }) => manifest.backupSetId),
  );
  for (const entry of sorted.slice(0, 3)) protectedIds.add(entry.manifest.backupSetId);
  const bucketRules = [
    { count: 7, key: (instant) => {
      const value = kstParts(instant);
      return `${value.year}-${String(value.month).padStart(2, "0")}-${String(value.day).padStart(2, "0")}`;
    } },
    { count: 4, key: isoWeekKey },
    { count: 6, key: (instant) => {
      const value = kstParts(instant);
      return `${value.year}-${String(value.month).padStart(2, "0")}`;
    } },
  ];
  for (const rule of bucketRules) {
    const buckets = new Set();
    for (const entry of sorted) {
      const key = rule.key(entry.manifest.startedAt);
      if (buckets.has(key) || buckets.size >= rule.count) continue;
      buckets.add(key);
      protectedIds.add(entry.manifest.backupSetId);
    }
  }
  return {
    protectedBackupSetIds: [...protectedIds].sort(),
    deleteBackupSetIds: sorted
      .map(({ manifest }) => manifest.backupSetId)
      .filter((setId) => !protectedIds.has(setId))
      .sort(),
  };
}

async function retentionPlan(environment) {
  const root = await repositoryRoot(environment);
  const setsRoot = join(root, "sets");
  const handle = await opendir(setsRoot).catch(() => fail());
  const completeIds = [];
  let incompleteCount = 0;
  for await (const entry of handle) {
    if (entry.name.startsWith(".incomplete-")) {
      incompleteCount += 1;
      continue;
    }
    if (!entry.isDirectory() || !SET_ID_PATTERN.test(entry.name)) fail("BACKUP_REPOSITORY_INVALID");
    completeIds.push(entry.name);
  }
  const manifests = [];
  for (const setId of completeIds.sort()) {
    const verification = await verifySetDirectory(completeSetPath(root, setId), "full-read");
    manifests.push(verification);
  }
  const selection = retentionSelection(manifests);
  return {
    status: "planned",
    applyAllowed: manifests.length >= 3 && incompleteCount === 0,
    verifiedBackupSetCount: manifests.length,
    incompleteBackupSetCount: incompleteCount,
    ...selection,
  };
}

async function retentionApply(environment) {
  const root = await repositoryRoot(environment);
  const plan = await retentionPlan(environment);
  if (!plan.applyAllowed) fail("BACKUP_RETENTION_BLOCKED");
  for (const setId of plan.deleteBackupSetIds) {
    const target = completeSetPath(root, setId);
    await requireDirectory(target);
    await verifySetDirectory(target, "full-read");
    await makeRemovable(target);
    await rm(target, { recursive: true, force: false });
  }
  const postcheck = await retentionPlan(environment);
  if (postcheck.deleteBackupSetIds.length !== 0) fail("BACKUP_RETENTION_FAILED");
  return { ...postcheck, status: "applied", deletedBackupSetIds: plan.deleteBackupSetIds };
}

async function run(argv = process.argv.slice(2), environment = process.env) {
  const [command, ...args] = argv;
  switch (command) {
    case "begin":
      if (args.length !== 1) fail("BACKUP_INPUT_INVALID");
      return beginSet(args[0], environment);
    case "capture-media":
      if (args.length !== 1) fail("BACKUP_INPUT_INVALID");
      return captureMedia(args[0], environment);
    case "finalize":
      if (args.length !== 6) fail("BACKUP_INPUT_INVALID");
      return finalizeSet(...args, environment);
    case "verify":
      if (args.length !== 2) fail("BACKUP_INPUT_INVALID");
      return verifySet(args[0], args[1], environment);
    case "issue-eligibility":
      if (args.length !== 2) fail("BACKUP_INPUT_INVALID");
      return issueEligibility(args[0], args[1], environment);
    case "verify-eligibility":
      if (args.length !== 1) fail("BACKUP_INPUT_INVALID");
      return verifyEligibility(args[0], environment);
    case "restore-media":
      if (args.length !== 1) fail("BACKUP_INPUT_INVALID");
      return restoreMedia(args[0], environment);
    case "retention-plan":
      if (args.length !== 0) fail("BACKUP_INPUT_INVALID");
      return retentionPlan(environment);
    case "retention-apply":
      if (args.length !== 0) fail("BACKUP_INPUT_INVALID");
      return retentionApply(environment);
    default:
      fail("BACKUP_INPUT_INVALID");
  }
}

const invokedDirectly =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (invokedDirectly) {
  run()
    .then((result) => process.stdout.write(`${JSON.stringify(result)}\n`))
    .catch((error) => {
      const code = error instanceof BackupContractError ? error.code : "BACKUP_INTERNAL_ERROR";
      process.stderr.write(`${code}\n`);
      process.exitCode = 1;
    });
}

export { REPOSITORY_SENTINEL, REPOSITORY_SENTINEL_CONTENT, run };
