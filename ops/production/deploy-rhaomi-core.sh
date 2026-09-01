#!/bin/sh

# 이 파일은 fixed production wrapper와 task-scoped validator가 공유한다.
# production root는 wrapper에서 /private/var/lib/rhaomi로 고정한다.

deploy_rhaomi() {
  set -eu
  umask 077

  deploy_root=$1
  shift
  release_sha=
  image_reference=
  sbom_reference=

  parse_deploy_arguments "$@"
  validate_deploy_inputs
  initialize_fixed_authorities
  validate_host_root
  acquire_deploy_lock
  trap deploy_on_exit EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM

  validate_fixed_configuration
  configure_release_environment
  pull_and_verify_release_image
  validate_backup_eligibility
  verify_public_web

  writer_maintenance_active=true
  compose_production stop --timeout 30 backend publisher
  verify_writer_quiescence
  verify_public_web

  compose_production --profile production-task run --rm --no-deps migration
  verify_writer_quiescence
  compose_production --profile production-task run --rm --no-deps schema-validate
  verify_writer_quiescence
  verify_public_web

  compose_production up --detach --no-deps --force-recreate backend
  wait_for_backend_health
  compose_production up --detach --no-deps --force-recreate publisher
  wait_for_publisher_running
  verify_runtime_image_identity

  # success evidence의 maintenanceReleased는 global deploy lock 소유권을
  # 실제로 반납한 뒤에만 기록한다.
  release_deploy_lock || deploy_fail DEPLOY_LOCK_RELEASE_FAILED
  writer_maintenance_active=false

  printf '%s\n' \
    '{' \
    '  "contract": "rhaomi-production-deploy-v1",' \
    "  \"releaseSha\": \"${release_sha}\"," \
    "  \"imageDigest\": \"${image_digest}\"," \
    "  \"sbomReference\": \"${sbom_reference}\"," \
    '  "migration": "success",' \
    '  "schemaValidation": "success",' \
    '  "backendHealth": "UP",' \
    '  "publisher": "running",' \
    '  "maintenanceReleased": true,' \
    '  "status": "success"' \
    '}'
}

parse_deploy_arguments() {
  release_seen=false
  image_seen=false
  sbom_seen=false

  while [ "$#" -gt 0 ]; do
    option=$1
    case "$option" in
      --release-sha)
        [ "$release_seen" = false ] && [ "$#" -ge 2 ] || deploy_fail DEPLOY_INPUT_INVALID
        release_sha=$2
        release_seen=true
        shift 2
        ;;
      --image)
        [ "$image_seen" = false ] && [ "$#" -ge 2 ] || deploy_fail DEPLOY_INPUT_INVALID
        image_reference=$2
        image_seen=true
        shift 2
        ;;
      --sbom)
        [ "$sbom_seen" = false ] && [ "$#" -ge 2 ] || deploy_fail DEPLOY_INPUT_INVALID
        sbom_reference=$2
        sbom_seen=true
        shift 2
        ;;
      *) deploy_fail DEPLOY_INPUT_INVALID ;;
    esac
  done

  [ "$release_seen" = true ] &&
    [ "$image_seen" = true ] &&
    [ "$sbom_seen" = true ] || deploy_fail DEPLOY_INPUT_INVALID
}

validate_deploy_inputs() {
  printf '%s' "$release_sha" | grep -Eq '^[0-9a-f]{40}$' ||
    deploy_fail DEPLOY_INPUT_INVALID
  printf '%s' "$image_reference" |
    grep -Eq '^ghcr[.]io/xxh3898/rhaomi@sha256:[0-9a-f]{64}$' ||
    deploy_fail DEPLOY_INPUT_INVALID
  printf '%s' "$sbom_reference" | grep -Eq '^sha256:[0-9a-f]{64}$' ||
    deploy_fail DEPLOY_INPUT_INVALID

  image_digest=${image_reference#ghcr.io/xxh3898/rhaomi@}
  image_tag="ghcr.io/xxh3898/rhaomi:${release_sha}"
}

initialize_fixed_authorities() {
  app_root="$deploy_root/app"
  compose_file="$app_root/compose.production.yaml"
  environment_file="$app_root/production.env"
  docker_config_root="$app_root/docker"
  docker_config_file="$docker_config_root/config.json"
  backup_gate="$deploy_root/state/deploy/backup-eligible.env"
  backup_evidence_file="$deploy_root/state/deploy/backup-eligibility.json"
  lock_parent="$deploy_root/state/locks"
  deploy_lock="$lock_parent/rhaomi-deploy.lock"
  deploy_lock_owner="$deploy_lock/owner"
  deploy_lock_token="${release_sha}:$$"
  deploy_lock_owned=false
  writer_maintenance_active=false
}

validate_host_root() {
  for directory in \
    "$deploy_root" \
    "$app_root" \
    "$deploy_root/state" \
    "$deploy_root/state/deploy" \
    "$lock_parent"; do
    require_owned_private_directory "$directory"
  done
}

acquire_deploy_lock() {
  if ! mkdir "$deploy_lock" 2>/dev/null; then
    deploy_fail DEPLOY_LOCKED
  fi
  printf '%s\n' "$deploy_lock_token" >"$deploy_lock_owner"
  deploy_lock_owned=true
}

deploy_on_exit() {
  deploy_result=$?
  trap - EXIT HUP INT TERM
  if [ "$deploy_result" -ne 0 ] &&
    [ "${writer_maintenance_active:-false}" = true ]; then
    if ! quiesce_writers_after_failure; then
      printf '%s\n' DEPLOY_FAILURE_QUIESCENCE_UNCONFIRMED >&2
      # writer 배제를 확인하지 못하면 own lock도 남겨 다음 deploy를 차단한다.
      exit 1
    fi
    writer_maintenance_active=false
  fi
  if [ "${deploy_lock_owned:-false}" = true ]; then
    if ! release_deploy_lock; then
      deploy_result=1
    fi
  fi
  exit "$deploy_result"
}

release_deploy_lock() {
  [ -f "$deploy_lock_owner" ] && [ ! -L "$deploy_lock_owner" ] || return 1
  [ "$(sed -n '1p' "$deploy_lock_owner")" = "$deploy_lock_token" ] || return 1
  rm "$deploy_lock_owner"
  rmdir "$deploy_lock"
  deploy_lock_owned=false
}

validate_fixed_configuration() {
  require_regular_file "$compose_file"
  [ "$(portable_owner_id "$compose_file")" = "$(id -u)" ] ||
    deploy_fail DEPLOY_HOST_INVALID
  require_regular_file "$environment_file"
  require_file_mode "$environment_file" 600
  [ "$(portable_owner_id "$environment_file")" = "$(id -u)" ] ||
    deploy_fail DEPLOY_HOST_INVALID
  [ -d "$docker_config_root" ] && [ ! -L "$docker_config_root" ] ||
    deploy_fail DEPLOY_HOST_INVALID
  [ "$(portable_file_mode "$docker_config_root")" = 700 ] ||
    deploy_fail DEPLOY_HOST_INVALID
  [ "$(portable_owner_id "$docker_config_root")" = "$(id -u)" ] ||
    deploy_fail DEPLOY_HOST_INVALID
  require_regular_file "$docker_config_file"
  require_file_mode "$docker_config_file" 600
  [ "$(portable_owner_id "$docker_config_file")" = "$(id -u)" ] ||
    deploy_fail DEPLOY_HOST_INVALID
  compose_mode=$(portable_file_mode "$compose_file")
  case "$compose_mode" in
    640 | 644) ;;
    *) deploy_fail DEPLOY_HOST_INVALID ;;
  esac
}

validate_backup_eligibility() {
  [ -f "$backup_gate" ] && [ ! -L "$backup_gate" ] ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
  [ "$(portable_file_mode "$backup_gate")" = 600 ] ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
  [ "$(portable_owner_id "$backup_gate")" = "$(id -u)" ] ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
  backup_contract=$(sed -n '1,5p' "$backup_gate")
  backup_lines=$(wc -l <"$backup_gate" | tr -d '[:space:]')
  [ "$backup_lines" = 4 ] || deploy_fail DEPLOY_BACKUP_REQUIRED
  expected_prefix=$(printf '%s\n' \
    'schemaVersion=1' \
    'status=eligible' \
    "releaseSha=${release_sha}")
  actual_prefix=$(printf '%s\n' "$backup_contract" | sed -n '1,3p')
  [ "$actual_prefix" = "$expected_prefix" ] || deploy_fail DEPLOY_BACKUP_REQUIRED
  backup_evidence=$(printf '%s\n' "$backup_contract" | sed -n '4s/^evidenceSha256=//p')
  printf '%s' "$backup_evidence" | grep -Eq '^[0-9a-f]{64}$' ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
  [ -f "$backup_evidence_file" ] && [ ! -L "$backup_evidence_file" ] ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
  [ "$(portable_file_mode "$backup_evidence_file")" = 600 ] ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
  [ "$(portable_owner_id "$backup_evidence_file")" = "$(id -u)" ] ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
  actual_evidence_hash=$(openssl dgst -sha256 "$backup_evidence_file" | awk '{print $NF}')
  [ "$actual_evidence_hash" = "$backup_evidence" ] ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
  load_deploy_backup_repository_authority
  validate_deploy_backup_repository
  RHAOMI_BACKUP_REPOSITORY_ROOT=$deploy_backup_repository_root
  export RHAOMI_BACKUP_REPOSITORY_ROOT
  compose_production --profile production-backup run --rm --no-deps \
    --user "$(id -u):$(id -g)" \
    backup-tool \
    node /opt/rhaomi/source/scripts/rhaomi-backup-tool.mjs \
    verify-eligibility "$release_sha" >/dev/null ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
}

load_deploy_backup_repository_authority() {
  repository_line_count=$(awk '
    BEGIN { count = 0 }
    /^RHAOMI_BACKUP_REPOSITORY_ROOT=/ { count += 1 }
    END { print count }
  ' "$environment_file")
  [ "$repository_line_count" = 1 ] || deploy_fail DEPLOY_BACKUP_REQUIRED
  deploy_backup_repository_root=$(sed -n 's/^RHAOMI_BACKUP_REPOSITORY_ROOT=//p' "$environment_file")
  [ -n "$deploy_backup_repository_root" ] || deploy_fail DEPLOY_BACKUP_REQUIRED
  case "$deploy_backup_repository_root" in
    /*) ;;
    *) deploy_fail DEPLOY_BACKUP_REQUIRED ;;
  esac
  case "$deploy_backup_repository_root" in
    *'/../'* | *'/./'* | */.. | */. | *'\'* | *[[:cntrl:]])
      deploy_fail DEPLOY_BACKUP_REQUIRED
      ;;
  esac
}

validate_deploy_backup_repository() {
  require_owned_private_directory "$deploy_backup_repository_root"
  [ "$(portable_file_mode "$deploy_backup_repository_root")" = 700 ] ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
  repository_physical=$(cd "$deploy_backup_repository_root" && pwd -P)
  deploy_physical=$(cd "$deploy_root" && pwd -P)
  [ "$repository_physical" = "$deploy_backup_repository_root" ] ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
  case "$repository_physical" in
    "$deploy_physical" | "$deploy_physical"/*) deploy_fail DEPLOY_BACKUP_REQUIRED ;;
  esac
  repository_sentinel="$deploy_backup_repository_root/.rhaomi-backup-repository"
  [ -f "$repository_sentinel" ] && [ ! -L "$repository_sentinel" ] ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
  [ "$(portable_file_mode "$repository_sentinel")" = 600 ] ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
  [ "$(sed -n '1p' "$repository_sentinel")" = rhaomi-backup-repository-v1 ] ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
  require_owned_private_directory "$deploy_backup_repository_root/sets"
  [ "$(portable_file_mode "$deploy_backup_repository_root/sets")" = 700 ] ||
    deploy_fail DEPLOY_BACKUP_REQUIRED
}

configure_release_environment() {
  for variable_name in \
    COMPOSE_FILE \
    COMPOSE_ENV_FILES \
    COMPOSE_PROFILES \
    COMPOSE_PROJECT_NAME \
    DOCKER_CONTEXT \
    DOCKER_DEFAULT_PLATFORM \
    DOCKER_HOST \
    DOCKER_TLS_VERIFY \
    RHAOMI_PRODUCTION_COMPOSE_PROJECT \
    RHAOMI_WEB_LOOPBACK_PORT \
    RHAOMI_POSTGRES_DB \
    RHAOMI_POSTGRES_USER \
    RHAOMI_POSTGRES_PASSWORD \
    RHAOMI_BUILD_SERVICE_TOKEN \
    RHAOMI_PUBLISHER_OWNER \
    RHAOMI_PUBLIC_SITE_URL \
    RHAOMI_PRODUCTION_IMAGE \
    RHAOMI_CODE_SHA \
    RHAOMI_CODE_IMAGE_TAG \
    RHAOMI_CODE_IMAGE_DIGEST \
    RHAOMI_FLYWAY_VERSION \
    RHAOMI_SBOM_REFERENCE \
    RHAOMI_BACKUP_REPOSITORY_ROOT; do
    unset "$variable_name"
  done

  RHAOMI_PRODUCTION_IMAGE=$image_reference
  RHAOMI_CODE_SHA=$release_sha
  RHAOMI_CODE_IMAGE_TAG=$image_tag
  RHAOMI_CODE_IMAGE_DIGEST=$image_digest
  RHAOMI_FLYWAY_VERSION=9
  RHAOMI_SBOM_REFERENCE=$sbom_reference
  DOCKER_CONFIG=$docker_config_root
  export \
    RHAOMI_PRODUCTION_IMAGE \
    RHAOMI_CODE_SHA \
    RHAOMI_CODE_IMAGE_TAG \
    RHAOMI_CODE_IMAGE_DIGEST \
    RHAOMI_FLYWAY_VERSION \
    RHAOMI_SBOM_REFERENCE \
    DOCKER_CONFIG
}

compose_production() {
  docker compose \
    --project-directory "$app_root" \
    --env-file "$environment_file" \
    --file "$compose_file" \
    "$@"
}

pull_and_verify_release_image() {
  docker pull "$image_reference"
  repo_digests=$(docker image inspect \
    --format '{{range .RepoDigests}}{{println .}}{{end}}' "$image_reference")
  printf '%s\n' "$repo_digests" | grep -Fxq "$image_reference" ||
    deploy_fail DEPLOY_IMAGE_INVALID
  image_revision=$(docker image inspect \
    --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
    "$image_reference")
  [ "$image_revision" = "$release_sha" ] || deploy_fail DEPLOY_IMAGE_INVALID
  release_image_id=$(docker image inspect --format '{{.Id}}' "$image_reference")
  printf '%s' "$release_image_id" | grep -Eq '^sha256:[0-9a-f]{64}$' ||
    deploy_fail DEPLOY_IMAGE_INVALID
}

verify_public_web() {
  web_id=$(compose_production ps --quiet rhaomi-web)
  [ -n "$web_id" ] || deploy_fail DEPLOY_PUBLIC_UNAVAILABLE
  [ "$(docker inspect --format '{{.State.Status}}' "$web_id")" = running ] ||
    deploy_fail DEPLOY_PUBLIC_UNAVAILABLE
  compose_production exec --no-TTY rhaomi-web \
    wget -qO- http://127.0.0.1:8080/ >/dev/null
}

verify_writer_quiescence() {
  writers_are_quiescent || deploy_fail DEPLOY_WRITER_ACTIVE
}

writers_are_quiescent() {
  for writer in backend publisher; do
    writer_id=$(compose_production ps --all --quiet "$writer") || return 1
    if [ -n "$writer_id" ]; then
      writer_status=$(docker inspect --format '{{.State.Status}}' "$writer_id") || return 1
      [ "$writer_status" = exited ] || return 1
    fi
  done
  return 0
}

quiesce_writers_after_failure() {
  compose_production stop --timeout 30 backend publisher || return 1
  writers_are_quiescent
}

wait_for_backend_health() {
  backend_id=$(compose_production ps --quiet backend)
  [ -n "$backend_id" ] || deploy_fail DEPLOY_BACKEND_UNHEALTHY
  wait_for_container_state "$backend_id" healthy 180 DEPLOY_BACKEND_UNHEALTHY
}

wait_for_publisher_running() {
  publisher_id=$(compose_production ps --quiet publisher)
  [ -n "$publisher_id" ] || deploy_fail DEPLOY_PUBLISHER_FAILED
  wait_for_container_state "$publisher_id" running 60 DEPLOY_PUBLISHER_FAILED
}

wait_for_container_state() {
  container_id=$1
  expected_state=$2
  maximum=$3
  error_code=$4
  attempt=0
  while [ "$attempt" -lt "$maximum" ]; do
    if [ "$expected_state" = healthy ]; then
      current_state=$(docker inspect \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        "$container_id")
    else
      current_state=$(docker inspect --format '{{.State.Status}}' "$container_id")
    fi
    if [ "$current_state" = "$expected_state" ]; then
      return 0
    fi
    case "$current_state" in
      exited | dead | unhealthy) deploy_fail "$error_code" ;;
    esac
    attempt=$((attempt + 1))
    sleep 1
  done
  deploy_fail "$error_code"
}

verify_runtime_image_identity() {
  for runtime_service in backend publisher; do
    runtime_id=$(compose_production ps --quiet "$runtime_service")
    [ -n "$runtime_id" ] || deploy_fail DEPLOY_RUNTIME_IMAGE_INVALID
    runtime_image_id=$(docker inspect --format '{{.Image}}' "$runtime_id")
    [ "$runtime_image_id" = "$release_image_id" ] ||
      deploy_fail DEPLOY_RUNTIME_IMAGE_INVALID
  done
}

require_regular_file() {
  required_file=$1
  [ -f "$required_file" ] && [ ! -L "$required_file" ] ||
    deploy_fail DEPLOY_HOST_INVALID
}

require_file_mode() {
  required_file=$1
  expected_mode=$2
  [ "$(portable_file_mode "$required_file")" = "$expected_mode" ] ||
    deploy_fail DEPLOY_HOST_INVALID
}

require_owned_private_directory() {
  required_directory=$1
  [ -d "$required_directory" ] && [ ! -L "$required_directory" ] ||
    deploy_fail DEPLOY_HOST_INVALID
  [ "$(portable_owner_id "$required_directory")" = "$(id -u)" ] ||
    deploy_fail DEPLOY_HOST_INVALID
  printf '%s' "$(portable_file_mode "$required_directory")" |
    grep -Eq '^7[0145][0145]$' || deploy_fail DEPLOY_HOST_INVALID
}

portable_file_mode() {
  mode_file=$1
  if mode_value=$(stat -f '%Lp' "$mode_file" 2>/dev/null); then
    printf '%s\n' "$mode_value"
  else
    stat -c '%a' "$mode_file"
  fi
}

portable_owner_id() {
  owner_file=$1
  if owner_value=$(stat -f '%u' "$owner_file" 2>/dev/null); then
    printf '%s\n' "$owner_value"
  else
    stat -c '%u' "$owner_file"
  fi
}

deploy_fail() {
  printf '%s\n' "$1" >&2
  exit 1
}
