// @vitest-environment node

import { describe, expect, it, vi } from "vitest";

import { IDS, mediaAsset, snapshotFixture } from "../build-transformer/test-fixtures";
import { loadBuildApiConfig, parsePublishGeneration } from "./config.mts";
import {
  BuildApiClient,
  HttpMediaContentProvider,
  type BuildApiFetch,
} from "./http-client.mts";

const SYNTHETIC_CREDENTIAL = "b".repeat(64);

function config(timeoutMs = 10_000) {
  return loadBuildApiConfig(
    {
      BUILD_API_INTERNAL_URL: "https://backend.internal/",
      BUILD_API_CREDENTIAL: SYNTHETIC_CREDENTIAL,
    },
    timeoutMs,
  );
}

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function mediaResponse(
  bytes: Uint8Array,
  overrides: Readonly<{
    status?: number;
    contentType?: string | null;
    contentLength?: string | null;
  }> = {},
): Response {
  const headers = new Headers();
  if (overrides.contentType !== null) {
    headers.set("content-type", overrides.contentType ?? "image/jpeg");
  }
  if (overrides.contentLength !== null) {
    headers.set(
      "content-length",
      overrides.contentLength ?? String(bytes.byteLength),
    );
  }
  return new Response(Uint8Array.from(bytes).buffer, {
    status: overrides.status ?? 200,
    headers,
  });
}

describe("Build API snapshot client", () => {
  it("exact Bearer와 generation query로 raw snapshot을 strict parser에 전달한다", async () => {
    const base = snapshotFixture();
    const longDescription = "설명".repeat(5_001);
    const raw = {
      ...base,
      breeds: [
        { ...base.breeds[0], description: "\u00a0설명\u00a0" },
        ...base.breeds.slice(1),
      ],
      services: [
        { ...base.services[0], description: longDescription },
        ...base.services.slice(1),
      ],
    };
    const fetchImpl = vi.fn<BuildApiFetch>(async (input, init) => {
      expect(String(input)).toBe(
        "https://backend.internal/api/build/snapshot?publishGeneration=7",
      );
      expect(new Headers(init?.headers).get("authorization")).toBe(
        `Bearer ${SYNTHETIC_CREDENTIAL}`,
      );
      expect(init?.redirect).toBe("manual");
      expect(init?.credentials).toBe("omit");
      return jsonResponse(raw);
    });
    const client = new BuildApiClient(config(), fetchImpl);

    const snapshot = await client.fetchSnapshot(parsePublishGeneration("7"));

    expect(snapshot.breeds[0]?.description).toBe("\u00a0설명\u00a0");
    expect(snapshot.services[0]?.description).toBe(longDescription);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(fetchImpl.mock.calls[0]?.[0].toString()).not.toContain(
      SYNTHETIC_CREDENTIAL,
    );
  });

  it("Java Long.MAX_VALUE generation을 query decimal에서 정밀도 손실 없이 보존한다", async () => {
    const fetchImpl = vi.fn<BuildApiFetch>(async (input) => {
      expect(String(input)).toBe(
        "https://backend.internal/api/build/snapshot?publishGeneration=9223372036854775807",
      );
      return jsonResponse({ code: "BUILD_GENERATION_NOT_ACTIVE" }, 409);
    });
    const client = new BuildApiClient(config(), fetchImpl);

    await expect(
      client.fetchSnapshot(parsePublishGeneration("9223372036854775807")),
    ).rejects.toMatchObject({ code: "BUILD_GENERATION_NOT_ACTIVE" });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it.each([
    [401, "BUILD_API_UNAUTHORIZED", "TERMINAL"],
    [403, "BUILD_API_UNAUTHORIZED", "TERMINAL"],
    [409, "BUILD_GENERATION_NOT_ACTIVE", "GENERATION"],
    [422, "BUILD_SNAPSHOT_INVALID", "TERMINAL"],
    [429, "BUILD_API_TRANSIENT", "TRANSIENT"],
    [500, "BUILD_API_TRANSIENT", "TRANSIENT"],
    [503, "BUILD_API_TRANSIENT", "TRANSIENT"],
    [418, "BUILD_RESPONSE_INVALID", "TERMINAL"],
    [302, "BUILD_RESPONSE_INVALID", "TERMINAL"],
  ])(
    "HTTP %i를 %s/%s로 분류한다",
    async (status, code, disposition) => {
      const fetchImpl = vi.fn<BuildApiFetch>(async () =>
        status === 302
          ? new Response(null, {
              status,
              headers: { location: "https://redirect.invalid/" },
            })
          : jsonResponse({ code: "SAFE" }, status),
      );
      const client = new BuildApiClient(config(), fetchImpl);

      await expect(
        client.fetchSnapshot(parsePublishGeneration("7")),
      ).rejects.toEqual(expect.objectContaining({ code, disposition }));
      expect(fetchImpl).toHaveBeenCalledTimes(1);
      expect(fetchImpl.mock.calls[0]?.[1]?.redirect).toBe("manual");
    },
  );

  it("malformed 200 JSON과 generation mismatch를 response-contract 오류로 거부한다", async () => {
    const malformed = new BuildApiClient(
      config(),
      vi.fn<BuildApiFetch>(async () =>
        new Response("{", {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    );
    await expect(
      malformed.fetchSnapshot(parsePublishGeneration("7")),
    ).rejects.toEqual(
      expect.objectContaining({ code: "BUILD_RESPONSE_INVALID" }),
    );

    const mismatch = new BuildApiClient(
      config(),
      vi.fn<BuildApiFetch>(async () =>
        jsonResponse(snapshotFixture({ publishGeneration: 8 })),
      ),
    );
    await expect(
      mismatch.fetchSnapshot(parsePublishGeneration("7")),
    ).rejects.toEqual(
      expect.objectContaining({ code: "BUILD_RESPONSE_INVALID" }),
    );
  });

  it("unexpected snapshot content type을 response-contract 오류로 거부한다", async () => {
    const client = new BuildApiClient(
      config(),
      vi.fn<BuildApiFetch>(async () =>
        new Response(JSON.stringify(snapshotFixture()), {
          status: 200,
          headers: { "content-type": "text/plain" },
        }),
      ),
    );

    await expect(
      client.fetchSnapshot(parsePublishGeneration("7")),
    ).rejects.toEqual(
      expect.objectContaining({ code: "BUILD_RESPONSE_INVALID" }),
    );
  });

  it("future schema와 unknown field를 기존 strict snapshot category로 거부한다", async () => {
    for (const raw of [
      { ...snapshotFixture(), schemaVersion: 2 },
      { ...snapshotFixture(), unexpected: true },
      {
        ...snapshotFixture(),
        breeds: [
          { ...snapshotFixture().breeds[0], unexpected: true },
          ...snapshotFixture().breeds.slice(1),
        ],
      },
    ]) {
      const client = new BuildApiClient(
        config(),
        vi.fn<BuildApiFetch>(async () => jsonResponse(raw)),
      );
      await expect(
        client.fetchSnapshot(parsePublishGeneration("7")),
      ).rejects.toEqual(
        expect.objectContaining({ code: "BUILD_SNAPSHOT_INVALID" }),
      );
    }
  });

  it("timeout/connection failure를 raw exception 없이 transient로 분류한다", async () => {
    const hangingFetch = vi.fn<BuildApiFetch>(
      async (_input, init) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => reject(new Error("aborted")));
        }),
    );
    const client = new BuildApiClient(config(5), hangingFetch);

    await expect(
      client.fetchSnapshot(parsePublishGeneration("7")),
    ).rejects.toEqual(
      expect.objectContaining({ code: "BUILD_API_TRANSIENT", disposition: "TRANSIENT" }),
    );

    const failedFetch = new BuildApiClient(
      config(),
      vi.fn<BuildApiFetch>(async () => {
        throw new Error("private connection detail");
      }),
    );
    await expect(
      failedFetch.fetchSnapshot(parsePublishGeneration("7")),
    ).rejects.toMatchObject({
      code: "BUILD_API_TRANSIENT",
      message: "BUILD_API_TRANSIENT",
    });
  });

  it("snapshot body가 완료되지 않아도 bounded timeout으로 중단한다", async () => {
    const body = new ReadableStream<Uint8Array>({
      start() {
        // Intentionally keep the response body open beyond the request deadline.
      },
    });
    const client = new BuildApiClient(
      config(5),
      vi.fn<BuildApiFetch>(async () =>
        new Response(body, {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    );

    await expect(
      client.fetchSnapshot(parsePublishGeneration("7")),
    ).rejects.toMatchObject({
      code: "BUILD_API_TRANSIENT",
      disposition: "TRANSIENT",
    });
  });

  it("직접 생성한 client도 malformed config를 request 전에 재검증한다", () => {
    const fetchImpl = vi.fn<BuildApiFetch>();
    expect(
      () =>
        new BuildApiClient(
          {
            baseUrl: new URL("https://backend.internal/"),
            credential: "invalid",
            requestTimeoutMs: 10_000,
          },
          fetchImpl,
        ),
    ).toThrow(expect.objectContaining({ code: "BUILD_API_CONFIG_INVALID" }));
    expect(fetchImpl).not.toHaveBeenCalled();
  });
});

describe("HTTP MediaContentProvider", () => {
  const generation = parsePublishGeneration("7");
  const asset = mediaAsset(IDS.mediaJpeg, "image/jpeg", 4, 640, 360);

  function provider(fetchImpl: BuildApiFetch) {
    return new HttpMediaContentProvider({
      config: config(),
      generation,
      assets: [asset],
      fetchImpl,
    });
  }

  it("manifest media를 exact endpoint/header로 가져오고 concurrent duplicate를 한 번만 fetch한다", async () => {
    const bytes = Uint8Array.from([1, 2, 3, 4]);
    const fetchImpl = vi.fn<BuildApiFetch>(async (input, init) => {
      expect(String(input)).toBe(
        `https://backend.internal/api/build/media/${IDS.mediaJpeg}/content?publishGeneration=7`,
      );
      expect(new Headers(init?.headers).get("authorization")).toBe(
        `Bearer ${SYNTHETIC_CREDENTIAL}`,
      );
      expect(init?.redirect).toBe("manual");
      return mediaResponse(bytes);
    });
    const mediaProvider = provider(fetchImpl);

    const [first, second, third] = await Promise.all([
      mediaProvider.get(IDS.mediaJpeg),
      mediaProvider.get(IDS.mediaJpeg),
      mediaProvider.get(IDS.mediaJpeg),
    ]);

    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(first).toEqual({ contentType: "image/jpeg", bytes });
    expect(second).toBe(first);
    expect(third).toBe(first);
  });

  it("manifest 밖 UUID를 network 전에 safe not-found로 거부한다", async () => {
    const fetchImpl = vi.fn<BuildApiFetch>();
    const mediaProvider = provider(fetchImpl);

    await expect(mediaProvider.get(IDS.mediaPng)).rejects.toMatchObject({
      code: "BUILD_MEDIA_NOT_FOUND",
      disposition: "TERMINAL",
      message: "BUILD_MEDIA_NOT_FOUND",
    });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("실패한 in-flight/result도 memoize해 같은 media의 network retry를 만들지 않는다", async () => {
    const fetchImpl = vi.fn<BuildApiFetch>(async () =>
      jsonResponse({ code: "SAFE" }, 503),
    );
    const mediaProvider = provider(fetchImpl);

    const concurrent = await Promise.allSettled([
      mediaProvider.get(IDS.mediaJpeg),
      mediaProvider.get(IDS.mediaJpeg),
    ]);
    expect(concurrent.every((result) => result.status === "rejected")).toBe(true);
    await expect(mediaProvider.get(IDS.mediaJpeg)).rejects.toMatchObject({
      code: "BUILD_MEDIA_UNAVAILABLE",
    });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["content type", { contentType: "image/png" }],
    ["missing content type", { contentType: null }],
    ["missing content length", { contentLength: null }],
    ["malformed content length", { contentLength: "04" }],
    ["declared length mismatch", { contentLength: "5" }],
  ])("%s mismatch를 response-contract 오류로 거부한다", async (_name, overrides) => {
    const mediaProvider = provider(
      vi.fn<BuildApiFetch>(async () =>
        mediaResponse(Uint8Array.from([1, 2, 3, 4]), overrides),
      ),
    );

    await expect(mediaProvider.get(IDS.mediaJpeg)).rejects.toEqual(
      expect.objectContaining({ code: "BUILD_RESPONSE_INVALID" }),
    );
  });

  it.each([
    [Uint8Array.from([1, 2, 3]), "short body"],
    [Uint8Array.from([1, 2, 3, 4, 5]), "long body"],
  ])("%s를 manifest byteSize mismatch로 거부한다", async (bytes) => {
    const mediaProvider = provider(
      vi.fn<BuildApiFetch>(async () =>
        mediaResponse(bytes, { contentLength: "4" }),
      ),
    );

    await expect(mediaProvider.get(IDS.mediaJpeg)).rejects.toEqual(
      expect.objectContaining({ code: "BUILD_RESPONSE_INVALID" }),
    );
  });

  it.each([
    [401, "BUILD_API_UNAUTHORIZED", "TERMINAL"],
    [403, "BUILD_API_UNAUTHORIZED", "TERMINAL"],
    [404, "BUILD_MEDIA_NOT_FOUND", "TERMINAL"],
    [409, "BUILD_GENERATION_NOT_ACTIVE", "GENERATION"],
    [429, "BUILD_API_TRANSIENT", "TRANSIENT"],
    [500, "BUILD_API_TRANSIENT", "TRANSIENT"],
    [503, "BUILD_MEDIA_UNAVAILABLE", "TRANSIENT"],
    [302, "BUILD_RESPONSE_INVALID", "TERMINAL"],
  ])("media HTTP %i를 %s/%s로 분류한다", async (status, code, disposition) => {
    const mediaProvider = provider(
      vi.fn<BuildApiFetch>(async () =>
        status === 302
          ? new Response(null, { status, headers: { location: "/elsewhere" } })
          : jsonResponse({ code: "SAFE" }, status),
      ),
    );

    await expect(mediaProvider.get(IDS.mediaJpeg)).rejects.toEqual(
      expect.objectContaining({ code, disposition }),
    );
  });
});
