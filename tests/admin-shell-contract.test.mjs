import assert from "node:assert/strict";
import { access, readdir, readFile } from "node:fs/promises";
import { dirname, extname, join, relative } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const sourceRoot = join(projectRoot, "src");

async function collectFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(
    entries.map((entry) => {
      const path = join(directory, entry.name);
      return entry.isDirectory() ? collectFiles(path) : [path];
    }),
  );
  return nested.flat();
}

async function assertMissing(path) {
  await assert.rejects(access(path));
}

test("admin route를 Static Export client shell과 검색 제외 metadata로 고정한다", async () => {
  const page = await readFile(join(sourceRoot, "app", "admin", "page.tsx"), "utf8");
  const shell = await readFile(
    join(sourceRoot, "app", "admin", "_components", "AdminAuthShell.tsx"),
    "utf8",
  );

  assert.match(page, /robots\s*:\s*\{/);
  assert.match(page, /index\s*:\s*false/);
  assert.match(page, /follow\s*:\s*false/);
  assert.match(page, /noarchive\s*:\s*true/);
  assert.match(shell, /^["']use client["'];/m);
  assert.match(shell, /checking/);
  assert.match(shell, /authenticated/);
  assert.match(shell, /anonymous/);
  assert.match(shell, /submitting/);
  assert.match(shell, /second-factor/);
  assert.match(shell, /RECOVERY_ROTATION_REQUIRED/);
  assert.match(shell, /prepareSessionCsrf/);
  assert.match(shell, /unavailable/);
  assert.doesNotMatch(shell, /dangerouslySetInnerHTML/);
});

test("browser production source에 credential persistence와 absolute backend URL을 두지 않는다", async () => {
  const files = (await collectFiles(sourceRoot)).filter((path) => {
    const extension = extname(path);
    return [".ts", ".tsx", ".js", ".jsx"].includes(extension) && !path.includes(".test.");
  });

  for (const file of files) {
    const source = await readFile(file, "utf8");
    const displayPath = relative(projectRoot, file);

    assert.doesNotMatch(source, /\b(?:localStorage|sessionStorage|indexedDB)\b/, displayPath);
    assert.doesNotMatch(source, /document\.cookie/, displayPath);
    assert.doesNotMatch(source, /location\.(?:search|hash)/, displayPath);
    assert.doesNotMatch(source, /https?:\/\/(?:localhost|127\.0\.0\.1|backend)(?::\d+)?/i, displayPath);
    assert.doesNotMatch(source, /console\.(?:log|debug|info|warn|error)/, displayPath);
  }
});

test("Middleware와 server runtime 인증 경계를 추가하지 않는다", async () => {
  for (const filename of ["middleware.ts", "middleware.js", "proxy.ts", "proxy.js"]) {
    await assertMissing(join(projectRoot, filename));
    await assertMissing(join(sourceRoot, filename));
  }

  const files = (await collectFiles(sourceRoot)).filter((path) => {
    const extension = extname(path);
    return [".ts", ".tsx", ".js", ".jsx"].includes(extension) && !path.includes(".test.");
  });
  for (const file of files) {
    const source = await readFile(file, "utf8");
    const displayPath = relative(projectRoot, file);
    assert.doesNotMatch(source, /^["']use server["'];/m, displayPath);
    assert.doesNotMatch(source, /from\s+["']next\/(?:headers|server)["']/, displayPath);
  }
});

test("admin API client 경계를 relative same-origin과 no-store로 고정한다", async () => {
  const source = await readFile(join(sourceRoot, "features", "admin-auth", "api.ts"), "utf8");
  const mediaApi = await readFile(
    join(sourceRoot, "features", "admin-media", "api.ts"),
    "utf8",
  );
  const mediaManager = await readFile(
    join(sourceRoot, "features", "admin-media", "AdminMediaManager.tsx"),
    "utf8",
  );
  const mediaPicker = await readFile(
    join(sourceRoot, "features", "admin-media", "AdminMediaPicker.tsx"),
    "utf8",
  );
  const shopApi = await readFile(
    join(sourceRoot, "features", "admin-shop-settings", "api.ts"),
    "utf8",
  );
  const shopManager = await readFile(
    join(
      sourceRoot,
      "features",
      "admin-shop-settings",
      "AdminShopSettingsManager.tsx",
    ),
    "utf8",
  );
  const breedApi = await readFile(
    join(sourceRoot, "features", "admin-breed", "api.ts"),
    "utf8",
  );
  const serviceApi = await readFile(
    join(sourceRoot, "features", "admin-service", "api.ts"),
    "utf8",
  );
  const breedManager = await readFile(
    join(sourceRoot, "features", "admin-breed", "AdminBreedManager.tsx"),
    "utf8",
  );
  const serviceManager = await readFile(
    join(sourceRoot, "features", "admin-service", "AdminServiceManager.tsx"),
    "utf8",
  );
  const galleryApi = await readFile(
    join(sourceRoot, "features", "admin-gallery", "api.ts"),
    "utf8",
  );
  const galleryManager = await readFile(
    join(
      sourceRoot,
      "features",
      "admin-gallery",
      "AdminGalleryManager.tsx",
    ),
    "utf8",
  );

  assert.match(source, /["']\/api\/admin\//);
  assert.match(source, /credentials:\s*["']same-origin["']/);
  assert.match(source, /cache:\s*["']no-store["']/);
  assert.doesNotMatch(source, /localStorage|sessionStorage|indexedDB|document\.cookie/);
  assert.match(mediaApi, /["']\/api\/admin\/media["']/);
  assert.match(mediaApi, /requestMultipartMutation/);
  assert.match(mediaApi, /requestAuthenticatedBlob/);
  assert.doesNotMatch(mediaApi, /https?:\/\//);
  assert.doesNotMatch(mediaManager, /src=[{]?["']\/api\/admin\/media/);
  assert.match(shopApi, /["']\/api\/admin\/shop-settings["']/);
  assert.match(shopApi, /requestAuthenticatedJson/);
  assert.match(shopApi, /requestJsonMutation/);
  assert.doesNotMatch(shopApi, /https?:\/\//);
  assert.match(shopManager, /AdminMediaPicker/);
  assert.match(shopManager, /buildShopSettingsRequest/);
  assert.doesNotMatch(shopManager, /(?:POST|PATCH|DELETE)["']/);
  assert.match(mediaPicker, /AdminMediaPreview/);
  assert.doesNotMatch(mediaPicker, /src=[{]?["']\/api\/admin\/media/);
  assert.match(breedApi, /["']\/api\/admin\/breeds["']/);
  assert.match(serviceApi, /["']\/api\/admin\/services["']/);
  assert.match(breedApi, /requestAuthenticatedJson/);
  assert.match(serviceApi, /requestAuthenticatedJson/);
  assert.match(breedApi, /requestJsonMutation/);
  assert.match(serviceApi, /requestJsonMutation/);
  assert.doesNotMatch(breedManager, /(?:PATCH|DELETE)["']/);
  assert.doesNotMatch(serviceManager, /(?:PATCH|DELETE)["']/);
  assert.match(galleryApi, /["']\/api\/admin\/gallery-items["']/);
  assert.match(galleryApi, /requestAuthenticatedJson/);
  assert.match(galleryApi, /requestJsonMutation/);
  assert.match(galleryManager, /buildGalleryCreateRequest/);
  assert.match(galleryManager, /buildGalleryUpdateRequest/);
  assert.match(galleryManager, /selectionPolicy="all-existing"/);
  assert.match(galleryManager, /setItems\(response\)/);
  assert.doesNotMatch(galleryManager, /(?:PATCH|DELETE)["']/);
});

test("admin content UI의 320px, touch target, focus와 reduced-motion 계약을 고정한다", async () => {
  const shellCss = await readFile(
    join(sourceRoot, "app", "admin", "admin.module.css"),
    "utf8",
  );
  const dashboardCss = await readFile(
    join(sourceRoot, "features", "admin-dashboard", "AdminDashboard.module.css"),
    "utf8",
  );
  const mediaCss = await readFile(
    join(sourceRoot, "features", "admin-media", "AdminMediaManager.module.css"),
    "utf8",
  );
  const pickerCss = await readFile(
    join(sourceRoot, "features", "admin-media", "AdminMediaPicker.module.css"),
    "utf8",
  );
  const shopCss = await readFile(
    join(
      sourceRoot,
      "features",
      "admin-shop-settings",
      "AdminShopSettingsManager.module.css",
    ),
    "utf8",
  );
  const contentCss = await readFile(
    join(
      sourceRoot,
      "features",
      "admin-content",
      "AdminContentManager.module.css",
    ),
    "utf8",
  );
  const galleryCss = await readFile(
    join(
      sourceRoot,
      "features",
      "admin-gallery",
      "AdminGalleryManager.module.css",
    ),
    "utf8",
  );

  assert.match(shellCss, /env\(safe-area-inset-top\)/);
  assert.match(shellCss, /env\(safe-area-inset-right\)/);
  assert.match(shellCss, /env\(safe-area-inset-bottom\)/);
  assert.match(shellCss, /env\(safe-area-inset-left\)/);
  assert.match(dashboardCss, /@media \(max-width: 359px\)[\s\S]*grid-template-columns: 1fr/);
  assert.match(mediaCss, /min-height: 2\.75rem/);
  assert.match(mediaCss, /@media \(max-width: 560px\)[\s\S]*grid-template-columns: 1fr/);
  assert.match(mediaCss, /:focus-visible|:focus-within/);
  assert.match(mediaCss, /@media \(prefers-reduced-motion: reduce\)/);
  assert.match(pickerCss, /min-height: 2\.75rem/);
  assert.match(pickerCss, /@media \(max-width: 560px\)[\s\S]*grid-template-columns: 1fr/);
  assert.match(pickerCss, /:focus-visible/);
  assert.match(pickerCss, /@media \(prefers-reduced-motion: reduce\)/);
  assert.match(shopCss, /min-height: 2\.75rem/);
  assert.match(shopCss, /@media \(max-width: 359px\)/);
  assert.match(shopCss, /:focus-visible/);
  assert.match(shopCss, /@media \(prefers-reduced-motion: reduce\)/);
  assert.match(contentCss, /min-height: 2\.75rem/);
  assert.match(contentCss, /@media \(max-width: 359px\)/);
  assert.match(contentCss, /:focus-visible/);
  assert.match(contentCss, /@media \(prefers-reduced-motion: reduce\)/);
  assert.match(galleryCss, /min-height: 2\.75rem/);
  assert.match(galleryCss, /@media \(max-width: 359px\)/);
  assert.match(galleryCss, /:focus-visible/);
  assert.match(galleryCss, /@media \(prefers-reduced-motion: reduce\)/);
});

test("gateway image, routing, HMR, upload limit과 proxy header 계약을 고정한다", async () => {
  const compose = await readFile(join(projectRoot, "compose.dev.yaml"), "utf8");
  const nginx = await readFile(join(projectRoot, "infra", "nginx", "dev.conf"), "utf8");
  const frontendSection = compose.match(/\n  frontend:\n([\s\S]*?)\n  gateway:\n/)?.[1] ?? "";
  const gatewaySection = compose.match(/\n  gateway:\n([\s\S]*?)\n  backend:\n/)?.[1] ?? "";

  assert.doesNotMatch(frontendSection, /\n\s+ports:/);
  assert.match(
    gatewaySection,
    /nginx:1\.31\.4-alpine3\.24@sha256:db35bfc6b2951e7f8a72db5db120288c127ffaeeb4a6d4b95a26fead017d5913/,
  );
  assert.match(gatewaySection, /127\.0\.0\.1:3000:3000/);
  assert.match(nginx, /server_tokens\s+off;/);
  assert.match(nginx, /client_max_body_size\s+21m;/);
  assert.match(nginx, /location \^~ \/api\/build\/\s*\{\s*return 404;/);
  assert.match(nginx, /location \^~ \/api\//);
  assert(
    nginx.indexOf("location ^~ /api/build/") < nginx.indexOf("location ^~ /api/ {"),
    "build namespace deny가 일반 API proxy보다 먼저 선언돼야 합니다.",
  );
  assert.match(nginx, /proxy_pass http:\/\/rhaomi_backend;/);
  assert.match(nginx, /proxy_set_header Host \$http_host;/);
  assert.match(nginx, /proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;/);
  assert.match(nginx, /proxy_set_header X-Forwarded-Proto \$scheme;/);
  assert.match(nginx, /proxy_set_header Upgrade \$http_upgrade;/);
  assert.match(nginx, /proxy_set_header Connection \$connection_upgrade;/);
  assert.doesNotMatch(nginx, /Access-Control-Allow-Origin|proxy_cookie_domain/i);
});

test("gateway와 PostgreSQL network를 직접 공유하지 않는다", async () => {
  const compose = await readFile(join(projectRoot, "compose.dev.yaml"), "utf8");
  const gatewaySection = compose.match(/\n  gateway:\n([\s\S]*?)\n  backend:\n/)?.[1] ?? "";
  const postgresSection = compose.match(/\n  postgres:\n([\s\S]*?)\n  smoke:\n/)?.[1] ?? "";

  assert.match(gatewaySection, /backend-gateway-internal/);
  assert.doesNotMatch(gatewaySection, /\n\s+- backend-internal\s/);
  assert.match(postgresSection, /backend-internal/);
  assert.doesNotMatch(postgresSection, /backend-gateway-internal|frontend-local/);
});
