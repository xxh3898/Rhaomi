import assert from "node:assert/strict";

const baseUrl = process.argv[2] ?? "http://backend:8080";
const email = process.env.RHAOMI_BOOTSTRAP_ADMIN_EMAIL;
const password = process.env.RHAOMI_BOOTSTRAP_ADMIN_PASSWORD;

assert(email, "smoke 관리자 email 환경변수가 필요합니다.");
assert(password, "smoke 관리자 password 환경변수가 필요합니다.");

const cookies = new Map();
let observedSessionCookie = "";

function cookieHeader() {
  return [...cookies.entries()].map(([name, value]) => `${name}=${value}`).join("; ");
}

function captureCookies(response) {
  const setCookies = response.headers.getSetCookie();
  for (const setCookie of setCookies) {
    const [pair] = setCookie.split(";", 1);
    const separator = pair.indexOf("=");
    const name = pair.slice(0, separator);
    const value = pair.slice(separator + 1);

    if (name === "RHAOMI_SESSION") {
      observedSessionCookie = setCookie;
    }

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

const anonymousMe = await request("/api/admin/auth/me");
assert.equal(anonymousMe.status, 401);
assert.match(anonymousMe.headers.get("content-type") ?? "", /^application\/json/i);
assert.equal(anonymousMe.headers.get("access-control-allow-origin"), null);

const csrfResponse = await request("/api/admin/auth/csrf");
assert.equal(csrfResponse.status, 200);
assert.match(csrfResponse.headers.get("content-type") ?? "", /^application\/json/i);
assert.equal(csrfResponse.headers.get("access-control-allow-origin"), null);
const csrf = await csrfResponse.json();
assert.equal(csrf.headerName, "X-CSRF-TOKEN");
assert(csrf.token);
assert.match(observedSessionCookie, /HttpOnly/i);
assert.match(observedSessionCookie, /SameSite=Lax/i);

const deniedLogin = await request("/api/admin/auth/login", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ email, password }),
});
assert.equal(deniedLogin.status, 403);

const login = await request("/api/admin/auth/login", {
  method: "POST",
  headers: {
    "Content-Type": "application/json",
    [csrf.headerName]: csrf.token,
  },
  body: JSON.stringify({ email, password }),
});
assert.equal(login.status, 200);
const loginBody = await login.text();
assert(!/password|hash/i.test(loginBody));

const me = await request("/api/admin/auth/me");
assert.equal(me.status, 200);
const meBody = await me.text();
assert(!/password|hash/i.test(meBody));

const freshCsrfResponse = await request("/api/admin/auth/csrf");
assert.equal(freshCsrfResponse.status, 200);
const freshCsrf = await freshCsrfResponse.json();
assert.equal(freshCsrf.headerName, "X-CSRF-TOKEN");
assert(freshCsrf.token);

const logout = await request("/api/admin/auth/logout", {
  method: "POST",
  headers: { [freshCsrf.headerName]: freshCsrf.token },
});
assert.equal(logout.status, 204);

const meAfterLogout = await request("/api/admin/auth/me");
assert.equal(meAfterLogout.status, 401);

console.log("Backend auth smoke passed");
