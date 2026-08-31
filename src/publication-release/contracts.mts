import { isAbsolute, normalize, parse, resolve } from "node:path";

import { parsePublishGeneration } from "../build-orchestration/config.mts";
import { releaseFail } from "./errors.mts";

const SHA_PATTERN = /^[0-9a-f]{40}$/u;
const DIGEST_PATTERN = /^sha256:[0-9a-f]{64}$/u;
const IMAGE_TAG_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._/@:-]{0,254}$/u;
const FLYWAY_PATTERN = /^(?:0|[1-9][0-9]{0,8})$/u;
const RELEASE_ID_PATTERN = /^[a-z0-9][a-z0-9.-]{0,127}$/u;
const INSTANT_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/u;
const MANIFEST_KEYS = [
  "schemaVersion",
  "releaseId",
  "contentRevision",
  "publishGeneration",
  "generatedAt",
  "codeSha",
  "codeImageTag",
  "codeImageDigest",
  "flywayVersion",
  "sbomReference",
  "siteSha256",
] as const;

export type PublicationReleaseEnvironment = Readonly<
  Record<string, string | undefined>
>;

export type PublicationReleaseConfig = Readonly<{
  sourceRoot: string;
  workRoot: string;
  releaseRoot: string;
  currentLink: string;
  previousLink: string;
  publicSiteUrl: string;
  codeSha: string;
  codeImageTag: string;
  codeImageDigest: string;
  flywayVersion: string;
  sbomReference: string;
  buildTimeoutMs: number;
  releaseRetention: number;
}>;

export type ReleaseManifestV1 = Readonly<{
  schemaVersion: 1;
  releaseId: string;
  contentRevision: string;
  publishGeneration: string;
  generatedAt: string;
  codeSha: string;
  codeImageTag: string;
  codeImageDigest: string;
  flywayVersion: string;
  sbomReference: string;
  siteSha256: string;
}>;

function required(environment: PublicationReleaseEnvironment, key: string): string {
  const value = environment[key];
  if (value === undefined || value.length === 0 || value !== value.trim()) {
    releaseFail("RELEASE_CONFIG_INVALID");
  }
  return value;
}

function absolutePath(
  environment: PublicationReleaseEnvironment,
  key: string,
): string {
  const value = required(environment, key);
  const normalized = normalize(value);
  if (
    !isAbsolute(value) ||
    normalized !== value ||
    normalized === parse(normalized).root
  ) {
    releaseFail("RELEASE_CONFIG_INVALID");
  }
  return normalized;
}

function safeSiteUrl(value: string): string {
  try {
    const parsed = new URL(value);
    if (
      parsed.protocol !== "https:" ||
      parsed.username.length > 0 ||
      parsed.password.length > 0 ||
      parsed.pathname !== "/" ||
      parsed.search.length > 0 ||
      parsed.hash.length > 0
    ) {
      releaseFail("RELEASE_CONFIG_INVALID");
    }
    return parsed.toString();
  } catch {
    releaseFail("RELEASE_CONFIG_INVALID");
  }
}

function matches(value: string, pattern: RegExp): string {
  if (!pattern.test(value)) releaseFail("RELEASE_CONFIG_INVALID");
  return value;
}

export function loadPublicationReleaseConfig(
  environment: PublicationReleaseEnvironment,
): PublicationReleaseConfig {
  const sourceRoot = absolutePath(environment, "RHAOMI_PUBLISHER_SOURCE_ROOT");
  const workRoot = absolutePath(environment, "RHAOMI_PUBLISHER_WORK_ROOT");
  const releaseRoot = absolutePath(environment, "RHAOMI_PUBLIC_RELEASE_ROOT");
  const currentLink = absolutePath(environment, "RHAOMI_PUBLIC_CURRENT_LINK");
  const previousLink = absolutePath(environment, "RHAOMI_PUBLIC_PREVIOUS_LINK");
  const releaseParent = resolve(releaseRoot, "..");
  if (
    sourceRoot === workRoot ||
    sourceRoot === releaseRoot ||
    workRoot === releaseRoot ||
    currentLink === previousLink ||
    resolve(currentLink, "..") !== releaseParent ||
    resolve(previousLink, "..") !== releaseParent ||
    currentLink.startsWith(`${releaseRoot}/`) ||
    previousLink.startsWith(`${releaseRoot}/`)
  ) {
    releaseFail("RELEASE_CONFIG_INVALID");
  }
  const buildTimeoutValue = environment.RHAOMI_PUBLISHER_BUILD_TIMEOUT_MS;
  const buildTimeoutMs =
    buildTimeoutValue === undefined ? 600_000 : Number(buildTimeoutValue);
  if (
    !Number.isSafeInteger(buildTimeoutMs) ||
    buildTimeoutMs < 1_000 ||
    buildTimeoutMs > 3_600_000
  ) {
    releaseFail("RELEASE_CONFIG_INVALID");
  }
  const retentionValue = environment.RHAOMI_RELEASE_RETENTION ?? "5";
  if (!/^[1-9][0-9]{0,2}$/u.test(retentionValue)) {
    releaseFail("RELEASE_CONFIG_INVALID");
  }
  const releaseRetention = Number(retentionValue);
  if (releaseRetention > 100) releaseFail("RELEASE_CONFIG_INVALID");
  return {
    sourceRoot,
    workRoot,
    releaseRoot,
    currentLink,
    previousLink,
    publicSiteUrl: safeSiteUrl(required(environment, "PUBLIC_SITE_URL")),
    codeSha: matches(required(environment, "RHAOMI_CODE_SHA"), SHA_PATTERN),
    codeImageTag: matches(
      required(environment, "RHAOMI_CODE_IMAGE_TAG"),
      IMAGE_TAG_PATTERN,
    ),
    codeImageDigest: matches(
      required(environment, "RHAOMI_CODE_IMAGE_DIGEST"),
      DIGEST_PATTERN,
    ),
    flywayVersion: matches(
      required(environment, "RHAOMI_FLYWAY_VERSION"),
      FLYWAY_PATTERN,
    ),
    sbomReference: matches(
      required(environment, "RHAOMI_SBOM_REFERENCE"),
      DIGEST_PATTERN,
    ),
    buildTimeoutMs,
    releaseRetention,
  };
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    releaseFail("RELEASE_CURRENT_INVALID");
  }
  return value as Record<string, unknown>;
}

function stringField(
  input: Record<string, unknown>,
  key: string,
  pattern: RegExp,
): string {
  const value = input[key];
  if (typeof value !== "string" || !pattern.test(value)) {
    releaseFail("RELEASE_CURRENT_INVALID");
  }
  return value;
}

export function parseReleaseManifest(value: unknown): ReleaseManifestV1 {
  const input = record(value);
  const keys = Object.keys(input);
  if (
    keys.length !== MANIFEST_KEYS.length ||
    keys.some((key) => !MANIFEST_KEYS.includes(key as (typeof MANIFEST_KEYS)[number])) ||
    input.schemaVersion !== 1
  ) {
    releaseFail("RELEASE_CURRENT_INVALID");
  }
  const contentRevision = stringField(
    input,
    "contentRevision",
    /^(?:0|[1-9][0-9]*)$/u,
  );
  const publishGeneration = stringField(
    input,
    "publishGeneration",
    /^[1-9][0-9]*$/u,
  );
  try {
    parsePublishGeneration(publishGeneration);
    parsePublishGeneration(contentRevision === "0" ? "1" : contentRevision);
  } catch {
    releaseFail("RELEASE_CURRENT_INVALID");
  }
  const generatedAt = stringField(input, "generatedAt", INSTANT_PATTERN);
  if (!Number.isFinite(new Date(generatedAt).getTime())) {
    releaseFail("RELEASE_CURRENT_INVALID");
  }
  return {
    schemaVersion: 1,
    releaseId: stringField(input, "releaseId", RELEASE_ID_PATTERN),
    contentRevision,
    publishGeneration,
    generatedAt,
    codeSha: stringField(input, "codeSha", SHA_PATTERN),
    codeImageTag: stringField(input, "codeImageTag", IMAGE_TAG_PATTERN),
    codeImageDigest: stringField(input, "codeImageDigest", DIGEST_PATTERN),
    flywayVersion: stringField(input, "flywayVersion", FLYWAY_PATTERN),
    sbomReference: stringField(input, "sbomReference", DIGEST_PATTERN),
    siteSha256: stringField(input, "siteSha256", /^[0-9a-f]{64}$/u),
  };
}

export function releaseIdFor(
  contentRevision: string,
  publishGeneration: string,
  codeSha: string,
): string {
  const value = `g-${publishGeneration}.r-${contentRevision}.c-${codeSha.slice(0, 12)}`;
  if (!RELEASE_ID_PATTERN.test(value)) releaseFail("RELEASE_INPUT_INVALID");
  return value;
}

export function compareGenerations(left: string, right: string): number {
  let leftValue: bigint;
  let rightValue: bigint;
  try {
    leftValue = BigInt(left);
    rightValue = BigInt(right);
  } catch {
    releaseFail("RELEASE_CURRENT_INVALID");
  }
  return leftValue < rightValue ? -1 : leftValue > rightValue ? 1 : 0;
}
