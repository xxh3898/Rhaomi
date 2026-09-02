#!/bin/sh

set -eu
umask 077

main() {
  repo_dir=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
  git_head=$(git -C "$repo_dir" rev-parse HEAD)
  git_short=$(printf '%s' "$git_head" | cut -c1-12)
  production_image=${RHAOMI_PRODUCTION_IMAGE:?RHAOMI_PRODUCTION_IMAGE is required}
  cleanup_task=${RHAOMI_CLEANUP_TASK:-55-application-consistent-restore-gate}
  evidence_dir=${RHAOMI_PRODUCTION_BACKUP_EVIDENCE_DIR:-}
  loopback_port=${RHAOMI_WEB_LOOPBACK_PORT:-18055}
  source_project="rhaomi-dimp4-source-${git_short}-$$"
  restore_project="rhaomi-dimp4-restore-${git_short}-$$"
  source_started=false
  restore_started=false
  validation_succeeded=false
  validation_parent=
  validation_marker=
  started_epoch=$(date +%s)

  printf '%s' "$git_head" | grep -Eq '^[0-9a-f]{40}$' || backup_validation_fail BACKUP_HEAD_INVALID
  case "$loopback_port" in *[!0-9]* | "") backup_validation_fail BACKUP_PORT_INVALID ;; esac
  [ "$loopback_port" -ge 1024 ] && [ "$loopback_port" -le 65535 ] ||
    backup_validation_fail BACKUP_PORT_INVALID
  for command in curl docker git openssl; do
    command -v "$command" >/dev/null 2>&1 || backup_validation_fail BACKUP_TOOL_MISSING
  done

  case "$(uname -s)" in
    Darwin) temp_parent=/private/var/tmp ;;
    *) temp_parent=${RUNNER_TEMP:-${TMPDIR:-/tmp}} ;;
  esac
  temp_parent=$(cd "$temp_parent" && pwd -P)
  validation_parent=$(mktemp -d "${temp_parent%/}/rhaomi-production-backup.XXXXXX")
  validation_parent=$(cd "$validation_parent" && pwd -P)
  chmod 700 "$validation_parent"
  validation_marker="$validation_parent/.rhaomi-production-backup-validation"
  printf '%s\n' "$git_head" >"$validation_marker"
  trap cleanup_backup_validation EXIT HUP INT TERM
  prepare_backup_evidence_directory

  source_root="$validation_parent/source-host"
  restore_root="$validation_parent/restore-host"
  backup_repository="$validation_parent/backup-repository"
  raw_dir="$validation_parent/raw"
  mkdir -m 700 "$backup_repository" "$backup_repository/sets" "$raw_dir"
  RHAOMI_HOMEOPS_TEST_LOG="$raw_dir/homeops-events.log"
  RHAOMI_HOMEOPS_TEST_OUTCOME=RETAINED
  export RHAOMI_HOMEOPS_TEST_LOG RHAOMI_HOMEOPS_TEST_OUTCOME
  : >"$RHAOMI_HOMEOPS_TEST_LOG"
  printf '%s\n' rhaomi-backup-repository-v1 \
    >"$backup_repository/.rhaomi-backup-repository"
  chmod 600 "$backup_repository/.rhaomi-backup-repository"

  image_id=$(docker image inspect "$production_image" --format '{{.Id}}')
  image_revision=$(docker image inspect "$production_image" \
    --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')
  image_architecture=$(docker image inspect "$production_image" --format '{{.Architecture}}')
  [ "$image_revision" = "$git_head" ] || backup_validation_fail BACKUP_IMAGE_REVISION_INVALID
  case "$(uname -m):${image_architecture}" in
    arm64:arm64 | aarch64:arm64 | x86_64:amd64) ;;
    *) backup_validation_fail BACKUP_IMAGE_ARCHITECTURE_INVALID ;;
  esac

  build_token=$(openssl rand -hex 32)
  postgres_password=$(openssl rand -hex 24)
  prepare_validation_host "$source_root" "$source_project" rhaomi_backup_source source
  prepare_validation_host "$restore_root" "$restore_project" rhaomi_backup_restore restore
  prepare_validation_compose_cli "$source_root/app/docker"

  docker volume ls --format '{{.Name}}' >"$raw_dir/preexisting-volumes.txt"
  docker image ls --no-trunc --format '{{.ID}}' | sort -u >"$raw_dir/preexisting-images.txt"

  source_started=true
  compose_for "$source_root" "$source_project" --profile production-task up --detach postgres >/dev/null
  wait_healthy "$source_root" "$source_project" postgres 90
  compose_for "$source_root" "$source_project" --profile production-task run --rm --no-deps migration \
    >"$raw_dir/source-migration.txt" 2>&1
  seed_source_a
  prepare_validation_media_runtime_layout "$source_root/data/media"
  prepare_runtime_bind_ownership "$source_root"
  transition_validation_media_state "$source_root" "$source_project" runtime
  transition_validation_media_state "$source_root" "$source_project" assert-runtime
  compose_for "$source_root" "$source_project" up --detach rhaomi-web backend publisher postgres >/dev/null
  wait_healthy "$source_root" "$source_project" postgres 90
  wait_healthy "$source_root" "$source_project" backend 180
  wait_healthy "$source_root" "$source_project" rhaomi-web 90
  wait_running "$source_root" "$source_project" publisher 90

  RHAOMI_BACKUP_VALIDATION_COMPOSE_FILE="$source_root/app/compose.production.validation.yaml"
  export RHAOMI_BACKUP_VALIDATION_COMPOSE_FILE
  # shellcheck disable=SC1090
  . "$repo_dir/ops/production/production-lifecycle-core.sh"
  # shellcheck disable=SC1090
  . "$repo_dir/ops/production/backup-rhaomi-core.sh"
  backup_output=$(backup_rhaomi \
    "$source_root" \
    --mode predeploy \
    --target-release-sha "$git_head")
  printf '%s\n' "$backup_output" >"$raw_dir/backup-result.json"
  printf '%s\n' "$backup_output" | grep -Fq '"homeOpsTelemetry": "retained"' ||
    backup_validation_fail BACKUP_HOMEOPS_LIFECYCLE_INVALID
  [ "$(grep -c '^backup ' "$RHAOMI_HOMEOPS_TEST_LOG")" = 2 ] ||
    backup_validation_fail BACKUP_HOMEOPS_LIFECYCLE_INVALID
  [ "$(sed -n '1s/^backup \([^ ]*\).*/\1/p' "$RHAOMI_HOMEOPS_TEST_LOG")" = RUNNING ] ||
    backup_validation_fail BACKUP_HOMEOPS_LIFECYCLE_INVALID
  [ "$(sed -n '2s/^backup \([^ ]*\).*/\1/p' "$RHAOMI_HOMEOPS_TEST_LOG")" = SUCCESS ] ||
    backup_validation_fail BACKUP_HOMEOPS_LIFECYCLE_INVALID
  backup_set_id=$(printf '%s\n' "$backup_output" |
    sed -n 's/^[[:space:]]*"backupSetId": "\([0-9A-Za-z-]*\)",$/\1/p')
  printf '%s' "$backup_set_id" | grep -Eq '^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$' ||
    backup_validation_fail BACKUP_SET_ID_INVALID
  complete_set="$backup_repository/sets/$backup_set_id"
  [ -d "$complete_set" ] && [ ! -L "$complete_set" ] ||
    backup_validation_fail BACKUP_COMPLETE_SET_MISSING
  [ ! -d "$backup_repository/sets/.incomplete-$backup_set_id" ] ||
    backup_validation_fail BACKUP_INCOMPLETE_PROMOTED
  compose_for "$source_root" "$source_project" --profile production-backup run --rm --no-deps \
    --user "$(id -u):$(id -g)" \
    backup-verifier verify-eligibility "$git_head" \
    >"$raw_dir/eligibility-verification.json"
  transition_validation_media_state "$source_root" "$source_project" assert-runtime

  quiesce_source_writers_for_host_mutation
  mutate_source_to_b
  verify_source_b
  compose_for "$source_root" "$source_project" down --remove-orphans >/dev/null
  restore_runtime_bind_ownership "$source_root"
  transition_validation_media_state "$source_root" "$source_project" assert-capture
  source_started=false

  restore_started=true
  compose_for "$restore_root" "$restore_project" --profile production-task up --detach postgres >/dev/null
  wait_healthy "$restore_root" "$restore_project" postgres 90
  dump_path="$complete_set/postgres.dump"
  compose_for "$restore_root" "$restore_project" exec --no-TTY postgres \
    pg_restore --list <"$dump_path" >"$raw_dir/restore-archive-list.txt"
  compose_for "$restore_root" "$restore_project" exec --no-TTY postgres \
    pg_restore \
      --exit-on-error \
      --no-owner \
      --no-privileges \
      --username rhaomi_backup_restore \
      --dbname rhaomi_backup_restore \
    <"$dump_path" >"$raw_dir/pg-restore.txt" 2>&1
  if ! compose_for "$restore_root" "$restore_project" --profile production-backup run --rm --no-deps \
    --user "$(id -u):$(id -g)" backup-tool \
    node /opt/rhaomi/source/scripts/rhaomi-backup-tool.mjs \
    restore-media "$backup_set_id" \
    >"$raw_dir/media-restore.json" 2>"$raw_dir/media-restore.err"; then
    restore_error=$(awk '/^BACKUP_[A-Z_]+$/ { print; exit }' "$raw_dir/media-restore.err")
    [ -z "$restore_error" ] || printf '%s\n' "$restore_error" >&2
    backup_validation_fail BACKUP_MEDIA_RESTORE_FAILED
  fi

  transition_validation_media_state "$restore_root" "$restore_project" capture
  transition_validation_media_state "$restore_root" "$restore_project" assert-capture
  verify_restored_a
  compose_for "$restore_root" "$restore_project" --profile production-task run --rm --no-deps schema-validate \
    >"$raw_dir/restore-schema-validation.txt" 2>&1
  prepare_validation_media_runtime_layout "$restore_root/data/media"
  prepare_runtime_bind_ownership "$restore_root"
  transition_validation_media_state "$restore_root" "$restore_project" runtime
  transition_validation_media_state "$restore_root" "$restore_project" assert-runtime
  compose_for "$restore_root" "$restore_project" up --detach backend >/dev/null
  wait_healthy "$restore_root" "$restore_project" backend 180
  compose_for "$restore_root" "$restore_project" up --detach publisher >/dev/null
  wait_running "$restore_root" "$restore_project" publisher 90
  wait_for_static_publication

  restore_volume="${restore_project}_postgres-data"
  source_volume="${source_project}_postgres-data"
  restore_volume_created=$(docker volume inspect "$restore_volume" --format '{{.CreatedAt}}')
  restore_volume_mountpoint=$(docker volume inspect "$restore_volume" --format '{{.Mountpoint}}')
  verify_task_volume_labels "$restore_volume"
  verify_task_volume_labels "$source_volume"

  compose_for "$restore_root" "$restore_project" restart postgres >/dev/null
  wait_healthy "$restore_root" "$restore_project" postgres 90
  wait_healthy "$restore_root" "$restore_project" backend 180
  verify_restored_a

  compose_for "$restore_root" "$restore_project" down --remove-orphans >/dev/null
  transition_validation_media_state "$restore_root" "$restore_project" capture
  transition_validation_media_state "$restore_root" "$restore_project" assert-capture
  restore_runtime_bind_ownership "$restore_root"
  docker volume inspect "$restore_volume" >/dev/null
  prepare_runtime_bind_ownership "$restore_root"
  transition_validation_media_state "$restore_root" "$restore_project" runtime
  transition_validation_media_state "$restore_root" "$restore_project" assert-runtime
  compose_for "$restore_root" "$restore_project" up --detach postgres backend publisher >/dev/null
  wait_healthy "$restore_root" "$restore_project" postgres 90
  wait_healthy "$restore_root" "$restore_project" backend 180
  wait_running "$restore_root" "$restore_project" publisher 90
  verify_restored_a
  [ "$(docker volume inspect "$restore_volume" --format '{{.CreatedAt}}')" = "$restore_volume_created" ] ||
    backup_validation_fail BACKUP_RESTORE_VOLUME_CHANGED
  [ "$(docker volume inspect "$restore_volume" --format '{{.Mountpoint}}')" = "$restore_volume_mountpoint" ] ||
    backup_validation_fail BACKUP_RESTORE_VOLUME_CHANGED

  media_count=$(restore_database_query \
    "SELECT COUNT(*) FROM media_assets WHERE sha256 = '$media_a_sha'")
  flyway_version=$(restore_database_query \
    "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")

  compose_for "$restore_root" "$restore_project" down --remove-orphans >/dev/null
  transition_validation_media_state "$restore_root" "$restore_project" capture
  transition_validation_media_state "$restore_root" "$restore_project" assert-capture
  restore_runtime_bind_ownership "$restore_root"
  restore_started=false
  docker volume inspect "$source_volume" >/dev/null
  docker volume inspect "$restore_volume" >/dev/null
  verify_preexisting_backup_resources
  verify_no_backup_task_runtime

  completed_epoch=$(date +%s)
  duration_seconds=$((completed_epoch - started_epoch))
  manifest_sha=$(openssl dgst -sha256 "$complete_set/backup-manifest.json" | awk '{print $NF}')

  printf '%s\n' \
    '{' \
    '  "contract": "rhaomi-production-backup-restore-v1",' \
    "  \"gitHead\": \"$git_head\"," \
    "  \"architecture\": \"$image_architecture\"," \
    "  \"backupSetId\": \"$backup_set_id\"," \
    "  \"backupManifestSha256\": \"$manifest_sha\"," \
    '  "sourceSnapshot": "A",' \
    '  "sourceMutationAfterBackup": "B",' \
    '  "isolatedRestoreSnapshot": "A",' \
    "  \"flywayVersion\": \"$flyway_version\"," \
    "  \"restoredMediaCount\": $media_count," \
    '  "schemaValidation": "success",' \
    '  "staticPublication": "success",' \
    '  "postgresRestartPersistence": "success",' \
    '  "composeDownUpPersistence": "success",' \
    '  "sourceCapturePermissionState": "owner-only",' \
    '  "sourceRuntimeRecoveryPermissionState": "verified",' \
    '  "restoreHostPermissionState": "owner-only",' \
    '  "restoreRuntimePermissionState": "verified",' \
    '  "finalHostPermissionState": "owner-only",' \
    '  "homeOpsBackupLifecycle": "verified",' \
    "  \"durationSeconds\": $duration_seconds," \
    '  "sameHostFailureDomain": true,' \
    '  "productionPathMutation": 0,' \
    '  "dockerVolumeDeletion": false,' \
    '  "dockerImageDeletion": false,' \
    '  "status": "success"' \
    '}' >"$evidence_dir/production-backup-restore.json"
  printf '%s\n' \
    "sourceVolume=$source_volume" \
    "restoreVolume=$restore_volume" \
    "restoreVolumeCreatedAt=$restore_volume_created" \
    'sourceVolumeRetained=true' \
    'restoreVolumeRetained=true' \
    'downWithoutVolumeDeletion=true' \
    'volumeDeleteOrPrune=0' \
    >"$evidence_dir/production-backup-volumes.txt"
  validation_succeeded=true

  printf '%s\n' \
    "backupSetId=$backup_set_id" \
    "manifestSha256=$manifest_sha" \
    'sourceAAfterMutationBRestoreA=verified' \
    "flywayVersion=$flyway_version" \
    'schemaValidation=success' \
    'staticPublication=success' \
    'postgresRestartPersistence=success' \
    'composeDownUpPersistence=success' \
    'sourceCapturePermissionState=owner-only' \
    'sourceRuntimeRecoveryPermissionState=verified' \
    'restoreHostPermissionState=owner-only' \
    'restoreRuntimePermissionState=verified' \
    'finalHostPermissionState=owner-only' \
    'homeOpsBackupLifecycle=verified' \
    "durationSeconds=$duration_seconds" \
    'sameHostFailureDomain=true' \
    'productionPathMutation=0' \
    'dockerVolumeDeletion=0' \
    'dockerImageDeletion=0' \
    'status=success'
}

prepare_backup_evidence_directory() {
  if [ -z "$evidence_dir" ]; then
    evidence_dir=$(mktemp -d "${temp_parent%/}/rhaomi-production-backup-evidence.XXXXXX")
    printf '%s\n' "evidence directory: ${evidence_dir}"
    return
  fi
  case "$evidence_dir" in
    /*) ;;
    *) backup_validation_fail BACKUP_EVIDENCE_DIRECTORY_INVALID ;;
  esac
  [ ! -L "$evidence_dir" ] || backup_validation_fail BACKUP_EVIDENCE_DIRECTORY_INVALID
  if [ -d "$evidence_dir" ] &&
    [ -n "$(find "$evidence_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    backup_validation_fail BACKUP_EVIDENCE_DIRECTORY_NOT_EMPTY
  fi
  mkdir -p "$evidence_dir"
  chmod 700 "$evidence_dir"
}

prepare_validation_host() {
  host_root=$1
  project_name=$2
  database_name=$3
  host_role=$4
  mkdir -m 700 \
    "$host_root" \
    "$host_root/app" \
    "$host_root/app/bin" \
    "$host_root/app/docker" \
    "$host_root/app/nginx" \
    "$host_root/data" \
    "$host_root/data/media" \
    "$host_root/state" \
    "$host_root/state/deploy" \
    "$host_root/state/locks" \
    "$host_root/state/publisher" \
    "$host_root/state/publisher/build-workspace" \
    "$host_root/logs"
  mkdir -p "$host_root/public/releases"
  chmod 755 "$host_root/public" "$host_root/public/releases"
  if [ "$host_role" = source ]; then
    mkdir -p "$host_root/public/releases/initial/site"
    chmod 755 "$host_root/public/releases/initial" \
      "$host_root/public/releases/initial/site"
    printf '%s\n' '<!doctype html><html><body>backup validation initial static</body></html>' \
      >"$host_root/public/releases/initial/site/index.html"
    chmod 644 "$host_root/public/releases/initial/site/index.html"
    ln -s releases/initial/site "$host_root/public/current"
  elif [ "$host_role" != restore ]; then
    backup_validation_fail BACKUP_HOST_ROLE_INVALID
  fi
  cp "$repo_dir/compose.production.yaml" "$host_root/app/compose.production.yaml"
  cp "$repo_dir/compose.production.validation.yaml" \
    "$host_root/app/compose.production.validation.yaml"
  cp "$repo_dir/infra/nginx/production.conf" "$host_root/app/nginx/production.conf"
  cp "$repo_dir/scripts/fixtures/fake-homeops-event-adapter.sh" \
    "$host_root/app/bin/report-rhaomi-event.py"
  chmod 700 "$host_root/app/bin/report-rhaomi-event.py"
  chmod 644 "$host_root/app/nginx/production.conf"
  printf '%s\n' '{}' >"$host_root/app/docker/config.json"
  printf '%s\n' \
    "RHAOMI_PRODUCTION_COMPOSE_PROJECT=$project_name" \
    "RHAOMI_PRODUCTION_VALIDATION_ROOT=$host_root" \
    "RHAOMI_BACKUP_REPOSITORY_ROOT=$backup_repository" \
    "RHAOMI_BACKUP_RESTORE_MEDIA_ROOT=$host_root/data/media" \
    "RHAOMI_PRODUCTION_IMAGE=$production_image" \
    "RHAOMI_WEB_LOOPBACK_PORT=$loopback_port" \
    "RHAOMI_POSTGRES_DB=$database_name" \
    "RHAOMI_POSTGRES_USER=$database_name" \
    "RHAOMI_POSTGRES_PASSWORD=$postgres_password" \
    "RHAOMI_BUILD_SERVICE_TOKEN=$build_token" \
    'RHAOMI_WEBAUTHN_RP_ID=validation.invalid' \
    'RHAOMI_WEBAUTHN_ORIGIN=https://validation.invalid' \
    'RHAOMI_WEBAUTHN_RP_NAME=Rhaomi Validation Admin' \
    "RHAOMI_PUBLISHER_OWNER=backup-${project_name}" \
    'RHAOMI_PUBLIC_SITE_URL=https://validation.invalid' \
    "RHAOMI_CODE_SHA=$git_head" \
    "RHAOMI_CODE_IMAGE_TAG=$production_image" \
    "RHAOMI_CODE_IMAGE_DIGEST=$image_id" \
    'RHAOMI_FLYWAY_VERSION=10' \
    "RHAOMI_SBOM_REFERENCE=$image_id" \
    "RHAOMI_CLEANUP_TASK=$cleanup_task" \
    "RHAOMI_CLEANUP_GIT_HEAD=$git_head" \
    >"$host_root/app/production.env"
  chmod 600 "$host_root/app/production.env" "$host_root/app/docker/config.json"
  if [ "$host_role" = source ]; then
    write_validation_lifecycle "$host_root"
  fi
}

write_validation_lifecycle() {
  host_root=$1
  lifecycle_evidence="$host_root/state/deploy/first-activation-recovery.json"
  printf '%s\n' \
    '{' \
    '  "schemaVersion": 1,' \
    "  \"releaseSha\": \"${git_head}\"," \
    "  \"imageDigest\": \"${image_id}\"," \
    '  "state": "STEADY_STATE"' \
    '}' >"$lifecycle_evidence"
  chmod 600 "$lifecycle_evidence"
  lifecycle_hash=$(openssl dgst -sha256 "$lifecycle_evidence" | awk '{print $NF}')
  printf '%s\n' \
    'schemaVersion=1' \
    'state=STEADY_STATE' \
    "releaseSha=$git_head" \
    "imageDigest=$image_id" \
    'updatedAt=2026-09-02T00:00:00Z' \
    'evidenceFile=first-activation-recovery.json' \
    "evidenceSha256=$lifecycle_hash" \
    >"$host_root/state/deploy/production-lifecycle.env"
  chmod 600 "$host_root/state/deploy/production-lifecycle.env"
}

prepare_validation_compose_cli() {
  validation_docker_config=$1
  if command -v docker-compose >/dev/null 2>&1; then
    return 0
  fi
  DOCKER_CONFIG=$validation_docker_config docker compose version >/dev/null 2>&1 ||
    backup_validation_fail BACKUP_COMPOSE_CLI_MISSING
  validation_cli_root="$validation_parent/validation-cli"
  mkdir -m 700 "$validation_cli_root"
  validation_compose_cli="$validation_cli_root/docker-compose"
  printf '%s\n' \
    '#!/bin/sh' \
    'exec docker compose "$@"' \
    >"$validation_compose_cli"
  chmod 700 "$validation_compose_cli"
  PATH="$validation_cli_root:$PATH"
  export PATH
  [ "$(command -v docker-compose)" = "$validation_compose_cli" ] ||
    backup_validation_fail BACKUP_COMPOSE_CLI_MISSING
}

prepare_runtime_bind_ownership() {
  host_root=$1
  if [ "$(uname -s)" != Linux ]; then
    return 0
  fi
  run_runtime_bind_ownership_helper "$host_root" prepare
}

restore_runtime_bind_ownership() {
  host_root=$1
  if [ "$(uname -s)" != Linux ]; then
    return 0
  fi
  run_runtime_bind_ownership_helper "$host_root" restore
}

run_runtime_bind_ownership_helper() {
  host_root=$1
  action=$2
  docker run --rm --network none --read-only \
    --user 0:0 \
    --security-opt no-new-privileges=true \
    --cap-drop ALL \
    --cap-add CHOWN \
    --cap-add DAC_OVERRIDE \
    --cap-add FOWNER \
    --label io.homeserver.cleanup.environment=development \
    --label io.homeserver.cleanup.project=rhaomi \
    --label "io.homeserver.cleanup.task=$cleanup_task" \
    --label io.homeserver.cleanup.lifecycle=task \
    --label io.homeserver.cleanup.retain=false \
    --label "io.homeserver.cleanup.git-head=$git_head" \
    --volume "$host_root:/validation" \
    "$production_image" \
    sh -ec '
      case "$1" in
        prepare)
          chown -R 0:0 /validation/public
          chown -R "$2:$3" /validation/state/deploy
          chown -R "0:$3" \
            /validation/state/locks \
            /validation/state/publisher
          chmod 0755 /validation/public
          chmod 0700 /validation/state/deploy
          find /validation/state/deploy -type d -exec chmod 0700 {} +
          find /validation/state/deploy -type f -exec chmod 0600 {} +
          chmod 0770 /validation/state/locks
          chmod 0750 \
            /validation/state/publisher \
            /validation/state/publisher/build-workspace
          ;;
        restore)
          chown -R "$2:$3" \
            /validation/public \
            /validation/state/deploy \
            /validation/state/locks \
            /validation/state/publisher
          find /validation/public -type d -exec chmod 0755 {} +
          find /validation/public -type f -exec chmod 0644 {} +
          find /validation/state/deploy /validation/state/locks /validation/state/publisher \
            -type d -exec chmod 0700 {} +
          find /validation/state/deploy /validation/state/locks /validation/state/publisher \
            -type f -exec chmod 0600 {} +
          ;;
        *) exit 64 ;;
      esac
    ' sh "$action" "$(id -u)" "$(id -g)"
}

transition_validation_media_state() {
  host_root=$1
  project_name=$2
  action=$3
  case "$action" in
    runtime | capture | assert-runtime | assert-capture) ;;
    *) backup_validation_fail BACKUP_MEDIA_PERMISSION_ACTION_INVALID ;;
  esac
  if [ "$(uname -s)" = Linux ]; then
    compose_for "$host_root" "$project_name" --profile production-backup run --rm --no-deps \
      backup-permission "$action" "$(id -u)" "$(id -g)" >/dev/null ||
      backup_validation_fail BACKUP_MEDIA_PERMISSION_TRANSITION_FAILED
    return 0
  fi
  case "$action" in
    runtime | capture) apply_host_owner_only_media_state "$host_root/data/media" ;;
    assert-runtime | assert-capture) ;;
  esac
  assert_host_owner_only_media_state "$host_root/data/media"
}

apply_host_owner_only_media_state() {
  media_root=$1
  [ -d "$media_root" ] && [ ! -L "$media_root" ] ||
    backup_validation_fail BACKUP_MEDIA_PERMISSION_INVALID
  [ -z "$(find "$media_root" ! -type d ! -type f -print -quit)" ] ||
    backup_validation_fail BACKUP_MEDIA_PERMISSION_INVALID
  find "$media_root" -type d -exec chgrp "$(id -g)" {} + ||
    backup_validation_fail BACKUP_MEDIA_PERMISSION_TRANSITION_FAILED
  find "$media_root" -type f -exec chgrp "$(id -g)" {} + ||
    backup_validation_fail BACKUP_MEDIA_PERMISSION_TRANSITION_FAILED
  find "$media_root" -type d -exec chmod 0700 {} + ||
    backup_validation_fail BACKUP_MEDIA_PERMISSION_TRANSITION_FAILED
  find "$media_root" -type f -exec chmod 0600 {} + ||
    backup_validation_fail BACKUP_MEDIA_PERMISSION_TRANSITION_FAILED
}

prepare_validation_media_runtime_layout() {
  media_root=$1
  [ -d "$media_root" ] && [ ! -L "$media_root" ] ||
    backup_validation_fail BACKUP_MEDIA_PERMISSION_INVALID
  if [ -e "$media_root/temp" ] || [ -L "$media_root/temp" ]; then
    [ -d "$media_root/temp" ] && [ ! -L "$media_root/temp" ] ||
      backup_validation_fail BACKUP_MEDIA_PERMISSION_INVALID
  else
    mkdir -m 0700 "$media_root/temp" ||
      backup_validation_fail BACKUP_MEDIA_PERMISSION_TRANSITION_FAILED
  fi
}

assert_host_owner_only_media_state() {
  media_root=$1
  [ -d "$media_root" ] && [ ! -L "$media_root" ] ||
    backup_validation_fail BACKUP_MEDIA_PERMISSION_INVALID
  [ -z "$(find "$media_root" ! -type d ! -type f -print -quit)" ] ||
    backup_validation_fail BACKUP_MEDIA_PERMISSION_INVALID
  find "$media_root" -type d -exec sh -eu -c '
    expected_owner=$1
    expected_group=$2
    shift 2
    for candidate do
      owner=$(stat -f %u "$candidate" 2>/dev/null || stat -c %u "$candidate")
      group=$(stat -f %g "$candidate" 2>/dev/null || stat -c %g "$candidate")
      mode=$(stat -f %Lp "$candidate" 2>/dev/null || stat -c %a "$candidate")
      [ "$owner" = "$expected_owner" ] && [ "$group" = "$expected_group" ] &&
        [ "$mode" = 700 ] || exit 1
    done
  ' sh "$(id -u)" "$(id -g)" {} + || backup_validation_fail BACKUP_MEDIA_PERMISSION_INVALID
  find "$media_root" -type f -exec sh -eu -c '
    expected_owner=$1
    expected_group=$2
    shift 2
    for candidate do
      owner=$(stat -f %u "$candidate" 2>/dev/null || stat -c %u "$candidate")
      group=$(stat -f %g "$candidate" 2>/dev/null || stat -c %g "$candidate")
      mode=$(stat -f %Lp "$candidate" 2>/dev/null || stat -c %a "$candidate")
      [ "$owner" = "$expected_owner" ] && [ "$group" = "$expected_group" ] &&
        [ "$mode" = 600 ] || exit 1
    done
  ' sh "$(id -u)" "$(id -g)" {} + || backup_validation_fail BACKUP_MEDIA_PERMISSION_INVALID
}

compose_for() {
  host_root=$1
  project_name=$2
  shift 2
  docker compose \
    --project-directory "$host_root/app" \
    --project-name "$project_name" \
    --env-file "$host_root/app/production.env" \
    --file "$host_root/app/compose.production.yaml" \
    --file "$host_root/app/compose.production.validation.yaml" \
    "$@"
}

source_database_query() {
  sql=$1
  database_query_for "$source_root" "$source_project" rhaomi_backup_source "$sql"
}

restore_database_query() {
  sql=$1
  database_query_for "$restore_root" "$restore_project" rhaomi_backup_restore "$sql"
}

database_query_for() {
  host_root=$1
  project_name=$2
  database_name=$3
  sql=$4
  compose_for "$host_root" "$project_name" exec --no-TTY postgres \
    psql -X --set ON_ERROR_STOP=1 \
      --username "$database_name" \
      --dbname "$database_name" \
      --tuples-only --no-align --command "$sql"
}

seed_source_a() {
  media_relative=masters/10/10000000-0000-0000-0000-000000000055.png
  media_path="$source_root/data/media/$media_relative"
  mkdir -m 700 "$source_root/data/media/masters"
  mkdir -m 700 "$source_root/data/media/masters/10"
  cp "$repo_dir/backend/src/test/resources/media/synthetic-source.png" "$media_path"
  chmod 600 "$media_path"
  media_a_sha=$(openssl dgst -sha256 "$media_path" | awk '{print $NF}')
  media_a_size=$(wc -c <"$media_path" | tr -d '[:space:]')
  source_database_query "
    INSERT INTO admin_users (id, email, password_hash)
    VALUES ('00000000-0000-0000-0000-000000000055', 'backup.validation@example.com', 'synthetic-hash');
    INSERT INTO media_assets (
      id, status, source_content_type, content_type, file_extension, storage_key,
      source_byte_size, byte_size, width, height, sha256, created_by, updated_by
    ) VALUES (
      '10000000-0000-0000-0000-000000000055', 'active', 'image/png', 'image/png',
      'png', '$media_relative', $media_a_size, $media_a_size, 64, 48, '$media_a_sha',
      '00000000-0000-0000-0000-000000000055', '00000000-0000-0000-0000-000000000055'
    );
    INSERT INTO breeds (id, status, name, slug, description, sort_order, created_by, updated_by)
    VALUES (
      '20000000-0000-0000-0000-000000000055', 'published', '백업 검증 견종 A',
      'backup-breed-a', '백업 시점 A 설명', 1,
      '00000000-0000-0000-0000-000000000055', '00000000-0000-0000-0000-000000000055'
    );
    INSERT INTO services (
      id, status, name, slug, description, price_text, sort_order, created_by, updated_by
    ) VALUES (
      '30000000-0000-0000-0000-000000000055', 'published', '백업 검증 서비스 A',
      'backup-service-a', '백업 시점 A 서비스', '상담 후 안내', 1,
      '00000000-0000-0000-0000-000000000055', '00000000-0000-0000-0000-000000000055'
    );
    INSERT INTO shop_settings (
      id, shop_name, region_label, business_type, phone, address, opening_time,
      closing_time, closed_weekday, parking_available, parking_note, hero_title,
      hero_description, reservation_notice, hero_image_id, hero_image_alt_text,
      og_image_id, created_by, updated_by
    ) VALUES (
      '40000000-0000-0000-0000-000000000055', '백업 검증 매장 A', '테스트 지역',
      '반려견 미용', '02-000-0055', '테스트시 백업구 검증로 55', '10:00', '19:00',
      'MONDAY', TRUE, '합성 주차 안내', '백업 검증 Hero A', '백업 시점 A 설명',
      '합성 예약 안내', '10000000-0000-0000-0000-000000000055', '합성 Hero 이미지',
      '10000000-0000-0000-0000-000000000055',
      '00000000-0000-0000-0000-000000000055', '00000000-0000-0000-0000-000000000055'
    );
    INSERT INTO gallery_items (
      id, status, dog_name, breed_id, primary_service_id, cover_image_id, summary,
      alt_text, featured, sort_order, performed_at, published_at, created_by, updated_by
    ) VALUES (
      '50000000-0000-0000-0000-000000000055', 'published', '백업 검증 강아지 A',
      '20000000-0000-0000-0000-000000000055', '30000000-0000-0000-0000-000000000055',
      '10000000-0000-0000-0000-000000000055', '백업 시점 갤러리 A', '합성 대표 이미지',
      TRUE, 1, '2020-01-01T00:00:00Z', '2020-01-02T00:00:00Z',
      '00000000-0000-0000-0000-000000000055', '00000000-0000-0000-0000-000000000055'
    );
    INSERT INTO notices (
      id, status, title, slug, summary, body_markdown, pinned, published_at,
      created_by, updated_by
    ) VALUES (
      '60000000-0000-0000-0000-000000000055', 'published', '백업 검증 공지 A',
      'backup-notice-a', '백업 시점 공지 A', '**백업 시점 A 본문**', TRUE,
      '2020-01-01T00:00:00Z', '00000000-0000-0000-0000-000000000055',
      '00000000-0000-0000-0000-000000000055'
    );
    UPDATE content_revision_state SET content_revision = 1 WHERE singleton_key = 1;
    INSERT INTO publishing_outbox (
      id, kind, source_type, source_id, content_revision, available_at
    ) VALUES (
      '70000000-0000-0000-0000-000000000055', 'CONTENT_CHANGED', 'SHOP_SETTINGS',
      '40000000-0000-0000-0000-000000000055', 1,
      CURRENT_TIMESTAMP + INTERVAL '15 seconds'
    );
  " >/dev/null
}

quiesce_source_writers_for_host_mutation() {
  compose_for "$source_root" "$source_project" stop --timeout 30 backend publisher >/dev/null
  verify_validation_writer_quiescence "$source_root" "$source_project"
  transition_validation_media_state "$source_root" "$source_project" capture
  transition_validation_media_state "$source_root" "$source_project" assert-capture
}

verify_validation_writer_quiescence() {
  host_root=$1
  project_name=$2
  for writer in backend publisher; do
    writer_id=$(compose_for "$host_root" "$project_name" ps --all --quiet "$writer") ||
      backup_validation_fail BACKUP_WRITER_ACTIVE
    if [ -n "$writer_id" ]; then
      [ "$(docker inspect "$writer_id" --format '{{.State.Status}}')" = exited ] ||
        backup_validation_fail BACKUP_WRITER_ACTIVE
    fi
  done
}

mutate_source_to_b() {
  media_path="$source_root/data/media/masters/10/10000000-0000-0000-0000-000000000055.png"
  cp "$repo_dir/backend/src/test/resources/media/synthetic-source-2.png" "$media_path"
  chmod 600 "$media_path"
  media_b_sha=$(openssl dgst -sha256 "$media_path" | awk '{print $NF}')
  media_b_size=$(wc -c <"$media_path" | tr -d '[:space:]')
  [ "$media_b_sha" != "$media_a_sha" ] || backup_validation_fail BACKUP_FIXTURE_INVALID
  source_database_query "
    UPDATE shop_settings
    SET shop_name = '백업 이후 변경 B', updated_at = CURRENT_TIMESTAMP
    WHERE id = '40000000-0000-0000-0000-000000000055';
    UPDATE media_assets
    SET sha256 = '$media_b_sha', source_byte_size = $media_b_size,
        byte_size = $media_b_size, updated_at = CURRENT_TIMESTAMP
    WHERE id = '10000000-0000-0000-0000-000000000055';
    UPDATE content_revision_state SET content_revision = 2 WHERE singleton_key = 1;
  " >/dev/null
}

verify_source_b() {
  [ "$(source_database_query "SELECT shop_name FROM shop_settings WHERE id = '40000000-0000-0000-0000-000000000055'")" = '백업 이후 변경 B' ] ||
    backup_validation_fail BACKUP_SOURCE_MUTATION_FAILED
  [ "$(openssl dgst -sha256 "$source_root/data/media/masters/10/10000000-0000-0000-0000-000000000055.png" | awk '{print $NF}')" = "$media_b_sha" ] ||
    backup_validation_fail BACKUP_SOURCE_MUTATION_FAILED
}

verify_restored_a() {
  [ "$(restore_database_query "SELECT shop_name FROM shop_settings WHERE id = '40000000-0000-0000-0000-000000000055'")" = '백업 검증 매장 A' ] ||
    backup_validation_fail BACKUP_RESTORE_CONTENT_MISMATCH
  [ "$(restore_database_query "SELECT content_revision FROM content_revision_state WHERE singleton_key = 1")" = 1 ] ||
    backup_validation_fail BACKUP_RESTORE_CONTENT_MISMATCH
  [ "$(restore_database_query "SELECT COUNT(*) FROM breeds WHERE status = 'published'")" = 1 ] ||
    backup_validation_fail BACKUP_RESTORE_CONTENT_MISMATCH
  [ "$(restore_database_query "SELECT COUNT(*) FROM services WHERE status = 'published'")" = 1 ] ||
    backup_validation_fail BACKUP_RESTORE_CONTENT_MISMATCH
  [ "$(restore_database_query "SELECT COUNT(*) FROM gallery_items WHERE status = 'published'")" = 1 ] ||
    backup_validation_fail BACKUP_RESTORE_CONTENT_MISMATCH
  [ "$(restore_database_query "SELECT COUNT(*) FROM notices WHERE status = 'published'")" = 1 ] ||
    backup_validation_fail BACKUP_RESTORE_CONTENT_MISMATCH
  [ "$(restore_database_query "SELECT COUNT(*) FROM shop_settings WHERE created_by = '00000000-0000-0000-0000-000000000055' AND updated_by = created_by")" = 1 ] ||
    backup_validation_fail BACKUP_RESTORE_AUDIT_MISMATCH
  [ "$(restore_database_query "SELECT sha256 FROM media_assets WHERE id = '10000000-0000-0000-0000-000000000055'")" = "$media_a_sha" ] ||
    backup_validation_fail BACKUP_RESTORE_MEDIA_MISMATCH
  restored_media="$restore_root/data/media/masters/10/10000000-0000-0000-0000-000000000055.png"
  [ -f "$restored_media" ] && [ ! -L "$restored_media" ] ||
    backup_validation_fail BACKUP_RESTORE_MEDIA_MISMATCH
  [ "$(openssl dgst -sha256 "$restored_media" | awk '{print $NF}')" = "$media_a_sha" ] ||
    backup_validation_fail BACKUP_RESTORE_MEDIA_MISMATCH
  docker run --rm --network none \
    --workdir /opt/rhaomi/source \
    --label io.homeserver.cleanup.environment=development \
    --label io.homeserver.cleanup.project=rhaomi \
    --label "io.homeserver.cleanup.task=$cleanup_task" \
    --label io.homeserver.cleanup.lifecycle=task \
    --label io.homeserver.cleanup.retain=false \
    --label "io.homeserver.cleanup.git-head=$git_head" \
    --volume "$restored_media:/validation/master.png:ro" \
    "$production_image" \
    node -e '
      import("sharp").then(({ default: sharp }) => sharp("/validation/master.png").metadata())
        .then((metadata) => {
          if (metadata.format !== "png" || metadata.width !== 64 || metadata.height !== 48) {
            process.exitCode = 1;
          }
        })
        .catch(() => { process.exitCode = 1; });
    ' || backup_validation_fail BACKUP_RESTORE_MEDIA_DECODE_FAILED
}

wait_for_static_publication() {
  attempt=0
  while [ "$attempt" -lt 180 ]; do
    if [ -L "$restore_root/public/current" ] &&
      [ -f "$restore_root/public/current/index.html" ] &&
      grep -Fq '백업 검증 매장 A' "$restore_root/public/current/index.html"; then
      publication_state=$(restore_database_query \
        "SELECT state FROM publishing_outbox WHERE id = '70000000-0000-0000-0000-000000000055'")
      case "$publication_state" in SUCCEEDED | NOOP) return 0 ;; esac
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  publication_diagnostic=$(restore_database_query \
    "SELECT state || '|' || COALESCE(publish_generation::text, 'none') || '|' || attempt_count::text || '|' || COALESCE(last_result_code, 'none') FROM publishing_outbox WHERE id = '70000000-0000-0000-0000-000000000055'")
  printf 'publicationState=%s\n' "$publication_diagnostic" >&2
  compose_for "$restore_root" "$restore_project" logs --no-color --tail 120 publisher >&2 || true
  backup_validation_fail BACKUP_RESTORE_STATIC_PUBLICATION_FAILED
}

wait_healthy() {
  host_root=$1
  project_name=$2
  service=$3
  maximum=$4
  attempt=0
  while [ "$attempt" -lt "$maximum" ]; do
    container_id=$(compose_for "$host_root" "$project_name" ps --all --quiet "$service")
    if [ -n "$container_id" ]; then
      status=$(docker inspect "$container_id" --format '{{.State.Status}}')
      health=$(docker inspect "$container_id" \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
      [ "$health" = healthy ] && return 0
      case "$status:$health" in exited:* | dead:* | *:unhealthy) return 1 ;; esac
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  return 1
}

wait_running() {
  host_root=$1
  project_name=$2
  service=$3
  maximum=$4
  attempt=0
  while [ "$attempt" -lt "$maximum" ]; do
    container_id=$(compose_for "$host_root" "$project_name" ps --quiet "$service")
    if [ -n "$container_id" ] &&
      [ "$(docker inspect "$container_id" --format '{{.State.Status}}')" = running ]; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  return 1
}

verify_task_volume_labels() {
  volume_name=$1
  for pair in \
    'io.homeserver.cleanup.environment=development' \
    'io.homeserver.cleanup.project=rhaomi' \
    "io.homeserver.cleanup.task=$cleanup_task" \
    'io.homeserver.cleanup.lifecycle=task' \
    'io.homeserver.cleanup.retain=false' \
    "io.homeserver.cleanup.git-head=$git_head"; do
    key=${pair%%=*}
    expected=${pair#*=}
    actual=$(docker volume inspect "$volume_name" --format "{{index .Labels \"$key\"}}")
    [ "$actual" = "$expected" ] || backup_validation_fail BACKUP_VOLUME_LABEL_INVALID
  done
}

verify_no_backup_task_runtime() {
  [ -z "$(docker ps --all --quiet --filter "label=io.homeserver.cleanup.task=$cleanup_task" \
    --filter "label=io.homeserver.cleanup.git-head=$git_head")" ] ||
    backup_validation_fail BACKUP_TASK_CONTAINER_REMAINS
  [ -z "$(docker network ls --quiet --filter "label=io.homeserver.cleanup.task=$cleanup_task" \
    --filter "label=io.homeserver.cleanup.git-head=$git_head")" ] ||
    backup_validation_fail BACKUP_TASK_NETWORK_REMAINS
}

verify_preexisting_backup_resources() {
  while IFS= read -r volume_name; do
    [ -z "$volume_name" ] || docker volume inspect "$volume_name" >/dev/null
  done <"$raw_dir/preexisting-volumes.txt"
  while IFS= read -r image_name; do
    [ -z "$image_name" ] || docker image inspect "$image_name" >/dev/null
  done <"$raw_dir/preexisting-images.txt"
}

cleanup_backup_validation() {
  cleanup_result=$?
  trap - EXIT HUP INT TERM
  if [ "$cleanup_result" -ne 0 ] &&
    [ "${validation_succeeded:-false}" != true ] &&
    [ -n "${evidence_dir:-}" ] &&
    [ -d "$evidence_dir" ] &&
    [ ! -L "$evidence_dir" ]; then
    printf '%s\n' \
      'contract=rhaomi-production-backup-restore-v1' \
      "gitHead=${git_head}" \
      'productionPathMutation=0' \
      'dockerVolumeDeletion=0' \
      'status=failure' \
      >"$evidence_dir/production-backup-restore-failure.txt"
  fi
  if [ "${source_started:-false}" = true ]; then
    compose_for "$source_root" "$source_project" down --remove-orphans >/dev/null 2>&1 || true
  fi
  if [ "${restore_started:-false}" = true ]; then
    compose_for "$restore_root" "$restore_project" down --remove-orphans >/dev/null 2>&1 || true
  fi
  if [ -n "$validation_parent" ] &&
    [ -f "$validation_marker" ] &&
    [ "$(sed -n '1p' "$validation_marker")" = "$git_head" ]; then
    restore_backup_validation_ownership
    find "$validation_parent" -type d -exec chmod u+rwx {} + 2>/dev/null || true
    find "$validation_parent" -type f -exec chmod u+rw {} + 2>/dev/null || true
    find "$validation_parent" -depth -delete
  fi
  exit "$cleanup_result"
}

restore_backup_validation_ownership() {
  docker run --rm --network none --read-only \
    --user 0:0 \
    --security-opt no-new-privileges=true \
    --cap-drop ALL \
    --cap-add CHOWN \
    --cap-add DAC_OVERRIDE \
    --cap-add FOWNER \
    --label io.homeserver.cleanup.environment=development \
    --label io.homeserver.cleanup.project=rhaomi \
    --label "io.homeserver.cleanup.task=$cleanup_task" \
    --label io.homeserver.cleanup.lifecycle=task \
    --label io.homeserver.cleanup.retain=false \
    --label "io.homeserver.cleanup.git-head=$git_head" \
    --volume "$validation_parent:/validation" \
    "$production_image" \
    sh -ec 'chown -R "$1:$2" /validation' sh "$(id -u)" "$(id -g)" \
    >/dev/null 2>&1 || true
}

backup_validation_fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

main "$@"
