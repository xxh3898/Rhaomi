#!/bin/sh

set -eu

log_file=${RHAOMI_INITIAL_ADMIN_TEST_LOG:?RHAOMI_INITIAL_ADMIN_TEST_LOG is required}
state_dir=${RHAOMI_INITIAL_ADMIN_TEST_STATE_DIR:?RHAOMI_INITIAL_ADMIN_TEST_STATE_DIR is required}
image_reference=${RHAOMI_INITIAL_ADMIN_TEST_IMAGE_REFERENCE:?RHAOMI_INITIAL_ADMIN_TEST_IMAGE_REFERENCE is required}
image_id=${RHAOMI_INITIAL_ADMIN_TEST_IMAGE_ID:?RHAOMI_INITIAL_ADMIN_TEST_IMAGE_ID is required}
failure_stage=${RHAOMI_INITIAL_ADMIN_TEST_FAIL_STAGE:-}

printf '%s\n' "$*" >>"$log_file"

inspect_container() {
  format=$3
  container_id=$4
  service=${container_id%-id}
  case "$format" in
    *State.Health*)
      if [ "$service" = backend ] && [ "$(cat "$state_dir/backend")" = running ]; then
        printf '%s\n' healthy
      else
        cat "$state_dir/$service"
      fi
      ;;
    *State.Status*) cat "$state_dir/$service" ;;
    *Config.Labels*) printf '%s\n' "${RHAOMI_INITIAL_ADMIN_TEST_RELEASE_SHA:?}" ;;
    *Config.Image*) printf '%s\n' "$image_reference" ;;
    *Image*) printf '%s\n' "$image_id" ;;
    *) exit 64 ;;
  esac
}

compose_command() {
  shift
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --project-directory | --env-file | --file | --profile) shift 2 ;;
      *) break ;;
    esac
  done
  subcommand=$1
  shift
  case "$subcommand" in
    version) ;;
    ps)
      service=
      for argument in "$@"; do
        case "$argument" in --all | --quiet) ;; *) service=$argument ;; esac
      done
      [ -n "$service" ] || exit 64
      printf '%s-id\n' "$service"
      ;;
    stop)
      [ "$failure_stage" != writer-stop ] || exit 1
      printf '%s\n' exited >"$state_dir/backend"
      printf '%s\n' exited >"$state_dir/publisher"
      ;;
    run)
      task=
      for argument in "$@"; do
        [ "$argument" = initial-admin ] && task=$argument
      done
      [ "$task" = initial-admin ] || exit 64
      [ "$(cat "$state_dir/backend")" = exited ] || exit 1
      [ "$(cat "$state_dir/publisher")" = exited ] || exit 1
      [ "$failure_stage" != task ] || exit 1
      printf '%s\n' '{"contract":"rhaomi-initial-admin-v1","status":"success","administratorCount":1}'
      ;;
    up)
      service=
      for argument in "$@"; do
        case "$argument" in --detach | --no-deps | --force-recreate) ;; *) service=$argument ;; esac
      done
      [ -n "$service" ] || exit 64
      [ "$failure_stage" != "${service}-recovery" ] || exit 1
      printf '%s\n' running >"$state_dir/$service"
      ;;
    *) exit 64 ;;
  esac
}

case "$1" in
  compose) compose_command "$@" ;;
  inspect) inspect_container "$@" ;;
  *) exit 64 ;;
esac
