#!/bin/sh

set -eu

log_file=${RHAOMI_FIRST_ACTIVATION_TEST_LOG:?RHAOMI_FIRST_ACTIVATION_TEST_LOG is required}
state_dir=${RHAOMI_FIRST_ACTIVATION_TEST_STATE_DIR:?RHAOMI_FIRST_ACTIVATION_TEST_STATE_DIR is required}
release_sha=${RHAOMI_FIRST_ACTIVATION_TEST_RELEASE_SHA:?RHAOMI_FIRST_ACTIVATION_TEST_RELEASE_SHA is required}
image_reference=${RHAOMI_FIRST_ACTIVATION_TEST_IMAGE_REFERENCE:?RHAOMI_FIRST_ACTIVATION_TEST_IMAGE_REFERENCE is required}
image_id=${RHAOMI_FIRST_ACTIVATION_TEST_IMAGE_ID:?RHAOMI_FIRST_ACTIVATION_TEST_IMAGE_ID is required}
failure_stage=${RHAOMI_FIRST_ACTIVATION_TEST_FAIL_STAGE:-}
recovery_root=${RHAOMI_FIRST_ACTIVATION_RECOVERY_ROOT:-}
probe_marker=${RHAOMI_FIRST_ACTIVATION_PROBE_MARKER:-}

printf '%s\n' "$*" >>"$log_file"

service_state() {
  prefix=$1
  service=$2
  printf '%s/%s-%s\n' "$state_dir" "$prefix" "$service"
}

compose_command() {
  compose_prefix=primary
  shift
  if [ "${1:-}" = version ]; then
    printf '%s\n' 'Docker Compose version v2.39.0'
    return 0
  fi
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --project-directory | --env-file | --file | --profile)
        shift 2
        ;;
      --project-name)
        compose_prefix=recovery
        shift 2
        ;;
      *) break ;;
    esac
  done
  command_name=$1
  shift
  case "$command_name" in
    ps)
      service=
      for argument in "$@"; do
        case "$argument" in --all | --quiet) ;; *) service=$argument ;; esac
      done
      state_file=$(service_state "$compose_prefix" "$service")
      if [ -f "$state_file" ]; then
        printf '%s-%s-id\n' "$compose_prefix" "$service"
      fi
      ;;
    up)
      service=
      for argument in "$@"; do
        case "$argument" in --detach | --no-deps | --force-recreate) ;; *) service=$argument ;; esac
      done
      [ -n "$service" ] || exit 64
      [ "$failure_stage" != "$service-start" ] || exit 1
      printf '%s\n' running >"$(service_state "$compose_prefix" "$service")"
      if [ "$compose_prefix:$service" = primary:postgres ]; then
        : >"$state_dir/postgres-volume"
      fi
      if [ "$compose_prefix:$service" = recovery:first-activation-publisher ]; then
        [ -n "$recovery_root" ] && [ -n "$probe_marker" ] || exit 64
        mkdir -p "$recovery_root/public/releases/acceptance/site"
        printf '<!doctype html><html><body>%s</body></html>\n' "$probe_marker" \
          >"$recovery_root/public/releases/acceptance/site/index.html"
        printf '%s\n' '{"schemaVersion":2}' \
          >"$recovery_root/public/releases/acceptance/release-manifest.json"
        ln -s releases/acceptance/site "$recovery_root/public/current"
      fi
      ;;
    run)
      service=
      while [ "$#" -gt 0 ]; do
        case "$1" in
          --rm | --no-deps) shift ;;
          --user) shift 2 ;;
          migration | schema-validate | first-activation-backup-verifier | \
            first-activation-media-restore | first-activation-schema-validate | \
            first-activation-smoke)
            service=$1
            shift
            break
            ;;
          *) shift ;;
        esac
      done
      case "$service" in
        migration) [ "$failure_stage" != migration ] ;;
        schema-validate) [ "$failure_stage" != schema-validate ] ;;
        first-activation-backup-verifier) [ "$failure_stage" != backup-full-read ] ;;
        first-activation-media-restore) [ "$failure_stage" != media-restore ] ;;
        first-activation-schema-validate) [ "$failure_stage" != recovery-schema ] ;;
        first-activation-smoke) [ "$failure_stage" != static-smoke ] ;;
        *) exit 64 ;;
      esac
      ;;
    exec)
      service=
      while [ "$#" -gt 0 ]; do
        case "$1" in
          --no-TTY) shift ;;
          postgres | first-activation-postgres)
            service=$1
            shift
            break
            ;;
          *) shift ;;
        esac
      done
      case "$service" in
        postgres)
          [ "$failure_stage" != flyway ] || printf '%s\n' 9
          [ "$failure_stage" = flyway ] || printf '%s\n' 10
          ;;
        first-activation-postgres)
          case "$*" in
            *pg_restore*) [ "$failure_stage" != database-restore ] ;;
            *flyway_schema_history*)
              if [ "$failure_stage" = recovery-flyway ]; then
                printf '%s\n' 9
              else
                printf '%s\n' 10
              fi
              ;;
            *'SELECT (SELECT COUNT(*)'*) printf '%s\n' '0|0|0|0|0|0|0|0' ;;
            *'INSERT INTO admin_users'*) : >"$state_dir/recovery-seeded" ;;
            *'SELECT state FROM publishing_outbox'*) printf '%s\n' SUCCEEDED ;;
            *) exit 64 ;;
          esac
          ;;
        *) exit 64 ;;
      esac
      ;;
    stop)
      [ "$failure_stage" != writer-quiescence ] || exit 1
      for service in backend publisher; do
        state_file=$(service_state primary "$service")
        if [ -f "$state_file" ]; then
          printf '%s\n' exited >"$state_file"
        fi
      done
      ;;
    down)
      [ "$failure_stage" != recovery-down ] || exit 1
      find "$state_dir" -type f -name 'recovery-*' -delete
      ;;
    *) exit 64 ;;
  esac
}

inspect_container() {
  format=$3
  container_id=$4
  prefix=${container_id%%-*}
  service=${container_id#*-}
  service=${service%-id}
  state_file=$(service_state "$prefix" "$service")
  case "$format" in
    *State.Health*)
      case "$failure_stage:$service" in
        backend-health:backend | recovery-backend-health:first-activation-backend)
          printf '%s\n' unhealthy
          return 0
          ;;
      esac
      case "$service" in
        postgres | backend | first-activation-postgres | first-activation-backend | first-activation-static)
          [ "$(cat "$state_file")" = running ] && printf '%s\n' healthy || cat "$state_file"
          ;;
        *) cat "$state_file" ;;
      esac
      ;;
    *State.Status*)
      case "$failure_stage:$service" in
        publisher-running:publisher | recovery-publisher-running:first-activation-publisher)
          printf '%s\n' exited
          ;;
        *) cat "$state_file" ;;
      esac
      ;;
    *HostConfig.PortBindings*) printf '%s\n' 0 ;;
    *Image*)
      if [ "$failure_stage" = runtime-image ]; then
        printf 'sha256:%064d\n' 9
      else
        printf '%s\n' "$image_id"
      fi
      ;;
    *) exit 64 ;;
  esac
}

case "$1" in
  compose) compose_command "$@" ;;
  ps)
    [ "$failure_stage" != state-unknown ] || exit 1
    case "$*" in
      *first-activation-recovery*) prefix=recovery ;;
      *) prefix=primary ;;
    esac
    for state_file in "$state_dir"/"$prefix"-*; do
      [ -f "$state_file" ] || continue
      case "$(cat "$state_file")" in running | exited) printf '%s\n' "${state_file##*/}-id" ;; esac
    done
    ;;
  volume)
    [ "$2" = ls ] || exit 64
    [ "$failure_stage" != volume-unknown ] || exit 1
    if [ -f "$state_dir/postgres-volume" ]; then
      printf '%s\n' rhaomi-first-activation-validation_postgres-data
    fi
    ;;
  pull) [ "$failure_stage" != image-pull ] ;;
  image)
    [ "$2" = inspect ] || exit 64
    case "$*" in
      *RepoDigests*) printf '%s\n' "$image_reference" ;;
      *org.opencontainers.image.revision*)
        if [ "$failure_stage" = image-revision ]; then
          printf '%040d\n' 9
        else
          printf '%s\n' "$release_sha"
        fi
        ;;
      *'{{.Id}}'*) printf '%s\n' "$image_id" ;;
      *) exit 64 ;;
    esac
    ;;
  inspect) inspect_container "$@" ;;
  *) exit 64 ;;
esac
