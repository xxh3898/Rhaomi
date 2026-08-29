#!/bin/sh

set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
compose_file="$repo_dir/compose.dev.yaml"
env_file=${1:-"$repo_dir/.env.dev.local"}
resource_prefix=${RHAOMI_COMPOSE_VOLUME_PREFIX:-dev-rhaomi}
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
  docker compose --env-file "$env_file" -f "$compose_file" "$@"
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

compose up -d --wait --wait-timeout 300 postgres backend
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
compose exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
test "$(compose port backend 8080)" = "127.0.0.1:8080"
postgres_id=$(compose ps -q postgres)
postgres_port_bindings=$(docker inspect "$postgres_id" --format '{{json .HostConfig.PortBindings}}')
case "$postgres_port_bindings" in
  null | '{}') ;;
  *)
    echo "PostgreSQL host port binding이 감지됐습니다." >&2
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
compose --profile smoke run --rm smoke
media_upload_output=$(compose --profile smoke run --rm smoke \
  node scripts/validate-backend-media.mjs http://backend:8080 upload)
media_state=$(printf '%s\n' "$media_upload_output" | sed -n 's/^MEDIA_STATE=//p' | tail -n 1)
test -n "$media_state"
after_upload_media=$(media_count)
test "$after_upload_media" -eq "$((before_media + 1))"

compose stop backend postgres
compose up -d --wait --wait-timeout 300 postgres backend

after_admins=$(admin_count)
after_media=$(media_count)
test "$after_admins" = "$before_admins"
test "$after_media" = "$after_upload_media"
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"'
compose --profile smoke run --rm smoke
compose --profile smoke run --rm smoke \
  node scripts/validate-backend-media.mjs http://backend:8080 verify "$media_state"

echo "Backend Compose validation passed: admin_users=$after_admins media_assets=$after_media"
