// @vitest-environment node

import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer, type Server } from "node:http";
import { mkdtemp, readFile, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

import sharp from "sharp";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { BuildSnapshotV2 } from "../build-transformer/contracts.mts";
import {
  IDS,
  galleryItem,
  mediaAsset,
  snapshotFixture,
} from "../build-transformer/test-fixtures";
import { preparePublicationStaging } from "./orchestrator.mts";
import type { BuildApiFetch } from "./http-client.mts";

const SYNTHETIC_CREDENTIAL = "c".repeat(64);
const createdRoots: string[] = [];
const servers: Server[] = [];

afterEach(async () => {
  await Promise.all(
    servers.splice(0).map(
      (server) =>
        new Promise<void>((resolveClose, rejectClose) => {
          server.close((error) =>
            error === undefined ? resolveClose() : rejectClose(error),
          );
        }),
    ),
  );
  await Promise.all(
    createdRoots.splice(0).map((path) =>
      rm(path, { recursive: true, force: true }),
    ),
  );
});

async function taskRoot(): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), "rhaomi-build-orchestration-test-"));
  createdRoots.push(path);
  return path;
}

async function syntheticJpeg(): Promise<Buffer> {
  return sharp({
    create: {
      width: 320,
      height: 180,
      channels: 3,
      background: { r: 204, g: 96, b: 82 },
    },
  })
    .jpeg({ quality: 90 })
    .withExif({
      IFD0: { ImageDescription: "synthetic-private-description" },
      IFD3: { GPSLatitudeRef: "N", GPSLatitude: "37/1 33/1 0/1" },
    })
    .toBuffer();
}

async function syntheticPng(): Promise<Buffer> {
  return sharp({
    create: {
      width: 160,
      height: 120,
      channels: 4,
      background: { r: 48, g: 132, b: 174, alpha: 0.75 },
    },
  })
    .png()
    .withXmp(
      '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description synthetic="private" /></rdf:RDF></x:xmpmeta>',
    )
    .toBuffer();
}

function orchestrationSnapshot(jpeg: Buffer, png: Buffer): BuildSnapshotV2 {
  const base = snapshotFixture();
  return snapshotFixture({
    shop: {
      ...base.shop,
      heroImageId: IDS.mediaJpeg,
      heroImageAltText: "합성 Hero 이미지",
      groomerImageId: IDS.mediaPng,
      groomerImageAltText: "합성 미용사 이미지",
      ogImageId: null,
    },
    galleryItems: [
      galleryItem({
        coverImageId: IDS.mediaJpeg,
        beforeImageId: IDS.mediaPng,
        afterImageId: null,
      }),
    ],
    mediaAssets: [
      mediaAsset(IDS.mediaJpeg, "image/jpeg", jpeg.length, 320, 180),
      mediaAsset(IDS.mediaPng, "image/png", png.length, 160, 120),
    ],
  });
}

async function listen(
  snapshot: BuildSnapshotV2,
  media: ReadonlyMap<string, Readonly<{ contentType: string; bytes: Buffer }>>,
): Promise<{
  baseUrl: string;
  counts: Map<string, number>;
  authorizationValues: string[];
  requestedUrls: string[];
}> {
  const counts = new Map<string, number>();
  const authorizationValues: string[] = [];
  const requestedUrls: string[] = [];
  const server = createServer((request, response) => {
    const requestUrl = request.url ?? "";
    requestedUrls.push(requestUrl);
    authorizationValues.push(request.headers.authorization ?? "");
    counts.set(requestUrl, (counts.get(requestUrl) ?? 0) + 1);

    if (
      request.method === "GET" &&
      requestUrl ===
        `/api/build/snapshot?publishGeneration=${snapshot.publishGeneration}`
    ) {
      const body = Buffer.from(JSON.stringify(snapshot));
      response.writeHead(200, {
        "content-type": "application/json",
        "content-length": String(body.length),
      });
      response.end(body);
      return;
    }

    const match = requestUrl.match(
      new RegExp(
        `^/api/build/media/([0-9a-f-]+)/content\\?publishGeneration=${snapshot.publishGeneration}$`,
        "u",
      ),
    );
    const content = match === null ? undefined : media.get(match[1] ?? "");
    if (request.method === "GET" && content !== undefined) {
      response.writeHead(200, {
        "content-type": content.contentType,
        "content-length": String(content.bytes.length),
      });
      response.end(content.bytes);
      return;
    }

    response.writeHead(404, { "content-type": "application/json" });
    response.end('{"code":"SAFE"}');
  });
  servers.push(server);
  await new Promise<void>((resolveListen, rejectListen) => {
    server.once("error", rejectListen);
    server.listen(0, "127.0.0.1", () => {
      server.off("error", rejectListen);
      resolveListen();
    });
  });
  const address = server.address();
  if (address === null || typeof address === "string") {
    throw new Error("test server address unavailable");
  }
  return {
    baseUrl: `http://127.0.0.1:${address.port}/`,
    counts,
    authorizationValues,
    requestedUrls,
  };
}

async function listenStatus(status: number): Promise<string> {
  const server = createServer((_request, response) => {
    response.writeHead(status, { "content-type": "application/json" });
    response.end('{"code":"SAFE"}');
  });
  servers.push(server);
  await new Promise<void>((resolveListen, rejectListen) => {
    server.once("error", rejectListen);
    server.listen(0, "127.0.0.1", () => {
      server.off("error", rejectListen);
      resolveListen();
    });
  });
  const address = server.address();
  if (address === null || typeof address === "string") {
    throw new Error("test server address unavailable");
  }
  return `http://127.0.0.1:${address.port}/`;
}

async function runCli(
  args: readonly string[],
  environment: NodeJS.ProcessEnv,
): Promise<Readonly<{ code: number | null; stdout: string; stderr: string }>> {
  const cli = resolve("scripts/prepare-publication-staging.mts");
  return new Promise((resolveChild, rejectChild) => {
    const child = spawn(process.execPath, [cli, ...args], {
      env: environment,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk: string) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk: string) => {
      stderr += chunk;
    });
    child.once("error", rejectChild);
    child.once("close", (code) => resolveChild({ code, stdout, stderr }));
  });
}

describe("publication staging orchestration", () => {
  it("invalid credential과 production output path를 network 전에 거부한다", async () => {
    const fetchImpl = vi.fn<BuildApiFetch>();
    for (const input of [
      {
        outputRoot: resolve("private-staging"),
        credential: "invalid",
      },
      {
        outputRoot: "/srv/rhaomi/public/current",
        credential: SYNTHETIC_CREDENTIAL,
      },
    ]) {
      await expect(
        preparePublicationStaging({
          publishGeneration: "7",
          outputRoot: input.outputRoot,
          environment: {
            BUILD_API_INTERNAL_URL: "https://backend.internal/",
            BUILD_API_CREDENTIAL: input.credential,
          },
          fetchImpl,
        }),
      ).rejects.toMatchObject({
        code: "BUILD_API_CONFIG_INVALID",
        disposition: "TERMINAL",
      });
    }
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("HTTP snapshot/media에서 strict responsive staging을 만들고 private 값을 출력에 남기지 않는다", async () => {
    const root = await taskRoot();
    const jpeg = await syntheticJpeg();
    const png = await syntheticPng();
    const snapshot = orchestrationSnapshot(jpeg, png);
    const server = await listen(
      snapshot,
      new Map([
        [IDS.mediaJpeg, { contentType: "image/jpeg", bytes: jpeg }],
        [IDS.mediaPng, { contentType: "image/png", bytes: png }],
      ]),
    );
    const output = join(root, "staging");

    const result = await preparePublicationStaging({
      publishGeneration: "7",
      outputRoot: output,
      environment: {
        BUILD_API_INTERNAL_URL: server.baseUrl,
        BUILD_API_CREDENTIAL: SYNTHETIC_CREDENTIAL,
      },
      requestTimeoutMs: 2_000,
    });

    expect(result).toEqual({
      status: "STAGING_PREPARED",
      contentRevision: snapshot.contentRevision,
      publishGeneration: snapshot.publishGeneration,
      generatedAt: snapshot.generatedAt,
      publicFileCount: expect.any(Number),
    });
    expect(result.publicFileCount).toBeGreaterThan(0);
    expect(server.counts.get("/api/build/snapshot?publishGeneration=7")).toBe(1);
    expect(
      server.counts.get(
        `/api/build/media/${IDS.mediaJpeg}/content?publishGeneration=7`,
      ),
    ).toBe(1);
    expect(
      server.counts.get(
        `/api/build/media/${IDS.mediaPng}/content?publishGeneration=7`,
      ),
    ).toBe(1);
    expect(new Set(server.authorizationValues)).toEqual(
      new Set([`Bearer ${SYNTHETIC_CREDENTIAL}`]),
    );
    expect(server.requestedUrls.join("\n")).not.toContain(SYNTHETIC_CREDENTIAL);

    const contentText = await readFile(
      join(output, "src/generated/content.json"),
      "utf8",
    );
    const manifestText = await readFile(
      join(output, "src/generated/media-manifest.json"),
      "utf8",
    );
    const content = JSON.parse(contentText) as Record<string, unknown>;
    const manifest = JSON.parse(manifestText) as {
      schemaVersion: number;
      contentRevision: string;
      publishGeneration: string;
      items: Array<{
        variants: Array<{ publicPath: string }>;
      }>;
    };
    expect(content).toMatchObject({
      schemaVersion: 2,
      contentRevision: snapshot.contentRevision,
      publishGeneration: snapshot.publishGeneration,
      generatedAt: snapshot.generatedAt,
    });
    expect(manifest).toMatchObject({
      schemaVersion: 2,
      contentRevision: snapshot.contentRevision,
      publishGeneration: snapshot.publishGeneration,
    });
    const publicPaths = [
      ...new Set(
        manifest.items.flatMap((item) =>
          item.variants.map((variant) => variant.publicPath),
        ),
      ),
    ];
    expect(publicPaths).toHaveLength(result.publicFileCount);
    expect(publicPaths.some((path) => path.endsWith(".avif"))).toBe(true);
    expect(publicPaths.some((path) => path.endsWith(".webp"))).toBe(true);
    expect(publicPaths.some((path) => path.endsWith(".jpeg"))).toBe(true);
    for (const publicPath of publicPaths) {
      const bytes = await readFile(
        join(output, "public", publicPath.replace(/^\//u, "")),
      );
      const expectedHash = publicPath.match(/[0-9a-f]{64}/u)?.[0];
      expect(createHash("sha256").update(bytes).digest("hex")).toBe(
        expectedHash,
      );
      const metadata = await sharp(bytes).metadata();
      expect(metadata.exif).toBeUndefined();
      expect(metadata.xmp).toBeUndefined();
      expect(metadata.orientation).toBeUndefined();
      expect(metadata.space).toBe("srgb");
    }

    const generated = `${JSON.stringify(result)}\n${contentText}\n${manifestText}`;
    expect(generated).not.toContain(SYNTHETIC_CREDENTIAL);
    expect(generated).not.toContain(server.baseUrl);
    expect(generated).not.toMatch(/authorization|storageKey|originalFilename/iu);
  }, 120_000);

  it("corrupt media를 deterministic BUILD_MEDIA_INVALID로 거부하고 partial target을 남기지 않는다", async () => {
    const root = await taskRoot();
    const corrupt = Buffer.from([0xff, 0xd8, 0xff, 0x00]);
    const base = snapshotFixture();
    const snapshot = snapshotFixture({
      shop: {
        ...base.shop,
        heroImageId: null,
        heroImageAltText: null,
        groomerImageId: IDS.mediaJpeg,
        groomerImageAltText: "합성 이미지",
        ogImageId: null,
      },
      galleryItems: [],
      mediaAssets: [
        mediaAsset(IDS.mediaJpeg, "image/jpeg", corrupt.length, 640, 360),
      ],
    });
    const fetchImpl = vi.fn<BuildApiFetch>(async (input) =>
      String(input).includes("/snapshot?")
        ? new Response(JSON.stringify(snapshot), {
            status: 200,
            headers: { "content-type": "application/json" },
          })
        : new Response(corrupt, {
            status: 200,
            headers: {
              "content-type": "image/jpeg",
              "content-length": String(corrupt.length),
            },
          }),
    );
    const output = join(root, "failed-staging");

    await expect(
      preparePublicationStaging({
        publishGeneration: "7",
        outputRoot: output,
        environment: {
          BUILD_API_INTERNAL_URL: "https://backend.internal/",
          BUILD_API_CREDENTIAL: SYNTHETIC_CREDENTIAL,
        },
        fetchImpl,
      }),
    ).rejects.toMatchObject({
      code: "BUILD_MEDIA_INVALID",
      disposition: "TERMINAL",
      message: "BUILD_MEDIA_INVALID",
    });
    expect(await readdir(root)).toEqual([]);
  });

  it("CLI가 environment-only credential과 fixed argv로 safe machine result를 제공한다", async () => {
    const root = await taskRoot();
    const jpeg = await syntheticJpeg();
    const png = await syntheticPng();
    const decimal = "9007199254740993";
    const snapshot = {
      ...orchestrationSnapshot(jpeg, png),
      contentRevision: decimal,
      publishGeneration: decimal,
    };
    const server = await listen(
      snapshot,
      new Map([
        [IDS.mediaJpeg, { contentType: "image/jpeg", bytes: jpeg }],
        [IDS.mediaPng, { contentType: "image/png", bytes: png }],
      ]),
    );
    const output = join(root, "cli-staging");
    const environment = {
      ...process.env,
      BUILD_API_INTERNAL_URL: server.baseUrl,
      BUILD_API_CREDENTIAL: SYNTHETIC_CREDENTIAL,
    };

    const success = await runCli(
      ["--publish-generation", decimal, "--output", output],
      environment,
    );

    expect(success.code).toBe(0);
    expect(success.stderr).toBe("");
    expect(JSON.parse(success.stdout)).toMatchObject({
      status: "STAGING_PREPARED",
      contentRevision: decimal,
      publishGeneration: decimal,
    });
    expect(success.stdout).not.toContain(SYNTHETIC_CREDENTIAL);
    expect(success.stdout).not.toContain(server.baseUrl);
    expect(success.stdout).not.toContain(output);
    expect(success.stdout).not.toContain(IDS.mediaJpeg);

    const failure = await runCli(
      [
        "--publish-generation",
        "7",
        "--output",
        join(root, "invalid-config-staging"),
      ],
      {
        ...process.env,
        BUILD_API_INTERNAL_URL: server.baseUrl,
        BUILD_API_CREDENTIAL: "invalid",
      },
    );
    expect(failure.code).toBe(20);
    expect(failure.stdout).toBe("");
    expect(JSON.parse(failure.stderr)).toEqual({
      status: "FAILED",
      code: "BUILD_API_CONFIG_INVALID",
      disposition: "TERMINAL",
    });
    expect(failure.stderr).not.toContain("invalid");
    expect(failure.stderr).not.toContain(server.baseUrl);
    expect(failure.stderr).not.toContain(root);
    expect(server.requestedUrls).toHaveLength(3);

    for (const [status, code, disposition, exitCode] of [
      [409, "BUILD_GENERATION_NOT_ACTIVE", "GENERATION", 22],
      [503, "BUILD_API_TRANSIENT", "TRANSIENT", 21],
    ] as const) {
      const baseUrl = await listenStatus(status);
      const failed = await runCli(
        [
          "--publish-generation",
          "7",
          "--output",
          join(root, `status-${status}-staging`),
        ],
        {
          ...process.env,
          BUILD_API_INTERNAL_URL: baseUrl,
          BUILD_API_CREDENTIAL: SYNTHETIC_CREDENTIAL,
        },
      );
      expect(failed.code).toBe(exitCode);
      expect(failed.stdout).toBe("");
      expect(JSON.parse(failed.stderr)).toEqual({
        status: "FAILED",
        code,
        disposition,
      });
      expect(failed.stderr).not.toContain(SYNTHETIC_CREDENTIAL);
      expect(failed.stderr).not.toContain(baseUrl);
    }
  }, 120_000);
});
