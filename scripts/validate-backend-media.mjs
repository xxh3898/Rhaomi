import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";

const baseUrl = process.argv[2] ?? "http://backend:8080";
const mode = process.argv[3] ?? "upload";
const encodedState = process.argv[4];
const email = process.env.RHAOMI_BOOTSTRAP_ADMIN_EMAIL;
const password = process.env.RHAOMI_BOOTSTRAP_ADMIN_PASSWORD;

assert(email, "smoke 관리자 email 환경변수가 필요합니다.");
assert(password, "smoke 관리자 password 환경변수가 필요합니다.");
assert(["upload", "verify"].includes(mode), "지원하지 않는 media smoke mode입니다.");

const cookies = new Map();

function cookieHeader() {
  return [...cookies.entries()].map(([name, value]) => `${name}=${value}`).join("; ");
}

function captureCookies(response) {
  for (const setCookie of response.headers.getSetCookie()) {
    const [pair] = setCookie.split(";", 1);
    const separator = pair.indexOf("=");
    const name = pair.slice(0, separator);
    const value = pair.slice(separator + 1);
    if (/max-age=0/i.test(setCookie) || value === "") {
      cookies.delete(name);
    } else {
      cookies.set(name, value);
    }
  }
}

async function request(path, options = {}) {
  const headers = new Headers(options.headers);
  if (cookies.size > 0) {
    headers.set("Cookie", cookieHeader());
  }
  const response = await fetch(`${baseUrl}${path}`, { ...options, headers, redirect: "manual" });
  captureCookies(response);
  return response;
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function assertNoPrivateMetadata(value) {
  const serialized = JSON.stringify(value).toLowerCase();
  for (const field of ["storagekey", "originalfilename", "sha256", "filesystem"]) {
    assert(!serialized.includes(field), `${field}가 API response에 노출됐습니다.`);
  }
}

function assertJpeg(bytes) {
  assert(bytes.length > 4);
  assert.equal(bytes[0], 0xff);
  assert.equal(bytes[1], 0xd8);
  assert.equal(bytes.at(-2), 0xff);
  assert.equal(bytes.at(-1), 0xd9);
  const text = bytes.toString("latin1");
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

async function login() {
  const csrfResponse = await request("/api/admin/auth/csrf");
  assert.equal(csrfResponse.status, 200);
  const csrf = await csrfResponse.json();
  assert.equal(csrf.headerName, "X-CSRF-TOKEN");
  assert(csrf.token);

  const loginResponse = await request("/api/admin/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify({ email, password }),
  });
  assert.equal(loginResponse.status, 200);
  return csrf;
}

async function readContent(id) {
  const response = await request(`/api/admin/media/${id}/content`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("content-type"), "image/jpeg");
  assert.equal(response.headers.get("cache-control"), "private, no-store");
  assert.equal(response.headers.get("x-content-type-options"), "nosniff");
  const bytes = Buffer.from(await response.arrayBuffer());
  assert.equal(Number(response.headers.get("content-length")), bytes.length);
  assertJpeg(bytes);
  return bytes;
}

await login();

if (mode === "upload") {
  const source = await readFile("/workspace/media-fixtures/synthetic-orientation-metadata.heic");
  const form = new FormData();
  form.append("file", new Blob([source], { type: "image/heic" }), "synthetic.heic");
  const csrfResponse = await request("/api/admin/auth/csrf");
  const csrf = await csrfResponse.json();
  const upload = await request("/api/admin/media", {
    method: "POST",
    headers: { [csrf.headerName]: csrf.token },
    body: form,
  });
  assert.equal(upload.status, 201);
  const metadata = await upload.json();
  assert.equal(metadata.status, "active");
  assert.equal(metadata.sourceContentType, "image/heic");
  assert.equal(metadata.contentType, "image/jpeg");
  assert.equal(metadata.width, 48);
  assert.equal(metadata.height, 64);
  assertNoPrivateMetadata(metadata);

  const bytes = await readContent(metadata.id);
  const state = {
    id: metadata.id,
    sha256: sha256(bytes),
    byteSize: bytes.length,
    width: metadata.width,
    height: metadata.height,
  };
  console.log(`MEDIA_STATE=${Buffer.from(JSON.stringify(state)).toString("base64url")}`);
  console.log(`Backend media upload smoke passed: id=${metadata.id} bytes=${bytes.length}`);
} else {
  assert(encodedState, "verify mode에는 upload state가 필요합니다.");
  const state = JSON.parse(Buffer.from(encodedState, "base64url").toString("utf8"));
  const detail = await request(`/api/admin/media/${state.id}`);
  assert.equal(detail.status, 200);
  const metadata = await detail.json();
  assert.equal(metadata.width, state.width);
  assert.equal(metadata.height, state.height);
  assertNoPrivateMetadata(metadata);

  const bytes = await readContent(state.id);
  assert.equal(bytes.length, state.byteSize);
  assert.equal(sha256(bytes), state.sha256);
  console.log(`Backend media restart persistence smoke passed: id=${state.id} bytes=${bytes.length}`);
}
