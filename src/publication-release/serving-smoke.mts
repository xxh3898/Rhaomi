import { createServer } from "node:http";
import { lstat, readFile } from "node:fs/promises";
import { isAbsolute, relative, resolve, sep } from "node:path";

import {
  isPublicationReleaseError,
  releaseFail,
  type PublicationReleaseErrorCode,
} from "./errors.mts";

function routeFile(siteRoot: string, requestUrl: string): string | null {
  let parsed: URL;
  try {
    parsed = new URL(requestUrl, "http://127.0.0.1/");
  } catch {
    return null;
  }
  if (parsed.search.length > 0 || parsed.hash.length > 0) return null;
  let pathname: string;
  try {
    pathname = decodeURIComponent(parsed.pathname);
  } catch {
    return null;
  }
  if (
    !pathname.startsWith("/") ||
    pathname.includes("\\") ||
    pathname.split("/").includes("..")
  ) {
    return null;
  }
  const route =
    pathname === "/"
      ? "index.html"
      : pathname.endsWith("/")
        ? `${pathname.slice(1)}index.html`
        : pathname.slice(1);
  const absolutePath = resolve(siteRoot, route);
  const child = relative(resolve(siteRoot), absolutePath);
  if (
    child.length === 0 ||
    child === ".." ||
    child.startsWith(`..${sep}`) ||
    isAbsolute(child)
  ) {
    return null;
  }
  return absolutePath;
}

export async function smokeStaticServingPath(input: Readonly<{
  siteRoot: string;
  noticePath?: string;
  mediaPath?: string;
  timeoutMs?: number;
  failureCode?: PublicationReleaseErrorCode;
}>): Promise<void> {
  const timeoutMs = input.timeoutMs ?? 10_000;
  const failureCode = input.failureCode ?? "RELEASE_POST_SWITCH_FAILED";
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 100 || timeoutMs > 60_000) {
    releaseFail("RELEASE_CONFIG_INVALID");
  }
  const server = createServer(async (request, response) => {
    if (request.method !== "GET") {
      response.writeHead(405).end();
      return;
    }
    const file = routeFile(input.siteRoot, request.url ?? "");
    if (file === null) {
      response.writeHead(404).end();
      return;
    }
    try {
      const stat = await lstat(file);
      if (!stat.isFile() || stat.isSymbolicLink()) {
        response.writeHead(404).end();
        return;
      }
      const bytes = await readFile(file);
      response.writeHead(200, { "content-length": String(bytes.length) });
      response.end(bytes);
    } catch {
      response.writeHead(404).end();
    }
  });
  try {
    await new Promise<void>((resolveListen, rejectListen) => {
      server.once("error", rejectListen);
      server.listen(0, "127.0.0.1", () => {
        server.off("error", rejectListen);
        resolveListen();
      });
    });
    const address = server.address();
    if (address === null || typeof address === "string") {
      releaseFail(failureCode);
    }
    const base = `http://127.0.0.1:${address.port}`;
    const checks = [
      ["/", 200],
      ...(input.noticePath === undefined
        ? []
        : ([[input.noticePath, 200]] as const)),
      ...(input.mediaPath === undefined
        ? []
        : ([[input.mediaPath, 200]] as const)),
      ["/__rhaomi_missing__", 404],
    ] as const;
    for (const [path, expectedStatus] of checks) {
      const response = await fetch(new URL(path, base), {
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (
        response.status !== expectedStatus ||
        (expectedStatus === 200 && (await response.arrayBuffer()).byteLength === 0)
      ) {
        releaseFail(failureCode);
      }
    }
  } catch (error) {
    if (isPublicationReleaseError(error)) throw error;
    releaseFail(failureCode);
  } finally {
    if (server.listening) {
      await new Promise<void>((resolveClose) => server.close(() => resolveClose()));
    }
  }
}
