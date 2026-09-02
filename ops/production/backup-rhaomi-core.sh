#!/bin/sh

# fixed production wrapper와 task-scoped validator가 공유한다.
# production root와 repository authority는 caller argument가 아니라 wrapper와
# fixed 0600 production.env에서만 정해진다.

backup_rhaomi() {
  set -eu
  umask 077

  backup_root=$1
  shift
  backup_mode=
  target_release_sha=
  requested_backup_set_id=

  parse_backup_arguments "$@"
  initialize_backup_authorities
  validate_backup_host_root
  acquire_backup_lock
  trap backup_on_exit EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM

  validate_backup_fixed_configuration
  validate_backup_lifecycle
  load_backup_repository_authority
  validate_backup_repository
  configure_backup_environment
  validate_backup_media_authority

  case "$backup_mode" in
    scheduled | on-demand | predeploy | first-activation) create_application_consistent_backup ;;
    structural-check)
      backup_tool verify "$requested_backup_set_id" structural >/dev/null ||
        backup_fail BACKUP_VERIFICATION_FAILED
      print_backup_result verified "$requested_backup_set_id"
      ;;
    full-read-check)
      backup_tool verify "$requested_backup_set_id" full-read >/dev/null ||
        backup_fail BACKUP_VERIFICATION_FAILED
      print_backup_result verified "$requested_backup_set_id"
      ;;
    retention-dry-run) backup_tool retention-plan || backup_fail BACKUP_RETENTION_FAILED ;;
    retention-apply) backup_tool retention-apply || backup_fail BACKUP_RETENTION_FAILED ;;
    *) backup_fail BACKUP_INPUT_INVALID ;;
  esac

  release_backup_lock || backup_fail BACKUP_LOCK_RELEASE_FAILED
  if [ "$backup_event_started" = true ] && [ "$backup_event_terminal" = false ]; then
    record_backup_event SUCCESS "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" -
    backup_event_terminal=true
    print_backup_result complete "$backup_set_id"
  fi
}

parse_backup_arguments() {
  mode_seen=false
  target_seen=false
  set_seen=false
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --mode)
        [ "$mode_seen" = false ] && [ "$#" -ge 2 ] || backup_fail BACKUP_INPUT_INVALID
        backup_mode=$2
        mode_seen=true
        shift 2
        ;;
      --target-release-sha)
        [ "$target_seen" = false ] && [ "$#" -ge 2 ] || backup_fail BACKUP_INPUT_INVALID
        target_release_sha=$2
        target_seen=true
        shift 2
        ;;
      --backup-set-id)
        [ "$set_seen" = false ] && [ "$#" -ge 2 ] || backup_fail BACKUP_INPUT_INVALID
        requested_backup_set_id=$2
        set_seen=true
        shift 2
        ;;
      *) backup_fail BACKUP_INPUT_INVALID ;;
    esac
  done
  [ "$mode_seen" = true ] || backup_fail BACKUP_INPUT_INVALID
  case "$backup_mode" in
    scheduled | on-demand)
      [ "$target_seen" = false ] && [ "$set_seen" = false ] || backup_fail BACKUP_INPUT_INVALID
      ;;
    predeploy | first-activation)
      [ "$target_seen" = true ] && [ "$set_seen" = false ] || backup_fail BACKUP_INPUT_INVALID
      printf '%s' "$target_release_sha" | grep -Eq '^[0-9a-f]{40}$' ||
        backup_fail BACKUP_INPUT_INVALID
      ;;
    structural-check | full-read-check)
      [ "$target_seen" = false ] && [ "$set_seen" = true ] || backup_fail BACKUP_INPUT_INVALID
      validate_backup_set_id "$requested_backup_set_id"
      ;;
    retention-dry-run | retention-apply)
      [ "$target_seen" = false ] && [ "$set_seen" = false ] || backup_fail BACKUP_INPUT_INVALID
      ;;
    *) backup_fail BACKUP_INPUT_INVALID ;;
  esac
}

initialize_backup_authorities() {
  backup_app_root="$backup_root/app"
  backup_compose_file="$backup_app_root/compose.production.yaml"
  backup_validation_compose_file=${RHAOMI_BACKUP_VALIDATION_COMPOSE_FILE:-}
  backup_environment_file="$backup_app_root/production.env"
  backup_docker_config_root="$backup_app_root/docker"
  backup_docker_config_file="$backup_docker_config_root/config.json"
  backup_lock_parent="$backup_root/state/locks"
  # deploy와 backup이 서로 다른 이름의 lock을 사용하지 않는다.
  backup_lock="$backup_lock_parent/rhaomi-deploy.lock"
  backup_lock_owner="$backup_lock/owner"
  backup_lock_token="backup:$$:$(date -u '+%Y%m%dT%H%M%SZ')"
  backup_lock_owned=false
  writer_maintenance_active=false
  backup_lock_preserve=false
  backup_event_started=false
  backup_event_terminal=false
  backup_failure_code=BACKUP_FAILED
  homeops_telemetry=not_configured
  homeops_event_adapter="$backup_app_root/bin/report-rhaomi-event.py"
  backup_linux_validation_permissions=false
  if [ -n "$backup_validation_compose_file" ] && [ "$(uname -s)" = Linux ]; then
    backup_linux_validation_permissions=true
  fi
  rhaomi_lifecycle_initialize "$backup_root"
}

validate_backup_host_root() {
  for directory in \
    "$backup_root" \
    "$backup_app_root" \
    "$backup_root/data" \
    "$backup_root/state" \
    "$backup_root/state/deploy"; do
    backup_require_owned_private_directory "$directory"
  done
  if [ "$backup_linux_validation_permissions" = true ]; then
    [ -d "$backup_root/data/media" ] && [ ! -L "$backup_root/data/media" ] ||
      backup_fail BACKUP_HOST_INVALID
    [ -d "$backup_lock_parent" ] && [ ! -L "$backup_lock_parent" ] ||
      backup_fail BACKUP_HOST_INVALID
  else
    backup_require_owned_private_directory "$backup_root/data/media"
    backup_require_owned_private_directory "$backup_lock_parent"
  fi
}

acquire_backup_lock() {
  mkdir "$backup_lock" 2>/dev/null || backup_fail BACKUP_LOCKED
  printf '%s\n' "$backup_lock_token" >"$backup_lock_owner"
  backup_lock_owned=true
}

backup_on_exit() {
  backup_result=$?
  trap - EXIT HUP INT TERM
  if [ "${writer_maintenance_active:-false}" = true ]; then
    if restore_backup_writers; then
      writer_maintenance_active=false
    else
      backup_lock_preserve=true
      backup_result=1
      backup_failure_code=BACKUP_WRITER_RECOVERY_FAILED
      printf '%s\n' BACKUP_WRITER_RECOVERY_FAILED >&2
    fi
  fi
  if [ "$backup_result" -ne 0 ] &&
    [ "${backup_event_started:-false}" = true ] &&
    [ "${backup_event_terminal:-false}" = false ]; then
    record_backup_event FAILED "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$backup_failure_code"
    backup_event_terminal=true
  fi
  if [ "${backup_lock_owned:-false}" = true ] &&
    [ "${backup_lock_preserve:-false}" = false ]; then
    release_backup_lock || backup_result=1
  fi
  exit "$backup_result"
}

release_backup_lock() {
  [ -f "$backup_lock_owner" ] && [ ! -L "$backup_lock_owner" ] || return 1
  [ "$(sed -n '1p' "$backup_lock_owner")" = "$backup_lock_token" ] || return 1
  rm "$backup_lock_owner"
  rmdir "$backup_lock"
  backup_lock_owned=false
}

validate_backup_fixed_configuration() {
  command -v docker >/dev/null 2>&1 || backup_fail BACKUP_HOST_INVALID
  command -v docker-compose >/dev/null 2>&1 || backup_fail BACKUP_HOST_INVALID
  backup_require_regular_file "$backup_compose_file"
  if [ -n "$backup_validation_compose_file" ]; then
    [ "$backup_validation_compose_file" = "$backup_app_root/compose.production.validation.yaml" ] ||
      backup_fail BACKUP_HOST_INVALID
    backup_require_regular_file "$backup_validation_compose_file"
  fi
  backup_require_regular_file "$backup_environment_file"
  backup_require_file_mode "$backup_environment_file" 600
  [ "$(backup_owner_id "$backup_environment_file")" = "$(id -u)" ] ||
    backup_fail BACKUP_HOST_INVALID
  [ -d "$backup_docker_config_root" ] && [ ! -L "$backup_docker_config_root" ] ||
    backup_fail BACKUP_HOST_INVALID
  [ "$(backup_file_mode "$backup_docker_config_root")" = 700 ] ||
    backup_fail BACKUP_HOST_INVALID
  [ "$(backup_owner_id "$backup_docker_config_root")" = "$(id -u)" ] ||
    backup_fail BACKUP_HOST_INVALID
  backup_require_regular_file "$backup_docker_config_file"
  backup_require_file_mode "$backup_docker_config_file" 600
  backup_require_regular_file "$homeops_event_adapter"
  backup_require_file_mode "$homeops_event_adapter" 700
  [ "$(backup_owner_id "$homeops_event_adapter")" = "$(id -u)" ] ||
    backup_fail BACKUP_HOST_INVALID
}

validate_backup_lifecycle() {
  case "$backup_mode" in
    first-activation)
      rhaomi_lifecycle_require_state RECOVERY_ACCEPTANCE_REQUIRED "$target_release_sha" ||
        backup_fail BACKUP_LIFECYCLE_INVALID
      [ ! -e "$backup_root/state/deploy/first-activation-backup.json" ] &&
        [ ! -L "$backup_root/state/deploy/first-activation-backup.json" ] &&
        [ ! -e "$backup_root/state/deploy/first-activation-backup.env" ] &&
        [ ! -L "$backup_root/state/deploy/first-activation-backup.env" ] ||
        backup_fail BACKUP_LIFECYCLE_INVALID
      ;;
    *)
      rhaomi_lifecycle_require_state STEADY_STATE ||
        backup_fail BACKUP_LIFECYCLE_INVALID
      ;;
  esac
}

load_backup_repository_authority() {
  repository_line_count=$(awk '
    BEGIN { count = 0 }
    /^RHAOMI_BACKUP_REPOSITORY_ROOT=/ { count += 1 }
    END { print count }
  ' "$backup_environment_file")
  [ "$repository_line_count" = 1 ] || backup_fail BACKUP_CONFIG_INVALID
  backup_repository_root=$(sed -n 's/^RHAOMI_BACKUP_REPOSITORY_ROOT=//p' "$backup_environment_file")
  [ -n "$backup_repository_root" ] || backup_fail BACKUP_CONFIG_INVALID
  case "$backup_repository_root" in
    /*) ;;
    *) backup_fail BACKUP_CONFIG_INVALID ;;
  esac
  case "$backup_repository_root" in
    *'/../'* | *'/./'* | */.. | */. | *'\'* | *[[:cntrl:]])
      backup_fail BACKUP_CONFIG_INVALID
      ;;
  esac
}

validate_backup_repository() {
  backup_require_owned_private_directory "$backup_repository_root"
  [ "$(backup_file_mode "$backup_repository_root")" = 700 ] ||
    backup_fail BACKUP_REPOSITORY_INVALID
  backup_repository_physical=$(cd "$backup_repository_root" && pwd -P)
  backup_root_physical=$(cd "$backup_root" && pwd -P)
  backup_media_physical=$(cd "$backup_root/data/media" && pwd -P)
  [ "$backup_repository_physical" = "$backup_repository_root" ] ||
    backup_fail BACKUP_REPOSITORY_INVALID
  case "$backup_repository_physical" in
    "$backup_root_physical" | "$backup_root_physical"/* | "$backup_media_physical" | "$backup_media_physical"/*)
      backup_fail BACKUP_REPOSITORY_INVALID
      ;;
  esac
  backup_sentinel="$backup_repository_root/.rhaomi-backup-repository"
  backup_require_regular_file "$backup_sentinel"
  backup_require_file_mode "$backup_sentinel" 600
  [ "$(sed -n '1p' "$backup_sentinel")" = rhaomi-backup-repository-v1 ] ||
    backup_fail BACKUP_REPOSITORY_INVALID
  [ "$(wc -l <"$backup_sentinel" | tr -d '[:space:]')" = 1 ] ||
    backup_fail BACKUP_REPOSITORY_INVALID
  backup_require_owned_private_directory "$backup_repository_root/sets"
  [ "$(backup_file_mode "$backup_repository_root/sets")" = 700 ] ||
    backup_fail BACKUP_REPOSITORY_INVALID
}

configure_backup_environment() {
  for variable_name in \
    COMPOSE_FILE COMPOSE_ENV_FILES COMPOSE_PROFILES COMPOSE_PROJECT_NAME \
    DOCKER_CONTEXT DOCKER_DEFAULT_PLATFORM DOCKER_HOST DOCKER_TLS_VERIFY \
    RHAOMI_PRODUCTION_IMAGE RHAOMI_BACKUP_MEDIA_ROOT \
    RHAOMI_BACKUP_DEPLOY_STATE_ROOT RHAOMI_BACKUP_RESTORE_MEDIA_ROOT; do
    unset "$variable_name"
  done
  RHAOMI_BACKUP_REPOSITORY_ROOT=$backup_repository_root
  DOCKER_CONFIG=$backup_docker_config_root
  export RHAOMI_BACKUP_REPOSITORY_ROOT DOCKER_CONFIG
}

validate_backup_media_authority() {
  if [ "$backup_linux_validation_permissions" = true ]; then
    transition_backup_validation_media_state assert-runtime ||
      backup_fail BACKUP_MEDIA_PERMISSION_INVALID
  else
    backup_require_owned_private_directory "$backup_root/data/media"
  fi
}

compose_backup_production() {
  if [ -n "$backup_validation_compose_file" ]; then
    docker-compose \
      --project-directory "$backup_app_root" \
      --env-file "$backup_environment_file" \
      --file "$backup_compose_file" \
      --file "$backup_validation_compose_file" \
      "$@"
  else
    docker-compose \
      --project-directory "$backup_app_root" \
      --env-file "$backup_environment_file" \
      --file "$backup_compose_file" \
      "$@"
  fi
}

backup_tool() {
  compose_backup_production --profile production-backup run --rm --no-deps \
    --user "$(id -u):$(id -g)" \
    backup-tool \
    node /opt/rhaomi/source/scripts/rhaomi-backup-tool.mjs "$@"
}

transition_backup_validation_media_state() {
  action=$1
  case "$action" in
    runtime | capture | assert-runtime | assert-capture) ;;
    *) return 1 ;;
  esac
  [ "$backup_linux_validation_permissions" = true ] || return 0
  compose_backup_production --profile production-backup run --rm --no-deps \
    backup-permission "$action" "$(id -u)" "$(id -g)" >/dev/null
}

create_application_consistent_backup() {
  if [ "$backup_mode" != first-activation ]; then
    verify_backup_public_web
  fi
  inspect_backup_source_identity
  if [ "$backup_mode" = first-activation ]; then
    [ "$backup_source_release_sha" = "$target_release_sha" ] ||
      backup_fail BACKUP_SOURCE_IDENTITY_INVALID
    rhaomi_lifecycle_require_state \
      RECOVERY_ACCEPTANCE_REQUIRED "$target_release_sha" "$backup_source_image_digest" ||
      backup_fail BACKUP_LIFECYCLE_INVALID
  fi
  backup_set_id="$(date -u '+%Y%m%dT%H%M%SZ')-$(openssl rand -hex 6)"
  validate_backup_set_id "$backup_set_id"
  backup_started_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  backup_purpose=on-demand
  [ "$backup_mode" = scheduled ] && backup_purpose=scheduled

  backup_event_started=true
  record_backup_event RUNNING - -

  backup_tool begin "$backup_set_id" >/dev/null || backup_fail BACKUP_BEGIN_FAILED
  writer_maintenance_active=true
  compose_backup_production stop --timeout 30 backend publisher ||
    backup_fail BACKUP_WRITER_STOP_FAILED
  verify_backup_writer_quiescence
  if [ "$backup_mode" != first-activation ]; then
    verify_backup_public_web
  fi
  transition_backup_validation_media_state capture ||
    backup_fail BACKUP_MEDIA_PERMISSION_INVALID

  dump_path="$backup_repository_root/sets/.incomplete-$backup_set_id/postgres.dump"
  compose_backup_production exec --no-TTY postgres sh -ec \
    'exec pg_dump --format=custom --no-password --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
    >"$dump_path" || backup_fail BACKUP_DUMP_FAILED
  chmod 600 "$dump_path"
  compose_backup_production exec --no-TTY postgres pg_restore --list <"$dump_path" >/dev/null ||
    backup_fail BACKUP_DUMP_INVALID
  backup_tool capture-media "$backup_set_id" >/dev/null ||
    backup_fail BACKUP_MEDIA_CAPTURE_FAILED

  restore_backup_writers || backup_fail BACKUP_WRITER_RECOVERY_FAILED
  writer_maintenance_active=false

  backup_tool finalize \
    "$backup_set_id" \
    "$backup_purpose" \
    "$backup_started_at" \
    "$backup_source_release_sha" \
    "$backup_source_image_digest" \
    "$backup_source_flyway_version" >/dev/null || backup_fail BACKUP_FINALIZE_FAILED

  if [ "$backup_mode" = predeploy ]; then
    backup_tool issue-eligibility "$backup_set_id" "$target_release_sha" >/dev/null ||
      backup_fail BACKUP_ELIGIBILITY_FAILED
  elif [ "$backup_mode" = first-activation ]; then
    backup_tool verify "$backup_set_id" full-read >/dev/null ||
      backup_fail BACKUP_VERIFICATION_FAILED
    write_first_activation_backup_candidate
  fi
}

write_first_activation_backup_candidate() {
  candidate_evidence="$backup_root/state/deploy/first-activation-backup.json"
  candidate_contract="$backup_root/state/deploy/first-activation-backup.env"
  [ ! -e "$candidate_evidence" ] && [ ! -L "$candidate_evidence" ] &&
    [ ! -e "$candidate_contract" ] && [ ! -L "$candidate_contract" ] ||
    backup_fail BACKUP_LIFECYCLE_INVALID

  candidate_manifest="$backup_repository_root/sets/$backup_set_id/backup-manifest.json"
  backup_require_regular_file "$candidate_manifest"
  candidate_manifest_sha=$(openssl dgst -sha256 "$candidate_manifest" | awk '{print $NF}')
  printf '%s' "$candidate_manifest_sha" | grep -Eq '^[0-9a-f]{64}$' ||
    backup_fail BACKUP_VERIFICATION_FAILED
  candidate_created_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

  candidate_evidence_temp=$(mktemp "$backup_root/state/deploy/.first-activation-backup.json.tmp.XXXXXX") ||
    backup_fail BACKUP_LIFECYCLE_INVALID
  chmod 600 "$candidate_evidence_temp" || backup_fail BACKUP_LIFECYCLE_INVALID
  printf '%s\n' \
    '{' \
    '  "schemaVersion": 1,' \
    '  "status": "RECOVERY_BACKUP_VERIFIED",' \
    "  \"releaseSha\": \"${target_release_sha}\"," \
    "  \"imageDigest\": \"${backup_source_image_digest}\"," \
    "  \"backupSetId\": \"${backup_set_id}\"," \
    "  \"backupManifestSha256\": \"${candidate_manifest_sha}\"," \
    "  \"verifiedAt\": \"${candidate_created_at}\"" \
    '}' >"$candidate_evidence_temp" || backup_fail BACKUP_LIFECYCLE_INVALID
  mv "$candidate_evidence_temp" "$candidate_evidence" ||
    backup_fail BACKUP_LIFECYCLE_INVALID
  candidate_evidence_sha=$(openssl dgst -sha256 "$candidate_evidence" | awk '{print $NF}')

  candidate_contract_temp=$(mktemp "$backup_root/state/deploy/.first-activation-backup.env.tmp.XXXXXX") ||
    backup_fail BACKUP_LIFECYCLE_INVALID
  chmod 600 "$candidate_contract_temp" || backup_fail BACKUP_LIFECYCLE_INVALID
  printf '%s\n' \
    'schemaVersion=1' \
    'status=RECOVERY_BACKUP_VERIFIED' \
    "releaseSha=$target_release_sha" \
    "imageDigest=$backup_source_image_digest" \
    "backupSetId=$backup_set_id" \
    "backupManifestSha256=$candidate_manifest_sha" \
    "evidenceSha256=$candidate_evidence_sha" >"$candidate_contract_temp" ||
    backup_fail BACKUP_LIFECYCLE_INVALID
  mv "$candidate_contract_temp" "$candidate_contract" ||
    backup_fail BACKUP_LIFECYCLE_INVALID
}

inspect_backup_source_identity() {
  backup_backend_id=$(compose_backup_production ps --quiet backend)
  backup_publisher_id=$(compose_backup_production ps --quiet publisher)
  [ -n "$backup_backend_id" ] && [ -n "$backup_publisher_id" ] ||
    backup_fail BACKUP_WRITER_UNAVAILABLE
  [ "$(docker inspect --format '{{.State.Status}}' "$backup_backend_id")" = running ] ||
    backup_fail BACKUP_WRITER_UNAVAILABLE
  [ "$(docker inspect --format '{{.State.Status}}' "$backup_publisher_id")" = running ] ||
    backup_fail BACKUP_WRITER_UNAVAILABLE
  backup_source_image_id=$(docker inspect --format '{{.Image}}' "$backup_backend_id")
  [ "$backup_source_image_id" = "$(docker inspect --format '{{.Image}}' "$backup_publisher_id")" ] ||
    backup_fail BACKUP_SOURCE_IDENTITY_INVALID
  printf '%s' "$backup_source_image_id" | grep -Eq '^sha256:[0-9a-f]{64}$' ||
    backup_fail BACKUP_SOURCE_IDENTITY_INVALID
  backup_source_image_reference=$(docker inspect --format '{{.Config.Image}}' "$backup_backend_id")
  [ "$backup_source_image_reference" = "$(docker inspect --format '{{.Config.Image}}' "$backup_publisher_id")" ] ||
    backup_fail BACKUP_SOURCE_IDENTITY_INVALID
  backup_source_release_sha=$(docker image inspect \
    --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
    "$backup_source_image_id")
  printf '%s' "$backup_source_release_sha" | grep -Eq '^[0-9a-f]{40}$' ||
    backup_fail BACKUP_SOURCE_IDENTITY_INVALID
  case "$backup_source_image_reference" in
    ghcr.io/xxh3898/rhaomi@sha256:*)
      backup_source_image_digest=${backup_source_image_reference#ghcr.io/xxh3898/rhaomi@}
      ;;
    *) backup_source_image_digest=$backup_source_image_id ;;
  esac
  printf '%s' "$backup_source_image_digest" | grep -Eq '^sha256:[0-9a-f]{64}$' ||
    backup_fail BACKUP_SOURCE_IDENTITY_INVALID
  backup_source_flyway_version=$(compose_backup_production exec --no-TTY postgres sh -ec \
    'exec psql --no-psqlrc --tuples-only --no-align --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --command="SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"')
  [ "$backup_source_flyway_version" = 10 ] || backup_fail BACKUP_SOURCE_SCHEMA_INVALID
  RHAOMI_PRODUCTION_IMAGE=$backup_source_image_reference
  export RHAOMI_PRODUCTION_IMAGE
}

verify_backup_writer_quiescence() {
  for writer in backend publisher; do
    writer_id=$(compose_backup_production ps --all --quiet "$writer") ||
      backup_fail BACKUP_WRITER_ACTIVE
    if [ -n "$writer_id" ]; then
      [ "$(docker inspect --format '{{.State.Status}}' "$writer_id")" = exited ] ||
        backup_fail BACKUP_WRITER_ACTIVE
    fi
  done
}

restore_backup_writers() {
  transition_backup_validation_media_state runtime || return 1
  compose_backup_production up --detach --no-deps --force-recreate backend || return 1
  backup_backend_id=$(compose_backup_production ps --quiet backend) || return 1
  [ -n "$backup_backend_id" ] || return 1
  wait_for_backup_container "$backup_backend_id" healthy 180 || return 1
  compose_backup_production up --detach --no-deps --force-recreate publisher || return 1
  backup_publisher_id=$(compose_backup_production ps --quiet publisher) || return 1
  [ -n "$backup_publisher_id" ] || return 1
  wait_for_backup_container "$backup_publisher_id" running 60 || return 1
  [ "$(docker inspect --format '{{.Image}}' "$backup_backend_id")" = "$backup_source_image_id" ] || return 1
  [ "$(docker inspect --format '{{.Image}}' "$backup_publisher_id")" = "$backup_source_image_id" ] || return 1
  if [ "$backup_mode" != first-activation ]; then
    verify_backup_public_web
  fi
}

wait_for_backup_container() {
  container_id=$1
  expected=$2
  maximum=$3
  attempt=0
  while [ "$attempt" -lt "$maximum" ]; do
    if [ "$expected" = healthy ]; then
      current=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id") || return 1
    else
      current=$(docker inspect --format '{{.State.Status}}' "$container_id") || return 1
    fi
    [ "$current" = "$expected" ] && return 0
    case "$current" in exited | dead | unhealthy) return 1 ;; esac
    attempt=$((attempt + 1))
    sleep 1
  done
  return 1
}

verify_backup_public_web() {
  web_id=$(compose_backup_production ps --quiet rhaomi-web)
  [ -n "$web_id" ] || backup_fail BACKUP_PUBLIC_UNAVAILABLE
  [ "$(docker inspect --format '{{.State.Status}}' "$web_id")" = running ] ||
    backup_fail BACKUP_PUBLIC_UNAVAILABLE
  compose_backup_production exec --no-TTY rhaomi-web \
    wget -qO- http://127.0.0.1:8080/ >/dev/null
}

print_backup_result() {
  result_status=$1
  result_set_id=$2
  printf '%s\n' \
    '{' \
    '  "contract": "rhaomi-production-backup-v1",' \
    "  \"mode\": \"${backup_mode}\"," \
    "  \"backupSetId\": \"${result_set_id}\"," \
    "  \"status\": \"${result_status}\"," \
    "  \"homeOpsTelemetry\": \"${homeops_telemetry}\"," \
    '  "sameHostFailureDomain": true' \
    '}'
}

validate_backup_set_id() {
  printf '%s' "$1" | grep -Eq '^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$' ||
    backup_fail BACKUP_INPUT_INVALID
}

backup_require_regular_file() {
  [ -f "$1" ] && [ ! -L "$1" ] || backup_fail BACKUP_HOST_INVALID
}

backup_require_file_mode() {
  [ "$(backup_file_mode "$1")" = "$2" ] || backup_fail BACKUP_HOST_INVALID
}

backup_require_owned_private_directory() {
  [ -d "$1" ] && [ ! -L "$1" ] || backup_fail BACKUP_HOST_INVALID
  [ "$(backup_owner_id "$1")" = "$(id -u)" ] || backup_fail BACKUP_HOST_INVALID
  printf '%s' "$(backup_file_mode "$1")" | grep -Eq '^7[0145][0145]$' ||
    backup_fail BACKUP_HOST_INVALID
}

backup_file_mode() {
  if mode_value=$(stat -f '%Lp' "$1" 2>/dev/null); then
    printf '%s\n' "$mode_value"
  else
    stat -c '%a' "$1"
  fi
}

backup_owner_id() {
  if owner_value=$(stat -f '%u' "$1" 2>/dev/null); then
    printf '%s\n' "$owner_value"
  else
    stat -c '%u' "$1"
  fi
}

record_backup_event() {
  event_status=$1
  event_finished_at=$2
  event_failure_code=$3
  event_outcome=FAILED
  if event_output=$("$homeops_event_adapter" \
    backup \
    "$event_status" \
    "$backup_set_id" \
    "$backup_started_at" \
    "$event_finished_at" \
    "$event_failure_code" 2>/dev/null); then
    case "$event_output" in
      RETAINED | NOT_CONFIGURED) event_outcome=$event_output ;;
      *) event_outcome=FAILED ;;
    esac
  fi
  case "$event_outcome" in
    RETAINED)
      [ "$homeops_telemetry" = failed ] || homeops_telemetry=retained
      ;;
    NOT_CONFIGURED) ;;
    FAILED)
      homeops_telemetry=failed
      printf '%s\n' HOMEOPS_TELEMETRY_FAILED >&2
      ;;
  esac
}

backup_fail() {
  backup_failure_code=$1
  printf '%s\n' "$1" >&2
  exit 1
}
