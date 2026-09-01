import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)));

async function source(path) {
  return readFile(join(projectRoot, path), "utf8");
}

function jobBlock(workflow, name, nextName) {
  const end = nextName ? `\n  ${nextName}:` : undefined;
  const start = workflow.indexOf(`\n  ${name}:`);
  assert.notEqual(start, -1, `${name} job이 필요합니다.`);
  if (!end) {
    return workflow.slice(start);
  }
  const finish = workflow.indexOf(end, start + 1);
  assert.notEqual(finish, -1, `${nextName} job 경계가 필요합니다.`);
  return workflow.slice(start, finish);
}

test("production release workflow가 exact main manual gate와 최소 job 권한을 고정한다", async () => {
  const workflow = await source(".github/workflows/production-release.yml");
  const validate = jobBlock(workflow, "validate_release", "publish_image");
  const publish = jobBlock(workflow, "publish_image", "deploy_production");
  const deploy = jobBlock(workflow, "deploy_production");

  assert.match(workflow, /^on:\n  workflow_dispatch:/mu);
  assert.doesNotMatch(workflow, /^  (?:pull_request|pull_request_target|push|schedule|workflow_run):/mu);
  assert.match(workflow, /release_sha:[\s\S]*required: true/u);
  assert.match(validate, /GITHUB_REF.*refs\/heads\/main/u);
  assert.match(validate, /GITHUB_SHA.*RELEASE_SHA/u);
  assert.match(validate, /\^\[0-9a-f\]\{40\}\$/u);
  assert.match(validate, /permissions:\n\s+contents: read/u);
  assert.doesNotMatch(validate, /packages:\s*write|environment:\s*production|secrets\./u);
  assert.match(publish, /permissions:[\s\S]*packages: write/u);
  assert.doesNotMatch(publish, /environment:\s*production|secrets\.RHAOMI_TAILSCALE/u);
  assert.match(deploy, /environment:\s*production/u);
  assert.match(deploy, /permissions:[\s\S]*packages: read/u);
  assert.match(deploy, /secrets\.RHAOMI_TAILSCALE_OAUTH_CLIENT_ID/u);
});

test("production release workflow가 immutable multi-arch image와 fixed remote entrypoint만 사용한다", async () => {
  const [workflow, dockerfile] = await Promise.all([
    source(".github/workflows/production-release.yml"),
    source("backend/Dockerfile.production"),
  ]);

  assert.match(dockerfile, /ARG RHAOMI_GIT_HEAD/u);
  assert.match(dockerfile, /grep -Eq '\^\[0-9a-f\]\{40\}\$'/u);
  assert.match(workflow, /backend\/Dockerfile\.production/u);
  assert.match(workflow, /platforms:\s*linux\/amd64,linux\/arm64/u);
  assert.match(
    workflow,
    /build-args:\s*\|\s*\n\s+RHAOMI_GIT_HEAD=\$\{\{ inputs\.release_sha \}\}/u,
  );
  assert.match(workflow, /ghcr\.io\/xxh3898\/rhaomi:\$\{\{ inputs\.release_sha \}\}/u);
  assert.match(workflow, /org\.opencontainers\.image\.revision=\$\{\{ inputs\.release_sha \}\}/u);
  assert.match(workflow, /org\.opencontainers\.image\.source=https:\/\/github\.com\/xxh3898\/Rhaomi/u);
  assert.match(workflow, /provenance:\s*mode=max/u);
  assert.match(workflow, /sbom:\s*true/u);
  assert.match(workflow, /\{\{json \.Manifest\}\}/u);
  assert.match(
    workflow,
    /for platform_entry in linux\/amd64:linux-amd64 linux\/arm64:linux-arm64/u,
  );
  assert(workflow.includes('--format "{{json (index .Image \\"${platform}\\")}}"'));
  assert(
    workflow.includes(
      '--format "{{json (index (index .SBOM \\"${platform}\\") \\"SPDX\\")}}"',
    ),
  );
  assert(workflow.includes('--format "{{json (index .SLSA \\"${platform}\\")}}"'));
  assert.match(workflow, /verify-published-production-image\.mjs/u);
  assert.match(workflow, /anchore\/grype:v0\.104\.1@sha256:e7d3cb36/u);
  assert.match(workflow, /sbom_reference="\$MANIFEST_DIGEST"/u);
  assert.doesNotMatch(workflow, /sha256sum "\$sbom_path"/u);
  assert.doesNotMatch(workflow, /"provenance": "buildkit-attestation"/u);
  assert.match(workflow, /steps\.publish\.outputs\.digest/u);
  assert.match(workflow, /digest: \$\{\{ steps\.release_evidence\.outputs\.digest \}\}/u);
  assert.match(
    workflow,
    /sbom_reference: \$\{\{ steps\.release_evidence\.outputs\.sbom_reference \}\}/u,
  );
  assert.match(
    workflow,
    /IMAGE_REFERENCE: ghcr\.io\/xxh3898\/rhaomi@\$\{\{ needs\.publish_image\.outputs\.digest \}\}/u,
  );
  assert.match(
    workflow,
    /SBOM_REFERENCE: \$\{\{ needs\.publish_image\.outputs\.sbom_reference \}\}/u,
  );
  assert.match(
    workflow,
    /docker buildx imagetools inspect "\$\{IMAGE_REPOSITORY\}:\$\{RELEASE_SHA\}"[\s\S]*immutable publish refuses overwrite/u,
  );
  assert.doesNotMatch(workflow, /(?:^|:)latest(?:$|\s)/imu);
  assert.match(workflow, /tailscale\/github-action@780049a30b6ff5c378a9e7b389d15ece7a204888/u);
  assert.match(workflow, /version:\s*1\.94\.2/u);
  assert.match(workflow, /sha256sum:\s*c6f99a5d774c7783b56902188d69e9756fc3dddfb08ac6be4cb2585f3fecdc32/u);
  assert.match(workflow, /\/private\/var\/lib\/rhaomi\/app\/bin\/deploy-rhaomi\.sh/u);
  assert.match(workflow, /--release-sha[\s\S]*--image[\s\S]*--sbom/u);
  assert.doesNotMatch(workflow, /ssh[^\n]*(?:bash -c|sh -c)|ssh[^\n]*\$\{\{ inputs\.[^}]+\}\}/u);
  assert.doesNotMatch(workflow, /<<[-]?\s*['"]?[A-Z_]+/u);
});

test("production Compose가 same-image non-web migration과 schema validation profile을 제공한다", async () => {
  const compose = await source("compose.production.yaml");

  for (const service of ["migration", "schema-validate"]) {
    assert.match(compose, new RegExp(`\\n  ${service}:`, "u"));
  }
  assert.equal((compose.match(/profiles: \["production-task"\]/gu) ?? []).length, 2);
  assert.equal(
    (compose.match(/image: \$\{RHAOMI_PRODUCTION_IMAGE:\?[^}]+\}/gu) ?? []).length,
    5,
  );
  assert.match(compose, /--rhaomi\.production-task=migrate/u);
  assert.match(compose, /--rhaomi\.production-task=schema-validate/u);
  assert.match(compose, /migration:[\s\S]*SPRING_FLYWAY_ENABLED: "true"/u);
  assert.match(compose, /schema-validate:[\s\S]*SPRING_FLYWAY_ENABLED: "false"/u);
  assert.equal((compose.match(/SPRING_JPA_HIBERNATE_DDL_AUTO: validate/gu) ?? []).length, 2);
});

test("fixed Mac deploy entrypoint가 lock, backup, digest, writer quiescence와 failure hold를 구현한다", async () => {
  const [entrypoint, core] = await Promise.all([
    source("ops/production/deploy-rhaomi.sh"),
    source("ops/production/deploy-rhaomi-core.sh"),
  ]);

  assert.match(entrypoint, /deploy_rhaomi \/private\/var\/lib\/rhaomi/u);
  assert.doesNotMatch(entrypoint, /RHAOMI_DEPLOY_(?:ROOT|COMMAND)|eval/u);
  assert.match(core, /--release-sha/u);
  assert.match(core, /ghcr\[\.\]io\/xxh3898\/rhaomi@sha256:/u);
  assert.match(core, /backup-eligible\.env/u);
  assert.match(core, /require_owned_private_directory/u);
  assert.match(core, /\^7\[0145\]\[0145\]\$/u);
  assert.match(core, /rhaomi-deploy\.lock/u);
  assert.match(core, /mkdir "\$deploy_lock"/u);
  assert.match(core, /compose_production stop --timeout 30 backend publisher/u);
  assert.match(core, /verify_writer_quiescence/u);
  assert.match(core, /compose_production --profile production-task run --rm --no-deps migration/u);
  assert.match(core, /compose_production --profile production-task run --rm --no-deps schema-validate/u);
  assert.match(core, /wait_for_backend_health/u);
  assert.match(core, /verify_runtime_image_identity/u);
  assert.match(core, /writer_maintenance_active=true/u);
  assert.match(core, /quiesce_writers_after_failure/u);
  assert.match(
    core,
    /verify_runtime_image_identity[\s\S]*release_deploy_lock \|\| deploy_fail DEPLOY_LOCK_RELEASE_FAILED[\s\S]*writer_maintenance_active=false[\s\S]*"maintenanceReleased": true/u,
  );
  assert.doesNotMatch(core, /down -v|docker (?:volume|image) (?:rm|prune)|docker system prune/u);
  assert.doesNotMatch(core, /\|\| true|eval|source .*production\.env/u);
});

test("task deploy validator가 fail-before-mutation, contention, failure hold와 redaction을 고정한다", async () => {
  const validator = await source("scripts/validate-production-deploy.sh");

  assert.match(validator, /deploy-rhaomi-core\.sh/u);
  assert.match(validator, /wrong-registry/u);
  assert.match(validator, /insecure-host-directory/u);
  assert.match(validator, /ineligible-backup/u);
  assert.match(validator, /migration/u);
  assert.match(validator, /schema-validate/u);
  assert.match(validator, /writer-stop-failure/u);
  assert.match(validator, /backend-health-failure/u);
  assert.match(validator, /publisher-start-failure/u);
  assert.match(validator, /validate_runtime_image_mismatch backend/u);
  assert.match(validator, /validate_runtime_image_mismatch publisher/u);
  assert.match(validator, /failureQuiescence=verified/u);
  assert.match(validator, /lock/u);
  assert.match(validator, /[Mm]aintenance/u);
  assert.match(validator, /REDACTED/u);
  assert.match(validator, /productionPathMutation=0/u);
  assert.match(validator, /ghcrPush=0/u);
  assert.match(validator, /tailscaleConnection=0/u);
  assert.doesNotMatch(validator, /docker (?:volume|image) (?:rm|prune)|docker system prune|down -v/u);
});

test("Hosted Validate Backend job이 D-IMP-3 validator를 exact-head image로 실행한다", async () => {
  const workflow = await source(".github/workflows/validate.yml");
  const jobs = [...workflow.matchAll(/^  [a-z0-9-]+:\s*$/gmu)].map((match) =>
    match[0].trim(),
  );

  assert.deepEqual(jobs, ["frontend:", "backend:", "compose-smoke:"]);
  assert.match(workflow, /scripts\/validate-production-deploy\.sh/u);
  assert.match(
    workflow,
    /RHAOMI_CLEANUP_TASK:\s*55-application-consistent-restore-gate/u,
  );
  assert.match(workflow, /RHAOMI_PRODUCTION_IMAGE: rhaomi-production-ci:\$\{\{ github\.event\.pull_request\.head\.sha \}\}/u);
  assert.doesNotMatch(workflow, /packages:\s*write/u);
});
