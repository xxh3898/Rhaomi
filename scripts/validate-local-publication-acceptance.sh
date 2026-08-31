#!/bin/sh

set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
compose_file="$repo_dir/compose.dev.yaml"
env_file=${1:-"$repo_dir/.env.example"}

if [ ! -f "$env_file" ]; then
  echo "환경파일을 찾을 수 없습니다: $env_file" >&2
  exit 1
fi

if ! git_head=$(git -C "$repo_dir" rev-parse HEAD) ||
  ! printf '%s' "$git_head" | grep -Eq '^[0-9a-f]{40}$'; then
  echo "acceptance exact Git HEAD를 확인하지 못했습니다." >&2
  exit 1
fi

acceptance_root=$(mktemp -d "${TMPDIR:-/tmp}/rhaomi-publication-acceptance.XXXXXX")
marker="$acceptance_root/.rhaomi-publication-acceptance"
: >"$marker"
acceptance_project_name="dev-rhaomi-publication-acceptance-$(printf '%s' "$git_head" | cut -c1-12)-$$"
export RHAOMI_PUBLICATION_ACCEPTANCE_ROOT="$acceptance_root"
export RHAOMI_CLEANUP_GIT_HEAD="$git_head"
export RHAOMI_CLEANUP_TASK="${RHAOMI_CLEANUP_TASK:-issue-45-local-publication-acceptance}"
export RHAOMI_ACCEPTANCE_GIT_HEAD="$git_head"
export RHAOMI_COMPOSE_PROJECT_NAME="$acceptance_project_name"
export RHAOMI_PUBLICATION_ACCEPTANCE_DATABASE_PASSWORD="publication-acceptance-$git_head"
export RHAOMI_PUBLICATION_ACCEPTANCE_ADMIN_PASSWORD="publication-acceptance-admin-$git_head"

compose() {
  docker compose --env-file "$env_file" -f "$compose_file" "$@"
}

cleanup() {
  cleanup_failed=false
  compose --profile publication-acceptance down >/dev/null 2>&1 || true
  if [ -n "$acceptance_root" ] &&
    [ "$acceptance_root" != "/" ] &&
    [ -f "$marker" ]; then
    if find "$acceptance_root" -mindepth 1 ! -path "$marker" -print -quit |
      grep -q .; then
      if ! compose --profile publication-acceptance run --rm --no-deps \
        --entrypoint /bin/sh publication-acceptance-runner -c \
        'test -f /acceptance/.rhaomi-publication-acceptance && find /acceptance -mindepth 1 ! -name .rhaomi-publication-acceptance -delete' \
        >/dev/null 2>&1; then
        cleanup_failed=true
      fi
      compose --profile publication-acceptance down >/dev/null 2>&1 || cleanup_failed=true
    fi
    rm -f -- "$marker" || cleanup_failed=true
    rmdir -- "$acceptance_root" || cleanup_failed=true
  fi
  if [ "$cleanup_failed" = "true" ]; then
    echo "publication acceptance marker 임시 root 정리에 실패했습니다." >&2
    return 1
  fi
}

trap cleanup EXIT HUP INT TERM

compose --profile publication-acceptance config --quiet
services=$(compose --profile publication-acceptance config --services | sort)
if [ "$services" != "backend
postgres
publication-acceptance-admin-gateway
publication-acceptance-postgres
publication-acceptance-runner
publication-acceptance-smoke
publication-acceptance-static" ]; then
  echo "publication acceptance profile service 경계가 예상과 다릅니다." >&2
  exit 1
fi

compose --profile publication-acceptance build publication-acceptance-runner
compose --profile publication-acceptance up -d --wait --wait-timeout 120 \
  publication-acceptance-postgres publication-acceptance-admin-gateway
admin_gateway_id=$(compose --profile publication-acceptance ps -q \
  publication-acceptance-admin-gateway)
test -n "$admin_gateway_id"
backend_networks=$(docker inspect "$admin_gateway_id" --format \
  '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}} {{end}}')
set -- $backend_networks
if [ "$#" -ne 1 ]; then
  echo "acceptance Admin gateway network 경계가 예상과 다릅니다." >&2
  exit 1
fi
acceptance_backend_network=$1
case "$acceptance_backend_network" in
  *-publication-acceptance-backend) ;;
  *)
    echo "acceptance backend network을 확인하지 못했습니다." >&2
    exit 1
    ;;
esac
compose --profile publication-acceptance up -d publication-acceptance-runner
runner_id=$(compose --profile publication-acceptance ps -q publication-acceptance-runner)
test -n "$runner_id"
runner_exit=$(docker wait "$runner_id")
docker logs "$runner_id"
if [ "$runner_exit" != "0" ]; then
  echo "publication acceptance runner가 실패했습니다." >&2
  exit 1
fi
compose --profile publication-acceptance stop \
  publication-acceptance-runner \
  publication-acceptance-admin-gateway \
  publication-acceptance-postgres

if [ -n "$(compose --profile publication-acceptance ps -q publication-acceptance-postgres)" ]; then
  postgres_state=$(compose --profile publication-acceptance ps --format json \
    publication-acceptance-postgres)
  case "$postgres_state" in
    *'"State":"exited"'* | *'"State":"stopped"'*) ;;
    *)
      echo "acceptance PostgreSQL이 중단되지 않았습니다." >&2
      exit 1
      ;;
  esac
fi

compose --profile publication-acceptance up -d --wait --wait-timeout 60 \
  publication-acceptance-static
static_id=$(compose --profile publication-acceptance ps -q publication-acceptance-static)
test -n "$static_id"
static_mount_mode=$(docker inspect "$static_id" --format \
  '{{range .Mounts}}{{if eq .Destination "/srv/rhaomi/public"}}{{.RW}}{{end}}{{end}}')
test "$static_mount_mode" = "false"
static_networks=$(docker inspect "$static_id" --format \
  '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}} {{end}}')
set -- $static_networks
if [ "$#" -ne 1 ]; then
  echo "acceptance static server network 경계가 예상과 다릅니다." >&2
  exit 1
fi
acceptance_public_network=$1
case "$acceptance_public_network" in
  *-publication-acceptance-public) ;;
  *)
    echo "acceptance static server의 public network를 확인하지 못했습니다." >&2
    exit 1
    ;;
esac
case "$static_networks" in
  *backend*)
    echo "acceptance static server가 backend network에 연결됐습니다." >&2
    exit 1
    ;;
esac

compose --profile publication-acceptance run --rm --no-deps \
  publication-acceptance-smoke

compose --profile publication-acceptance down
remaining_containers=$(docker ps -aq \
  --filter "label=com.docker.compose.project=$acceptance_project_name")
test -z "$remaining_containers"
if docker network inspect "$acceptance_public_network" >/dev/null 2>&1 ||
  docker network inspect "$acceptance_backend_network" >/dev/null 2>&1; then
  echo "acceptance task network가 남았습니다." >&2
  exit 1
fi

echo "Local publication acceptance passed: exact-head=$git_head task-resources=0"
