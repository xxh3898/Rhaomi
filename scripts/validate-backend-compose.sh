#!/bin/sh

set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
compose_file="$repo_dir/compose.dev.yaml"
env_file=${1:-"$repo_dir/.env.dev.local"}
resource_prefix=${RHAOMI_COMPOSE_VOLUME_PREFIX:-dev-rhaomi}
network_prefix=${RHAOMI_COMPOSE_NETWORK_PREFIX:-dev-rhaomi}
media_volume="$resource_prefix-backend-media-masters"
postgres_volume="$resource_prefix-postgres-18-backend-data"

if [ ! -f "$env_file" ]; then
  echo "환경파일을 찾을 수 없습니다: $env_file" >&2
  exit 1
fi

if [ "${RHAOMI_BOOTSTRAP_ADMIN_ENABLED:-}" != "true" ] ||
  [ -z "${RHAOMI_BOOTSTRAP_ADMIN_EMAIL:-}" ] ||
  [ -z "${RHAOMI_BOOTSTRAP_ADMIN_PASSWORD:-}" ]; then
  echo "Compose smoke에는 명시적인 local/test bootstrap 환경변수가 필요합니다." >&2
  exit 1
fi

if ! command -v openssl >/dev/null 2>&1; then
  echo "Compose smoke token 검증에 openssl이 필요합니다." >&2
  exit 1
fi

local_file_build_token=$(awk '
  index($0, "RHAOMI_BUILD_SERVICE_TOKEN=") == 1 {
    print substr($0, length("RHAOMI_BUILD_SERVICE_TOKEN=") + 1)
    exit
  }
' "$env_file")
local_file_token_digest=""
if printf '%s' "$local_file_build_token" | grep -Eq '^[0-9a-f]{64}$'; then
  local_file_token_digest=$(
    printf '%s' "$local_file_build_token" | openssl dgst -sha256 | awk '{print $NF}'
  )
fi
unset local_file_build_token

RHAOMI_BUILD_SERVICE_TOKEN=$(openssl rand -hex 32)
export RHAOMI_BUILD_SERVICE_TOKEN
build_token_digest=$(
  printf '%s' "$RHAOMI_BUILD_SERVICE_TOKEN" | openssl dgst -sha256 | awk '{print $NF}'
)
if ! printf '%s' "$build_token_digest" | grep -Eq '^[0-9a-f]{64}$'; then
  echo "Compose smoke token digest를 생성하지 못했습니다." >&2
  exit 1
fi

compose() {
  if [ -n "${RHAOMI_COMPOSE_OVERLAY:-}" ]; then
    docker compose --env-file "$env_file" -f "$compose_file" -f "$RHAOMI_COMPOSE_OVERLAY" "$@"
  else
    docker compose --env-file "$env_file" -f "$compose_file" "$@"
  fi
}

cleanup() {
  compose --profile frontend --profile smoke --profile validation down >/dev/null 2>&1 || true
}

admin_count() {
  compose exec -T postgres sh -c \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT count(*) FROM admin_users;"'
}

media_count() {
  compose exec -T postgres sh -c \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT count(*) FROM media_assets;"'
}

trap cleanup EXIT HUP INT TERM

compose config --quiet

services=$(compose config --services | sort)
if [ "$services" != "backend
postgres" ]; then
  echo "기본 Compose service가 backend/postgres 경계를 벗어났습니다." >&2
  exit 1
fi

frontend_services=$(compose --profile frontend config --services | sort)
if [ "$frontend_services" != "backend
frontend
gateway
postgres" ]; then
  echo "frontend profile service 경계가 예상과 다릅니다." >&2
  exit 1
fi

validation_services=$(compose --profile validation config --services | sort)
if [ "$validation_services" != "backend
contract-check
postgres" ]; then
  echo "validation profile service 경계가 예상과 다릅니다." >&2
  exit 1
fi

compose --profile frontend run --rm --no-deps frontend npm ci
compose --profile validation run --rm --no-deps contract-check sh -c '
  architecture=$(uname -m)
  case "$architecture" in
    x86_64 | aarch64) ;;
    *)
      echo "지원하지 않는 transformer container architecture입니다." >&2
      exit 1
      ;;
  esac
  echo "Transformer container architecture: $architecture"
  npm run test:transformer
'
compose --profile frontend up -d --wait --wait-timeout 300 postgres backend frontend gateway
compose ps

backend_java_version=$(compose exec -T backend java -version 2>&1)
case "$backend_java_version" in
  *'version "25.'*) ;;
  *)
    echo "backend Java 25 runtime을 확인하지 못했습니다." >&2
    exit 1
    ;;
esac

curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"'
curl --fail --silent --show-error http://127.0.0.1:3000/admin/ | grep -q '라오미펫 관리자'
compose exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
test "$(compose port backend 8080)" = "127.0.0.1:8080"
test "$(compose --profile frontend port gateway 3000)" = "127.0.0.1:3000"
frontend_id=$(compose --profile frontend ps -q frontend)
gateway_id=$(compose --profile frontend ps -q gateway)
frontend_port_bindings=$(docker inspect "$frontend_id" --format '{{json .HostConfig.PortBindings}}')
case "$frontend_port_bindings" in
  null | '{}') ;;
  *)
    echo "frontend direct host port binding이 감지됐습니다." >&2
    exit 1
    ;;
esac
frontend_token_env_count=$(
  docker inspect "$frontend_id" --format '{{range .Config.Env}}{{println .}}{{end}}' |
    awk -F= '$1 == "RHAOMI_BUILD_SERVICE_TOKEN" { count += 1 } END { print count + 0 }'
)
gateway_token_env_count=$(
  docker inspect "$gateway_id" --format '{{range .Config.Env}}{{println .}}{{end}}' |
    awk -F= '$1 == "RHAOMI_BUILD_SERVICE_TOKEN" { count += 1 } END { print count + 0 }'
)
test "$frontend_token_env_count" -eq 0
test "$gateway_token_env_count" -eq 0
frontend_workspace_root_mount=$(
  docker inspect "$frontend_id" --format \
    '{{range .Mounts}}{{if eq .Destination "/workspace"}}{{.Source}}{{end}}{{end}}'
)
test -z "$frontend_workspace_root_mount"
compose exec -T frontend test ! -e /workspace/.env.dev.local

backend_token_digest=$(
  compose exec -T backend sh -c \
    'test -n "$RHAOMI_BUILD_SERVICE_TOKEN" && printf "%s" "$RHAOMI_BUILD_SERVICE_TOKEN" | sha256sum | cut -d " " -f 1'
)
test "$backend_token_digest" = "$build_token_digest"
if [ -n "$local_file_token_digest" ] && [ "$local_file_token_digest" != "$build_token_digest" ]; then
  compose exec -T frontend node scripts/validate-frontend-credential-isolation.mjs \
    /workspace "$build_token_digest" "$local_file_token_digest"
else
  compose exec -T frontend node scripts/validate-frontend-credential-isolation.mjs \
    /workspace "$build_token_digest"
fi

backend_build_status=$(
  printf 'Authorization: Bearer %s\n' "$RHAOMI_BUILD_SERVICE_TOKEN" |
    curl --header @- --silent --show-error --output /dev/null --write-out '%{http_code}' \
      'http://127.0.0.1:8080/api/build/snapshot?publishGeneration=9223372036854775807'
)
test "$backend_build_status" = "409"
gateway_build_status=$(
  printf 'Authorization: Bearer %s\n' "$RHAOMI_BUILD_SERVICE_TOKEN" |
    curl --header @- --silent --show-error --output /dev/null --write-out '%{http_code}' \
      'http://127.0.0.1:3000/api/build/snapshot?publishGeneration=9223372036854775807'
)
test "$gateway_build_status" = "404"

postgres_id=$(compose ps -q postgres)
postgres_port_bindings=$(docker inspect "$postgres_id" --format '{{json .HostConfig.PortBindings}}')
case "$postgres_port_bindings" in
  null | '{}') ;;
  *)
    echo "PostgreSQL host port binding이 감지됐습니다." >&2
    exit 1
    ;;
esac
gateway_networks=$(docker inspect "$gateway_id" --format '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}} {{end}}')
postgres_networks=$(docker inspect "$postgres_id" --format '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}} {{end}}')
case "$gateway_networks" in
  *"$network_prefix-backend-internal"*)
    echo "gateway가 PostgreSQL backend network에 연결됐습니다." >&2
    exit 1
    ;;
esac
case "$postgres_networks" in
  *"$network_prefix-backend-gateway-internal"* | *"$network_prefix-frontend-local"*)
    echo "PostgreSQL이 gateway/frontend network에 연결됐습니다." >&2
    exit 1
    ;;
esac
test "$(docker volume inspect "$postgres_volume" --format '{{.Name}}')" = "$postgres_volume"
test "$(docker volume inspect "$media_volume" --format '{{.Name}}')" = "$media_volume"
backend_id=$(compose ps -q backend)
backend_media_mount=$(docker inspect "$backend_id" --format \
  '{{range .Mounts}}{{if eq .Destination "/var/lib/rhaomi/media"}}{{.Name}}{{end}}{{end}}')
test "$backend_media_mount" = "$media_volume"

before_admins=$(admin_count)
before_media=$(media_count)
test "$before_admins" -ge 1
compose --profile frontend --profile smoke run --rm smoke \
  node scripts/validate-gateway.mjs http://gateway:3000 normal
compose --profile frontend --profile smoke run --rm smoke
media_upload_output=$(compose --profile frontend --profile smoke run --rm smoke \
  node scripts/validate-backend-media.mjs http://gateway:3000 upload)
media_state=$(printf '%s\n' "$media_upload_output" | sed -n 's/^MEDIA_STATE=//p' | tail -n 1)
test -n "$media_state"
after_upload_media=$(media_count)
test "$after_upload_media" -eq "$((before_media + 1))"

compose stop backend
compose --profile frontend --profile smoke run --rm --no-deps smoke \
  node scripts/validate-gateway.mjs http://gateway:3000 upstream-unavailable
compose up -d --wait --wait-timeout 300 backend

compose stop backend postgres
compose up -d --wait --wait-timeout 300 postgres backend

after_admins=$(admin_count)
after_media=$(media_count)
test "$after_admins" = "$before_admins"
test "$after_media" = "$after_upload_media"
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"'
compose --profile frontend --profile smoke run --rm smoke
compose --profile frontend --profile smoke run --rm smoke \
  node scripts/validate-backend-media.mjs http://gateway:3000 verify "$media_state"

echo "Same-origin Compose validation passed: admin_users=$after_admins media_assets=$after_media"
