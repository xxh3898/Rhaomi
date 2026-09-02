#!/bin/sh

set -eu

log_file=${RHAOMI_BACKUP_TEST_LOG:?RHAOMI_BACKUP_TEST_LOG is required}
state_dir=${RHAOMI_BACKUP_TEST_STATE_DIR:?RHAOMI_BACKUP_TEST_STATE_DIR is required}
repository=${RHAOMI_BACKUP_TEST_REPOSITORY:?RHAOMI_BACKUP_TEST_REPOSITORY is required}
release_sha=${RHAOMI_BACKUP_TEST_RELEASE_SHA:?RHAOMI_BACKUP_TEST_RELEASE_SHA is required}
image_reference=${RHAOMI_BACKUP_TEST_IMAGE_REFERENCE:?RHAOMI_BACKUP_TEST_IMAGE_REFERENCE is required}
image_id=${RHAOMI_BACKUP_TEST_IMAGE_ID:?RHAOMI_BACKUP_TEST_IMAGE_ID is required}
failure_stage=${RHAOMI_BACKUP_TEST_FAIL_STAGE:-}
lock_owner=${RHAOMI_BACKUP_TEST_LOCK_OWNER:-}

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
    *Config.Image*) printf '%s\n' "$image_reference" ;;
    *Image*) printf '%s\n' "$image_id" ;;
    *) exit 64 ;;
  esac
}

compose_command() {
  shift
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --project-directory | --env-file | --file | --profile)
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
        case "$argument" in --all | --quiet) ;; *) service=$argument ;; esac
      done
      printf '%s-id\n' "$service"
      ;;
    stop)
      [ "$failure_stage" != writer-stop ] || exit 1
      printf '%s\n' exited >"$state_dir/backend"
      printf '%s\n' exited >"$state_dir/publisher"
      ;;
    up)
      service=
      for argument in "$@"; do
        case "$argument" in --detach | --no-deps | --force-recreate) ;; *) service=$argument ;; esac
      done
      [ -n "$service" ] || exit 64
      [ "$failure_stage" != "${service}-start" ] || exit 1
      printf '%s\n' running >"$state_dir/$service"
      ;;
    exec)
      service=
      for argument in "$@"; do
        case "$argument" in --no-TTY) ;; rhaomi-web | postgres) service=$argument; break ;; esac
      done
      case "$service" in
        rhaomi-web) [ "$failure_stage" != public-web ] ;;
        postgres)
          case "$*" in
            *pg_dump*)
              [ "$failure_stage" != dump ] || exit 1
              printf 'PGDMPfake-archive'
              ;;
            *pg_restore*) [ "$failure_stage" != dump-verify ] ;;
            *flyway_schema_history*) printf '%s\n' 10 ;;
            *) exit 64 ;;
          esac
          ;;
        *) exit 64 ;;
      esac
      ;;
    run)
      service=
      while [ "$#" -gt 0 ]; do
        case "$1" in
          --rm | --no-deps) shift ;;
          --user) shift 2 ;;
          backup-tool | backup-permission) service=$1; shift; break ;;
          *) shift ;;
        esac
      done
      [ -n "$service" ] || exit 64
      if [ "$service" = backup-permission ]; then
        operation=$1
        case "$operation" in
          assert-runtime)
            [ "$(cat "$state_dir/media-permission")" = runtime ] || exit 1
            ;;
          assert-capture)
            [ "$(cat "$state_dir/media-permission")" = capture ] || exit 1
            ;;
          capture)
            printf '%s\n' capture >"$state_dir/media-permission"
            ;;
          runtime)
            [ "$failure_stage" != runtime-permission ] || exit 1
            printf '%s\n' runtime >"$state_dir/media-permission"
            ;;
          *) exit 64 ;;
        esac
        exit 0
      fi
      [ "$1" = node ] || exit 64
      shift 2
      operation=$1
      shift
      case "$operation" in
        begin)
          set_id=$1
          mkdir "$repository/sets/.incomplete-$set_id"
          ;;
        capture-media)
          if [ "$failure_stage" = capture ]; then exit 1; fi
          ;;
        finalize)
          [ "$failure_stage" != finalize ] || exit 1
          set_id=$1
          mv "$repository/sets/.incomplete-$set_id" "$repository/sets/$set_id"
          if [ "$failure_stage" = lock-release ]; then
            [ -n "$lock_owner" ] || exit 64
            printf '%s\n' synthetic-other-owner >"$lock_owner"
          fi
          ;;
        issue-eligibility)
          if [ "$failure_stage" = eligibility ]; then exit 1; fi
          ;;
        verify | retention-plan | retention-apply) ;;
        *) exit 64 ;;
      esac
      printf '{"status":"ok"}\n'
      ;;
    *) exit 64 ;;
  esac
}

case "${0##*/}" in
  docker-compose) compose_command compose "$@" ;;
  *)
    case "$1" in
      inspect) inspect_container "$@" ;;
      image)
        case "$*" in
          *org.opencontainers.image.revision*) printf '%s\n' "$release_sha" ;;
          *) exit 64 ;;
        esac
        ;;
      *) exit 64 ;;
    esac
    ;;
esac
