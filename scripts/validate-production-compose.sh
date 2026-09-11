#!/bin/sh

set -eu

main() {
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
git_head=$(git -C "$repo_dir" rev-parse HEAD)
git_short=$(printf '%s' "$git_head" | cut -c1-12)
production_image=${RHAOMI_PRODUCTION_IMAGE:?RHAOMI_PRODUCTION_IMAGE is required}
cleanup_task=${RHAOMI_CLEANUP_TASK:-51-production-compose-nginx}
loopback_port=${RHAOMI_WEB_LOOPBACK_PORT:?RHAOMI_WEB_LOOPBACK_PORT is required}
evidence_dir=${RHAOMI_PRODUCTION_COMPOSE_EVIDENCE_DIR:-}
project_name="rhaomi-dimp2-${git_short}-$$"
validation_root=
marker=
compose_started=false
validation_bind_ownership_prepared=false
validation_bind_ownership_mode=docker-desktop-host-bind
validation_host_uid=$(id -u)
validation_host_gid=$(id -g)

if ! printf '%s' "$git_head" | grep -Eq '^[0-9a-f]{40}$'; then
  echo "exact 40-character Git HEAD가 필요합니다." >&2
  exit 1
fi

case "$loopback_port" in
  *[!0-9]* | "")
    echo "RHAOMI_WEB_LOOPBACK_PORT는 decimal port여야 합니다." >&2
    exit 1
    ;;
esac
if [ "$loopback_port" -lt 1024 ] || [ "$loopback_port" -gt 65535 ]; then
  echo "RHAOMI_WEB_LOOPBACK_PORT는 1024..65535 범위여야 합니다." >&2
  exit 1
fi

for command in curl docker git openssl; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "production Compose 검증에 ${command}가 필요합니다." >&2
    exit 1
  fi
done

case "$(uname -s)" in
  Darwin) validation_parent=/private/var/tmp ;;
  *) validation_parent=${RUNNER_TEMP:-${TMPDIR:-/tmp}} ;;
esac
validation_root=$(mktemp -d "${validation_parent%/}/rhaomi-production-compose.XXXXXX")
chmod 700 "$validation_root"
marker="$validation_root/.rhaomi-production-compose-validation"
printf '%s\n' "$project_name" >"$marker"

compose_runtime() {
  docker compose \
    --project-directory "$repo_dir" \
    --project-name "$project_name" \
    --file "$repo_dir/compose.production.yaml" \
    --file "$repo_dir/compose.production.validation.yaml" \
    "$@"
}

compose_validation() {
  docker compose \
    --project-directory "$repo_dir" \
    --project-name "$project_name" \
    --file "$repo_dir/compose.production.yaml" \
    --file "$repo_dir/compose.production.validation.yaml" \
    --profile production-task \
    --profile production-backup \
    "$@"
}

cleanup() {
  if [ "$compose_started" = true ]; then
    compose_validation down --remove-orphans >/dev/null 2>&1 || true
  fi
  if [ "$validation_bind_ownership_prepared" = true ]; then
    restore_linux_bind_ownership
  fi
  if [ -n "$validation_root" ] &&
    [ -f "$marker" ] &&
    [ "$(sed -n '1p' "$marker")" = "$project_name" ]; then
    find "$validation_root" -depth -delete
  fi
}
trap cleanup EXIT HUP INT TERM

if [ -z "$evidence_dir" ]; then
  evidence_dir=$(mktemp -d "${validation_parent%/}/rhaomi-production-compose-evidence.XXXXXX")
  echo "evidence directory: ${evidence_dir}"
else
  if [ -d "$evidence_dir" ] &&
    [ -n "$(find "$evidence_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    echo "stale evidence 혼합을 막기 위해 비어 있는 evidence directory가 필요합니다." >&2
    exit 1
  fi
  mkdir -p "$evidence_dir"
fi

mkdir -p \
  "$validation_root/app/nginx" \
  "$validation_root/public/releases/validation/site/admin" \
  "$validation_root/public/releases/validation/site/_next/static" \
  "$validation_root/public/releases/validation/site/generated/media" \
  "$validation_root/data/media" \
  "$validation_root/backup-repository/sets" \
  "$validation_root/restore-media" \
  "$validation_root/state/deploy" \
  "$validation_root/state/initial-content/media" \
  "$validation_root/state/publisher" \
  "$validation_root/state/publisher/build-workspace" \
  "$validation_root/state/locks" \
  "$validation_root/raw"
chmod 700 \
  "$validation_root/backup-repository" \
  "$validation_root/backup-repository/sets" \
  "$validation_root/restore-media" \
  "$validation_root/state/deploy"
chmod 700 \
  "$validation_root/state/initial-content" \
  "$validation_root/state/initial-content/media"
printf '%s\n' rhaomi-backup-repository-v1 \
  >"$validation_root/backup-repository/.rhaomi-backup-repository"
chmod 600 "$validation_root/backup-repository/.rhaomi-backup-repository"
cp "$repo_dir/infra/nginx/production.conf" \
  "$validation_root/app/nginx/production.conf"
printf '%s\n' \
  '<!doctype html><html lang="ko"><body>rhaomi production compose validation</body></html>' \
  >"$validation_root/public/releases/validation/site/index.html"
printf '%s\n' \
  '<!doctype html><html lang="ko"><head><meta name="robots" content="noindex"></head><body>admin validation</body></html>' \
  >"$validation_root/public/releases/validation/site/admin/index.html"
printf '%s\n' 'console.log("static validation asset");' \
  >"$validation_root/public/releases/validation/site/_next/static/validation.js"
for hidden_path in \
  admin/.synthetic-hidden \
  _next/static/.synthetic-hidden \
  generated/media/.synthetic-hidden; do
  printf '%s\n' 'synthetic hidden fixture' \
    >"$validation_root/public/releases/validation/site/$hidden_path"
done
printf '%s\n' \
  '{"schemaVersion":1,"contentRevision":"0","publishGeneration":"1","generatedAt":"2026-09-01T00:00:00Z"}' \
  >"$validation_root/public/releases/validation/release-manifest.json"
ln -s "releases/validation/site" "$validation_root/public/current"

cp "$repo_dir/backend/src/test/resources/media/synthetic-source.png" \
  "$validation_root/state/initial-content/media/cover.png"
cat >"$validation_root/state/initial-content/content.json" <<'JSON'
{
  "schemaVersion": 1,
  "shopSettings": {
    "shopName": "synthetic shop", "regionLabel": "validation", "businessType": "grooming",
    "phone": "02-0000-0000", "address": "validation address", "openingTime": "10:00",
    "closingTime": "19:00", "closedWeekday": "MONDAY", "parkingAvailable": true,
    "parkingNote": null, "heroTitle": "synthetic hero", "heroDescription": "synthetic description",
    "groomerName": "synthetic groomer", "groomerIntro": "synthetic intro",
    "reservationNotice": "synthetic reservation", "heroMediaKey": "cover",
    "heroImageAltText": "synthetic cover", "groomerMediaKey": null,
    "groomerImageAltText": null, "ogMediaKey": "cover", "instagramUrl": null,
    "naverBlogUrl": null, "naverMapUrl": null, "kakaoMapUrl": null,
    "naverTalktalkUrl": null, "kakaoChannelUrl": null
  },
  "breeds": [{"key":"breed","name":"synthetic breed","slug":"synthetic-breed","description":"synthetic","sortOrder":1}],
  "services": [{"key":"service","name":"synthetic service","slug":"synthetic-service","description":"synthetic","priceText":null,"sortOrder":1}],
  "notices": [{"key":"notice","title":"synthetic notice","slug":"synthetic-notice","summary":"synthetic","bodyMarkdown":"synthetic body","pinned":false,"publishedAt":"2026-09-01T00:00:00Z","expiresAt":null}],
  "galleryItems": [{"key":"gallery","dogName":"synthetic dog","breedKey":"breed","primaryServiceKey":"service","coverMediaKey":"cover","beforeMediaKey":null,"afterMediaKey":null,"summary":"synthetic","altText":"synthetic dog","featured":true,"sortOrder":1,"performedAt":"2026-09-01T00:00:00Z","publishedAt":"2026-09-01T00:00:00Z"}]
}
JSON
initial_content_path="$validation_root/state/initial-content/content.json"
initial_media_path="$validation_root/state/initial-content/media/cover.png"
initial_content_size=$(wc -c <"$initial_content_path" | tr -d '[:space:]')
initial_media_size=$(wc -c <"$initial_media_path" | tr -d '[:space:]')
initial_content_sha=$(openssl dgst -sha256 "$initial_content_path" | awk '{print $NF}')
initial_media_sha=$(openssl dgst -sha256 "$initial_media_path" | awk '{print $NF}')
cat >"$validation_root/state/initial-content/manifest.json" <<JSON
{
  "schemaVersion": 1,
  "content": {"path":"content.json","byteSize":${initial_content_size},"sha256":"${initial_content_sha}"},
  "media": [{"key":"cover","path":"media/cover.png","contentType":"image/png","byteSize":${initial_media_size},"sha256":"${initial_media_sha}"}],
  "counts": {"shopSettings":1,"media":1,"breeds":1,"services":1,"notices":1,"galleryItems":1}
}
JSON
chmod 600 \
  "$validation_root/state/initial-content/manifest.json" \
  "$initial_content_path" \
  "$initial_media_path"

build_token=$(openssl rand -hex 32)
postgres_password=$(openssl rand -hex 24)
image_id=$(docker image inspect "$production_image" --format '{{.Id}}')
image_revision=$(docker image inspect "$production_image" \
  --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')
image_architecture=$(docker image inspect "$production_image" --format '{{.Architecture}}')
if [ "$image_revision" != "$git_head" ]; then
  echo "production image OCI revision이 exact Git HEAD와 다릅니다." >&2
  exit 1
fi
case "$(uname -m):${image_architecture}" in
  arm64:arm64 | aarch64:arm64 | x86_64:amd64) ;;
  *)
    echo "host와 production image architecture가 일치하지 않습니다." >&2
    exit 1
    ;;
esac
prepare_linux_bind_ownership

export RHAOMI_PRODUCTION_COMPOSE_PROJECT="$project_name"
export RHAOMI_PRODUCTION_VALIDATION_ROOT="$validation_root"
export RHAOMI_BACKUP_REPOSITORY_ROOT="$validation_root/backup-repository"
export RHAOMI_BACKUP_RESTORE_MEDIA_ROOT="$validation_root/restore-media"
export RHAOMI_PRODUCTION_IMAGE="$production_image"
export RHAOMI_WEB_LOOPBACK_PORT="$loopback_port"
export RHAOMI_POSTGRES_DB=rhaomi_validation
export RHAOMI_POSTGRES_USER=rhaomi_validation
export RHAOMI_POSTGRES_PASSWORD="$postgres_password"
export RHAOMI_BUILD_SERVICE_TOKEN="$build_token"
export RHAOMI_WEBAUTHN_RP_ID=validation.invalid
export RHAOMI_WEBAUTHN_ORIGIN=https://validation.invalid
export RHAOMI_WEBAUTHN_RP_NAME="Rhaomi Validation Admin"
export RHAOMI_PUBLISHER_OWNER="validation-${git_short}-$$"
export RHAOMI_PUBLIC_SITE_URL=https://validation.invalid
export RHAOMI_CODE_SHA="$git_head"
export RHAOMI_CODE_IMAGE_TAG="$production_image"
export RHAOMI_CODE_IMAGE_DIGEST="$image_id"
export RHAOMI_FLYWAY_VERSION=10
export RHAOMI_SBOM_REFERENCE="$image_id"
export RHAOMI_CLEANUP_TASK="$cleanup_task"
export RHAOMI_CLEANUP_GIT_HEAD="$git_head"

docker volume ls --format '{{.Name}}' >"$validation_root/raw/preexisting-volumes.txt"
docker image ls --no-trunc --format '{{.ID}}' | sort -u \
  >"$validation_root/raw/preexisting-images.txt"

docker compose \
  --project-directory "$repo_dir" \
  --project-name "$project_name" \
    --file "$repo_dir/compose.production.yaml" \
    --profile production-task \
    --profile production-backup \
  config --format json >"$validation_root/raw/base-config.json"
compose_validation config --format json \
  >"$validation_root/raw/validation-config.json"

docker run --rm --network none \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label "io.homeserver.cleanup.task=${cleanup_task}" \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=${git_head}" \
  --volume "$repo_dir/scripts/validate-production-compose-contract.mjs:/validation/validate-production-compose-contract.mjs:ro" \
  --volume "$repo_dir/compose.production.yaml:/validation/compose.production.yaml:ro" \
  --volume "$repo_dir/compose.production.validation.yaml:/validation/compose.production.validation.yaml:ro" \
  --volume "$validation_root/raw:/validation/input:ro" \
  --workdir /validation \
  "$production_image" \
  node validate-production-compose-contract.mjs \
    /validation/input/base-config.json \
    /validation/input/validation-config.json \
    /validation/compose.production.yaml \
    /validation/compose.production.validation.yaml \
    "$validation_root" \
    "$production_image" \
    "$cleanup_task" \
    "$git_head" \
  >"$evidence_dir/production-compose-contract.json"

sh "$repo_dir/scripts/validate-production-initial-admin.sh" \
  >"$evidence_dir/production-initial-admin-control.txt"
sh "$repo_dir/scripts/validate-production-initial-content.sh" \
  >"$evidence_dir/production-initial-content-control.txt"

compose_started=true
verify_backup_verifier_read_only_boundary
compose_validation up --detach postgres >/dev/null
wait_healthy postgres 90
compose_validation run --rm --no-deps migration \
  >"$validation_root/raw/migration-task.txt" 2>&1
flyway_version=$(database_query \
  "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")
if [ "$flyway_version" != "10" ]; then
  echo "one-shot migration이 Flyway V1~V10을 적용하지 못했습니다." >&2
  exit 1
fi
compose_validation run --rm --no-deps schema-validate \
  >"$validation_root/raw/schema-validation-task.txt" 2>&1
if compose_validation run --rm --no-deps schema-validate \
  java -jar /opt/rhaomi/backend.jar --rhaomi.production-task=invalid \
  >"$validation_root/raw/invalid-production-task.txt" 2>&1; then
  echo "malformed production task mode가 성공했습니다." >&2
  exit 1
fi
verify_initial_admin_runtime_boundary
verify_initial_content_runtime_boundary

compose_runtime up --detach rhaomi-web backend publisher postgres >/dev/null
wait_healthy postgres 90
wait_healthy backend 180
wait_healthy rhaomi-web 90
wait_running publisher 90

verify_runtime_surface
verify_mount_permissions
verify_http_contract
verify_internal_build_authentication

compose_runtime stop --timeout 30 backend publisher >/dev/null
verify_writers_stopped
assert_http_status "http://127.0.0.1:${loopback_port}/" 200
compose_validation run --rm --no-deps migration \
  >"$validation_root/raw/maintenance-migration-task.txt" 2>&1
verify_writers_stopped
compose_validation run --rm --no-deps schema-validate \
  >"$validation_root/raw/maintenance-schema-validation-task.txt" 2>&1
verify_writers_stopped
assert_http_status "http://127.0.0.1:${loopback_port}/" 200
compose_runtime up --detach --no-deps backend >/dev/null
wait_healthy backend 180
compose_runtime up --detach --no-deps publisher >/dev/null
wait_running publisher 90

database_query \
  "CREATE TABLE IF NOT EXISTS validation_sentinel (id integer PRIMARY KEY, marker text NOT NULL); INSERT INTO validation_sentinel (id, marker) VALUES (1, 'compose-down-up') ON CONFLICT (id) DO UPDATE SET marker = EXCLUDED.marker;" \
  >/dev/null
sentinel_before=$(database_query \
  "SELECT COUNT(*) FROM validation_sentinel WHERE id = 1 AND marker = 'compose-down-up'")
history_before=$(database_query \
  "SELECT COUNT(*) FROM flyway_schema_history WHERE success")
if [ "$sentinel_before" != "1" ]; then
  echo "validation sentinel 생성에 실패했습니다." >&2
  exit 1
fi

volume_name="${project_name}_postgres-data"
volume_created=$(docker volume inspect "$volume_name" --format '{{.CreatedAt}}')
verify_task_volume_labels "$volume_name"

compose_runtime down --remove-orphans >/dev/null
docker volume inspect "$volume_name" >/dev/null

compose_runtime up --detach rhaomi-web backend publisher postgres >/dev/null
wait_healthy postgres 90
wait_healthy backend 180
wait_healthy rhaomi-web 90
wait_running publisher 90
sentinel_after=$(database_query \
  "SELECT COUNT(*) FROM validation_sentinel WHERE id = 1 AND marker = 'compose-down-up'")
history_after=$(database_query \
  "SELECT COUNT(*) FROM flyway_schema_history WHERE success")
if [ "$sentinel_after" != "1" ] || [ "$history_after" != "$history_before" ]; then
  echo "general Compose down/up 뒤 PostgreSQL persistence contract가 깨졌습니다." >&2
  exit 1
fi
if [ "$(docker volume inspect "$volume_name" --format '{{.CreatedAt}}')" != "$volume_created" ]; then
  echo "general Compose down/up가 PostgreSQL volume identity를 바꿨습니다." >&2
  exit 1
fi

verify_http_contract
compose_runtime down --remove-orphans >/dev/null
verify_no_task_containers_or_networks
verify_preexisting_resources_preserved

printf '%s\n' \
  "contract=production-compose-v1" \
  "gitHead=${git_head}" \
  "architecture=${image_architecture}" \
  "project=${project_name}" \
  "imageId=${image_id}" \
  "backendPublisherSameImage=true" \
  "webOnlyLoopbackPort=true" \
  "runtimeMountModes=verified" \
  "runtimeNetworkAdjacency=verified" \
  "credentialIsolation=verified" \
  "backupVerifierRepositoryReadOnly=true" \
  "backupVerifierDeployStateReadOnly=true" \
  "backupVerifierMediaMount=false" \
  "backupVerifierNetwork=false" \
  "normalFlywayDisabled=true" \
  "normalBootstrapDisabled=true" \
  "oneShotMigration=true" \
  "oneShotSchemaValidation=true" \
  "oneShotInitialAdmin=true" \
  "oneShotInitialContent=true" \
  "initialAdminCredentialIsolation=verified" \
  "initialAdminNonInteractiveMutation=0" \
  "initialContentBundleReadOnly=true" \
  "initialContentMediaReadWrite=true" \
  "initialContentPristineAuthorityMutation=0" \
  "oneShotHttpListener=false" \
  "writerQuiescenceBeforeMigration=true" \
  "publicStaticDuringMaintenance=200" \
  "validationBindOwnershipMode=${validation_bind_ownership_mode}" \
  >"$evidence_dir/production-compose-runtime.txt"
printf '%s\n' \
  "volumeName=${volume_name}" \
  "volumeIdentityPreserved=true" \
  "sentinelBefore=${sentinel_before}" \
  "sentinelAfter=${sentinel_after}" \
  "flywayVersion=${flyway_version}" \
  "flywayHistoryBefore=${history_before}" \
  "flywayHistoryAfter=${history_after}" \
  "volumeDeleted=false" \
  >"$evidence_dir/production-compose-persistence.txt"
printf '%s\n' \
  "staticHome=200" \
  "adminRedirectStatus=308" \
  "adminRedirectLocation=/admin/" \
  "adminRedirectInternalAuthorityLeak=false" \
  "backendForwardedOrigin=https:443" \
  "staticAdmin=200" \
  "adminUpstreamAnonymous=401" \
  "csrfSecureSessionCookie=verified" \
  "buildRoute=404" \
  "internalRoute=404" \
  "actuatorRoute=404" \
  "releaseManifestRoute=404" \
  "unknownRoute=404" \
  "nestedHiddenPaths=404" \
  "queryBearingRefererLogged=false" \
  "queryFreeAccessLogContract=verified" \
  >"$evidence_dir/production-compose-http.txt"
printf '%s\n' \
  "preexistingVolumesPreserved=true" \
  "preexistingImagesPreserved=true" \
  "taskContainersRemaining=0" \
  "taskNetworksRemaining=0" \
  "taskVolumeRetained=true" \
  "dockerVolumeDeletionPerformed=false" \
  "dockerImageDeletionPerformed=false" \
  >"$evidence_dir/production-compose-cleanup.txt"

echo "production Compose contract validation: PASS"
echo "retained task volume: ${volume_name}"
}

wait_healthy() {
  service=$1
  maximum=$2
  attempt=0
  while [ "$attempt" -lt "$maximum" ]; do
    container_id=$(compose_validation ps --all --quiet "$service")
    if [ -n "$container_id" ]; then
      state=$(docker inspect "$container_id" --format '{{.State.Status}}')
      if [ "$state" = exited ] || [ "$state" = dead ]; then
        write_service_failure_evidence "$service" "$container_id"
        echo "${service}가 healthy 이전에 종료됐습니다." >&2
        exit 1
      fi
      health=$(docker inspect "$container_id" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
      if [ "$health" = healthy ]; then
        return 0
      fi
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  compose_validation ps >&2 || true
  if [ -n "${container_id:-}" ]; then
    write_service_failure_evidence "$service" "$container_id"
  fi
  echo "${service} health timeout" >&2
  exit 1
}

prepare_linux_bind_ownership() {
  if [ "$(uname -s)" != Linux ]; then
    return 0
  fi

  run_bind_ownership_helper prepare
  validation_bind_ownership_prepared=true
  validation_bind_ownership_mode=docker-helper-root-owned
}

restore_linux_bind_ownership() {
  run_bind_ownership_helper restore
  validation_bind_ownership_prepared=false
}

run_bind_ownership_helper() {
  action=$1
  docker run --rm --network none --read-only \
    --user 0:0 \
    --security-opt no-new-privileges=true \
    --cap-drop ALL \
    --cap-add CHOWN \
    --cap-add DAC_OVERRIDE \
    --cap-add FOWNER \
    --label io.homeserver.cleanup.environment=development \
    --label io.homeserver.cleanup.project=rhaomi \
    --label "io.homeserver.cleanup.task=${cleanup_task}" \
    --label io.homeserver.cleanup.lifecycle=task \
    --label io.homeserver.cleanup.retain=false \
    --label "io.homeserver.cleanup.git-head=${git_head}" \
    --volume "$validation_root/public:/validation/public" \
    --volume "$validation_root/data/media:/validation/media" \
    --volume "$validation_root/state/publisher:/validation/publisher" \
    --volume "$validation_root/state/locks:/validation/locks" \
    "$production_image" \
    sh -ec '
      case "$1" in
        prepare)
          chown 0:0 /validation/public /validation/media /validation/publisher \
            /validation/publisher/build-workspace /validation/locks
          chmod 0755 /validation/public
          chmod 0750 /validation/media /validation/publisher \
            /validation/publisher/build-workspace /validation/locks
          ;;
        restore)
          chown -R "$2:$3" /validation/public /validation/media /validation/publisher /validation/locks
          ;;
        *) exit 64 ;;
      esac
    ' sh "$action" "$validation_host_uid" "$validation_host_gid"
}

write_service_failure_evidence() {
  service=$1
  container_id=$2
  failure_evidence="$evidence_dir/production-compose-${service}-failure.txt"

  {
    docker inspect "$container_id" \
      --format 'status={{.State.Status}} exitCode={{.State.ExitCode}} oomKilled={{.State.OOMKilled}} error={{json .State.Error}}'
    compose_validation logs --no-color --tail 200 "$service" 2>&1 || true
  } |
    sed \
      -e "s/${postgres_password}/[REDACTED_POSTGRES_PASSWORD]/g" \
      -e "s/${build_token}/[REDACTED_BUILD_TOKEN]/g" \
      >"$failure_evidence"
  cat "$failure_evidence" >&2
}

wait_running() {
  service=$1
  maximum=$2
  attempt=0
  while [ "$attempt" -lt "$maximum" ]; do
    container_id=$(compose_validation ps --quiet "$service")
    if [ -n "$container_id" ] &&
      [ "$(docker inspect "$container_id" --format '{{.State.Status}}')" = running ]; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  compose_validation ps >&2 || true
  echo "${service} running timeout" >&2
  exit 1
}

verify_writers_stopped() {
  for service in backend publisher; do
    container_id=$(compose_validation ps --all --quiet "$service")
    if [ -n "$container_id" ] &&
      [ "$(docker inspect "$container_id" --format '{{.State.Status}}')" != exited ]; then
      echo "${service} writer가 migration 전에 종료되지 않았습니다." >&2
      exit 1
    fi
  done
}

verify_backup_verifier_read_only_boundary() {
  repository_before=$(directory_content_digest "$validation_root/backup-repository")
  deploy_state_before=$(directory_content_digest "$validation_root/state/deploy")
  compose_validation run --rm --no-deps \
    --entrypoint /bin/sh \
    backup-verifier -ec '
      test ! -e /var/lib/rhaomi/media
      test ! -e /var/run/docker.sock
      test ! -e /sys/class/net/eth0
      if env | grep -Eq "RHAOMI_BACKUP_MEDIA_ROOT|RHAOMI_BUILD_SERVICE_TOKEN|BUILD_API_CREDENTIAL|SPRING_DATASOURCE_PASSWORD"; then
        exit 1
      fi
      if touch /var/lib/rhaomi/backup-repository/verifier-write-must-fail 2>/dev/null; then
        exit 1
      fi
      if touch /var/lib/rhaomi/deploy-state/verifier-write-must-fail 2>/dev/null; then
        exit 1
      fi
      if touch /opt/rhaomi/verifier-root-write-must-fail 2>/dev/null; then
        exit 1
      fi
    ' >"$validation_root/raw/backup-verifier-boundary.txt" 2>&1
  if [ "$(directory_content_digest "$validation_root/backup-repository")" != "$repository_before" ] ||
    [ "$(directory_content_digest "$validation_root/state/deploy")" != "$deploy_state_before" ]; then
    echo "read-only backup verifier가 recovery authority를 변경했습니다." >&2
    exit 1
  fi
}

verify_initial_admin_runtime_boundary() {
  initial_admin_email_marker='synthetic-initial-admin@example.invalid'
  initial_admin_password_marker='synthetic-initial-admin-password-must-not-cross-boundary'
  administrator_count_before=$(database_query "SELECT COUNT(*) FROM admin_users")

  RHAOMI_INITIAL_ADMIN_EMAIL="$initial_admin_email_marker" \
    RHAOMI_INITIAL_ADMIN_PASSWORD="$initial_admin_password_marker" \
    compose_validation create --no-build initial-admin >/dev/null
  initial_admin_id=$(compose_validation ps --all --quiet initial-admin)
  [ -n "$initial_admin_id" ] || {
    echo "initial-admin one-shot container를 inspect할 수 없습니다." >&2
    exit 1
  }

  [ "$(docker inspect "$initial_admin_id" --format '{{.HostConfig.ReadonlyRootfs}}')" = true ]
  [ "$(docker inspect "$initial_admin_id" --format '{{len .Mounts}}')" = 0 ]
  initial_admin_ports=$(docker inspect "$initial_admin_id" --format '{{json .HostConfig.PortBindings}}')
  [ "$initial_admin_ports" = "{}" ] || [ "$initial_admin_ports" = "null" ]
  [ "$(docker inspect "$initial_admin_id" --format '{{json .Config.Cmd}}')" = \
    '["java","-jar","/opt/rhaomi/backend.jar","--rhaomi.production-task=initial-admin"]' ]

  if docker inspect "$initial_admin_id" \
    --format '{{range .Config.Env}}{{println .}}{{end}}{{json .Config.Cmd}}' |
    grep -Eq \
      'RHAOMI_(INITIAL_ADMIN|BOOTSTRAP_ADMIN)_(EMAIL|PASSWORD)|RHAOMI_(WEBAUTHN_RP|BUILD_SERVICE_TOKEN)|BUILD_API_CREDENTIAL'; then
    echo "initial-admin container에 미허용 credential 경계가 있습니다." >&2
    exit 1
  fi
  if docker inspect "$initial_admin_id" \
    --format '{{range .Config.Env}}{{println .}}{{end}}{{json .Config.Cmd}}' |
    grep -Fq "$initial_admin_email_marker" ||
    docker inspect "$initial_admin_id" \
      --format '{{range .Config.Env}}{{println .}}{{end}}{{json .Config.Cmd}}' |
      grep -Fq "$initial_admin_password_marker"; then
    echo "initial-admin raw credential marker가 Docker inspect에 노출됐습니다." >&2
    exit 1
  fi

  if RHAOMI_INITIAL_ADMIN_EMAIL="$initial_admin_email_marker" \
    RHAOMI_INITIAL_ADMIN_PASSWORD="$initial_admin_password_marker" \
    compose_validation run --rm --no-deps -T initial-admin \
      >"$validation_root/raw/initial-admin-non-interactive.txt" 2>&1; then
    echo "non-interactive initial-admin task가 성공했습니다." >&2
    exit 1
  fi
  grep -Fq INITIAL_ADMIN_INTERACTIVE_TERMINAL_REQUIRED \
    "$validation_root/raw/initial-admin-non-interactive.txt"
  if grep -Fq "$initial_admin_email_marker" \
    "$validation_root/raw/initial-admin-non-interactive.txt" ||
    grep -Fq "$initial_admin_password_marker" \
      "$validation_root/raw/initial-admin-non-interactive.txt" ||
    grep -Fq "$postgres_password" \
      "$validation_root/raw/initial-admin-non-interactive.txt" ||
    grep -Fq "$build_token" \
      "$validation_root/raw/initial-admin-non-interactive.txt"; then
    echo "initial-admin stdout/stderr에 credential이 노출됐습니다." >&2
    exit 1
  fi

  administrator_count_after=$(database_query "SELECT COUNT(*) FROM admin_users")
  if [ "$administrator_count_before" != 0 ] || [ "$administrator_count_after" != 0 ]; then
    echo "non-interactive initial-admin fail-close가 mutation 0을 보장하지 못했습니다." >&2
    exit 1
  fi

  if ! docker container rm "$initial_admin_id" >/dev/null; then
    echo "inspect용 initial-admin container를 제거하지 못했습니다." >&2
    exit 1
  fi
  if docker container inspect "$initial_admin_id" >/dev/null 2>&1; then
    echo "inspect용 initial-admin container 제거를 확인할 수 없습니다." >&2
    exit 1
  fi
}

verify_initial_content_runtime_boundary() {
  content_state_before=$(initial_content_database_state)
  media_state_before=$(runtime_media_content_digest)
  initial_content_expected_code=INITIAL_CONTENT_ADMIN_AUTHORITY_INVALID
  initial_content_raw_output="$validation_root/raw/initial-content-pristine-authority.txt"

  compose_validation create --no-build initial-content >/dev/null
  initial_content_id=$(compose_validation ps --all --quiet initial-content)
  [ -n "$initial_content_id" ] || {
    echo "initial-content one-shot container를 inspect할 수 없습니다." >&2
    exit 1
  }

  [ "$(docker inspect "$initial_content_id" --format '{{.HostConfig.ReadonlyRootfs}}')" = true ]
  initial_content_ports=$(docker inspect "$initial_content_id" --format '{{json .HostConfig.PortBindings}}')
  [ "$initial_content_ports" = "{}" ] || [ "$initial_content_ports" = "null" ]
  [ "$(docker inspect "$initial_content_id" --format '{{json .Config.Cmd}}')" = \
    '["java","-jar","/opt/rhaomi/backend.jar","--rhaomi.production-task=initial-content"]' ]
  assert_mounts "$initial_content_id" \
    "/run/rhaomi-initial-content:false /var/lib/rhaomi/media:true"
  assert_networks "$initial_content_id" "${project_name}_data-internal"

  if docker inspect "$initial_content_id" \
    --format '{{range .Config.Env}}{{println .}}{{end}}{{json .Config.Cmd}}' |
    grep -Eq \
      'RHAOMI_(INITIAL_ADMIN|BOOTSTRAP_ADMIN)_(EMAIL|PASSWORD)|RHAOMI_(WEBAUTHN_RP|BUILD_SERVICE_TOKEN)|BUILD_API_CREDENTIAL'; then
    echo "initial-content container에 미허용 credential 경계가 있습니다." >&2
    exit 1
  fi

  initial_content_non_zero=true
  if compose_validation run --rm --no-deps initial-content \
    >"$initial_content_raw_output" 2>&1; then
    initial_content_non_zero=false
  fi
  initial_content_observed_codes=$(collect_initial_content_failure_codes \
    "$initial_content_raw_output")
  initial_content_credential_leak=false
  if grep -Fq "$postgres_password" \
    "$initial_content_raw_output" ||
    grep -Fq "$build_token" \
      "$initial_content_raw_output"; then
    initial_content_credential_leak=true
  fi

  content_state_after=$(initial_content_database_state)
  media_state_after=$(runtime_media_content_digest)
  initial_content_database_mutation_zero=false
  initial_content_media_mutation_zero=false
  if [ "$content_state_after" = "$content_state_before" ]; then
    initial_content_database_mutation_zero=true
  fi
  if [ "$media_state_after" = "$media_state_before" ]; then
    initial_content_media_mutation_zero=true
  fi

  write_initial_content_pristine_authority_evidence \
    "$initial_content_expected_code" \
    "$initial_content_observed_codes" \
    "$initial_content_non_zero" \
    "$initial_content_database_mutation_zero" \
    "$initial_content_media_mutation_zero"

  if [ "$initial_content_non_zero" != true ]; then
    echo "admin authority가 없는 initial-content task가 성공했습니다." >&2
    exit 1
  fi
  if [ "$initial_content_credential_leak" = true ]; then
    echo "initial-content stdout/stderr에 credential이 노출됐습니다." >&2
    exit 1
  fi
  if [ "$initial_content_observed_codes" != "$initial_content_expected_code" ]; then
    echo "initial-content failure code가 expectedCode와 다릅니다. sanitized evidence를 확인하십시오." >&2
    exit 1
  fi
  if [ "$initial_content_database_mutation_zero" != true ] ||
    [ "$initial_content_media_mutation_zero" != true ]; then
    echo "initial-content pristine authority fail-close가 mutation 0을 보장하지 못했습니다." >&2
    exit 1
  fi

  if ! docker container rm "$initial_content_id" >/dev/null; then
    echo "inspect용 initial-content container를 제거하지 못했습니다." >&2
    exit 1
  fi
  if docker container inspect "$initial_content_id" >/dev/null 2>&1; then
    echo "inspect용 initial-content container 제거를 확인할 수 없습니다." >&2
    exit 1
  fi
}

collect_initial_content_failure_codes() {
  initial_content_failure_output=$1
  LC_ALL=C grep -Eo 'INITIAL_CONTENT_[A-Z0-9_]+' \
    "$initial_content_failure_output" | LC_ALL=C sort -u || true
}

write_initial_content_pristine_authority_evidence() {
  initial_content_evidence_expected_code=$1
  initial_content_evidence_observed_codes=$2
  initial_content_evidence_non_zero=$3
  initial_content_evidence_database_mutation_zero=$4
  initial_content_evidence_media_mutation_zero=$5
  initial_content_evidence_file="$evidence_dir/production-initial-content-pristine-authority.json"
  initial_content_evidence_temporary="${initial_content_evidence_file}.tmp"

  {
    printf '%s\n' '{'
    printf '  "contract": "rhaomi-initial-content-pristine-authority-v1",\n'
    printf '  "expectedCode": "%s",\n' "$initial_content_evidence_expected_code"
    printf '  "observedCodes": ['
    initial_content_evidence_separator=
    if [ -n "$initial_content_evidence_observed_codes" ]; then
      printf '%s\n' "$initial_content_evidence_observed_codes" |
        while IFS= read -r initial_content_evidence_code; do
          [ -n "$initial_content_evidence_code" ] || continue
          printf '%s"%s"' \
            "$initial_content_evidence_separator" \
            "$initial_content_evidence_code"
          initial_content_evidence_separator=', '
        done
    fi
    printf '],\n'
    printf '  "nonZeroExit": %s,\n' "$initial_content_evidence_non_zero"
    printf '  "databaseMutationZero": %s,\n' \
      "$initial_content_evidence_database_mutation_zero"
    printf '  "mediaMutationZero": %s\n' \
      "$initial_content_evidence_media_mutation_zero"
    printf '%s\n' '}'
  } >"$initial_content_evidence_temporary"
  chmod 600 "$initial_content_evidence_temporary"
  mv "$initial_content_evidence_temporary" "$initial_content_evidence_file"
}

runtime_media_content_digest() {
  docker run --rm --network none --read-only \
    --user 0:0 \
    --security-opt no-new-privileges=true \
    --cap-drop ALL \
    --label io.homeserver.cleanup.environment=development \
    --label io.homeserver.cleanup.project=rhaomi \
    --label "io.homeserver.cleanup.task=${cleanup_task}" \
    --label io.homeserver.cleanup.lifecycle=task \
    --label io.homeserver.cleanup.retain=false \
    --label "io.homeserver.cleanup.git-head=${git_head}" \
    --volume "$validation_root/data/media:/validation/media:ro" \
    "$production_image" \
    sh -ec '
      find /validation/media -type f -print | LC_ALL=C sort |
        while IFS= read -r digest_file; do
          printf "%s " "${digest_file#/validation/media/}"
          openssl dgst -sha256 "$digest_file" | awk "{print \$NF}"
        done |
        openssl dgst -sha256 | awk "{print \$NF}"
    '
}

initial_content_database_state() {
  database_query \
    "SELECT CONCAT_WS('|',
      (SELECT COUNT(*) FROM shop_settings),
      (SELECT COUNT(*) FROM breeds),
      (SELECT COUNT(*) FROM services),
      (SELECT COUNT(*) FROM notices),
      (SELECT COUNT(*) FROM gallery_items),
      (SELECT COUNT(*) FROM media_assets),
      (SELECT COUNT(*) FROM publishing_outbox),
      (SELECT content_revision FROM content_revision_state WHERE singleton_key = 1),
      (SELECT publish_generation FROM publish_generation_state WHERE singleton_key = 1));"
}

directory_content_digest() {
  digest_root=$1
  find "$digest_root" -type f -print | LC_ALL=C sort | while IFS= read -r digest_file; do
    printf '%s ' "${digest_file#"$digest_root"/}"
    openssl dgst -sha256 "$digest_file" | awk '{print $NF}'
  done | openssl dgst -sha256 | awk '{print $NF}'
}

database_query() {
  sql=$1
  compose_runtime exec --no-TTY postgres \
    psql -X --set ON_ERROR_STOP=1 \
      --username "$RHAOMI_POSTGRES_USER" \
      --dbname "$RHAOMI_POSTGRES_DB" \
      --tuples-only --no-align --command "$sql"
}

verify_runtime_surface() {
  web_id=$(compose_runtime ps --quiet rhaomi-web)
  backend_id=$(compose_runtime ps --quiet backend)
  publisher_id=$(compose_runtime ps --quiet publisher)
  postgres_id=$(compose_runtime ps --quiet postgres)

  web_ports=$(docker inspect "$web_id" --format '{{json .HostConfig.PortBindings}}')
  printf '%s' "$web_ports" | grep -Fq '"HostIp":"127.0.0.1"'
  printf '%s' "$web_ports" | grep -Fq "\"HostPort\":\"${loopback_port}\""
  for container_id in "$backend_id" "$publisher_id" "$postgres_id"; do
    bindings=$(docker inspect "$container_id" --format '{{json .HostConfig.PortBindings}}')
    if [ "$bindings" != "{}" ] && [ "$bindings" != "null" ]; then
      echo "web 외 production service에 PortBindings가 있습니다." >&2
      exit 1
    fi
  done

  assert_networks "$web_id" \
    "${project_name}_loopback-edge ${project_name}_web-backend"
  assert_networks "$backend_id" \
    "${project_name}_build-internal ${project_name}_data-internal ${project_name}_web-backend"
  assert_networks "$publisher_id" \
    "${project_name}_build-internal ${project_name}_data-internal"
  assert_networks "$postgres_id" "${project_name}_data-internal"

  assert_mounts "$web_id" \
    "/etc/nginx/conf.d/default.conf:false /srv/rhaomi/public:false"
  assert_mounts "$backend_id" "/var/lib/rhaomi/media:true"
  assert_mounts "$publisher_id" \
    "/opt/rhaomi/source/.rhaomi-publication-work:true /srv/rhaomi/public:true /var/lib/rhaomi/locks:true /var/lib/rhaomi/media:false /var/lib/rhaomi/publisher:true"
  assert_mounts "$postgres_id" "/var/lib/postgresql:true"

  if [ "$(docker inspect "$backend_id" --format '{{.Image}}')" != "$image_id" ] ||
    [ "$(docker inspect "$publisher_id" --format '{{.Image}}')" != "$image_id" ]; then
    echo "backend/publisher runtime image identity가 다릅니다." >&2
    exit 1
  fi

  for setting in \
    SPRING_FLYWAY_ENABLED=false \
    RHAOMI_SESSION_COOKIE_SECURE=true \
    RHAOMI_BOOTSTRAP_ADMIN_ENABLED=false; do
    docker inspect "$backend_id" --format '{{range .Config.Env}}{{println .}}{{end}}' |
      grep -Fxq "$setting"
  done
  docker inspect "$publisher_id" --format '{{range .Config.Env}}{{println .}}{{end}}' |
    grep -Fxq 'SPRING_FLYWAY_ENABLED=false'
  if docker inspect "$web_id" --format '{{range .Config.Env}}{{println .}}{{end}}' |
    grep -Eq 'RHAOMI_BUILD_SERVICE_TOKEN|BUILD_API_CREDENTIAL|SPRING_DATASOURCE_PASSWORD'; then
    echo "web environment에 credential이 노출됐습니다." >&2
    exit 1
  fi
}

assert_networks() {
  container_id=$1
  expected=$2
  actual=$(docker inspect "$container_id" \
    --format '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' |
    sed '/^[[:space:]]*$/d' |
    sort | tr '\n' ' ' | sed 's/ $//')
  expected_sorted=$(printf '%s\n' $expected | sort | tr '\n' ' ' | sed 's/ $//')
  if [ "$actual" != "$expected_sorted" ]; then
    echo "runtime NetworkSettings adjacency가 다릅니다: expected=${expected_sorted}, actual=${actual}" >&2
    exit 1
  fi
}

assert_mounts() {
  container_id=$1
  expected=$2
  actual=$(docker inspect "$container_id" \
    --format '{{range .Mounts}}{{printf "%s:%t\n" .Destination .RW}}{{end}}' |
    sed '/^[[:space:]]*$/d' |
    sort | tr '\n' ' ' | sed 's/ $//')
  expected_sorted=$(printf '%s\n' $expected | tr ' ' '\n' | sort | tr '\n' ' ' | sed 's/ $//')
  if [ "$actual" != "$expected_sorted" ]; then
    echo "runtime Mounts target/mode가 다릅니다." >&2
    exit 1
  fi
}

verify_mount_permissions() {
  backend_id=$(compose_runtime ps --quiet backend)
  publisher_id=$(compose_runtime ps --quiet publisher)
  web_id=$(compose_runtime ps --quiet rhaomi-web)

  docker exec "$backend_id" sh -ec '
    touch /var/lib/rhaomi/media/backend-write-ok
    test ! -e /srv/rhaomi/public
    test ! -e /var/lib/rhaomi/publisher
    test ! -e /var/lib/rhaomi/locks
  '
  docker exec "$publisher_id" sh -ec '
    touch /srv/rhaomi/public/publisher-write-ok
    touch /var/lib/rhaomi/publisher/publisher-state-write-ok
    touch /opt/rhaomi/source/.rhaomi-publication-work/build-workspace-write-ok
    touch /var/lib/rhaomi/locks/publisher-lock-write-ok
    if touch /opt/rhaomi/source/source-write-must-fail 2>/dev/null; then
      exit 1
    fi
    if touch /var/lib/rhaomi/media/publisher-write-must-fail 2>/dev/null; then
      exit 1
    fi
  '
  docker exec "$web_id" sh -ec '
    if touch /srv/rhaomi/public/web-write-must-fail 2>/dev/null; then
      exit 1
    fi
  '
}

verify_http_contract() {
  base_url="http://127.0.0.1:${loopback_port}"
  web_id=$(compose_runtime ps --quiet rhaomi-web)
  docker exec "$web_id" nginx -T \
    >"$validation_root/raw/nginx-runtime-config.txt" 2>&1
  grep -Fq 'absolute_redirect off;' \
    "$validation_root/raw/nginx-runtime-config.txt"
  grep -Fq 'proxy_set_header X-Forwarded-Proto https;' \
    "$validation_root/raw/nginx-runtime-config.txt"
  grep -Fq 'proxy_set_header X-Forwarded-Port 443;' \
    "$validation_root/raw/nginx-runtime-config.txt"
  if grep -Eq 'proxy_set_header X-Forwarded-(Proto|Port)[[:space:]]+\$' \
    "$validation_root/raw/nginx-runtime-config.txt"; then
    echo "production Nginx가 internal/client forwarded origin을 backend authority로 사용합니다." >&2
    exit 1
  fi

  assert_http_status "$base_url/" 200
  curl --silent --show-error "$base_url/" | grep -Fq 'rhaomi production compose validation'
  admin_redirect_status=$(curl --silent --show-error \
    --output /dev/null \
    --dump-header "$validation_root/raw/admin-redirect-headers.txt" \
    --header "Host: external-origin.invalid" \
    --write-out '%{http_code}' \
    "$base_url/admin")
  if [ "$admin_redirect_status" != "308" ]; then
    echo "external origin /admin redirect status가 308이 아닙니다." >&2
    exit 1
  fi
  admin_redirect_location=$(grep -i '^location:' \
    "$validation_root/raw/admin-redirect-headers.txt" |
    sed 's/^[^:]*:[[:space:]]*//' |
    tr -d '\r')
  if [ "$admin_redirect_location" != "/admin/" ]; then
    echo "external origin /admin redirect Location이 relative canonical path가 아닙니다." >&2
    exit 1
  fi
  case "$admin_redirect_location" in
    http://* | https://* | *:8080* | *:"$loopback_port"*)
      echo "external origin redirect가 internal scheme 또는 port를 노출합니다." >&2
      exit 1
      ;;
  esac
  assert_http_status "$base_url/admin/" 200
  curl --silent --show-error --dump-header "$validation_root/raw/admin-headers.txt" \
    --output /dev/null "$base_url/admin/"
  grep -Eiq '^x-robots-tag: noindex, nofollow' "$validation_root/raw/admin-headers.txt"
  assert_http_status "$base_url/api/admin/auth/me" 401

  curl --silent --show-error --dump-header "$validation_root/raw/csrf-headers.txt" \
    --output "$validation_root/raw/csrf-body.json" \
    "$base_url/api/admin/auth/csrf"
  grep -Eiq '^HTTP/[0-9.]+ 200' "$validation_root/raw/csrf-headers.txt"
  grep -Ei '^set-cookie: RHAOMI_SESSION=' "$validation_root/raw/csrf-headers.txt" |
    grep -Eiq 'secure'

  for path in \
    /api/build/snapshot?publishGeneration=1 \
    /internal/validation \
    /actuator/health \
    /release-manifest.json \
    /.env \
    /admin/.synthetic-hidden \
    /_next/static/.synthetic-hidden \
    /generated/media/.synthetic-hidden \
    /unknown-validation-route; do
    assert_http_status "$base_url$path" 404
  done

  referer_query_marker="rhaomi-referer-query-${git_short}-$$"
  curl --silent --show-error --output /dev/null \
    --header "Referer: https://referrer.invalid/source?marker=${referer_query_marker}" \
    "$base_url/"
  if docker logs "$web_id" 2>&1 | grep -Fq "$referer_query_marker"; then
    echo "query-bearing Referer가 production access log에 기록됐습니다." >&2
    exit 1
  fi
}

assert_http_status() {
  url=$1
  expected=$2
  actual=$(curl --silent --show-error --output /dev/null \
    --write-out '%{http_code}' "$url")
  if [ "$actual" != "$expected" ]; then
    echo "HTTP route contract status가 다릅니다." >&2
    exit 1
  fi
}

verify_internal_build_authentication() {
  publisher_id=$(compose_runtime ps --quiet publisher)
  docker exec "$publisher_id" node -e '
    const url = `${process.env.BUILD_API_INTERNAL_URL}/api/build/snapshot?publishGeneration=1`;
    fetch(url, { headers: { Authorization: `Bearer ${process.env.BUILD_API_CREDENTIAL}` } })
      .then((response) => {
        if (response.status !== 409) process.exitCode = 1;
      })
      .catch(() => { process.exitCode = 1; });
  '
}

verify_task_volume_labels() {
  task_volume=$1
  for pair in \
    "io.homeserver.cleanup.environment=development" \
    "io.homeserver.cleanup.project=rhaomi" \
    "io.homeserver.cleanup.task=${cleanup_task}" \
    "io.homeserver.cleanup.lifecycle=task" \
    "io.homeserver.cleanup.retain=false" \
    "io.homeserver.cleanup.git-head=${git_head}"; do
    key=${pair%%=*}
    expected=${pair#*=}
    actual=$(docker volume inspect "$task_volume" --format "{{index .Labels \"${key}\"}}")
    if [ "$actual" != "$expected" ]; then
      echo "task PostgreSQL volume label이 다릅니다." >&2
      exit 1
    fi
  done
}

verify_no_task_containers_or_networks() {
  if [ -n "$(docker ps --all --quiet --filter "label=io.homeserver.cleanup.task=${cleanup_task}" \
    --filter "label=io.homeserver.cleanup.git-head=${git_head}")" ]; then
    echo "current task container가 남았습니다." >&2
    exit 1
  fi
  if [ -n "$(docker network ls --quiet --filter "label=io.homeserver.cleanup.task=${cleanup_task}" \
    --filter "label=io.homeserver.cleanup.git-head=${git_head}")" ]; then
    echo "current task network가 남았습니다." >&2
    exit 1
  fi
}

verify_preexisting_resources_preserved() {
  while IFS= read -r name; do
    [ -z "$name" ] || docker volume inspect "$name" >/dev/null
  done <"$validation_root/raw/preexisting-volumes.txt"
  while IFS= read -r id; do
    [ -z "$id" ] || docker image inspect "$id" >/dev/null
  done <"$validation_root/raw/preexisting-images.txt"
}

main "$@"
