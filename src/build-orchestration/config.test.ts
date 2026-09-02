// @vitest-environment node

import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

import {
  loadBuildApiConfig,
  parsePublishGeneration,
  validateStagingOutputRoot,
} from "./config.mts";

const SYNTHETIC_CREDENTIAL = "a".repeat(64);

describe("build orchestration configuration", () => {
  it("internal root URL과 exact lowercase-hex credential을 승인한다", () => {
    const config = loadBuildApiConfig({
      BUILD_API_INTERNAL_URL: "http://backend:8080/",
      BUILD_API_CREDENTIAL: SYNTHETIC_CREDENTIAL,
    });

    expect(config.baseUrl.href).toBe("http://backend:8080/");
    expect(config.credential).toBe(SYNTHETIC_CREDENTIAL);
    expect(config.requestTimeoutMs).toBe(10_000);
  });

  it.each([
    ["missing URL", undefined, SYNTHETIC_CREDENTIAL],
    ["relative URL", "/backend", SYNTHETIC_CREDENTIAL],
    ["scheme without authority delimiter", "http:backend", SYNTHETIC_CREDENTIAL],
    ["single-slash authority", "http:/backend", SYNTHETIC_CREDENTIAL],
    ["empty authority", "http:///backend", SYNTHETIC_CREDENTIAL],
    ["unsupported scheme", "ftp://backend/", SYNTHETIC_CREDENTIAL],
    ["userinfo", "http://user@backend/", SYNTHETIC_CREDENTIAL],
    ["query", "http://backend/?debug=true", SYNTHETIC_CREDENTIAL],
    ["empty query", "http://backend/?", SYNTHETIC_CREDENTIAL],
    ["fragment", "http://backend/#fragment", SYNTHETIC_CREDENTIAL],
    ["empty fragment", "http://backend/#", SYNTHETIC_CREDENTIAL],
    ["empty userinfo", "http://@backend/", SYNTHETIC_CREDENTIAL],
    ["non-root path", "http://backend/internal", SYNTHETIC_CREDENTIAL],
    ["surrounding whitespace", " http://backend/", SYNTHETIC_CREDENTIAL],
    ["missing credential", "http://backend/", undefined],
    ["short credential", "http://backend/", "a".repeat(63)],
    ["uppercase credential", "http://backend/", "A".repeat(64)],
    ["non-hex credential", "http://backend/", "g".repeat(64)],
  ])("%s를 request 전 safe config 오류로 거부한다", (_name, url, credential) => {
    expect(() =>
      loadBuildApiConfig({
        BUILD_API_INTERNAL_URL: url,
        BUILD_API_CREDENTIAL: credential,
      }),
    ).toThrow(
      expect.objectContaining({
        code: "BUILD_API_CONFIG_INVALID",
        disposition: "TERMINAL",
      }),
    );
  });

  it("positive Java long 전체 범위를 canonical decimal로 보존한다", () => {
    expect(parsePublishGeneration("1")).toEqual({
      decimal: "1",
      value: BigInt("1"),
    });
    expect(parsePublishGeneration("9223372036854775807")).toEqual({
      decimal: "9223372036854775807",
      value: BigInt("9223372036854775807"),
    });
  });

  it.each([
    "",
    "0",
    "-1",
    "+1",
    "01",
    "1.0",
    " 1",
    "9223372036854775808",
  ])("invalid generation %j을 request 전 거부한다", (value) => {
    expect(() => parsePublishGeneration(value)).toThrow(
      expect.objectContaining({
        code: "BUILD_API_CONFIG_INVALID",
        disposition: "TERMINAL",
      }),
    );
  });

  it("private staging path는 resolve하고 production public/host root는 거부한다", () => {
    expect(validateStagingOutputRoot("private/staging")).toBe(
      resolve("private/staging"),
    );
    for (const path of [
      "/srv/rhaomi/public/current",
      "/srv/rhaomi/public/releases/generation-7",
      "/private/var/lib/rhaomi/state/publisher/staging",
    ]) {
      expect(() => validateStagingOutputRoot(path)).toThrow(
        expect.objectContaining({ code: "BUILD_API_CONFIG_INVALID" }),
      );
    }
  });
});
