#!/bin/sh

# fixed production wrapper와 task-scoped validator가 공유한다.
# production root와 bundle path는 caller argument로 바꿀 수 없다.

import_initial_content_rhaomi() {
  set -eu
  umask 077

  initial_content_root=$1
  shift
  [ "$#" -eq 0 ] || initial_content_fail INITIAL_CONTENT_INPUT_INVALID

  initialize_initial_content_authorities
  validate_initial_content_host_root
  validate_initial_content_bundle_permissions
  acquire_initial_content_lock
  trap initial_content_on_exit EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM

  validate_initial_content_fixed_configuration
  rhaomi_lifecycle_initialize "$initial_content_root"
  rhaomi_lifecycle_require_state STEADY_STATE ||
    initial_content_fail INITIAL_CONTENT_LIFECYCLE_INVALID
  configure_initial_content_environment
  inspect_initial_content_source_identity

  initial_content_writer_maintenance=true
  initial_content_compose stop --timeout 30 backend publisher ||
    initial_content_fail INITIAL_CONTENT_WRITER_STOP_FAILED
  initial_content_writers_are_quiescent || {
    initial_content_lock_preserve=true
    initial_content_fail INITIAL_CONTENT_WRITER_QUIESCENCE_UNCONFIRMED
  }

  initial_content_compose --profile production-task run --rm --no-deps initial-content ||
    initial_content_fail INITIAL_CONTENT_TASK_FAILED

  restore_initial_content_writers || {
    initial_content_lock_preserve=true
    initial_content_fail INITIAL_CONTENT_WRITER_RECOVERY_FAILED
  }
  initial_content_writer_maintenance=false

  release_initial_content_lock || initial_content_fail INITIAL_CONTENT_LOCK_RELEASE_FAILED
  printf '%s\n' \
    '{"contract":"rhaomi-initial-content-host-v1","status":"success","contentCommit":"verified","publicationStatus":"PENDING","writerRecovery":"verified"}'
}

initialize_initial_content_authorities() {
  initial_content_app_root="$initial_content_root/app"
  initial_content_compose_file="$initial_content_app_root/compose.production.yaml"
  initial_content_environment_file="$initial_content_app_root/production.env"
  initial_content_docker_config_root="$initial_content_app_root/docker"
  initial_content_docker_config_file="$initial_content_docker_config_root/config.json"
  initial_content_bundle_root="$initial_content_root/state/initial-content"
  initial_content_lock_parent="$initial_content_root/state/locks"
  initial_content_lock="$initial_content_lock_parent/rhaomi-deploy.lock"
  initial_content_lock_owner="$initial_content_lock/owner"
  initial_content_lock_token="initial-content:$$:$(date -u '+%Y%m%dT%H%M%SZ')"
  initial_content_lock_owned=false
  initial_content_lock_preserve=false
  initial_content_writer_maintenance=false
  initial_content_failure_code=INITIAL_CONTENT_FAILED
}

validate_initial_content_host_root() {
  for directory in \
    "$initial_content_root" \
    "$initial_content_app_root" \
    "$initial_content_root/state" \
    "$initial_content_root/state/deploy" \
    "$initial_content_lock_parent"; do
    initial_content_require_owned_private_directory "$directory"
  done
}

validate_initial_content_bundle_permissions() {
  [ -d "$initial_content_bundle_root" ] && [ ! -L "$initial_content_bundle_root" ] ||
    initial_content_fail INITIAL_CONTENT_BUNDLE_INVALID
  [ "$(initial_content_owner_id "$initial_content_bundle_root")" = "$(id -u)" ] &&
    [ "$(initial_content_file_mode "$initial_content_bundle_root")" = 700 ] ||
    initial_content_fail INITIAL_CONTENT_BUNDLE_INVALID

  [ -z "$(find "$initial_content_bundle_root" -type l -print -quit)" ] ||
    initial_content_fail INITIAL_CONTENT_BUNDLE_INVALID
  [ -z "$(find "$initial_content_bundle_root" ! -type d ! -type f -print -quit)" ] ||
    initial_content_fail INITIAL_CONTENT_BUNDLE_INVALID

  find "$initial_content_bundle_root" -type d -print | while IFS= read -r directory; do
    [ "$(initial_content_owner_id "$directory")" = "$(id -u)" ] &&
      [ "$(initial_content_file_mode "$directory")" = 700 ] || exit 1
  done || initial_content_fail INITIAL_CONTENT_BUNDLE_INVALID

  find "$initial_content_bundle_root" -type f -print | while IFS= read -r file; do
    [ "$(initial_content_owner_id "$file")" = "$(id -u)" ] &&
      [ "$(initial_content_file_mode "$file")" = 600 ] &&
      [ "$(initial_content_link_count "$file")" = 1 ] || exit 1
  done || initial_content_fail INITIAL_CONTENT_BUNDLE_INVALID
}

validate_initial_content_fixed_configuration() {
  command -v docker >/dev/null 2>&1 || initial_content_fail INITIAL_CONTENT_HOST_INVALID
  initial_content_require_regular_file "$initial_content_compose_file"
  initial_content_require_regular_file "$initial_content_environment_file"
  [ "$(initial_content_file_mode "$initial_content_environment_file")" = 600 ] ||
    initial_content_fail INITIAL_CONTENT_HOST_INVALID
  [ -d "$initial_content_docker_config_root" ] &&
    [ ! -L "$initial_content_docker_config_root" ] &&
    [ "$(initial_content_file_mode "$initial_content_docker_config_root")" = 700 ] &&
    [ "$(initial_content_owner_id "$initial_content_docker_config_root")" = "$(id -u)" ] ||
    initial_content_fail INITIAL_CONTENT_HOST_INVALID
  initial_content_require_regular_file "$initial_content_docker_config_file"
  [ "$(initial_content_file_mode "$initial_content_docker_config_file")" = 600 ] ||
    initial_content_fail INITIAL_CONTENT_HOST_INVALID
  DOCKER_CONFIG=$initial_content_docker_config_root
  export DOCKER_CONFIG
  docker compose version >/dev/null 2>&1 || initial_content_fail INITIAL_CONTENT_HOST_INVALID
}

configure_initial_content_environment() {
  for variable_name in \
    COMPOSE_FILE COMPOSE_ENV_FILES COMPOSE_PROFILES COMPOSE_PROJECT_NAME \
    DOCKER_CONTEXT DOCKER_DEFAULT_PLATFORM DOCKER_HOST DOCKER_TLS_VERIFY \
    RHAOMI_BOOTSTRAP_ADMIN_EMAIL RHAOMI_BOOTSTRAP_ADMIN_PASSWORD \
    RHAOMI_INITIAL_ADMIN_EMAIL RHAOMI_INITIAL_ADMIN_PASSWORD; do
    unset "$variable_name"
  done
  DOCKER_CONFIG=$initial_content_docker_config_root
  export DOCKER_CONFIG
}

inspect_initial_content_source_identity() {
  initial_content_backend_id=$(initial_content_compose ps --quiet backend) ||
    initial_content_fail INITIAL_CONTENT_WRITER_UNAVAILABLE
  initial_content_publisher_id=$(initial_content_compose ps --quiet publisher) ||
    initial_content_fail INITIAL_CONTENT_WRITER_UNAVAILABLE
  [ -n "$initial_content_backend_id" ] && [ -n "$initial_content_publisher_id" ] ||
    initial_content_fail INITIAL_CONTENT_WRITER_UNAVAILABLE
  [ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$initial_content_backend_id")" = healthy ] ||
    initial_content_fail INITIAL_CONTENT_WRITER_UNAVAILABLE
  [ "$(docker inspect --format '{{.State.Status}}' "$initial_content_publisher_id")" = running ] ||
    initial_content_fail INITIAL_CONTENT_WRITER_UNAVAILABLE

  initial_content_source_image_id=$(docker inspect --format '{{.Image}}' "$initial_content_backend_id")
  [ "$initial_content_source_image_id" = "$(docker inspect --format '{{.Image}}' "$initial_content_publisher_id")" ] ||
    initial_content_fail INITIAL_CONTENT_SOURCE_IDENTITY_INVALID
  printf '%s' "$initial_content_source_image_id" | grep -Eq '^sha256:[0-9a-f]{64}$' ||
    initial_content_fail INITIAL_CONTENT_SOURCE_IDENTITY_INVALID
  initial_content_source_image_reference=$(docker inspect --format '{{.Config.Image}}' "$initial_content_backend_id")
  [ "$initial_content_source_image_reference" = "$(docker inspect --format '{{.Config.Image}}' "$initial_content_publisher_id")" ] ||
    initial_content_fail INITIAL_CONTENT_SOURCE_IDENTITY_INVALID
  printf '%s' "$initial_content_source_image_reference" |
    grep -Eq '^ghcr[.]io/xxh3898/rhaomi@sha256:[0-9a-f]{64}$' ||
    initial_content_fail INITIAL_CONTENT_SOURCE_IDENTITY_INVALID
  initial_content_source_image_digest=${initial_content_source_image_reference#ghcr.io/xxh3898/rhaomi@}
  [ "$initial_content_source_image_digest" = "$lifecycle_image_digest" ] ||
    initial_content_fail INITIAL_CONTENT_SOURCE_IDENTITY_INVALID
  initial_content_source_revision=$(docker inspect \
    --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
    "$initial_content_backend_id")
  [ "$initial_content_source_revision" = "$lifecycle_release_sha" ] ||
    initial_content_fail INITIAL_CONTENT_SOURCE_IDENTITY_INVALID
  RHAOMI_PRODUCTION_IMAGE=$initial_content_source_image_reference
  export RHAOMI_PRODUCTION_IMAGE
}

initial_content_compose() {
  docker compose \
    --project-directory "$initial_content_app_root" \
    --env-file "$initial_content_environment_file" \
    --file "$initial_content_compose_file" \
    "$@"
}

initial_content_writers_are_quiescent() {
  for writer in backend publisher; do
    writer_id=$(initial_content_compose ps --all --quiet "$writer") || return 1
    if [ -n "$writer_id" ]; then
      [ "$(docker inspect --format '{{.State.Status}}' "$writer_id")" = exited ] || return 1
    fi
  done
  return 0
}

restore_initial_content_writers() {
  initial_content_compose up --detach --no-deps --force-recreate backend || return 1
  initial_content_backend_id=$(initial_content_compose ps --quiet backend) || return 1
  [ -n "$initial_content_backend_id" ] || return 1
  initial_content_wait_for_container "$initial_content_backend_id" healthy 180 || return 1

  initial_content_compose up --detach --no-deps --force-recreate publisher || return 1
  initial_content_publisher_id=$(initial_content_compose ps --quiet publisher) || return 1
  [ -n "$initial_content_publisher_id" ] || return 1
  initial_content_wait_for_container "$initial_content_publisher_id" running 60 || return 1

  [ "$(docker inspect --format '{{.Image}}' "$initial_content_backend_id")" = "$initial_content_source_image_id" ] || return 1
  [ "$(docker inspect --format '{{.Image}}' "$initial_content_publisher_id")" = "$initial_content_source_image_id" ] || return 1
}

initial_content_wait_for_container() {
  container_id=$1
  expected_state=$2
  maximum=$3
  attempt=0
  while [ "$attempt" -lt "$maximum" ]; do
    if [ "$expected_state" = healthy ]; then
      current_state=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id") || return 1
    else
      current_state=$(docker inspect --format '{{.State.Status}}' "$container_id") || return 1
    fi
    [ "$current_state" = "$expected_state" ] && return 0
    case "$current_state" in exited | dead | unhealthy) return 1 ;; esac
    attempt=$((attempt + 1))
    sleep 1
  done
  return 1
}

acquire_initial_content_lock() {
  mkdir "$initial_content_lock" 2>/dev/null || initial_content_fail INITIAL_CONTENT_LOCKED
  printf '%s\n' "$initial_content_lock_token" >"$initial_content_lock_owner"
  initial_content_lock_owned=true
}

release_initial_content_lock() {
  [ -f "$initial_content_lock_owner" ] && [ ! -L "$initial_content_lock_owner" ] || return 1
  [ "$(sed -n '1p' "$initial_content_lock_owner")" = "$initial_content_lock_token" ] || return 1
  rm "$initial_content_lock_owner"
  rmdir "$initial_content_lock"
  initial_content_lock_owned=false
}

initial_content_on_exit() {
  initial_content_result=$?
  trap - EXIT HUP INT TERM

  if [ "$initial_content_result" -ne 0 ] &&
    [ "${initial_content_writer_maintenance:-false}" = true ]; then
    if restore_initial_content_writers; then
      initial_content_writer_maintenance=false
    else
      initial_content_lock_preserve=true
      printf '%s\n' INITIAL_CONTENT_WRITER_RECOVERY_FAILED >&2
    fi
  fi

  if [ "${initial_content_lock_owned:-false}" = true ] &&
    [ "${initial_content_lock_preserve:-false}" = false ]; then
    release_initial_content_lock || initial_content_result=1
  fi
  exit "$initial_content_result"
}

initial_content_require_regular_file() {
  required_file=$1
  [ -f "$required_file" ] && [ ! -L "$required_file" ] ||
    initial_content_fail INITIAL_CONTENT_HOST_INVALID
  [ "$(initial_content_owner_id "$required_file")" = "$(id -u)" ] ||
    initial_content_fail INITIAL_CONTENT_HOST_INVALID
}

initial_content_require_owned_private_directory() {
  required_directory=$1
  [ -d "$required_directory" ] && [ ! -L "$required_directory" ] ||
    initial_content_fail INITIAL_CONTENT_HOST_INVALID
  [ "$(initial_content_owner_id "$required_directory")" = "$(id -u)" ] ||
    initial_content_fail INITIAL_CONTENT_HOST_INVALID
  printf '%s' "$(initial_content_file_mode "$required_directory")" |
    grep -Eq '^7[0145][0145]$' || initial_content_fail INITIAL_CONTENT_HOST_INVALID
}

initial_content_file_mode() {
  if mode_value=$(stat -f '%Lp' "$1" 2>/dev/null); then
    printf '%s\n' "$mode_value"
  else
    stat -c '%a' "$1"
  fi
}

initial_content_owner_id() {
  if owner_value=$(stat -f '%u' "$1" 2>/dev/null); then
    printf '%s\n' "$owner_value"
  else
    stat -c '%u' "$1"
  fi
}

initial_content_link_count() {
  if link_value=$(stat -f '%l' "$1" 2>/dev/null); then
    printf '%s\n' "$link_value"
  else
    stat -c '%h' "$1"
  fi
}

initial_content_fail() {
  initial_content_failure_code=$1
  printf '%s\n' "$initial_content_failure_code" >&2
  exit 1
}
