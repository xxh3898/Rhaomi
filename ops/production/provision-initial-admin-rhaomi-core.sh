#!/bin/sh

# fixed production wrapper와 task-scoped validator가 공유한다.
# production root와 credential transport는 caller argument로 바꿀 수 없다.

provision_initial_admin_rhaomi() {
  set -eu
  umask 077

  initial_admin_root=$1
  shift
  [ "$#" -eq 0 ] || initial_admin_fail INITIAL_ADMIN_INPUT_INVALID

  initialize_initial_admin_authorities
  initial_admin_require_interactive_terminal ||
    initial_admin_fail INITIAL_ADMIN_INTERACTIVE_TERMINAL_REQUIRED
  validate_initial_admin_host_root
  acquire_initial_admin_lock
  trap initial_admin_on_exit EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM

  validate_initial_admin_fixed_configuration
  rhaomi_lifecycle_initialize "$initial_admin_root"
  rhaomi_lifecycle_require_state STEADY_STATE ||
    initial_admin_fail INITIAL_ADMIN_LIFECYCLE_INVALID
  configure_initial_admin_environment
  inspect_initial_admin_source_identity

  initial_admin_writer_maintenance=true
  initial_admin_compose stop --timeout 30 backend publisher ||
    initial_admin_fail INITIAL_ADMIN_WRITER_STOP_FAILED
  initial_admin_writers_are_quiescent || {
    initial_admin_lock_preserve=true
    initial_admin_fail INITIAL_ADMIN_WRITER_QUIESCENCE_UNCONFIRMED
  }

  initial_admin_compose --profile production-task run --rm --no-deps initial-admin ||
    initial_admin_fail INITIAL_ADMIN_TASK_FAILED

  restore_initial_admin_writers || {
    initial_admin_lock_preserve=true
    initial_admin_fail INITIAL_ADMIN_WRITER_RECOVERY_FAILED
  }
  initial_admin_writer_maintenance=false

  release_initial_admin_lock || initial_admin_fail INITIAL_ADMIN_LOCK_RELEASE_FAILED
  printf '%s\n' \
    '{"contract":"rhaomi-initial-admin-host-v1","status":"success","writerRecovery":"verified"}'
}

initialize_initial_admin_authorities() {
  initial_admin_app_root="$initial_admin_root/app"
  initial_admin_compose_file="$initial_admin_app_root/compose.production.yaml"
  initial_admin_environment_file="$initial_admin_app_root/production.env"
  initial_admin_docker_config_root="$initial_admin_app_root/docker"
  initial_admin_docker_config_file="$initial_admin_docker_config_root/config.json"
  initial_admin_lock_parent="$initial_admin_root/state/locks"
  initial_admin_lock="$initial_admin_lock_parent/rhaomi-deploy.lock"
  initial_admin_lock_owner="$initial_admin_lock/owner"
  initial_admin_lock_token="initial-admin:$$:$(date -u '+%Y%m%dT%H%M%SZ')"
  initial_admin_lock_owned=false
  initial_admin_lock_preserve=false
  initial_admin_writer_maintenance=false
  initial_admin_failure_code=INITIAL_ADMIN_FAILED
}

initial_admin_require_interactive_terminal() {
  [ -t 0 ] && [ -t 1 ]
}

validate_initial_admin_host_root() {
  for directory in \
    "$initial_admin_root" \
    "$initial_admin_app_root" \
    "$initial_admin_root/state" \
    "$initial_admin_root/state/deploy" \
    "$initial_admin_lock_parent"; do
    initial_admin_require_owned_private_directory "$directory"
  done
}

validate_initial_admin_fixed_configuration() {
  command -v docker >/dev/null 2>&1 || initial_admin_fail INITIAL_ADMIN_HOST_INVALID
  initial_admin_require_regular_file "$initial_admin_compose_file"
  initial_admin_require_regular_file "$initial_admin_environment_file"
  [ "$(initial_admin_file_mode "$initial_admin_environment_file")" = 600 ] ||
    initial_admin_fail INITIAL_ADMIN_HOST_INVALID
  [ -d "$initial_admin_docker_config_root" ] &&
    [ ! -L "$initial_admin_docker_config_root" ] &&
    [ "$(initial_admin_file_mode "$initial_admin_docker_config_root")" = 700 ] &&
    [ "$(initial_admin_owner_id "$initial_admin_docker_config_root")" = "$(id -u)" ] ||
    initial_admin_fail INITIAL_ADMIN_HOST_INVALID
  initial_admin_require_regular_file "$initial_admin_docker_config_file"
  [ "$(initial_admin_file_mode "$initial_admin_docker_config_file")" = 600 ] ||
    initial_admin_fail INITIAL_ADMIN_HOST_INVALID
  DOCKER_CONFIG=$initial_admin_docker_config_root
  export DOCKER_CONFIG
  docker compose version >/dev/null 2>&1 || initial_admin_fail INITIAL_ADMIN_HOST_INVALID
}

configure_initial_admin_environment() {
  for variable_name in \
    COMPOSE_FILE COMPOSE_ENV_FILES COMPOSE_PROFILES COMPOSE_PROJECT_NAME \
    DOCKER_CONTEXT DOCKER_DEFAULT_PLATFORM DOCKER_HOST DOCKER_TLS_VERIFY \
    RHAOMI_INITIAL_ADMIN_EMAIL RHAOMI_INITIAL_ADMIN_PASSWORD \
    RHAOMI_BOOTSTRAP_ADMIN_EMAIL RHAOMI_BOOTSTRAP_ADMIN_PASSWORD; do
    unset "$variable_name"
  done
  DOCKER_CONFIG=$initial_admin_docker_config_root
  export DOCKER_CONFIG
}

inspect_initial_admin_source_identity() {
  initial_admin_backend_id=$(initial_admin_compose ps --quiet backend) ||
    initial_admin_fail INITIAL_ADMIN_WRITER_UNAVAILABLE
  initial_admin_publisher_id=$(initial_admin_compose ps --quiet publisher) ||
    initial_admin_fail INITIAL_ADMIN_WRITER_UNAVAILABLE
  [ -n "$initial_admin_backend_id" ] && [ -n "$initial_admin_publisher_id" ] ||
    initial_admin_fail INITIAL_ADMIN_WRITER_UNAVAILABLE
  [ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$initial_admin_backend_id")" = healthy ] ||
    initial_admin_fail INITIAL_ADMIN_WRITER_UNAVAILABLE
  [ "$(docker inspect --format '{{.State.Status}}' "$initial_admin_publisher_id")" = running ] ||
    initial_admin_fail INITIAL_ADMIN_WRITER_UNAVAILABLE

  initial_admin_source_image_id=$(docker inspect --format '{{.Image}}' "$initial_admin_backend_id")
  [ "$initial_admin_source_image_id" = "$(docker inspect --format '{{.Image}}' "$initial_admin_publisher_id")" ] ||
    initial_admin_fail INITIAL_ADMIN_SOURCE_IDENTITY_INVALID
  printf '%s' "$initial_admin_source_image_id" | grep -Eq '^sha256:[0-9a-f]{64}$' ||
    initial_admin_fail INITIAL_ADMIN_SOURCE_IDENTITY_INVALID
  initial_admin_source_image_reference=$(docker inspect --format '{{.Config.Image}}' "$initial_admin_backend_id")
  [ "$initial_admin_source_image_reference" = "$(docker inspect --format '{{.Config.Image}}' "$initial_admin_publisher_id")" ] ||
    initial_admin_fail INITIAL_ADMIN_SOURCE_IDENTITY_INVALID
  printf '%s' "$initial_admin_source_image_reference" |
    grep -Eq '^ghcr[.]io/xxh3898/rhaomi@sha256:[0-9a-f]{64}$' ||
    initial_admin_fail INITIAL_ADMIN_SOURCE_IDENTITY_INVALID
  initial_admin_source_image_digest=${initial_admin_source_image_reference#ghcr.io/xxh3898/rhaomi@}
  [ "$initial_admin_source_image_digest" = "$lifecycle_image_digest" ] ||
    initial_admin_fail INITIAL_ADMIN_SOURCE_IDENTITY_INVALID
  initial_admin_source_revision=$(docker inspect \
    --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
    "$initial_admin_backend_id")
  [ "$initial_admin_source_revision" = "$lifecycle_release_sha" ] ||
    initial_admin_fail INITIAL_ADMIN_SOURCE_IDENTITY_INVALID
  RHAOMI_PRODUCTION_IMAGE=$initial_admin_source_image_reference
  export RHAOMI_PRODUCTION_IMAGE
}

initial_admin_compose() {
  docker compose \
    --project-directory "$initial_admin_app_root" \
    --env-file "$initial_admin_environment_file" \
    --file "$initial_admin_compose_file" \
    "$@"
}

initial_admin_writers_are_quiescent() {
  for writer in backend publisher; do
    writer_id=$(initial_admin_compose ps --all --quiet "$writer") || return 1
    if [ -n "$writer_id" ]; then
      [ "$(docker inspect --format '{{.State.Status}}' "$writer_id")" = exited ] || return 1
    fi
  done
  return 0
}

restore_initial_admin_writers() {
  initial_admin_compose up --detach --no-deps --force-recreate backend || return 1
  initial_admin_backend_id=$(initial_admin_compose ps --quiet backend) || return 1
  [ -n "$initial_admin_backend_id" ] || return 1
  initial_admin_wait_for_container "$initial_admin_backend_id" healthy 180 || return 1

  initial_admin_compose up --detach --no-deps --force-recreate publisher || return 1
  initial_admin_publisher_id=$(initial_admin_compose ps --quiet publisher) || return 1
  [ -n "$initial_admin_publisher_id" ] || return 1
  initial_admin_wait_for_container "$initial_admin_publisher_id" running 60 || return 1

  [ "$(docker inspect --format '{{.Image}}' "$initial_admin_backend_id")" = "$initial_admin_source_image_id" ] || return 1
  [ "$(docker inspect --format '{{.Image}}' "$initial_admin_publisher_id")" = "$initial_admin_source_image_id" ] || return 1
}

initial_admin_wait_for_container() {
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

acquire_initial_admin_lock() {
  mkdir "$initial_admin_lock" 2>/dev/null || initial_admin_fail INITIAL_ADMIN_LOCKED
  printf '%s\n' "$initial_admin_lock_token" >"$initial_admin_lock_owner"
  initial_admin_lock_owned=true
}

release_initial_admin_lock() {
  [ -f "$initial_admin_lock_owner" ] && [ ! -L "$initial_admin_lock_owner" ] || return 1
  [ "$(sed -n '1p' "$initial_admin_lock_owner")" = "$initial_admin_lock_token" ] || return 1
  rm "$initial_admin_lock_owner"
  rmdir "$initial_admin_lock"
  initial_admin_lock_owned=false
}

initial_admin_on_exit() {
  initial_admin_result=$?
  trap - EXIT HUP INT TERM

  if [ "$initial_admin_result" -ne 0 ] &&
    [ "${initial_admin_writer_maintenance:-false}" = true ]; then
    if restore_initial_admin_writers; then
      initial_admin_writer_maintenance=false
    else
      initial_admin_lock_preserve=true
      printf '%s\n' INITIAL_ADMIN_WRITER_RECOVERY_FAILED >&2
    fi
  fi

  if [ "${initial_admin_lock_owned:-false}" = true ] &&
    [ "${initial_admin_lock_preserve:-false}" = false ]; then
    release_initial_admin_lock || initial_admin_result=1
  fi
  exit "$initial_admin_result"
}

initial_admin_require_regular_file() {
  required_file=$1
  [ -f "$required_file" ] && [ ! -L "$required_file" ] ||
    initial_admin_fail INITIAL_ADMIN_HOST_INVALID
  [ "$(initial_admin_owner_id "$required_file")" = "$(id -u)" ] ||
    initial_admin_fail INITIAL_ADMIN_HOST_INVALID
}

initial_admin_require_owned_private_directory() {
  required_directory=$1
  [ -d "$required_directory" ] && [ ! -L "$required_directory" ] ||
    initial_admin_fail INITIAL_ADMIN_HOST_INVALID
  [ "$(initial_admin_owner_id "$required_directory")" = "$(id -u)" ] ||
    initial_admin_fail INITIAL_ADMIN_HOST_INVALID
  printf '%s' "$(initial_admin_file_mode "$required_directory")" |
    grep -Eq '^7[0145][0145]$' || initial_admin_fail INITIAL_ADMIN_HOST_INVALID
}

initial_admin_file_mode() {
  mode_file=$1
  if mode_value=$(stat -f '%Lp' "$mode_file" 2>/dev/null); then
    printf '%s\n' "$mode_value"
  else
    stat -c '%a' "$mode_file"
  fi
}

initial_admin_owner_id() {
  owner_file=$1
  if owner_value=$(stat -f '%u' "$owner_file" 2>/dev/null); then
    printf '%s\n' "$owner_value"
  else
    stat -c '%u' "$owner_file"
  fi
}

initial_admin_fail() {
  initial_admin_failure_code=$1
  printf '%s\n' "$initial_admin_failure_code" >&2
  exit 1
}
