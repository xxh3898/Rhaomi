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

compose() {
  if [ -n "${RHAOMI_COMPOSE_OVERLAY:-}" ]; then
    docker compose --env-file "$env_file" -f "$compose_file" -f "$RHAOMI_COMPOSE_OVERLAY" "$@"
  else
    docker compose --env-file "$env_file" -f "$compose_file" "$@"
  fi
}

cleanup() {
  compose --profile frontend --profile smoke down >/dev/null 2>&1 || true
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

compose --profile frontend run --rm --no-deps frontend npm ci
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
frontend_port_bindings=$(docker inspect "$frontend_id" --format '{{json .HostConfig.PortBindings}}')
case "$frontend_port_bindings" in
  null | '{}') ;;
  *)
    echo "frontend direct host port binding이 감지됐습니다." >&2
    exit 1
    ;;
esac
postgres_id=$(compose ps -q postgres)
postgres_port_bindings=$(docker inspect "$postgres_id" --format '{{json .HostConfig.PortBindings}}')
case "$postgres_port_bindings" in
  null | '{}') ;;
  *)
    echo "PostgreSQL host port binding이 감지됐습니다." >&2
    exit 1
    ;;
esac
gateway_id=$(compose --profile frontend ps -q gateway)
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
