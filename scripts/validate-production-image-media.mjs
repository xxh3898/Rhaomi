import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFile } from "node:fs/promises";

const require = createRequire("/opt/rhaomi/source/package.json");
const sharp = require("sharp");

const baseUrl = process.argv[2] ?? "http://backend:8080";
const fixtureRoot = process.argv[3] ?? "/fixtures";
const email = process.env.RHAOMI_BOOTSTRAP_ADMIN_EMAIL;
const password = process.env.RHAOMI_BOOTSTRAP_ADMIN_PASSWORD;

assert(email, "production image smoke 관리자 email이 필요합니다.");
assert(password, "production image smoke 관리자 password가 필요합니다.");

const cookies = new Map();

function captureCookies(response) {
  for (const value of response.headers.getSetCookie()) {
    const [pair] = value.split(";", 1);
    const separator = pair.indexOf("=");
    const name = pair.slice(0, separator);
    const cookieValue = pair.slice(separator + 1);
    if (/max-age=0/iu.test(value) || cookieValue.length === 0) {
      cookies.delete(name);
    } else {
      cookies.set(name, cookieValue);
    }
  }
}

async function request(path, options = {}) {
  const headers = new Headers(options.headers);
  if (cookies.size > 0) {
    headers.set(
      "Cookie",
      [...cookies.entries()].map(([name, value]) => `${name}=${value}`).join("; "),
    );
  }
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers,
    redirect: "manual",
  });
  captureCookies(response);
  return response;
}

async function waitForBackend() {
  for (let attempt = 0; attempt < 90; attempt += 1) {
    try {
      const response = await request("/actuator/health");
      if (response.status === 200) return;
    } catch {
      // The final image may still be initializing the JVM or Flyway.
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  assert.fail("production image backend가 제한 시간 안에 준비되지 않았습니다.");
}

async function csrf() {
  const response = await request("/api/admin/auth/csrf");
  assert.equal(response.status, 200);
  const value = await response.json();
  assert.equal(value.headerName, "X-CSRF-TOKEN");
  assert.match(value.token, /^\S+$/u);
  return value;
}

async function login() {
  const token = await csrf();
  const response = await request("/api/admin/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      [token.headerName]: token.token,
    },
    body: JSON.stringify({ email, password }),
  });
  assert.equal(response.status, 200);
}

async function upload(filename, contentType, bytes) {
  const token = await csrf();
  const form = new FormData();
  form.append("file", new Blob([bytes], { type: contentType }), filename);
  return request("/api/admin/media", {
    method: "POST",
    headers: { [token.headerName]: token.token },
    body: form,
  });
}

async function assertNormalizedStill(filename, contentType, sourceContentType, bytes) {
  const response = await upload(filename, contentType, bytes);
  if (response.status !== 201) {
    assert.fail(`정상 이미지 upload가 실패했습니다: HTTP ${response.status}`);
  }
  const media = await response.json();
  assert.equal(media.sourceContentType, sourceContentType);
  assert.equal(media.contentType, "image/jpeg");
  assert.equal(media.width, 48);
  assert.equal(media.height, 64);

  const content = await request(`/api/admin/media/${media.id}/content`);
  assert.equal(content.status, 200);
  assert.equal(content.headers.get("content-type"), "image/jpeg");
  const normalized = Buffer.from(await content.arrayBuffer());
  const metadata = await sharp(normalized).metadata();
  assert.equal(metadata.format, "jpeg");
  assert.equal(metadata.width, 48);
  assert.equal(metadata.height, 64);
  assert.equal(metadata.space, "srgb");
  assert.equal(metadata.orientation, undefined);
  assert.equal(metadata.exif, undefined);
  assert.equal(metadata.xmp, undefined);
  assert.equal(metadata.iptc, undefined);

  const text = normalized.toString("latin1");
  for (const marker of [
    "Exif",
    "application/rdf+xml",
    "SYNTHETIC-ONLY",
    "GPSLatitude",
    "DisplayP3",
  ]) {
    assert(!text.includes(marker), `${marker} metadata가 normalized JPEG에 남았습니다.`);
  }
}

async function assertUploadError(filename, contentType, bytes, status, code) {
  const response = await upload(filename, contentType, bytes);
  assert.equal(response.status, status);
  const error = await response.json();
  assert.equal(error.code, code);
  assert.deepEqual(Object.keys(error).sort(), ["code", "message"]);
}

function minimalAvif() {
  const bytes = Buffer.alloc(24);
  bytes.writeUInt32BE(bytes.length, 0);
  bytes.write("ftyp", 4, "ascii");
  bytes.write("avif", 8, "ascii");
  bytes.writeUInt32BE(0, 12);
  bytes.write("avif", 16, "ascii");
  bytes.write("mif1", 20, "ascii");
  return bytes;
}

await waitForBackend();
await login();

const [heic, heif, sequence] = await Promise.all([
  readFile(`${fixtureRoot}/synthetic-orientation-metadata.heic`),
  readFile(`${fixtureRoot}/synthetic-orientation-metadata.heif`),
  readFile(`${fixtureRoot}/synthetic-sequence-branded.heic`),
]);

await assertNormalizedStill("iphone.heic", "image/heic", "image/heic", heic);
await assertNormalizedStill("generic.heif", "image/heif", "image/heif", heif);
await assertUploadError(
  "sequence.heic",
  "image/heic",
  sequence,
  422,
  "MEDIA_INVALID_IMAGE",
);
await assertUploadError(
  "unsupported.avif",
  "image/avif",
  minimalAvif(),
  415,
  "MEDIA_TYPE_UNSUPPORTED",
);

console.log(
  "Production image media smoke passed: HEIC/HEIF normalized, sequence/AVIF rejected",
);
