import { spawn, type ChildProcess } from "node:child_process";
import {
  cp,
  lstat,
  mkdir,
  rm,
} from "node:fs/promises";
import { dirname, isAbsolute, join, relative, sep } from "node:path";

import { releaseFail } from "./errors.mts";

const MAX_CAPTURE_BYTES = 64 * 1024;

async function exists(path: string): Promise<boolean> {
  try {
    await lstat(path);
    return true;
  } catch (error) {
    if (
      typeof error === "object" &&
      error !== null &&
      "code" in error &&
      error.code === "ENOENT"
    ) {
      return false;
    }
    releaseFail("RELEASE_FILESYSTEM_FAILED");
  }
}

function capture(stream: NodeJS.ReadableStream | null): Promise<Buffer> {
  if (stream === null) return Promise.resolve(Buffer.alloc(0));
  return new Promise((resolveCapture) => {
    const chunks: Buffer[] = [];
    let size = 0;
    stream.on("data", (chunk: Buffer | string) => {
      const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      if (size < MAX_CAPTURE_BYTES) {
        const remaining = MAX_CAPTURE_BYTES - size;
        chunks.push(bytes.subarray(0, remaining));
        size += Math.min(bytes.length, remaining);
      }
    });
    stream.on("end", () => resolveCapture(Buffer.concat(chunks)));
    stream.on("error", () => resolveCapture(Buffer.concat(chunks)));
  });
}

async function terminate(child: ChildProcess): Promise<void> {
  if (child.exitCode !== null || child.signalCode !== null) return;
  try {
    if (child.pid !== undefined && process.platform !== "win32") {
      process.kill(-child.pid, "SIGTERM");
    } else {
      child.kill("SIGTERM");
    }
  } catch {
    // The process may have exited between the state check and the signal.
  }
  await new Promise((resolveWait) => setTimeout(resolveWait, 2_000));
  if (child.exitCode !== null || child.signalCode !== null) return;
  try {
    if (child.pid !== undefined && process.platform !== "win32") {
      process.kill(-child.pid, "SIGKILL");
    } else {
      child.kill("SIGKILL");
    }
  } catch {
    // The process may have exited between the state check and the signal.
  }
}

async function runNextBuild(
  sourceRoot: string,
  workspaceRoot: string,
  homeRoot: string,
  publicSiteUrl: string,
  timeoutMs: number,
): Promise<void> {
  const nextExecutable = join(
    sourceRoot,
    "node_modules",
    "next",
    "dist",
    "bin",
    "next",
  );
  const child = spawn(process.execPath, [nextExecutable, "build"], {
    cwd: workspaceRoot,
    detached: process.platform !== "win32",
    stdio: ["ignore", "pipe", "pipe"],
    env: {
      HOME: homeRoot,
      PATH: process.env.PATH ?? "",
      NODE_ENV: "production",
      NEXT_TELEMETRY_DISABLED: "1",
      PUBLIC_SITE_URL: publicSiteUrl,
    },
  });
  const stdout = capture(child.stdout);
  const stderr = capture(child.stderr);
  let timeout: NodeJS.Timeout | undefined;
  try {
    const exit = await Promise.race([
      new Promise<{ code: number | null; signal: NodeJS.Signals | null }>(
        (resolveExit, rejectExit) => {
          child.once("error", rejectExit);
          child.once("exit", (code, signal) => resolveExit({ code, signal }));
        },
      ),
      new Promise<never>((_resolve, rejectTimeout) => {
        timeout = setTimeout(
          () => rejectTimeout(new Error("Static build timeout")),
          timeoutMs,
        );
      }),
    ]);
    await Promise.all([stdout, stderr]);
    if (exit.code !== 0 || exit.signal !== null) {
      releaseFail("RELEASE_BUILD_FAILED");
    }
  } catch {
    await terminate(child);
    await Promise.all([stdout, stderr]);
    releaseFail("RELEASE_BUILD_FAILED");
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
  }
}

export async function buildIsolatedNextExport(input: Readonly<{
  sourceRoot: string;
  stagingRoot: string;
  workspaceRoot: string;
  publicSiteUrl: string;
  timeoutMs: number;
  noticeCount: number;
}>): Promise<string> {
  const workspaceRelative = relative(input.sourceRoot, input.workspaceRoot);
  if (
    !isAbsolute(input.sourceRoot) ||
    !isAbsolute(input.workspaceRoot) ||
    workspaceRelative === "" ||
    workspaceRelative === ".." ||
    workspaceRelative.startsWith(`..${sep}`) ||
    isAbsolute(workspaceRelative)
  ) {
    releaseFail("RELEASE_CONFIG_INVALID");
  }
  const nodeModules = join(input.sourceRoot, "node_modules");
  const nodeModulesStat = await lstat(nodeModules).catch(() =>
    releaseFail("RELEASE_CONFIG_INVALID"),
  );
  if (!nodeModulesStat.isDirectory()) releaseFail("RELEASE_CONFIG_INVALID");

  await mkdir(input.workspaceRoot, { recursive: false }).catch(() =>
    releaseFail("RELEASE_FILESYSTEM_FAILED"),
  );
  const homeRoot = join(dirname(input.stagingRoot), "home");
  await mkdir(homeRoot, { recursive: false }).catch(() =>
    releaseFail("RELEASE_FILESYSTEM_FAILED"),
  );
  for (const directory of ["src", "public"] as const) {
    const source = join(input.sourceRoot, directory);
    if (await exists(source)) {
      await cp(source, join(input.workspaceRoot, directory), {
        recursive: true,
        errorOnExist: true,
        force: false,
      });
    } else if (directory === "public") {
      await mkdir(join(input.workspaceRoot, directory));
    } else {
      releaseFail("RELEASE_CONFIG_INVALID");
    }
  }
  for (const file of [
    "package.json",
    "next.config.ts",
    "tsconfig.json",
  ]) {
    await cp(join(input.sourceRoot, file), join(input.workspaceRoot, file), {
      errorOnExist: true,
      force: false,
    }).catch(() => releaseFail("RELEASE_CONFIG_INVALID"));
  }

  const workspaceGenerated = join(input.workspaceRoot, "src", "generated");
  const workspacePublicGenerated = join(
    input.workspaceRoot,
    "public",
    "generated",
  );
  await rm(workspaceGenerated, { recursive: true, force: true });
  await rm(workspacePublicGenerated, { recursive: true, force: true });
  await cp(join(input.stagingRoot, "src", "generated"), workspaceGenerated, {
    recursive: true,
    errorOnExist: true,
    force: false,
  }).catch(() => releaseFail("RELEASE_INPUT_INVALID"));
  await cp(
    join(input.stagingRoot, "public", "generated"),
    workspacePublicGenerated,
    { recursive: true, errorOnExist: true, force: false },
  ).catch(() => releaseFail("RELEASE_INPUT_INVALID"));
  if (!Number.isSafeInteger(input.noticeCount) || input.noticeCount < 0) {
    releaseFail("RELEASE_INPUT_INVALID");
  }
  if (input.noticeCount === 0) {
    await rm(join(input.workspaceRoot, "src", "app", "notices", "[slug]"), {
      recursive: true,
      force: true,
    });
  }
  await runNextBuild(
    input.sourceRoot,
    input.workspaceRoot,
    homeRoot,
    input.publicSiteUrl,
    input.timeoutMs,
  );
  const outputRoot = join(input.workspaceRoot, "out");
  const outputStat = await lstat(outputRoot).catch(() =>
    releaseFail("RELEASE_BUILD_FAILED"),
  );
  if (!outputStat.isDirectory() || outputStat.isSymbolicLink()) {
    releaseFail("RELEASE_BUILD_FAILED");
  }
  return outputRoot;
}
