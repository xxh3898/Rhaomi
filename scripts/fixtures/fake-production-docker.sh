#!/bin/sh

set -eu

log_file=${RHAOMI_DEPLOY_TEST_LOG:?RHAOMI_DEPLOY_TEST_LOG is required}
state_dir=${RHAOMI_DEPLOY_TEST_STATE_DIR:?RHAOMI_DEPLOY_TEST_STATE_DIR is required}
release_sha=${RHAOMI_DEPLOY_TEST_RELEASE_SHA:?RHAOMI_DEPLOY_TEST_RELEASE_SHA is required}
image_reference=${RHAOMI_DEPLOY_TEST_IMAGE_REFERENCE:?RHAOMI_DEPLOY_TEST_IMAGE_REFERENCE is required}
image_id=${RHAOMI_DEPLOY_TEST_IMAGE_ID:?RHAOMI_DEPLOY_TEST_IMAGE_ID is required}
failure_stage=${RHAOMI_DEPLOY_TEST_FAIL_STAGE:-}

inspect_image() {
  format=$4
  case "$format" in
    *RepoDigests*) printf '%s\n' "$image_reference" ;;
    *org.opencontainers.image.revision*)
      printf '%s\n' "${RHAOMI_DEPLOY_TEST_REVISION_OVERRIDE:-$release_sha}"
      ;;
    *Id*) printf '%s\n' "$image_id" ;;
    *) exit 64 ;;
  esac
}

inspect_container() {
  format=$3
  container_id=$4
  service=${container_id%-id}
  case "$format" in
    *State.Health*)
      if [ "$service" = backend ] && [ "$failure_stage" = backend-health ]; then
        printf '%s\n' unhealthy
      elif [ "$service" = backend ] && [ "$(cat "$state_dir/$service")" = running ]; then
        printf '%s\n' healthy
      else
        cat "$state_dir/$service"
      fi
      ;;
    *State.Status*) cat "$state_dir/$service" ;;
    *Image*)
      if [ "$failure_stage" = "runtime-${service}-image" ]; then
        printf 'sha256:%064d\n' 0
      else
        printf '%s\n' "$image_id"
      fi
      ;;
    *) exit 64 ;;
  esac
}

compose_command() {
  shift
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --project-directory | --env-file | --file)
        shift 2
        ;;
      --profile)
        shift 2
        ;;
      *) break ;;
    esac
  done
  subcommand=$1
  shift

  case "$subcommand" in
    ps)
      service=
      for argument in "$@"; do
        case "$argument" in
          --all | --quiet) ;;
          *) service=$argument ;;
        esac
      done
      [ -n "$service" ] || exit 64
      printf '%s-id\n' "$service"
      ;;
    exec)
      [ "$failure_stage" != public-web ] || exit 1
      [ "$(cat "$state_dir/rhaomi-web")" = running ]
      ;;
    stop)
      [ "$failure_stage" != writer-stop ] || exit 1
      printf '%s\n' exited >"$state_dir/backend"
      printf '%s\n' exited >"$state_dir/publisher"
      ;;
    run)
      task=
      for argument in "$@"; do
        case "$argument" in
          migration | schema-validate) task=$argument ;;
        esac
      done
      [ -n "$task" ] || exit 64
      if [ "$(cat "$state_dir/backend")" != exited ] ||
        [ "$(cat "$state_dir/publisher")" != exited ]; then
        printf '%s\n' "$task" >"$state_dir/quiescence-violation"
        exit 1
      fi
      [ "$failure_stage" != "$task" ] || exit 1
      ;;
    up)
      service=
      for argument in "$@"; do
        case "$argument" in
          --detach | --no-deps | --force-recreate) ;;
          *) service=$argument ;;
        esac
      done
      [ -n "$service" ] || exit 64
      [ "$failure_stage" != "${service}-start" ] || exit 1
      printf '%s\n' running >"$state_dir/$service"
      ;;
    *) exit 64 ;;
  esac
}

printf '%s\n' "$*" >>"$log_file"

case "$1" in
  pull)
    [ "$failure_stage" != image-pull ] || exit 1
    if [ -n "${RHAOMI_DEPLOY_TEST_PULL_RELEASE_FILE:-}" ]; then
      : >"$state_dir/pull-started"
      while [ ! -f "$RHAOMI_DEPLOY_TEST_PULL_RELEASE_FILE" ]; do
        sleep 1
      done
    fi
    ;;
  image) inspect_image "$@" ;;
  inspect) inspect_container "$@" ;;
  compose) compose_command "$@" ;;
  *) exit 64 ;;
esac
