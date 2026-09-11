import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));

async function source(path) {
  return readFile(join(projectRoot, path), "utf8");
}

function shellFunction(script, name) {
  const match = script.match(
    new RegExp(`^${name}\\(\\) \\{\\n([\\s\\S]*?)^\\}$`, "mu"),
  );
  assert.ok(match, `${name} shell function이 필요합니다.`);
  return `${name}() {\n${match[1]}}`;
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
  const publisher = serviceBlock(compose, "publisher", "migration");
  const migration = serviceBlock(compose, "migration", "schema-validate");
  const schemaValidate = serviceBlock(compose, "schema-validate", "initial-admin");
  const initialAdmin = serviceBlock(compose, "initial-admin", "initial-content");
  const initialContent = serviceBlock(compose, "initial-content", "backup-tool");
  const backupTool = serviceBlock(compose, "backup-tool", "backup-verifier");
  const backupVerifier = serviceBlock(compose, "backup-verifier", "postgres");
  const postgres = serviceBlock(compose, "postgres");

  assert.match(
    compose,
    /^name: \$\{RHAOMI_PRODUCTION_COMPOSE_PROJECT:\?[^}]+\}$/mu,
  );
  assert.deepEqual(
    [...compose.matchAll(/^  [a-z0-9-]+:\s*$/gmu)]
      .map((match) => match[0].trim())
      .filter((entry) =>
        [
          "rhaomi-web:",
          "backend:",
          "publisher:",
          "migration:",
          "schema-validate:",
          "initial-admin:",
          "initial-content:",
          "backup-tool:",
          "backup-verifier:",
          "postgres:",
        ].includes(entry),
      ),
    [
      "rhaomi-web:",
      "backend:",
      "publisher:",
      "migration:",
      "schema-validate:",
      "initial-admin:",
      "initial-content:",
      "backup-tool:",
      "backup-verifier:",
      "postgres:",
    ],
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
  assert.match(web, /homeops\.managed: "true"/u);
  assert.doesNotMatch(
    `${backend}\n${publisher}\n${migration}\n${schemaValidate}\n${initialAdmin}\n${initialContent}\n${backupTool}\n${backupVerifier}\n${postgres}`,
    /homeops\.managed/u,
  );
  assert.match(web, /\/var\/cache\/nginx:rw,noexec,nosuid,size=64m,uid=101,gid=101,mode=0750/u);
  assert.match(
    postgres,
    /postgres:18\.6-alpine3\.23@sha256:697c180dbf244d3ce4a8f4cbc0156cde840af055c1bf8b76aebe422a4822086f/u,
  );

  assert.match(web, /127\.0\.0\.1:\$\{RHAOMI_WEB_LOOPBACK_PORT:\?[^}]+\}:8080/u);
  assert.doesNotMatch(
    `${backend}\n${publisher}\n${initialAdmin}\n${initialContent}\n${postgres}`,
    /ports:/u,
  );
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
  assert.match(backend, /RHAOMI_ADMIN_WEBAUTHN_REQUIRED: "true"/u);
  assert.match(backend, /RHAOMI_WEBAUTHN_RP_ID: \$\{RHAOMI_WEBAUTHN_RP_ID:\?[^}]+\}/u);
  assert.match(backend, /RHAOMI_WEBAUTHN_ORIGIN: \$\{RHAOMI_WEBAUTHN_ORIGIN:\?[^}]+\}/u);
  assert.match(backend, /RHAOMI_WEBAUTHN_CHALLENGE_TTL: "5m"/u);
  assert.match(publisher, /SPRING_FLYWAY_ENABLED: "false"/u);
  assert.match(
    publisher,
    /--rhaomi\.publisher\.mode=control-loop/u,
  );
  assert.match(publisher, /BUILD_API_INTERNAL_URL: http:\/\/backend:8080/u);
  assert.match(migration, /profiles: \["production-task"\]/u);
  assert.match(migration, /--rhaomi\.production-task=migrate/u);
  assert.match(migration, /SPRING_FLYWAY_ENABLED: "true"/u);
  assert.match(migration, /SPRING_JPA_HIBERNATE_DDL_AUTO: validate/u);
  assert.match(schemaValidate, /profiles: \["production-task"\]/u);
  assert.match(schemaValidate, /--rhaomi\.production-task=schema-validate/u);
  assert.match(schemaValidate, /SPRING_FLYWAY_ENABLED: "false"/u);
  assert.match(schemaValidate, /SPRING_JPA_HIBERNATE_DDL_AUTO: validate/u);
  assert.match(initialAdmin, /profiles: \["production-task"\]/u);
  assert.match(initialAdmin, /--rhaomi\.production-task=initial-admin/u);
  assert.match(initialAdmin, /SPRING_FLYWAY_ENABLED: "false"/u);
  assert.match(initialAdmin, /SPRING_JPA_HIBERNATE_DDL_AUTO: validate/u);
  assert.match(initialAdmin, /RHAOMI_BOOTSTRAP_ADMIN_ENABLED: "false"/u);
  assert.match(initialAdmin, /read_only: true/u);
  assert.match(initialAdmin, /cap_drop: \["ALL"\]/u);
  assert.match(initialAdmin, /no-new-privileges:true/u);
  assert.match(initialAdmin, /networks:\s*\n\s+- data-internal/u);
  assert.doesNotMatch(
    initialAdmin,
    /volumes:|RHAOMI_(?:INITIAL_ADMIN|BOOTSTRAP_ADMIN)_(?:EMAIL|PASSWORD)|RHAOMI_WEBAUTHN|BUILD_API_CREDENTIAL|RHAOMI_BUILD_SERVICE_TOKEN/u,
  );
  assert.match(initialContent, /profiles: \["production-task"\]/u);
  assert.match(initialContent, /--rhaomi\.production-task=initial-content/u);
  assert.match(initialContent, /SPRING_FLYWAY_ENABLED: "false"/u);
  assert.match(initialContent, /SPRING_JPA_HIBERNATE_DDL_AUTO: validate/u);
  assert.match(initialContent, /RHAOMI_BOOTSTRAP_ADMIN_ENABLED: "false"/u);
  assert.match(initialContent, /read_only: true/u);
  assert.match(initialContent, /cap_drop: \["ALL"\]/u);
  assert.doesNotMatch(initialContent, /cap_add:|^\s+user:/mu);
  assert.match(initialContent, /no-new-privileges:true/u);
  assert.match(initialContent, /networks:\s*\n\s+- data-internal/u);
  assert.match(
    initialContent,
    /source: \/private\/var\/lib\/rhaomi\/state\/initial-content[\s\S]*target: \/run\/rhaomi-initial-content[\s\S]*read_only: true/u,
  );
  assert.match(
    initialContent,
    /source: \/private\/var\/lib\/rhaomi\/data\/media[\s\S]*target: \/var\/lib\/rhaomi\/media/u,
  );
  assert.doesNotMatch(
    initialContent,
    /RHAOMI_(?:INITIAL_ADMIN|BOOTSTRAP_ADMIN)_(?:EMAIL|PASSWORD)|RHAOMI_WEBAUTHN|BUILD_API_CREDENTIAL|RHAOMI_BUILD_SERVICE_TOKEN|\/srv\/rhaomi\/public|\/var\/lib\/rhaomi\/(?:publisher|locks|backup-repository|deploy-state)|docker\.sock/u,
  );
  assert.match(backupTool, /profiles: \["production-backup"\]/u);
  assert.match(backupVerifier, /profiles: \["production-backup"\]/u);
  assert.match(backupVerifier, /entrypoint: \["\/usr\/local\/bin\/rhaomi-backup-verifier"\]/u);
  assert.match(backupVerifier, /network_mode: none/u);
  assert.match(backupVerifier, /cap_drop: \["ALL"\]/u);
  assert.match(
    backupVerifier,
    /target: \/var\/lib\/rhaomi\/backup-repository[\s\S]*read_only: true/u,
  );
  assert.match(
    backupVerifier,
    /target: \/var\/lib\/rhaomi\/deploy-state[\s\S]*read_only: true/u,
  );
  assert.doesNotMatch(
    backupVerifier,
    /RHAOMI_BACKUP_MEDIA_ROOT|POSTGRES_PASSWORD|BUILD_SERVICE_TOKEN|docker\.sock|ports:/u,
  );
  assert.match(backend, /RHAOMI_BUILD_SERVICE_TOKEN:/u);
  assert.doesNotMatch(web, /BUILD_API_CREDENTIAL|RHAOMI_BUILD_SERVICE_TOKEN|POSTGRES_PASSWORD/u);
  assert.doesNotMatch(postgres, /BUILD_API_CREDENTIAL|RHAOMI_BUILD_SERVICE_TOKEN/u);
  assert.doesNotMatch(
    `${web}\n${publisher}\n${initialAdmin}\n${initialContent}\n${postgres}`,
    /RHAOMI_WEBAUTHN_(?:RP_ID|ORIGIN|RP_NAME)/u,
  );

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
  assert.match(nginx, /absolute_redirect off;/u);
  const adminProxy = nginx.match(
    /location \^~ \/api\/admin\/ \{([\s\S]*?)\n    \}/u,
  )?.[1];
  assert.ok(adminProxy, "/api/admin/** proxy location이 필요합니다.");
  assert.match(adminProxy, /proxy_pass/u);
  assert.match(adminProxy, /proxy_set_header X-Forwarded-Proto https;/u);
  assert.match(adminProxy, /proxy_set_header X-Forwarded-Port 443;/u);
  assert.doesNotMatch(
    adminProxy,
    /proxy_set_header X-Forwarded-(?:Proto|Port) \$/u,
  );
  assert.match(nginx, /location = \/admin[\s\S]*return 308 \/admin\/;/u);
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

test("validation overlay가 task temp source와 one-shot service label만 덮어쓴다", async () => {
  const overlay = await source("compose.production.validation.yaml");

  assertEveryBindMountDisablesHostPathCreation(overlay);
  assert.match(overlay, /source: \$\{RHAOMI_PRODUCTION_VALIDATION_ROOT:\?[^}]+\}\/public/u);
  assert.match(overlay, /source: \$\{RHAOMI_PRODUCTION_VALIDATION_ROOT:\?[^}]+\}\/data\/media/u);
  assert.match(overlay, /source: \$\{RHAOMI_PRODUCTION_VALIDATION_ROOT:\?[^}]+\}\/state\/publisher/u);
  assert.match(overlay, /source: \$\{RHAOMI_PRODUCTION_VALIDATION_ROOT:\?[^}]+\}\/state\/locks/u);
  assert.match(overlay, /migration:[\s\S]*labels: \*validation-labels/u);
  assert.match(overlay, /schema-validate:[\s\S]*labels: \*validation-labels/u);
  assert.match(overlay, /initial-admin:[\s\S]*labels: \*validation-labels/u);
  assert.match(overlay, /initial-content:[\s\S]*labels: \*validation-labels/u);
  assert.match(
    overlay,
    /initial-content:[\s\S]*state\/initial-content[\s\S]*target: \/run\/rhaomi-initial-content[\s\S]*read_only: true[\s\S]*data\/media[\s\S]*target: \/var\/lib\/rhaomi\/media/u,
  );
  assert.match(overlay, /backup-verifier:[\s\S]*labels: \*validation-labels/u);
  assert.match(
    overlay,
    /backup-verifier:[\s\S]*target: \/var\/lib\/rhaomi\/backup-repository[\s\S]*read_only: true[\s\S]*target: \/var\/lib\/rhaomi\/deploy-state[\s\S]*read_only: true/u,
  );
  assert.doesNotMatch(overlay, /schema-bootstrap|SPRING_FLYWAY_ENABLED/u);
  assert.match(overlay, /io\.homeserver\.cleanup\.task:/u);
  assert.doesNotMatch(overlay, /\/private\/var\/lib\/rhaomi|down -v|volume prune/u);
});

test("provisioning validator가 persistence·runtime 경계와 non-destructive cleanup을 검증한다", async () => {
  const [entrypoint, contract, initialContentControl] = await Promise.all([
    source("scripts/validate-production-compose.sh"),
    source("scripts/validate-production-compose-contract.mjs"),
    source("scripts/validate-production-initial-content.sh"),
  ]);

  assert.match(entrypoint, /git -C .* rev-parse HEAD/u);
  assert.match(entrypoint, /docker compose[\s\S]*compose\.production\.yaml/u);
  assert.match(entrypoint, /config --format json/u);
  assert.match(entrypoint, /validate-production-compose-contract\.mjs/u);
  assert.match(entrypoint, /\/validation\/compose\.production\.yaml/u);
  assert.match(entrypoint, /\/validation\/compose\.production\.validation\.yaml/u);
  assert.match(entrypoint, /CREATE TABLE[\s\S]*validation_sentinel/u);
  assert.match(entrypoint, /run --rm --no-deps migration/u);
  assert.match(entrypoint, /run --rm --no-deps schema-validate/u);
  assert.match(entrypoint, /verify_initial_admin_runtime_boundary/u);
  assert.match(entrypoint, /validate-production-initial-content\.sh/u);
  assert.match(entrypoint, /verify_initial_content_runtime_boundary/u);
  assert.match(entrypoint, /run --rm --no-deps initial-content/u);
  assert.match(entrypoint, /INITIAL_CONTENT_ADMIN_AUTHORITY_INVALID/u);
  assert.match(entrypoint, /initialContentPristineAuthorityMutation=0/u);
  assert.match(
    entrypoint,
    /grep -Eo 'INITIAL_CONTENT_\[A-Z0-9_\]\+'/u,
  );
  assert.match(
    entrypoint,
    /initial_content_evidence_file="\$evidence_dir\/production-initial-content-pristine-authority\.json"/u,
  );
  assert.doesNotMatch(
    entrypoint,
    /(?:cp|mv)[^\n]*initial-content-pristine-authority\.txt[^\n]*\$evidence_dir/u,
  );
  const initialContentBoundaryMatch = entrypoint.match(
    /verify_initial_content_runtime_boundary\(\) \{\n([\s\S]*?)\n\}/u,
  );
  assert.ok(initialContentBoundaryMatch);
  const initialContentBoundary = initialContentBoundaryMatch[1];
  const initialContentMutationCheckIndex = initialContentBoundary.indexOf(
    "initial-content pristine authority fail-close가 mutation 0을 보장하지 못했습니다.",
  );
  const initialContentEvidenceIndex = initialContentBoundary.indexOf(
    "write_initial_content_pristine_authority_evidence",
  );
  const initialContentCodeMismatchIndex = initialContentBoundary.indexOf(
    "initial-content failure code가 expectedCode와 다릅니다.",
  );
  const initialContentRemovalIndex = initialContentBoundary.indexOf(
    'docker container rm "$initial_content_id"',
  );
  const initialContentAbsenceIndex = initialContentBoundary.indexOf(
    'docker container inspect "$initial_content_id"',
  );
  assert.ok(initialContentMutationCheckIndex >= 0);
  assert.ok(initialContentEvidenceIndex >= 0);
  assert.ok(initialContentCodeMismatchIndex > initialContentEvidenceIndex);
  assert.ok(initialContentMutationCheckIndex > initialContentEvidenceIndex);
  assert.ok(initialContentRemovalIndex > initialContentMutationCheckIndex);
  assert.ok(initialContentAbsenceIndex > initialContentRemovalIndex);
  assert.match(
    initialContentBoundary,
    /media_state_before=\$\(runtime_media_content_digest\)[\s\S]*media_state_after=\$\(runtime_media_content_digest\)/u,
  );
  const runtimeMediaDigestMatch = entrypoint.match(
    /runtime_media_content_digest\(\) \{\n([\s\S]*?)\n\}/u,
  );
  assert.ok(runtimeMediaDigestMatch);
  const runtimeMediaDigest = runtimeMediaDigestMatch[1];
  assert.match(runtimeMediaDigest, /--network none --read-only/u);
  assert.match(runtimeMediaDigest, /--user 0:0/u);
  assert.match(runtimeMediaDigest, /--cap-drop ALL/u);
  assert.match(
    runtimeMediaDigest,
    /--volume "\$validation_root\/data\/media:\/validation\/media:ro"/u,
  );
  assert.match(runtimeMediaDigest, /find \/validation\/media -type f/u);
  assert.match(entrypoint, /compose_validation create --no-build initial-admin/u);
  assert.doesNotMatch(
    entrypoint,
    /compose_validation create --no-build --no-deps initial-admin/u,
  );
  assert.match(entrypoint, /run --rm --no-deps -T initial-admin/u);
  assert.match(entrypoint, /initialAdminNonInteractiveMutation=0/u);
  const initialAdminBoundaryMatch = entrypoint.match(
    /verify_initial_admin_runtime_boundary\(\) \{\n([\s\S]*?)\n\}/u,
  );
  assert.ok(initialAdminBoundaryMatch);
  const initialAdminBoundary = initialAdminBoundaryMatch[1];
  const mutationCheckIndex = initialAdminBoundary.indexOf(
    "non-interactive initial-admin fail-close가 mutation 0을 보장하지 못했습니다.",
  );
  const exactRemovalIndex = initialAdminBoundary.indexOf(
    'docker container rm "$initial_admin_id"',
  );
  const absenceCheckIndex = initialAdminBoundary.indexOf(
    'docker container inspect "$initial_admin_id"',
  );
  assert.ok(mutationCheckIndex >= 0);
  assert.ok(exactRemovalIndex > mutationCheckIndex);
  assert.ok(absenceCheckIndex > exactRemovalIndex);
  assert.doesNotMatch(
    initialAdminBoundary,
    /docker (?:system|container|volume|image|network) prune/u,
  );
  assert.match(entrypoint, /verify_writers_stopped/u);
  assert.match(entrypoint, /publicStaticDuringMaintenance=200/u);
  assert.match(entrypoint, /compose_runtime down/u);
  assert.match(entrypoint, /sentinel/u);
  assert.match(entrypoint, /ps --all --quiet/u);
  assert.match(entrypoint, /write_service_failure_evidence/u);
  assert.match(entrypoint, /REDACTED_POSTGRES_PASSWORD/u);
  assert.match(entrypoint, /REDACTED_BUILD_TOKEN/u);
  assert.match(entrypoint, /prepare_linux_bind_ownership/u);
  assert.match(entrypoint, /restore_linux_bind_ownership/u);
  assert.match(entrypoint, /verify_initial_content_validation_fixture 0 0/u);
  assert.match(
    entrypoint,
    /verify_initial_content_validation_fixture \\\n+\s+"\$validation_host_uid" "\$validation_host_gid"/u,
  );
  assert.match(
    entrypoint,
    /--volume "\$validation_root\/state\/initial-content:\/validation\/initial-content"/u,
  );
  assert.match(entrypoint, /chown -R 0:0 \/validation\/initial-content/u);
  assert.match(
    entrypoint,
    /chown -R "\$2:\$3"[\s\S]*\/validation\/initial-content/u,
  );
  const ownershipHelperMatch = entrypoint.match(
    /run_bind_ownership_helper\(\) \{\n([\s\S]*?)\n\}/u,
  );
  assert.ok(ownershipHelperMatch);
  assert.doesNotMatch(
    ownershipHelperMatch[1],
    /chmod[^\n]*initial-content/u,
  );
  assert.match(
    entrypoint,
    /initialContentFixtureModes=directories-0700-files-0600/u,
  );
  assert.match(entrypoint, /initialContentFixtureOwnershipRestored=true/u);
  assert.match(entrypoint, /docker run --rm --network none --read-only/u);
  assert.match(entrypoint, /--user 0:0/u);
  assert.match(entrypoint, /--security-opt no-new-privileges=true/u);
  assert.match(entrypoint, /--cap-drop ALL/u);
  assert.match(entrypoint, /--cap-add CHOWN/u);
  assert.match(
    entrypoint,
    /chown 0:0[^\n]*\/validation\/publisher \\\n\s+\/validation\/publisher\/build-workspace/u,
  );
  assert.match(entrypoint, /validationBindOwnershipMode/u);
  assert.match(entrypoint, /verify_backup_verifier_read_only_boundary/u);
  assert.match(entrypoint, /backup-verifier -ec/u);
  assert.match(entrypoint, /verifier-write-must-fail/u);
  assert.match(entrypoint, /backupVerifierRepositoryReadOnly=true/u);
  assert.match(entrypoint, /backupVerifierDeployStateReadOnly=true/u);
  assert.match(entrypoint, /backupVerifierMediaMount=false/u);
  assert.match(entrypoint, /backupVerifierNetwork=false/u);
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
  assert.match(entrypoint, /Host: external-origin\.invalid/u);
  assert.match(entrypoint, /admin-redirect-headers\.txt/u);
  assert.match(entrypoint, /adminRedirectStatus=308/u);
  assert.match(entrypoint, /adminRedirectLocation=\/admin\//u);
  assert.match(entrypoint, /adminRedirectInternalAuthorityLeak=false/u);
  assert.match(entrypoint, /X-Forwarded-Proto https/u);
  assert.match(entrypoint, /X-Forwarded-Port 443/u);
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

  assert.match(initialContentControl, /validate_task_failure_recovery/u);
  assert.match(initialContentControl, /validate_recovery_failure_lock_hold/u);
  assert.match(initialContentControl, /validate_bundle_boundary_fail_closed/u);
  assert.match(initialContentControl, /group-readable-file/u);
  assert.match(initialContentControl, /symbolic-link/u);
  assert.match(initialContentControl, /hard-link/u);
  assert.match(
    initialContentControl,
    /--profile production-task run --rm --no-deps initial-content/u,
  );
  assert.match(initialContentControl, /INITIAL_CONTENT_SOURCE_IDENTITY_INVALID/u);
  assert.match(initialContentControl, /INITIAL_CONTENT_WRITER_RECOVERY_FAILED/u);
  assert.doesNotMatch(
    initialContentControl,
    /down -v|docker (?:system|container|volume|image|network) prune|docker (?:volume|image) rm/u,
  );
});

test("initial-content failure evidence가 raw cleanup 뒤 sanitized artifact로 남는다", async (context) => {
  const entrypoint = await source("scripts/validate-production-compose.sh");
  const temporaryRoot = await mkdtemp(
    join(tmpdir(), "rhaomi-initial-content-evidence-"),
  );
  context.after(() => rm(temporaryRoot, { recursive: true, force: true }));

  const validationRoot = join(temporaryRoot, "validation");
  const evidenceDir = join(temporaryRoot, "evidence");
  const rawDir = join(validationRoot, "raw");
  const rawOutput = join(rawDir, "initial-content-pristine-authority.txt");
  await mkdir(rawDir, { recursive: true });
  await mkdir(evidenceDir);
  await writeFile(
    rawOutput,
    [
      "startup detail that must not survive",
      "INITIAL_CONTENT_ADMIN_AUTHORITY_INVALID",
      "INITIAL_CONTENT_ADMIN_AUTHORITY_INVALID",
      "/private/var/lib/rhaomi/private-path-must-not-survive",
      "raw stack trace must not survive",
    ].join("\n"),
    "utf8",
  );

  const runner = [
    "set -eu",
    "evidence_dir=$1",
    "validation_root=$2",
    "raw_output=$3",
    shellFunction(entrypoint, "collect_initial_content_failure_codes"),
    shellFunction(
      entrypoint,
      "write_initial_content_pristine_authority_evidence",
    ),
    'observed_codes=$(collect_initial_content_failure_codes "$raw_output")',
    "write_initial_content_pristine_authority_evidence \\",
    "  INITIAL_CONTENT_ADMIN_AUTHORITY_INVALID \\",
    '  "$observed_codes" true true true',
    'find "$validation_root" -depth -delete',
  ].join("\n");
  const result = spawnSync(
    "/bin/sh",
    ["-c", runner, "sh", evidenceDir, validationRoot, rawOutput],
    { encoding: "utf8" },
  );
  assert.equal(result.status, 0, result.stderr);

  const evidenceText = await readFile(
    join(evidenceDir, "production-initial-content-pristine-authority.json"),
    "utf8",
  );
  assert.deepEqual(JSON.parse(evidenceText), {
    contract: "rhaomi-initial-content-pristine-authority-v1",
    expectedCode: "INITIAL_CONTENT_ADMIN_AUTHORITY_INVALID",
    observedCodes: ["INITIAL_CONTENT_ADMIN_AUTHORITY_INVALID"],
    nonZeroExit: true,
    databaseMutationZero: true,
    mediaMutationZero: true,
  });
  assert.doesNotMatch(
    evidenceText,
    /startup detail|private-path-must-not-survive|raw stack trace/u,
  );
  await assert.rejects(readFile(rawOutput, "utf8"), { code: "ENOENT" });
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
