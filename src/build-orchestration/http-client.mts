import {
  BuildTransformError,
  parseBuildSnapshotV2,
  type BuildMediaAssetV1,
  type BuildSnapshotV2,
  type MediaContent,
  type MediaContentProvider,
} from "../build-transformer/index.mts";
import {
  loadBuildApiConfig,
  type BuildApiConfig,
  type PublishGeneration,
} from "./config.mts";
import {
  BuildPreparationError,
  isBuildPreparationError,
  preparationFail,
} from "./errors.mts";

export type BuildApiFetch = (
  input: string | URL,
  init?: RequestInit,
) => Promise<Response>;

type TimedOperation<T> = (signal: AbortSignal) => Promise<T>;

async function withRequestTimeout<T>(
  timeoutMs: number,
  operation: TimedOperation<T>,
): Promise<T> {
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => {
      controller.abort();
      reject(new Error("request timed out"));
    }, timeoutMs);
  });

  try {
    return await Promise.race([operation(controller.signal), timeout]);
  } catch (error) {
    if (isBuildPreparationError(error)) throw error;
    throw new BuildPreparationError("BUILD_API_TRANSIENT");
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

function requestUrl(
  config: BuildApiConfig,
  pathname: string,
  generation: PublishGeneration,
): URL {
  const url = new URL(config.baseUrl.href);
  url.pathname = pathname;
  url.searchParams.set("publishGeneration", generation.decimal);
  return url;
}

function requestInit(
  config: BuildApiConfig,
  accept: string,
  signal: AbortSignal,
): RequestInit {
  return {
    method: "GET",
    headers: {
      accept,
      authorization: `Bearer ${config.credential}`,
    },
    cache: "no-store",
    credentials: "omit",
    redirect: "manual",
    signal,
  };
}

function rejectRedirected(response: Response): void {
  if (response.redirected || (response.status >= 300 && response.status < 400)) {
    preparationFail("BUILD_RESPONSE_INVALID");
  }
}

function validateSnapshotStatus(response: Response): void {
  rejectRedirected(response);
  if (response.status === 200) return;
  if (response.status === 401 || response.status === 403) {
    preparationFail("BUILD_API_UNAUTHORIZED");
  }
  if (response.status === 409) {
    preparationFail("BUILD_GENERATION_NOT_ACTIVE");
  }
  if (response.status === 422) {
    preparationFail("BUILD_SNAPSHOT_INVALID");
  }
  if (response.status === 429 || response.status >= 500) {
    preparationFail("BUILD_API_TRANSIENT");
  }
  preparationFail("BUILD_RESPONSE_INVALID");
}

function validateMediaStatus(response: Response): void {
  rejectRedirected(response);
  if (response.status === 200) return;
  if (response.status === 401 || response.status === 403) {
    preparationFail("BUILD_API_UNAUTHORIZED");
  }
  if (response.status === 404) {
    preparationFail("BUILD_MEDIA_NOT_FOUND");
  }
  if (response.status === 409) {
    preparationFail("BUILD_GENERATION_NOT_ACTIVE");
  }
  if (response.status === 503) {
    preparationFail("BUILD_MEDIA_UNAVAILABLE");
  }
  if (response.status === 429 || response.status >= 500) {
    preparationFail("BUILD_API_TRANSIENT");
  }
  preparationFail("BUILD_RESPONSE_INVALID");
}

async function responseJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    preparationFail("BUILD_RESPONSE_INVALID");
  }
}

async function exactResponseBytes(
  response: Response,
  expectedLength: number,
): Promise<Uint8Array> {
  if (response.body === null) preparationFail("BUILD_RESPONSE_INVALID");
  const reader = response.body.getReader();
  const bytes = new Uint8Array(expectedLength);
  let offset = 0;
  while (true) {
    const chunk = await reader.read();
    if (chunk.done) break;
    if (chunk.value.byteLength > expectedLength - offset) {
      try {
        await reader.cancel();
      } catch {
        // The safe response-contract failure remains authoritative.
      }
      preparationFail("BUILD_RESPONSE_INVALID");
    }
    bytes.set(chunk.value, offset);
    offset += chunk.value.byteLength;
  }
  if (offset !== expectedLength) preparationFail("BUILD_RESPONSE_INVALID");
  return bytes;
}

export class BuildApiClient {
  readonly #config: BuildApiConfig;
  readonly #fetch: BuildApiFetch;

  constructor(config: BuildApiConfig, fetchImpl: BuildApiFetch = fetch) {
    this.#config = loadBuildApiConfig(
      {
        BUILD_API_INTERNAL_URL: config.baseUrl.href,
        BUILD_API_CREDENTIAL: config.credential,
      },
      config.requestTimeoutMs,
    );
    this.#fetch = fetchImpl;
  }

  async fetchSnapshot(
    generation: PublishGeneration,
  ): Promise<BuildSnapshotV2> {
    return withRequestTimeout(
      this.#config.requestTimeoutMs,
      async (signal) => {
        const response = await this.#fetch(
          requestUrl(this.#config, "/api/build/snapshot", generation),
          requestInit(this.#config, "application/json", signal),
        );
        validateSnapshotStatus(response);
        if (response.headers.get("content-type") !== "application/json") {
          preparationFail("BUILD_RESPONSE_INVALID");
        }
        const rawSnapshot = await responseJson(response);
        let snapshot: BuildSnapshotV2;
        try {
          snapshot = parseBuildSnapshotV2(rawSnapshot);
        } catch (error) {
          if (
            error instanceof BuildTransformError &&
            error.code === "SNAPSHOT_INVALID"
          ) {
            preparationFail("BUILD_SNAPSHOT_INVALID");
          }
          throw error;
        }
        if (BigInt(snapshot.publishGeneration) !== generation.value) {
          preparationFail("BUILD_RESPONSE_INVALID");
        }
        return snapshot;
      },
    );
  }

  createMediaContentProvider(
    generation: PublishGeneration,
    assets: readonly BuildMediaAssetV1[],
  ): HttpMediaContentProvider {
    return new HttpMediaContentProvider({
      config: this.#config,
      generation,
      assets,
      fetchImpl: this.#fetch,
    });
  }
}

export class HttpMediaContentProvider implements MediaContentProvider {
  readonly #config: BuildApiConfig;
  readonly #generation: PublishGeneration;
  readonly #assets: ReadonlyMap<string, BuildMediaAssetV1>;
  readonly #fetch: BuildApiFetch;
  readonly #content = new Map<string, Promise<MediaContent>>();

  constructor(input: Readonly<{
    config: BuildApiConfig;
    generation: PublishGeneration;
    assets: readonly BuildMediaAssetV1[];
    fetchImpl?: BuildApiFetch;
  }>) {
    this.#config = loadBuildApiConfig(
      {
        BUILD_API_INTERNAL_URL: input.config.baseUrl.href,
        BUILD_API_CREDENTIAL: input.config.credential,
      },
      input.config.requestTimeoutMs,
    );
    this.#generation = input.generation;
    this.#assets = new Map(input.assets.map((asset) => [asset.id, asset]));
    this.#fetch = input.fetchImpl ?? fetch;
  }

  async get(mediaId: string): Promise<MediaContent> {
    const asset = this.#assets.get(mediaId);
    if (asset === undefined) preparationFail("BUILD_MEDIA_NOT_FOUND");
    const existing = this.#content.get(mediaId);
    if (existing !== undefined) return existing;

    const pending = Promise.resolve().then(() => this.#fetchContent(asset));
    this.#content.set(mediaId, pending);
    return pending;
  }

  async prefetchAll(): Promise<void> {
    for (const asset of this.#assets.values()) {
      await this.get(asset.id);
    }
  }

  async #fetchContent(asset: BuildMediaAssetV1): Promise<MediaContent> {
    return withRequestTimeout(
      this.#config.requestTimeoutMs,
      async (signal) => {
        const response = await this.#fetch(
          requestUrl(
            this.#config,
            `/api/build/media/${asset.id}/content`,
            this.#generation,
          ),
          requestInit(this.#config, asset.contentType, signal),
        );
        validateMediaStatus(response);
        if (response.headers.get("content-type") !== asset.contentType) {
          preparationFail("BUILD_RESPONSE_INVALID");
        }
        const contentLength = response.headers.get("content-length");
        if (
          contentLength === null ||
          !/^(?:0|[1-9][0-9]*)$/u.test(contentLength) ||
          Number(contentLength) !== asset.byteSize
        ) {
          preparationFail("BUILD_RESPONSE_INVALID");
        }
        return {
          contentType: asset.contentType,
          bytes: await exactResponseBytes(response, asset.byteSize),
        };
      },
    );
  }
}
