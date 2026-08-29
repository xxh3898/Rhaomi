import assert from "node:assert/strict";

const baseUrl = process.argv[2] ?? "http://gateway:3000";
const mode = process.argv[3] ?? "normal";

assert(["normal", "upstream-unavailable"].includes(mode));

async function request(path, options = {}) {
  return fetch(`${baseUrl}${path}`, { ...options, redirect: "manual" });
}

function assertNoCors(response) {
  assert.equal(response.headers.get("access-control-allow-origin"), null);
}

if (mode === "upstream-unavailable") {
  const response = await request("/api/admin/auth/csrf");
  const body = await response.text();

  assert(response.status >= 500, `backend 중단 중 API status가 ${response.status}입니다.`);
  assert.notEqual(response.status, 200);
  assert(!body.includes("라오미펫 관리자"), "API 장애가 frontend HTML로 대체됐습니다.");
  assertNoCors(response);
  console.log(`Gateway upstream failure contract passed: status=${response.status}`);
} else {
  const home = await request("/");
  assert.equal(home.status, 200);
  assert.match(home.headers.get("content-type") ?? "", /^text\/html/i);
  assert.match(await home.text(), /라오미펫/);
  assertNoCors(home);

  const admin = await request("/admin/");
  assert.equal(admin.status, 200);
  assert.match(admin.headers.get("content-type") ?? "", /^text\/html/i);
  assert.match(await admin.text(), /라오미펫 관리자/);
  assertNoCors(admin);

  const csrf = await request("/api/admin/auth/csrf");
  assert.equal(csrf.status, 200);
  assert.match(csrf.headers.get("content-type") ?? "", /^application\/json/i);
  assert.equal((await csrf.json()).headerName, "X-CSRF-TOKEN");
  assertNoCors(csrf);

  const unknownApi = await request("/api/admin/not-a-route");
  assert.equal(unknownApi.status, 401);
  assert.match(unknownApi.headers.get("content-type") ?? "", /^application\/json/i);
  assert(!((await unknownApi.text()).includes("라오미펫")));
  assertNoCors(unknownApi);

  const nginxServerHeader = admin.headers.get("server") ?? "";
  assert(!/nginx\/\d/i.test(nginxServerHeader), "Nginx version이 노출됐습니다.");

  const form = new FormData();
  form.append(
    "file",
    new Blob([new Uint8Array(20 * 1024 * 1024)], {
      type: "application/octet-stream",
    }),
    "twenty-mebibytes.bin",
  );
  const uploadBoundary = await request("/api/admin/media", {
    method: "POST",
    body: form,
  });
  assert.notEqual(
    uploadBoundary.status,
    413,
    "gateway가 20 MiB source request를 차단했습니다.",
  );
  assert([401, 403].includes(uploadBoundary.status));
  assertNoCors(uploadBoundary);

  console.log(
    `Gateway routing contract passed: home=${home.status} admin=${admin.status} api=${csrf.status} upload=${uploadBoundary.status}`,
  );
}
