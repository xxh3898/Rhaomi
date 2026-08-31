import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));

async function source(path) {
  return readFile(join(projectRoot, path), "utf8");
}

function serviceBlock(compose, service, nextService) {
  const end = nextService ? `\n  ${nextService}:` : "\nvolumes:";
  const match = compose.match(
    new RegExp(`\\n  ${service}:\\n([\\s\\S]*?)${end}`, "u"),
  );
  assert.ok(match, `${service} service block이 필요합니다.`);
  return match[1];
}

function assertEveryBindMountDisablesHostPathCreation(compose) {
  const bindMountCount = (compose.match(/^\s+- type: bind\s*$/gmu) ?? []).length;
  const disabledCount =
    (compose.match(/^\s+create_host_path: false\s*$/gmu) ?? []).length;

  assert.ok(bindMountCount > 0);
  assert.equal(disabledCount, bindMountCount);
  assert.doesNotMatch(compose, /^\s+create_host_path: true\s*$/mu);
}

test("production Compose가 external same-image와 최소 service topology를 고정한다", async () => {
  const compose = await source("compose.production.yaml");
  const web = serviceBlock(compose, "rhaomi-web", "backend");
  const backend = serviceBlock(compose, "backend", "publisher");
  const publisher = serviceBlock(compose, "publisher", "postgres");
  const postgres = serviceBlock(compose, "postgres");

  assert.match(
    compose,
    /^name: \$\{RHAOMI_PRODUCTION_COMPOSE_PROJECT:\?[^}]+\}$/mu,
  );
  assert.deepEqual(
    [...compose.matchAll(/^  [a-z0-9-]+:\s*$/gmu)]
      .map((match) => match[0].trim())
      .filter((entry) =>
        ["rhaomi-web:", "backend:", "publisher:", "postgres:"].includes(entry),
      ),
    ["rhaomi-web:", "backend:", "publisher:", "postgres:"],
  );
  assert.doesNotMatch(compose, /^\s+build:/mu);
  assert.doesNotMatch(compose, /(?:^|:)latest(?:$|\s)/imu);
  assertEveryBindMountDisablesHostPathCreation(compose);
  assert.match(
    backend,
    /image: \$\{RHAOMI_PRODUCTION_IMAGE:\?[^}]+\}/u,
  );
  assert.match(
    publisher,
    /image: \$\{RHAOMI_PRODUCTION_IMAGE:\?[^}]+\}/u,
  );
  assert.match(
    web,
    /nginx:1\.31\.4-alpine3\.24@sha256:db35bfc6b2951e7f8a72db5db120288c127ffaeeb4a6d4b95a26fead017d5913/u,
  );
  assert.match(web, /user: "101:101"/u);
  assert.match(web, /\/var\/cache\/nginx:rw,noexec,nosuid,size=64m,uid=101,gid=101,mode=0750/u);
  assert.match(
    postgres,
    /postgres:18\.6-alpine3\.23@sha256:697c180dbf244d3ce4a8f4cbc0156cde840af055c1bf8b76aebe422a4822086f/u,
  );

  assert.match(web, /127\.0\.0\.1:\$\{RHAOMI_WEB_LOOPBACK_PORT:\?[^}]+\}:8080/u);
  assert.doesNotMatch(`${backend}\n${publisher}\n${postgres}`, /ports:/u);
  assert.match(web, /source: \/private\/var\/lib\/rhaomi\/public[\s\S]*target: \/srv\/rhaomi\/public[\s\S]*read_only: true/u);
  assert.match(backend, /source: \/private\/var\/lib\/rhaomi\/data\/media[\s\S]*target: \/var\/lib\/rhaomi\/media/u);
  assert.match(publisher, /source: \/private\/var\/lib\/rhaomi\/public[\s\S]*target: \/srv\/rhaomi\/public/u);
  assert.match(publisher, /source: \/private\/var\/lib\/rhaomi\/data\/media[\s\S]*target: \/var\/lib\/rhaomi\/media[\s\S]*read_only: true/u);
  assert.match(publisher, /source: \/private\/var\/lib\/rhaomi\/state\/publisher[\s\S]*target: \/var\/lib\/rhaomi\/publisher/u);
  assert.match(publisher, /source: \/private\/var\/lib\/rhaomi\/state\/locks[\s\S]*target: \/var\/lib\/rhaomi\/locks/u);
  assert.match(postgres, /type: volume[\s\S]*source: postgres-data[\s\S]*target: \/var\/lib\/postgresql/u);
  assert.doesNotMatch(postgres, /\/private\/|type: bind/u);
  assert.doesNotMatch(compose, /source: \/srv\/rhaomi|docker\.sock|network_mode:\s*host|privileged:\s*true/u);

  assert.match(backend, /SPRING_FLYWAY_ENABLED: "false"/u);
  assert.match(backend, /RHAOMI_SESSION_COOKIE_SECURE: "true"/u);
  assert.match(backend, /RHAOMI_BOOTSTRAP_ADMIN_ENABLED: "false"/u);
  assert.match(publisher, /SPRING_FLYWAY_ENABLED: "false"/u);
  assert.match(
    publisher,
    /--rhaomi\.publisher\.mode=control-loop/u,
  );
  assert.match(publisher, /BUILD_API_INTERNAL_URL: http:\/\/backend:8080/u);
  assert.match(backend, /RHAOMI_BUILD_SERVICE_TOKEN:/u);
  assert.doesNotMatch(web, /BUILD_API_CREDENTIAL|RHAOMI_BUILD_SERVICE_TOKEN|POSTGRES_PASSWORD/u);
  assert.doesNotMatch(postgres, /BUILD_API_CREDENTIAL|RHAOMI_BUILD_SERVICE_TOKEN/u);

  assert.match(compose, /web-backend:[\s\S]*internal: true/u);
  assert.match(compose, /build-internal:[\s\S]*internal: true/u);
  assert.match(compose, /data-internal:[\s\S]*internal: true/u);
  assert.match(compose, /loopback-edge:\s*\n  web-backend:/u);
  const volumeBlock = compose.match(/\nvolumes:\n([\s\S]*?)\nnetworks:/u)?.[1];
  assert.equal(volumeBlock, "  postgres-data:\n");
});

test("production Nginx가 static/admin 경계와 fail-closed route를 고정한다", async () => {
  const nginx = await source("infra/nginx/production.conf");

  assert.match(nginx, /root \/srv\/rhaomi\/public\/current;/u);
  assert.match(nginx, /location \^~ \/api\/admin\/[\s\S]*proxy_pass/u);
  for (const path of ["api/build", "internal", "actuator"]) {
    assert.match(nginx, new RegExp(`location \\^~ \\/${path}\\/`, "u"));
  }
  assert.match(nginx, /location = \/release-manifest\.json[\s\S]*return 404;/u);
  assert.match(nginx, /try_files \$uri \$uri\/ \$uri\/index\.html =404;/u);
  assert.doesNotMatch(nginx, /try_files[^;]*\/index\.html;/u);
  assert.match(nginx, /X-Robots-Tag "noindex, nofollow" always/u);
  assert.match(nginx, /immutable/u);
  assert.match(nginx, /log_format[\s\S]*\$request_method \$uri \$server_protocol/u);
  assert.doesNotMatch(nginx, /\$http_referer/u);
  assert.doesNotMatch(nginx, /\$request_uri|\$args|\$query_string/u);
  const hiddenPathGuard = 'if ($uri ~ "(^|/)[.]") {';
  assert.ok(nginx.includes(hiddenPathGuard));
  assert.ok(
    nginx.indexOf(hiddenPathGuard) < nginx.indexOf("\n    location "),
  );
  assert.doesNotMatch(nginx, /ssl_certificate|listen\s+443|cloudflare|websocket|upgrade/iu);
});

test("validation overlay만 task temp source와 schema bootstrap seam을 사용한다", async () => {
  const overlay = await source("compose.production.validation.yaml");

  assertEveryBindMountDisablesHostPathCreation(overlay);
  assert.match(overlay, /source: \$\{RHAOMI_PRODUCTION_VALIDATION_ROOT:\?[^}]+\}\/public/u);
  assert.match(overlay, /source: \$\{RHAOMI_PRODUCTION_VALIDATION_ROOT:\?[^}]+\}\/data\/media/u);
  assert.match(overlay, /source: \$\{RHAOMI_PRODUCTION_VALIDATION_ROOT:\?[^}]+\}\/state\/publisher/u);
  assert.match(overlay, /source: \$\{RHAOMI_PRODUCTION_VALIDATION_ROOT:\?[^}]+\}\/state\/locks/u);
  assert.match(overlay, /schema-bootstrap:/u);
  assert.match(overlay, /profiles: \["production-validation"\]/u);
  assert.match(overlay, /SPRING_FLYWAY_ENABLED: "true"/u);
  assert.match(overlay, /io\.homeserver\.cleanup\.task:/u);
  assert.doesNotMatch(overlay, /\/private\/var\/lib\/rhaomi|down -v|volume prune/u);
});

test("provisioning validator가 persistence·runtime 경계와 non-destructive cleanup을 검증한다", async () => {
  const [entrypoint, contract] = await Promise.all([
    source("scripts/validate-production-compose.sh"),
    source("scripts/validate-production-compose-contract.mjs"),
  ]);

  assert.match(entrypoint, /git -C .* rev-parse HEAD/u);
  assert.match(entrypoint, /docker compose[\s\S]*compose\.production\.yaml/u);
  assert.match(entrypoint, /config --format json/u);
  assert.match(entrypoint, /validate-production-compose-contract\.mjs/u);
  assert.match(entrypoint, /\/validation\/compose\.production\.yaml/u);
  assert.match(entrypoint, /\/validation\/compose\.production\.validation\.yaml/u);
  assert.match(entrypoint, /CREATE TABLE[\s\S]*validation_sentinel/u);
  assert.match(entrypoint, /compose_runtime down/u);
  assert.match(entrypoint, /sentinel/u);
  assert.match(entrypoint, /ps --all --quiet/u);
  assert.match(entrypoint, /write_service_failure_evidence/u);
  assert.match(entrypoint, /REDACTED_POSTGRES_PASSWORD/u);
  assert.match(entrypoint, /REDACTED_BUILD_TOKEN/u);
  assert.match(entrypoint, /prepare_linux_bind_ownership/u);
  assert.match(entrypoint, /restore_linux_bind_ownership/u);
  assert.match(entrypoint, /docker run --rm --network none --read-only/u);
  assert.match(entrypoint, /--user 0:0/u);
  assert.match(entrypoint, /--security-opt no-new-privileges=true/u);
  assert.match(entrypoint, /--cap-drop ALL/u);
  assert.match(entrypoint, /--cap-add CHOWN/u);
  assert.match(entrypoint, /validationBindOwnershipMode/u);
  assert.match(entrypoint, /\/private\/var\/tmp/u);
  assert.match(entrypoint, /Mounts|PortBindings|NetworkSettings/u);
  assert.match(entrypoint, /api\/build|internal|actuator|release-manifest/u);
  for (const path of [
    "admin/.synthetic-hidden",
    "_next/static/.synthetic-hidden",
    "generated/media/.synthetic-hidden",
  ]) {
    assert.ok(entrypoint.includes(path));
  }
  assert.match(entrypoint, /Referer: https:\/\/referrer\.invalid/u);
  assert.match(entrypoint, /docker logs/u);
  assert.match(entrypoint, /queryBearingRefererLogged=false/u);
  assert.doesNotMatch(
    entrypoint,
    /down -v|docker (?:volume|image) (?:rm|prune)|docker system prune|rm -rf/u,
  );
  assert.doesNotMatch(entrypoint, /echo .*TOKEN|printf .*PASSWORD/iu);

  assert.match(contract, /rhaomi-web/u);
  assert.match(contract, /backend/u);
  assert.match(contract, /publisher/u);
  assert.match(contract, /postgres/u);
  assert.match(contract, /validateBindSourceContract/u);
  assert.match(contract, /create_host_path: false/u);
  assert.match(contract, /assert\.notEqual\([\s\S]*create_host_path/u);
  assert.match(contract, /postgres-data/u);
  assert.match(contract, /BUILD_API_CREDENTIAL/u);
});

test("Hosted Validate가 기존 3-job에서 exact-head image를 Compose gate에 재사용한다", async () => {
  const workflow = await source(".github/workflows/validate.yml");
  const jobs = [...workflow.matchAll(/^  [a-z0-9-]+:\s*$/gmu)].map((match) =>
    match[0].trim(),
  );

  assert.deepEqual(jobs, ["frontend:", "backend:", "compose-smoke:"]);
  assert.equal(
    [...workflow.matchAll(/ref: \$\{\{ github\.event\.pull_request\.head\.sha \}\}/gu)]
      .length,
    3,
  );
  assert.match(workflow, /scripts\/validate-production-image\.sh/u);
  assert.match(workflow, /scripts\/validate-production-compose\.sh/u);
  assert.match(
    workflow,
    /RHAOMI_PRODUCTION_IMAGE: rhaomi-production-ci:\$\{\{ github\.event\.pull_request\.head\.sha \}\}/u,
  );
  assert.doesNotMatch(workflow, /github\.sha|packages:\s*write|ghcr\.io/iu);
});
