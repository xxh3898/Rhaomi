#!/bin/sh

# fixed production wrapper와 task-scoped validator가 공유한다.
# production root, lifecycle state와 recovery root는 caller가 바꿀 수 없다.

first_activate_rhaomi() {
  set -eu
  umask 077

  first_activation_root=$1
  shift
  first_activation_mode=
  release_sha=
  image_reference=
  sbom_reference=

  parse_first_activation_arguments "$@"
  validate_first_activation_inputs
  initialize_first_activation_authorities
  validate_first_activation_host_root
  acquire_first_activation_lock
  trap first_activation_on_exit EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM

  validate_first_activation_fixed_configuration
  configure_first_activation_environment

  case "$first_activation_mode" in
    bootstrap) execute_first_activation_bootstrap ;;
    accept-recovery) execute_first_activation_recovery ;;
    *) first_activation_fail FIRST_ACTIVATION_INPUT_INVALID ;;
  esac

  release_first_activation_lock || first_activation_fail FIRST_ACTIVATION_LOCK_RELEASE_FAILED
}

parse_first_activation_arguments() {
  mode_seen=false
  release_seen=false
  image_seen=false
  sbom_seen=false
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --mode)
        [ "$mode_seen" = false ] && [ "$#" -ge 2 ] ||
          first_activation_fail FIRST_ACTIVATION_INPUT_INVALID
        first_activation_mode=$2
        mode_seen=true
        shift 2
        ;;
      --release-sha)
        [ "$release_seen" = false ] && [ "$#" -ge 2 ] ||
          first_activation_fail FIRST_ACTIVATION_INPUT_INVALID
        release_sha=$2
        release_seen=true
        shift 2
        ;;
      --image)
        [ "$image_seen" = false ] && [ "$#" -ge 2 ] ||
          first_activation_fail FIRST_ACTIVATION_INPUT_INVALID
        image_reference=$2
        image_seen=true
        shift 2
        ;;
      --sbom)
        [ "$sbom_seen" = false ] && [ "$#" -ge 2 ] ||
          first_activation_fail FIRST_ACTIVATION_INPUT_INVALID
        sbom_reference=$2
        sbom_seen=true
        shift 2
        ;;
      *) first_activation_fail FIRST_ACTIVATION_INPUT_INVALID ;;
    esac
  done
  [ "$mode_seen" = true ] &&
    [ "$release_seen" = true ] &&
    [ "$image_seen" = true ] &&
    [ "$sbom_seen" = true ] || first_activation_fail FIRST_ACTIVATION_INPUT_INVALID
  case "$first_activation_mode" in bootstrap | accept-recovery) ;; *)
    first_activation_fail FIRST_ACTIVATION_INPUT_INVALID ;;
  esac
}

validate_first_activation_inputs() {
  printf '%s' "$release_sha" | grep -Eq '^[0-9a-f]{40}$' ||
    first_activation_fail FIRST_ACTIVATION_INPUT_INVALID
  printf '%s' "$image_reference" |
    grep -Eq '^ghcr[.]io/xxh3898/rhaomi@sha256:[0-9a-f]{64}$' ||
    first_activation_fail FIRST_ACTIVATION_INPUT_INVALID
  printf '%s' "$sbom_reference" | grep -Eq '^sha256:[0-9a-f]{64}$' ||
    first_activation_fail FIRST_ACTIVATION_INPUT_INVALID
  image_digest=${image_reference#ghcr.io/xxh3898/rhaomi@}
  image_tag="ghcr.io/xxh3898/rhaomi:${release_sha}"
}

initialize_first_activation_authorities() {
  first_activation_app_root="$first_activation_root/app"
  first_activation_compose_file="$first_activation_app_root/compose.production.yaml"
  first_activation_recovery_compose_file="$first_activation_app_root/compose.production.first-activation.yaml"
  first_activation_environment_file="$first_activation_app_root/production.env"
  first_activation_docker_config_root="$first_activation_app_root/docker"
  first_activation_docker_config_file="$first_activation_docker_config_root/config.json"
  first_activation_lock_parent="$first_activation_root/state/locks"
  first_activation_lock="$first_activation_lock_parent/rhaomi-deploy.lock"
  first_activation_lock_owner="$first_activation_lock/owner"
  first_activation_lock_token="first-activation:${release_sha}:$$"
  first_activation_lock_owned=false
  first_activation_mutation_started=false
  first_activation_recovery_active=false
  first_activation_lock_preserve=false
  first_activation_recovery_root="$first_activation_root/state/recovery-acceptance"
  first_activation_validation_compose_file=${RHAOMI_FIRST_ACTIVATION_VALIDATION_COMPOSE_FILE:-}
  rhaomi_lifecycle_initialize "$first_activation_root"
}

validate_first_activation_host_root() {
  for directory in \
    "$first_activation_root" \
    "$first_activation_app_root" \
    "$first_activation_root/data" \
    "$first_activation_root/data/media" \
    "$first_activation_root/public" \
    "$first_activation_root/public/releases" \
    "$first_activation_lock_parent" \
    "$first_activation_root/state/publisher"; do
    first_activation_require_owned_directory "$directory"
  done
  first_activation_require_owned_private_directory "$first_activation_root/state"
  first_activation_require_owned_private_directory "$first_activation_root/state/deploy"
}

acquire_first_activation_lock() {
  mkdir "$first_activation_lock" 2>/dev/null ||
    first_activation_fail FIRST_ACTIVATION_LOCKED
  printf '%s\n' "$first_activation_lock_token" >"$first_activation_lock_owner"
  first_activation_lock_owned=true
}

release_first_activation_lock() {
  [ -f "$first_activation_lock_owner" ] && [ ! -L "$first_activation_lock_owner" ] || return 1
  [ "$(sed -n '1p' "$first_activation_lock_owner")" = "$first_activation_lock_token" ] || return 1
  rm "$first_activation_lock_owner"
  rmdir "$first_activation_lock"
  first_activation_lock_owned=false
}

first_activation_on_exit() {
  first_activation_result=$?
  trap - EXIT HUP INT TERM
  if [ "$first_activation_result" -ne 0 ]; then
    if [ "${first_activation_recovery_active:-false}" = true ]; then
      if ! stop_first_activation_recovery; then
        first_activation_lock_preserve=true
        printf '%s\n' FIRST_ACTIVATION_RECOVERY_QUIESCENCE_UNCONFIRMED >&2
      fi
    fi
    if [ "${first_activation_mutation_started:-false}" = true ]; then
      if ! quiesce_first_activation_writers; then
        first_activation_lock_preserve=true
        printf '%s\n' FIRST_ACTIVATION_WRITER_QUIESCENCE_UNCONFIRMED >&2
      fi
    fi
  fi
  if [ "${first_activation_lock_owned:-false}" = true ] &&
    [ "${first_activation_lock_preserve:-false}" = false ]; then
    release_first_activation_lock || first_activation_result=1
  fi
  exit "$first_activation_result"
}

validate_first_activation_fixed_configuration() {
  for command in docker openssl; do
    command -v "$command" >/dev/null 2>&1 ||
      first_activation_fail FIRST_ACTIVATION_HOST_INVALID
  done
  docker compose version >/dev/null 2>&1 ||
    first_activation_fail FIRST_ACTIVATION_HOST_INVALID
  for required_file in \
    "$first_activation_compose_file" \
    "$first_activation_recovery_compose_file" \
    "$first_activation_environment_file" \
    "$first_activation_docker_config_file"; do
    first_activation_require_regular_file "$required_file"
  done
  [ "$(first_activation_file_mode "$first_activation_environment_file")" = 600 ] ||
    first_activation_fail FIRST_ACTIVATION_HOST_INVALID
  [ -d "$first_activation_docker_config_root" ] &&
    [ ! -L "$first_activation_docker_config_root" ] &&
    [ "$(first_activation_file_mode "$first_activation_docker_config_root")" = 700 ] &&
    [ "$(first_activation_owner_id "$first_activation_docker_config_root")" = "$(id -u)" ] ||
    first_activation_fail FIRST_ACTIVATION_HOST_INVALID
  [ "$(first_activation_file_mode "$first_activation_docker_config_file")" = 600 ] ||
    first_activation_fail FIRST_ACTIVATION_HOST_INVALID
  if [ -n "$first_activation_validation_compose_file" ]; then
    [ "$first_activation_validation_compose_file" = \
      "$first_activation_app_root/compose.production.first-activation.validation.yaml" ] ||
      first_activation_fail FIRST_ACTIVATION_HOST_INVALID
    first_activation_require_regular_file "$first_activation_validation_compose_file"
  fi

  first_activation_project=$(first_activation_environment_value RHAOMI_PRODUCTION_COMPOSE_PROJECT)
  printf '%s' "$first_activation_project" | grep -Eq '^[a-z0-9][a-z0-9_-]{0,62}$' ||
    first_activation_fail FIRST_ACTIVATION_CONFIG_INVALID
  first_activation_backup_repository=$(first_activation_environment_value RHAOMI_BACKUP_REPOSITORY_ROOT)
  case "$first_activation_backup_repository" in /*) ;; *)
    first_activation_fail FIRST_ACTIVATION_CONFIG_INVALID ;;
  esac
  case "$first_activation_backup_repository" in
    *'/../'* | *'/./'* | */.. | */. | *'\'* | *[[:cntrl:]])
      first_activation_fail FIRST_ACTIVATION_CONFIG_INVALID
      ;;
  esac
  first_activation_recovery_project="${first_activation_project}-first-activation-recovery"
  [ "${#first_activation_recovery_project}" -le 63 ] ||
    first_activation_fail FIRST_ACTIVATION_CONFIG_INVALID
}

first_activation_environment_value() {
  first_activation_environment_key=$1
  first_activation_environment_count=$(awk -F= -v key="$first_activation_environment_key" '
    $1 == key { count += 1 }
    END { print count + 0 }
  ' "$first_activation_environment_file")
  [ "$first_activation_environment_count" = 1 ] ||
    first_activation_fail FIRST_ACTIVATION_CONFIG_INVALID
  sed -n "s/^${first_activation_environment_key}=//p" "$first_activation_environment_file"
}

configure_first_activation_environment() {
  for variable_name in \
    COMPOSE_FILE COMPOSE_ENV_FILES COMPOSE_PROFILES COMPOSE_PROJECT_NAME \
    DOCKER_CONTEXT DOCKER_DEFAULT_PLATFORM DOCKER_HOST DOCKER_TLS_VERIFY \
    RHAOMI_PRODUCTION_IMAGE RHAOMI_CODE_SHA RHAOMI_CODE_IMAGE_TAG \
    RHAOMI_CODE_IMAGE_DIGEST RHAOMI_FLYWAY_VERSION RHAOMI_SBOM_REFERENCE \
    RHAOMI_FIRST_ACTIVATION_APP_ROOT \
    RHAOMI_FIRST_ACTIVATION_RECOVERY_ROOT RHAOMI_FIRST_ACTIVATION_RECOVERY_PROJECT \
    RHAOMI_FIRST_ACTIVATION_BACKUP_REPOSITORY RHAOMI_FIRST_ACTIVATION_PROBE_MARKER; do
    unset "$variable_name"
  done
  RHAOMI_PRODUCTION_IMAGE=$image_reference
  RHAOMI_CODE_SHA=$release_sha
  RHAOMI_CODE_IMAGE_TAG=$image_tag
  RHAOMI_CODE_IMAGE_DIGEST=$image_digest
  RHAOMI_FLYWAY_VERSION=10
  RHAOMI_SBOM_REFERENCE=$sbom_reference
  RHAOMI_FIRST_ACTIVATION_APP_ROOT=$first_activation_app_root
  RHAOMI_FIRST_ACTIVATION_RECOVERY_ROOT=$first_activation_recovery_root
  RHAOMI_FIRST_ACTIVATION_RECOVERY_PROJECT=$first_activation_recovery_project
  RHAOMI_FIRST_ACTIVATION_BACKUP_REPOSITORY=$first_activation_backup_repository
  RHAOMI_FIRST_ACTIVATION_PROBE_MARKER="first-activation-${release_sha}"
  DOCKER_CONFIG=$first_activation_docker_config_root
  export \
    RHAOMI_PRODUCTION_IMAGE RHAOMI_CODE_SHA RHAOMI_CODE_IMAGE_TAG \
    RHAOMI_CODE_IMAGE_DIGEST RHAOMI_FLYWAY_VERSION RHAOMI_SBOM_REFERENCE \
    RHAOMI_FIRST_ACTIVATION_APP_ROOT \
    RHAOMI_FIRST_ACTIVATION_RECOVERY_ROOT RHAOMI_FIRST_ACTIVATION_RECOVERY_PROJECT \
    RHAOMI_FIRST_ACTIVATION_BACKUP_REPOSITORY RHAOMI_FIRST_ACTIVATION_PROBE_MARKER \
    DOCKER_CONFIG
}

execute_first_activation_bootstrap() {
  verify_first_activation_empty
  record_verified_empty_evidence
  bootstrap_started_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  record_bootstrap_evidence RUNNING -
  rhaomi_lifecycle_write_state \
    FIRST_ACTIVATION_BOOTSTRAPPING "$release_sha" "$image_digest" \
    "$bootstrap_started_at" first-activation-bootstrap.json ||
    first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  first_activation_mutation_started=true

  pull_and_verify_first_activation_image
  compose_first_activation up --detach --no-deps postgres ||
    first_activation_fail FIRST_ACTIVATION_POSTGRES_FAILED
  wait_for_first_activation_service postgres healthy 180 FIRST_ACTIVATION_POSTGRES_FAILED
  compose_first_activation --profile production-task run --rm --no-deps migration ||
    first_activation_fail FIRST_ACTIVATION_MIGRATION_FAILED
  verify_first_activation_flyway
  compose_first_activation --profile production-task run --rm --no-deps schema-validate ||
    first_activation_fail FIRST_ACTIVATION_SCHEMA_INVALID
  compose_first_activation up --detach --no-deps --force-recreate backend ||
    first_activation_fail FIRST_ACTIVATION_BACKEND_FAILED
  wait_for_first_activation_service backend healthy 180 FIRST_ACTIVATION_BACKEND_FAILED
  compose_first_activation up --detach --no-deps --force-recreate publisher ||
    first_activation_fail FIRST_ACTIVATION_PUBLISHER_FAILED
  wait_for_first_activation_service publisher running 60 FIRST_ACTIVATION_PUBLISHER_FAILED
  verify_first_activation_runtime_identity
  verify_first_activation_private_runtime

  bootstrap_completed_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  record_bootstrap_evidence RECOVERY_ACCEPTANCE_REQUIRED "$bootstrap_completed_at"
  rhaomi_lifecycle_write_state \
    RECOVERY_ACCEPTANCE_REQUIRED "$release_sha" "$image_digest" \
    "$bootstrap_completed_at" first-activation-bootstrap.json ||
    first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  first_activation_mutation_started=false

  printf '%s\n' \
    '{' \
    '  "contract": "rhaomi-first-activation-v1",' \
    '  "mode": "bootstrap",' \
    "  \"releaseSha\": \"${release_sha}\"," \
    '  "state": "RECOVERY_ACCEPTANCE_REQUIRED",' \
    '  "publicIngressActivated": false,' \
    '  "status": "success"' \
    '}'
}

verify_first_activation_empty() {
  first_activation_require_empty_tree "$first_activation_root/state/deploy"
  for path in \
    "$first_activation_root/public/current" \
    "$first_activation_root/public/previous" \
    "$first_activation_recovery_root"; do
    [ ! -e "$path" ] && [ ! -L "$path" ] ||
      first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  done
  first_activation_require_empty_tree "$first_activation_root/public/releases"
  for tree in \
    "$first_activation_root/data/media" \
    "$first_activation_root/state/publisher"; do
    first_activation_require_directory_only_tree "$tree"
  done

  validate_empty_backup_repository
  project_containers=$(docker ps --all --quiet \
    --filter "label=com.docker.compose.project=$first_activation_project") ||
    first_activation_fail FIRST_ACTIVATION_STATE_UNKNOWN
  [ -z "$project_containers" ] || first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  project_volume="${first_activation_project}_postgres-data"
  project_volumes=$(docker volume ls --quiet --filter "name=^${project_volume}$") ||
    first_activation_fail FIRST_ACTIVATION_STATE_UNKNOWN
  if printf '%s\n' "$project_volumes" | grep -Fxq "$project_volume"; then
    first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  fi
}

first_activation_require_empty_tree() {
  empty_tree=$1
  empty_tree_entry=$(find "$empty_tree" -mindepth 1 -print -quit) ||
    first_activation_fail FIRST_ACTIVATION_STATE_UNKNOWN
  [ -z "$empty_tree_entry" ] || first_activation_fail FIRST_ACTIVATION_STATE_INVALID
}

first_activation_require_directory_only_tree() {
  directory_tree=$1
  non_directory_entry=$(find "$directory_tree" -mindepth 1 ! -type d -print -quit) ||
    first_activation_fail FIRST_ACTIVATION_STATE_UNKNOWN
  [ -z "$non_directory_entry" ] || first_activation_fail FIRST_ACTIVATION_STATE_INVALID
}

validate_empty_backup_repository() {
  first_activation_require_owned_private_directory "$first_activation_backup_repository"
  first_activation_require_owned_private_directory "$first_activation_backup_repository/sets"
  case "$first_activation_backup_repository" in
    "$first_activation_root" | "$first_activation_root"/*)
      first_activation_fail FIRST_ACTIVATION_CONFIG_INVALID
      ;;
  esac
  backup_sentinel="$first_activation_backup_repository/.rhaomi-backup-repository"
  first_activation_require_private_file "$backup_sentinel"
  [ "$(sed -n '1p' "$backup_sentinel")" = rhaomi-backup-repository-v1 ] &&
    [ "$(wc -l <"$backup_sentinel" | tr -d '[:space:]')" = 1 ] ||
    first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  first_activation_require_empty_tree "$first_activation_backup_repository/sets"
}

record_verified_empty_evidence() {
  checked_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  rhaomi_lifecycle_begin_evidence first-activation-verified-empty.json ||
    first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  printf '%s\n' \
    '{' \
    '  "schemaVersion": 1,' \
    "  \"checkedAt\": \"${checked_at}\"," \
    "  \"targetReleaseSha\": \"${release_sha}\"," \
    '  "status": "VERIFIED_EMPTY",' \
    '  "predecessor": {' \
    '    "currentRelease": false,' \
    '    "previousRelease": false,' \
    '    "deployMarker": false,' \
    '    "backupEligibility": false,' \
    '    "completeBackupSet": false,' \
    '    "productionContainer": false,' \
    '    "postgresDataAuthority": false,' \
    '    "privateMediaAuthority": false,' \
    '    "publicReleaseAuthority": false' \
    '  },' \
    '  "productionResource": {' \
    "    \"composeProject\": \"${first_activation_project}\"," \
    "    \"postgresVolume\": \"${project_volume}\"" \
    '  }' \
    '}' >"$rhaomi_lifecycle_evidence_temp" ||
    first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  rhaomi_lifecycle_commit_evidence first-activation-verified-empty.json ||
    first_activation_fail FIRST_ACTIVATION_STATE_INVALID
}

record_bootstrap_evidence() {
  bootstrap_state=$1
  bootstrap_finished_at=$2
  if [ "$bootstrap_state" = RUNNING ]; then
    bootstrap_completed_json=null
    bootstrap_check_status=pending
    rhaomi_lifecycle_begin_evidence first-activation-bootstrap.json ||
      first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  else
    bootstrap_completed_json="\"$bootstrap_finished_at\""
    bootstrap_check_status=success
    rhaomi_lifecycle_replace_evidence first-activation-bootstrap.json ||
      first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  fi
  verified_empty_sha=$(openssl dgst -sha256 \
    "$first_activation_root/state/deploy/first-activation-verified-empty.json" | awk '{print $NF}')
  printf '%s\n' \
    '{' \
    '  "schemaVersion": 1,' \
    "  \"releaseSha\": \"${release_sha}\"," \
    "  \"imageDigest\": \"${image_digest}\"," \
    "  \"startedAt\": \"${bootstrap_started_at}\"," \
    "  \"completedAt\": ${bootstrap_completed_json}," \
    "  \"verifiedEmptySha256\": \"${verified_empty_sha}\"," \
    "  \"state\": \"${bootstrap_state}\"," \
    "  \"migration\": \"${bootstrap_check_status}\"," \
    "  \"schemaValidation\": \"${bootstrap_check_status}\"," \
    "  \"backendHealth\": \"${bootstrap_check_status}\"," \
    "  \"publisher\": \"${bootstrap_check_status}\"," \
    '  "publicIngressActivated": false' \
    '}' >"$rhaomi_lifecycle_evidence_temp" ||
    first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  rhaomi_lifecycle_commit_evidence first-activation-bootstrap.json ||
    first_activation_fail FIRST_ACTIVATION_STATE_INVALID
}

pull_and_verify_first_activation_image() {
  docker pull "$image_reference" || first_activation_fail FIRST_ACTIVATION_IMAGE_INVALID
  docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$image_reference" |
    grep -Fxq "$image_reference" || first_activation_fail FIRST_ACTIVATION_IMAGE_INVALID
  [ "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image_reference")" = "$release_sha" ] ||
    first_activation_fail FIRST_ACTIVATION_IMAGE_INVALID
  first_activation_image_id=$(docker image inspect --format '{{.Id}}' "$image_reference")
  printf '%s' "$first_activation_image_id" | grep -Eq '^sha256:[0-9a-f]{64}$' ||
    first_activation_fail FIRST_ACTIVATION_IMAGE_INVALID
}

verify_first_activation_flyway() {
  first_activation_flyway=$(compose_first_activation exec --no-TTY postgres sh -ec \
    'exec psql --no-psqlrc --tuples-only --no-align --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --command="SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"')
  [ "$first_activation_flyway" = 10 ] ||
    first_activation_fail FIRST_ACTIVATION_SCHEMA_INVALID
}

verify_first_activation_runtime_identity() {
  for service in backend publisher; do
    container_id=$(compose_first_activation ps --quiet "$service")
    [ -n "$container_id" ] || first_activation_fail FIRST_ACTIVATION_RUNTIME_INVALID
    [ "$(docker inspect --format '{{.Image}}' "$container_id")" = "$first_activation_image_id" ] ||
      first_activation_fail FIRST_ACTIVATION_RUNTIME_INVALID
  done
}

verify_first_activation_private_runtime() {
  web_container=$(compose_first_activation ps --all --quiet rhaomi-web)
  [ -z "$web_container" ] || first_activation_fail FIRST_ACTIVATION_PUBLIC_EXPOSURE_INVALID
  for service in postgres backend publisher; do
    container_id=$(compose_first_activation ps --quiet "$service")
    [ -n "$container_id" ] || first_activation_fail FIRST_ACTIVATION_RUNTIME_INVALID
    [ "$(docker inspect --format '{{len .HostConfig.PortBindings}}' "$container_id")" = 0 ] ||
      first_activation_fail FIRST_ACTIVATION_PUBLIC_EXPOSURE_INVALID
  done
}

execute_first_activation_recovery() {
  rhaomi_lifecycle_require_state RECOVERY_ACCEPTANCE_REQUIRED "$release_sha" "$image_digest" ||
    first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  validate_first_activation_backup_candidate
  [ ! -e "$first_activation_recovery_root" ] && [ ! -L "$first_activation_recovery_root" ] ||
    first_activation_fail FIRST_ACTIVATION_STATE_INVALID

  recovery_started_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  record_recovery_evidence RUNNING -
  rhaomi_lifecycle_write_state \
    RECOVERY_ACCEPTANCE_IN_PROGRESS "$release_sha" "$image_digest" \
    "$recovery_started_at" first-activation-recovery.json ||
    first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  first_activation_recovery_active=true

  prepare_first_activation_recovery_root
  compose_recovery --profile first-activation-recovery run --rm --no-deps \
    --user "$(id -u):$(id -g)" first-activation-backup-verifier \
    verify-backup-set "$recovery_backup_set_id" >/dev/null ||
    first_activation_fail FIRST_ACTIVATION_BACKUP_INVALID
  compose_recovery --profile first-activation-recovery up --detach --no-deps \
    first-activation-postgres || first_activation_fail FIRST_ACTIVATION_RESTORE_FAILED
  wait_for_recovery_service first-activation-postgres healthy 60 FIRST_ACTIVATION_RESTORE_FAILED
  restore_first_activation_database
  restore_first_activation_media
  verify_recovery_flyway
  compose_recovery --profile first-activation-recovery run --rm --no-deps \
    first-activation-schema-validate >/dev/null ||
    first_activation_fail FIRST_ACTIVATION_SCHEMA_INVALID
  verify_recovered_empty_content
  seed_recovery_acceptance_probe
  compose_recovery --profile first-activation-recovery up --detach --no-deps \
    first-activation-backend || first_activation_fail FIRST_ACTIVATION_API_FAILED
  wait_for_recovery_service first-activation-backend healthy 180 FIRST_ACTIVATION_API_FAILED
  compose_recovery --profile first-activation-recovery up --detach --no-deps \
    first-activation-publisher || first_activation_fail FIRST_ACTIVATION_PUBLICATION_FAILED
  wait_for_recovery_service first-activation-publisher running 60 FIRST_ACTIVATION_PUBLICATION_FAILED
  wait_for_recovery_publication
  compose_recovery --profile first-activation-recovery up --detach --no-deps \
    first-activation-static || first_activation_fail FIRST_ACTIVATION_STATIC_FAILED
  wait_for_recovery_service first-activation-static healthy 60 FIRST_ACTIVATION_STATIC_FAILED
  compose_recovery --profile first-activation-recovery run --rm --no-deps \
    first-activation-smoke >/dev/null ||
    first_activation_fail FIRST_ACTIVATION_STATIC_FAILED
  verify_recovery_runtime_isolation
  stop_first_activation_recovery ||
    first_activation_fail FIRST_ACTIVATION_RECOVERY_QUIESCENCE_UNCONFIRMED
  first_activation_recovery_active=false

  recovery_completed_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  record_recovery_evidence STEADY_STATE "$recovery_completed_at"
  rhaomi_lifecycle_write_state \
    STEADY_STATE "$release_sha" "$image_digest" "$recovery_completed_at" \
    first-activation-recovery.json || first_activation_fail FIRST_ACTIVATION_STATE_INVALID

  printf '%s\n' \
    '{' \
    '  "contract": "rhaomi-first-activation-v1",' \
    '  "mode": "accept-recovery",' \
    "  \"releaseSha\": \"${release_sha}\"," \
    "  \"backupSetId\": \"${recovery_backup_set_id}\"," \
    '  "state": "STEADY_STATE",' \
    '  "publicIngressActivated": false,' \
    '  "status": "success"' \
    '}'
}

validate_first_activation_backup_candidate() {
  candidate_contract="$first_activation_root/state/deploy/first-activation-backup.env"
  candidate_evidence="$first_activation_root/state/deploy/first-activation-backup.json"
  first_activation_require_private_file "$candidate_contract"
  first_activation_require_private_file "$candidate_evidence"
  [ "$(wc -l <"$candidate_contract" | tr -d '[:space:]')" = 7 ] ||
    first_activation_fail FIRST_ACTIVATION_BACKUP_INVALID
  [ "$(sed -n '1p' "$candidate_contract")" = schemaVersion=1 ] &&
    [ "$(sed -n '2p' "$candidate_contract")" = status=RECOVERY_BACKUP_VERIFIED ] &&
    [ "$(sed -n '3p' "$candidate_contract")" = "releaseSha=$release_sha" ] &&
    [ "$(sed -n '4p' "$candidate_contract")" = "imageDigest=$image_digest" ] ||
    first_activation_fail FIRST_ACTIVATION_BACKUP_INVALID
  recovery_backup_set_id=$(sed -n '5s/^backupSetId=//p' "$candidate_contract")
  recovery_manifest_sha=$(sed -n '6s/^backupManifestSha256=//p' "$candidate_contract")
  candidate_evidence_sha=$(sed -n '7s/^evidenceSha256=//p' "$candidate_contract")
  printf '%s' "$recovery_backup_set_id" | grep -Eq '^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$' ||
    first_activation_fail FIRST_ACTIVATION_BACKUP_INVALID
  printf '%s' "$recovery_manifest_sha" | grep -Eq '^[0-9a-f]{64}$' ||
    first_activation_fail FIRST_ACTIVATION_BACKUP_INVALID
  printf '%s' "$candidate_evidence_sha" | grep -Eq '^[0-9a-f]{64}$' ||
    first_activation_fail FIRST_ACTIVATION_BACKUP_INVALID
  [ "$(openssl dgst -sha256 "$candidate_evidence" | awk '{print $NF}')" = "$candidate_evidence_sha" ] ||
    first_activation_fail FIRST_ACTIVATION_BACKUP_INVALID
  [ "$(wc -l <"$candidate_evidence" | tr -d '[:space:]')" = 9 ] &&
    [ "$(sed -n '2p' "$candidate_evidence")" = '  "schemaVersion": 1,' ] &&
    [ "$(sed -n '3p' "$candidate_evidence")" = '  "status": "RECOVERY_BACKUP_VERIFIED",' ] &&
    [ "$(sed -n '4p' "$candidate_evidence")" = "  \"releaseSha\": \"${release_sha}\"," ] &&
    [ "$(sed -n '5p' "$candidate_evidence")" = "  \"imageDigest\": \"${image_digest}\"," ] &&
    [ "$(sed -n '6p' "$candidate_evidence")" = "  \"backupSetId\": \"${recovery_backup_set_id}\"," ] &&
    [ "$(sed -n '7p' "$candidate_evidence")" = "  \"backupManifestSha256\": \"${recovery_manifest_sha}\"," ] ||
    first_activation_fail FIRST_ACTIVATION_BACKUP_INVALID
  candidate_verified_at=$(sed -n '8s/^  "verifiedAt": "\(.*\)"$/\1/p' "$candidate_evidence")
  printf '%s' "$candidate_verified_at" |
    grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' ||
    first_activation_fail FIRST_ACTIVATION_BACKUP_INVALID
  recovery_manifest="$first_activation_backup_repository/sets/$recovery_backup_set_id/backup-manifest.json"
  first_activation_require_backup_file "$recovery_manifest"
  [ "$(openssl dgst -sha256 "$recovery_manifest" | awk '{print $NF}')" = "$recovery_manifest_sha" ] ||
    first_activation_fail FIRST_ACTIVATION_BACKUP_INVALID
}

record_recovery_evidence() {
  recovery_state=$1
  recovery_finished_at=$2
  if [ "$recovery_state" = RUNNING ]; then
    recovery_completed_json=null
    recovery_check_status=pending
    recovery_flyway_evidence=pending
    rhaomi_lifecycle_begin_evidence first-activation-recovery.json ||
      first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  else
    recovery_completed_json="\"$recovery_finished_at\""
    recovery_check_status=success
    recovery_flyway_evidence=10
    rhaomi_lifecycle_replace_evidence first-activation-recovery.json ||
      first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  fi
  printf '%s\n' \
    '{' \
    '  "schemaVersion": 1,' \
    "  \"releaseSha\": \"${release_sha}\"," \
    "  \"imageDigest\": \"${image_digest}\"," \
    "  \"backupSetId\": \"${recovery_backup_set_id}\"," \
    "  \"backupManifestSha256\": \"${recovery_manifest_sha}\"," \
    "  \"startedAt\": \"${recovery_started_at}\"," \
    "  \"completedAt\": ${recovery_completed_json}," \
    "  \"state\": \"${recovery_state}\"," \
    "  \"completeSetFullRead\": \"${recovery_check_status}\"," \
    "  \"isolatedPostgresRestore\": \"${recovery_check_status}\"," \
    "  \"isolatedMediaRestore\": \"${recovery_check_status}\"," \
    "  \"flywayVersion\": \"${recovery_flyway_evidence}\"," \
    "  \"apiSmoke\": \"${recovery_check_status}\"," \
    "  \"staticPublicationSmoke\": \"${recovery_check_status}\"," \
    "  \"representativeMedia\": \"${recovery_check_status}\"," \
    '  "publicIngressActivated": false' \
    '}' >"$rhaomi_lifecycle_evidence_temp" ||
    first_activation_fail FIRST_ACTIVATION_STATE_INVALID
  rhaomi_lifecycle_commit_evidence first-activation-recovery.json ||
    first_activation_fail FIRST_ACTIVATION_STATE_INVALID
}

prepare_first_activation_recovery_root() {
  mkdir "$first_activation_recovery_root"
  chmod 700 "$first_activation_recovery_root"
  mkdir -p \
    "$first_activation_recovery_root/media" \
    "$first_activation_recovery_root/public/releases" \
    "$first_activation_recovery_root/state/publisher/build-workspace" \
    "$first_activation_recovery_root/state/locks"
  chmod 700 "$first_activation_recovery_root/media"
  chmod 755 "$first_activation_recovery_root/public" \
    "$first_activation_recovery_root/public/releases"
  chmod 750 "$first_activation_recovery_root/state" \
    "$first_activation_recovery_root/state/publisher" \
    "$first_activation_recovery_root/state/publisher/build-workspace" \
    "$first_activation_recovery_root/state/locks"
}

restore_first_activation_database() {
  dump_path="$first_activation_backup_repository/sets/$recovery_backup_set_id/postgres.dump"
  first_activation_require_backup_file "$dump_path"
  compose_recovery exec --no-TTY first-activation-postgres sh -ec \
    'exec pg_restore --exit-on-error --no-owner --no-acl --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
    <"$dump_path" >/dev/null || first_activation_fail FIRST_ACTIVATION_RESTORE_FAILED
}

restore_first_activation_media() {
  compose_recovery --profile first-activation-recovery run --rm --no-deps \
    --user "$(id -u):$(id -g)" first-activation-media-restore \
    node /opt/rhaomi/source/scripts/rhaomi-backup-tool.mjs \
    restore-media "$recovery_backup_set_id" >/dev/null ||
    first_activation_fail FIRST_ACTIVATION_RESTORE_FAILED
}

verify_recovery_flyway() {
  recovery_flyway=$(recovery_database_query \
    "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")
  [ "$recovery_flyway" = 10 ] || first_activation_fail FIRST_ACTIVATION_SCHEMA_INVALID
}

verify_recovered_empty_content() {
  recovered_counts=$(recovery_database_query \
    "SELECT (SELECT COUNT(*) FROM admin_users)::text || '|' || (SELECT COUNT(*) FROM media_assets)::text || '|' || (SELECT COUNT(*) FROM breeds)::text || '|' || (SELECT COUNT(*) FROM services)::text || '|' || (SELECT COUNT(*) FROM gallery_items)::text || '|' || (SELECT COUNT(*) FROM notices)::text || '|' || (SELECT COUNT(*) FROM shop_settings)::text || '|' || (SELECT COUNT(*) FROM publishing_outbox)::text")
  [ "$recovered_counts" = '0|0|0|0|0|0|0|0' ] ||
    first_activation_fail FIRST_ACTIVATION_RESTORE_NOT_EMPTY
}

seed_recovery_acceptance_probe() {
  probe_media_relative=masters/76/76000000-0000-0000-0000-000000000076.png
  probe_media="$first_activation_recovery_root/media/$probe_media_relative"
  mkdir -p "$(dirname "$probe_media")"
  printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=' |
    openssl base64 -d -A >"$probe_media"
  chmod 600 "$probe_media"
  probe_media_sha=$(openssl dgst -sha256 "$probe_media" | awk '{print $NF}')
  probe_media_size=$(wc -c <"$probe_media" | tr -d '[:space:]')
  recovery_database_query "
    INSERT INTO admin_users (id, email, password_hash)
    VALUES ('76000000-0000-0000-0000-000000000076', 'first.activation@example.invalid', 'synthetic-not-a-credential');
    INSERT INTO media_assets (
      id, status, source_content_type, content_type, file_extension, storage_key,
      source_byte_size, byte_size, width, height, sha256, created_by, updated_by
    ) VALUES (
      '76000000-0000-0000-0000-000000000176', 'active', 'image/png', 'image/png',
      'png', '$probe_media_relative', $probe_media_size, $probe_media_size, 1, 1,
      '$probe_media_sha', '76000000-0000-0000-0000-000000000076',
      '76000000-0000-0000-0000-000000000076'
    );
    INSERT INTO breeds (id, status, name, slug, description, sort_order, created_by, updated_by)
    VALUES ('76000000-0000-0000-0000-000000000276', 'published',
      '최초 활성화 검증 견종', 'first-activation-breed', '격리 복구 검증', 1,
      '76000000-0000-0000-0000-000000000076', '76000000-0000-0000-0000-000000000076');
    INSERT INTO services (id, status, name, slug, description, price_text, sort_order, created_by, updated_by)
    VALUES ('76000000-0000-0000-0000-000000000376', 'published',
      '최초 활성화 검증 서비스', 'first-activation-service', '격리 복구 검증', '검증용', 1,
      '76000000-0000-0000-0000-000000000076', '76000000-0000-0000-0000-000000000076');
    INSERT INTO shop_settings (
      id, shop_name, region_label, business_type, phone, address, opening_time,
      closing_time, closed_weekday, parking_available, parking_note, hero_title,
      hero_description, reservation_notice, hero_image_id, hero_image_alt_text,
      og_image_id, created_by, updated_by
    ) VALUES (
      '76000000-0000-0000-0000-000000000476', '$RHAOMI_FIRST_ACTIVATION_PROBE_MARKER',
      '격리 검증', '반려견 미용', '02-0000-0076', '격리 복구 검증 주소', '10:00', '19:00',
      'MONDAY', TRUE, '검증용', '최초 활성화 복구 검증', '격리 복구 검증', '검증용',
      '76000000-0000-0000-0000-000000000176', '격리 검증 이미지',
      '76000000-0000-0000-0000-000000000176',
      '76000000-0000-0000-0000-000000000076', '76000000-0000-0000-0000-000000000076'
    );
    INSERT INTO gallery_items (
      id, status, dog_name, breed_id, primary_service_id, cover_image_id, summary,
      alt_text, featured, sort_order, performed_at, published_at, created_by, updated_by
    ) VALUES (
      '76000000-0000-0000-0000-000000000576', 'published', '복구 검증견',
      '76000000-0000-0000-0000-000000000276', '76000000-0000-0000-0000-000000000376',
      '76000000-0000-0000-0000-000000000176', '격리 복구 검증', '격리 검증 이미지',
      TRUE, 1, '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z',
      '76000000-0000-0000-0000-000000000076', '76000000-0000-0000-0000-000000000076'
    );
    INSERT INTO notices (
      id, status, title, slug, summary, body_markdown, pinned, published_at,
      created_by, updated_by
    ) VALUES (
      '76000000-0000-0000-0000-000000000676', 'published', '최초 활성화 복구 검증',
      'first-activation-recovery', '격리 복구 검증', '**격리 복구 검증**', TRUE,
      '2026-01-01T00:00:00Z', '76000000-0000-0000-0000-000000000076',
      '76000000-0000-0000-0000-000000000076'
    );
    UPDATE content_revision_state SET content_revision = 1 WHERE singleton_key = 1;
    INSERT INTO publishing_outbox (
      id, kind, source_type, source_id, content_revision, available_at
    ) VALUES (
      '76000000-0000-0000-0000-000000000776', 'CONTENT_CHANGED', 'SHOP_SETTINGS',
      '76000000-0000-0000-0000-000000000476', 1,
      CURRENT_TIMESTAMP - INTERVAL '31 seconds'
    );
  " >/dev/null
}

wait_for_recovery_publication() {
  attempt=0
  while [ "$attempt" -lt 180 ]; do
    if [ -L "$first_activation_recovery_root/public/current" ] &&
      [ -f "$first_activation_recovery_root/public/current/index.html" ] &&
      grep -Fq "$RHAOMI_FIRST_ACTIVATION_PROBE_MARKER" \
        "$first_activation_recovery_root/public/current/index.html"; then
      recovery_publication_state=$(recovery_database_query \
        "SELECT state FROM publishing_outbox WHERE id = '76000000-0000-0000-0000-000000000776'")
      case "$recovery_publication_state" in SUCCEEDED | NOOP) return 0 ;; esac
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  first_activation_fail FIRST_ACTIVATION_PUBLICATION_FAILED
}

verify_recovery_runtime_isolation() {
  for service in \
    first-activation-postgres first-activation-backend first-activation-publisher \
    first-activation-static; do
    container_id=$(compose_recovery ps --quiet "$service")
    [ -n "$container_id" ] || first_activation_fail FIRST_ACTIVATION_RECOVERY_INVALID
    [ "$(docker inspect --format '{{len .HostConfig.PortBindings}}' "$container_id")" = 0 ] ||
      first_activation_fail FIRST_ACTIVATION_RECOVERY_INVALID
  done
}

stop_first_activation_recovery() {
  compose_recovery --profile first-activation-recovery down --remove-orphans >/dev/null 2>&1 ||
    return 1
  remaining=$(docker ps --all --quiet \
    --filter "label=com.docker.compose.project=$first_activation_recovery_project") || return 1
  [ -z "$remaining" ]
}

quiesce_first_activation_writers() {
  compose_first_activation stop --timeout 30 backend publisher >/dev/null 2>&1 || return 1
  for service in backend publisher; do
    container_id=$(compose_first_activation ps --all --quiet "$service") || return 1
    if [ -n "$container_id" ]; then
      [ "$(docker inspect --format '{{.State.Status}}' "$container_id")" = exited ] || return 1
    fi
  done
}

compose_first_activation() {
  docker compose \
    --project-directory "$first_activation_app_root" \
    --env-file "$first_activation_environment_file" \
    --file "$first_activation_compose_file" \
    "$@"
}

compose_recovery() {
  if [ -n "$first_activation_validation_compose_file" ]; then
    docker compose \
      --project-directory "$first_activation_app_root" \
      --project-name "$first_activation_recovery_project" \
      --env-file "$first_activation_environment_file" \
      --file "$first_activation_recovery_compose_file" \
      --file "$first_activation_validation_compose_file" \
      "$@"
  else
    docker compose \
      --project-directory "$first_activation_app_root" \
      --project-name "$first_activation_recovery_project" \
      --env-file "$first_activation_environment_file" \
      --file "$first_activation_recovery_compose_file" \
      "$@"
  fi
}

wait_for_first_activation_service() {
  service=$1
  expected=$2
  maximum=$3
  error_code=$4
  container_id=$(compose_first_activation ps --quiet "$service")
  [ -n "$container_id" ] || first_activation_fail "$error_code"
  wait_for_first_activation_container "$container_id" "$expected" "$maximum" "$error_code"
}

wait_for_recovery_service() {
  service=$1
  expected=$2
  maximum=$3
  error_code=$4
  container_id=$(compose_recovery ps --quiet "$service")
  [ -n "$container_id" ] || first_activation_fail "$error_code"
  wait_for_first_activation_container "$container_id" "$expected" "$maximum" "$error_code"
}

wait_for_first_activation_container() {
  container_id=$1
  expected=$2
  maximum=$3
  error_code=$4
  attempt=0
  while [ "$attempt" -lt "$maximum" ]; do
    if [ "$expected" = healthy ]; then
      current=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")
    else
      current=$(docker inspect --format '{{.State.Status}}' "$container_id")
    fi
    [ "$current" = "$expected" ] && return 0
    case "$current" in exited | dead | unhealthy) first_activation_fail "$error_code" ;; esac
    attempt=$((attempt + 1))
    sleep 1
  done
  first_activation_fail "$error_code"
}

recovery_database_query() {
  recovery_sql=$1
  compose_recovery exec --no-TTY first-activation-postgres \
    psql -X --set ON_ERROR_STOP=1 \
      --username "$(first_activation_environment_value RHAOMI_POSTGRES_USER)" \
      --dbname "$(first_activation_environment_value RHAOMI_POSTGRES_DB)" \
      --tuples-only --no-align --command "$recovery_sql"
}

first_activation_require_regular_file() {
  [ -f "$1" ] && [ ! -L "$1" ] ||
    first_activation_fail FIRST_ACTIVATION_HOST_INVALID
  [ "$(first_activation_owner_id "$1")" = "$(id -u)" ] ||
    first_activation_fail FIRST_ACTIVATION_HOST_INVALID
}

first_activation_require_private_file() {
  first_activation_require_regular_file "$1"
  [ "$(first_activation_file_mode "$1")" = 600 ] ||
    first_activation_fail FIRST_ACTIVATION_HOST_INVALID
}

first_activation_require_backup_file() {
  first_activation_require_regular_file "$1"
  [ "$(first_activation_file_mode "$1")" = 400 ] ||
    first_activation_fail FIRST_ACTIVATION_BACKUP_INVALID
}

first_activation_require_owned_directory() {
  [ -d "$1" ] && [ ! -L "$1" ] &&
    [ "$(first_activation_owner_id "$1")" = "$(id -u)" ] ||
    first_activation_fail FIRST_ACTIVATION_HOST_INVALID
  physical_directory=$(CDPATH= cd -- "$1" && pwd -P) ||
    first_activation_fail FIRST_ACTIVATION_HOST_INVALID
  [ "$physical_directory" = "$1" ] ||
    first_activation_fail FIRST_ACTIVATION_HOST_INVALID
  printf '%s' "$(first_activation_file_mode "$1")" | grep -Eq '^7[0145][0145]$' ||
    first_activation_fail FIRST_ACTIVATION_HOST_INVALID
}

first_activation_require_owned_private_directory() {
  first_activation_require_owned_directory "$1"
  [ "$(first_activation_file_mode "$1")" = 700 ] ||
    first_activation_fail FIRST_ACTIVATION_HOST_INVALID
}

first_activation_file_mode() {
  if first_activation_mode_value=$(stat -f '%Lp' "$1" 2>/dev/null); then
    printf '%s\n' "$first_activation_mode_value"
  else
    stat -c '%a' "$1"
  fi
}

first_activation_owner_id() {
  if first_activation_owner_value=$(stat -f '%u' "$1" 2>/dev/null); then
    printf '%s\n' "$first_activation_owner_value"
  else
    stat -c '%u' "$1"
  fi
}

first_activation_fail() {
  printf '%s\n' "$1" >&2
  exit 1
}
