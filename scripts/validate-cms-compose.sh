#!/bin/sh

set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
compose_file="$repo_dir/compose.dev.yaml"
env_file=${1:-"$repo_dir/.env.dev.local"}

if [ ! -f "$env_file" ]; then
  echo "환경파일을 찾을 수 없습니다: $env_file" >&2
  exit 1
fi

compose() {
  docker compose --env-file "$env_file" -f "$compose_file" "$@"
}

cleanup() {
  compose --profile frontend down >/dev/null 2>&1 || true
}

table_count() {
  compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT count(*) FROM information_schema.tables WHERE table_schema = '\''public'\'';"'
}

admin_count() {
  compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT count(*) FROM directus_users WHERE role IS NOT NULL;"'
}

trap cleanup EXIT HUP INT TERM

compose config --quiet
compose up -d --wait --wait-timeout 180 postgres directus
compose ps

test "$(curl --fail --silent --show-error http://127.0.0.1:8055/server/ping)" = "pong"
compose exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'

before_tables=$(table_count)
before_admins=$(admin_count)

test "$before_tables" -gt 0
test "$before_admins" -gt 0
test "$(docker volume inspect dev-rhaomi-postgres-18-data --format '{{.Name}}')" = "dev-rhaomi-postgres-18-data"
test "$(docker volume inspect dev-rhaomi-directus-uploads --format '{{.Name}}')" = "dev-rhaomi-directus-uploads"

compose stop directus postgres
compose up -d --wait --wait-timeout 180 postgres directus

after_tables=$(table_count)
after_admins=$(admin_count)

test "$after_tables" = "$before_tables"
test "$after_admins" = "$before_admins"
test "$(curl --fail --silent --show-error http://127.0.0.1:8055/server/ping)" = "pong"

echo "CMS Compose validation passed: tables=$after_tables, admin_users=$after_admins"
