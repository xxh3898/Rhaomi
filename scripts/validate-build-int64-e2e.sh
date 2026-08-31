#!/bin/sh

set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
git_head=$(git -C "$repo_dir" rev-parse HEAD)
resource_name="dev-rhaomi-int64-e2e-$$"
network_name="$resource_name-network"
postgres_name="$resource_name-postgres"
backend_name="$resource_name-backend"
postgres_image='postgres:18.6-alpine3.23@sha256:697c180dbf244d3ce4a8f4cbc0156cde840af055c1bf8b76aebe422a4822086f'
node_image='node:24.20.0-alpine3.23@sha256:0388af2af070cd4736a1567cfed02469ba117848845b4165d87a333edb53d2ca'
backend_image=${RHAOMI_INT64_BACKEND_IMAGE:-dev-rhaomi-backend:java25-libheif-1.23.0}
volume_prefix=${RHAOMI_COMPOSE_VOLUME_PREFIX:-dev-rhaomi}
node_modules_volume=${RHAOMI_INT64_NODE_MODULES_VOLUME:-"$volume_prefix-frontend-node-modules"}
gradle_cache_volume=${RHAOMI_INT64_GRADLE_CACHE_VOLUME:-"$volume_prefix-backend-gradle-cache"}
cleanup_task=${RHAOMI_CLEANUP_TASK:-issue-42-int64-v2-corrective}

validate_resource_name() {
  case "$1" in
    "" | [!A-Za-z0-9]* | *[!A-Za-z0-9_.-]*)
      echo "int64 E2E resource 이름이 유효하지 않습니다." >&2
      exit 1
      ;;
  esac
}

validate_resource_name "$node_modules_volume"
validate_resource_name "$gradle_cache_volume"
validate_resource_name "$cleanup_task"

if ! docker volume inspect "$node_modules_volume" "$gradle_cache_volume" >/dev/null 2>&1; then
  echo "int64 E2E에는 사전에 label과 함께 생성된 Node/Gradle cache volume이 필요합니다." >&2
  exit 1
fi

if [ -z "${RHAOMI_BUILD_SERVICE_TOKEN:-}" ]; then
  echo "int64 E2E에는 build service test token이 필요합니다." >&2
  exit 1
fi

cleanup() {
  docker stop "$backend_name" >/dev/null 2>&1 || true
  docker stop "$postgres_name" >/dev/null 2>&1 || true
  docker network rm "$network_name" >/dev/null 2>&1 || true
}

trap cleanup EXIT HUP INT TERM

docker network create --internal \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label "io.homeserver.cleanup.task=$cleanup_task" \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=$git_head" \
  "$network_name" >/dev/null

docker run -d --rm \
  --name "$postgres_name" \
  --network "$network_name" \
  --network-alias postgres \
  --tmpfs /var/lib/postgresql:rw,nosuid,nodev,size=512m \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label "io.homeserver.cleanup.task=$cleanup_task" \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=$git_head" \
  -e POSTGRES_DB=rhaomi_int64_e2e \
  -e POSTGRES_USER=rhaomi_int64_e2e \
  -e POSTGRES_PASSWORD=rhaomi-int64-e2e-password \
  "$postgres_image" >/dev/null

for attempt in $(seq 1 60); do
  if docker exec "$postgres_name" \
    pg_isready -U rhaomi_int64_e2e -d rhaomi_int64_e2e >/dev/null 2>&1; then
    break
  fi
  if [ "$attempt" -eq 60 ]; then
    echo "int64 E2E PostgreSQL이 준비되지 않았습니다." >&2
    exit 1
  fi
  sleep 1
done

docker run -d --rm \
  --name "$backend_name" \
  --network "$network_name" \
  --network-alias backend \
  --tmpfs /tmp:rw,nosuid,nodev,size=512m \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label "io.homeserver.cleanup.task=$cleanup_task" \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=$git_head" \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/rhaomi_int64_e2e \
  -e SPRING_DATASOURCE_USERNAME=rhaomi_int64_e2e \
  -e SPRING_DATASOURCE_PASSWORD=rhaomi-int64-e2e-password \
  -e RHAOMI_BOOTSTRAP_ADMIN_ENABLED=true \
  -e RHAOMI_BOOTSTRAP_ADMIN_EMAIL=int64.e2e@example.com \
  -e RHAOMI_BOOTSTRAP_ADMIN_PASSWORD=local-int64-e2e-password-123! \
  -e RHAOMI_BUILD_SERVICE_TOKEN \
  -e RHAOMI_MEDIA_ROOT=/tmp/media \
  -v "$repo_dir/backend:/workspace/backend" \
  -v "$gradle_cache_volume:/root/.gradle" \
  -w /workspace/backend \
  "$backend_image" \
  ./gradlew bootRun --no-daemon >/dev/null

for attempt in $(seq 1 120); do
  if docker exec "$backend_name" sh -c \
    'wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"'"'"status":"UP"'"'"'' \
    >/dev/null 2>&1; then
    break
  fi
  if [ "$attempt" -eq 120 ]; then
    docker logs --tail 120 "$backend_name" >&2
    echo "int64 E2E backend가 준비되지 않았습니다." >&2
    exit 1
  fi
  sleep 1
done

docker exec -i "$postgres_name" \
  psql -v ON_ERROR_STOP=1 -U rhaomi_int64_e2e -d rhaomi_int64_e2e >/dev/null <<'SQL'
INSERT INTO shop_settings (
    id, singleton_key, shop_name, region_label, business_type, phone, address,
    opening_time, closing_time, parking_available, created_by, updated_by
) VALUES (
    '42000000-0000-0000-0000-000000000001', TRUE,
    '라오미펫', '서울', '반려견 미용', '02-1234-5678', '서울 테스트 주소',
    '10:00', '19:00', FALSE,
    (SELECT id FROM admin_users ORDER BY id LIMIT 1),
    (SELECT id FROM admin_users ORDER BY id LIMIT 1)
);

UPDATE content_revision_state
SET content_revision = 9007199254740993
WHERE singleton_key = 1;

UPDATE publish_generation_state
SET publish_generation = 9007199254740993
WHERE singleton_key = 1;

INSERT INTO publishing_outbox (
    id, kind, source_type, source_id, content_revision, available_at,
    state, publish_generation, attempt_count, claim_owner, claimed_at, lease_until
) VALUES (
    '42000000-0000-0000-0000-000000000002',
    'CONTENT_CHANGED', 'SHOP_SETTINGS', '42000000-0000-0000-0000-000000000001',
    9007199254740993, CURRENT_TIMESTAMP - INTERVAL '1 minute',
    'PROCESSING', 9007199254740993, 1, 'int64-e2e-publisher',
    CURRENT_TIMESTAMP - INTERVAL '30 seconds', CURRENT_TIMESTAMP + INTERVAL '30 minutes'
);
SQL

BUILD_API_INTERNAL_URL=http://backend:8080
BUILD_API_CREDENTIAL=$RHAOMI_BUILD_SERVICE_TOKEN
export BUILD_API_INTERNAL_URL BUILD_API_CREDENTIAL

run_node_validation() {
  expected_value=$1
  docker run --rm \
    --network "$network_name" \
    --label io.homeserver.cleanup.environment=development \
    --label io.homeserver.cleanup.project=rhaomi \
    --label "io.homeserver.cleanup.task=$cleanup_task" \
    --label io.homeserver.cleanup.lifecycle=task \
    --label io.homeserver.cleanup.retain=false \
    --label "io.homeserver.cleanup.git-head=$git_head" \
    -e BUILD_API_INTERNAL_URL \
    -e BUILD_API_CREDENTIAL \
    -v "$repo_dir/src:/workspace/src:ro" \
    -v "$repo_dir/scripts:/workspace/scripts:ro" \
    -v "$repo_dir/package.json:/workspace/package.json:ro" \
    -v "$repo_dir/package-lock.json:/workspace/package-lock.json:ro" \
    -v "$repo_dir/tsconfig.json:/workspace/tsconfig.json:ro" \
    -v "$node_modules_volume:/workspace/node_modules" \
    -w /workspace \
    "$node_image" \
    node scripts/validate-build-int64-staging.mts \
      --content-revision "$expected_value" \
      --publish-generation "$expected_value" \
      --output "/tmp/staging-$expected_value"
}

run_node_validation 9007199254740993

docker exec -i "$postgres_name" \
  psql -v ON_ERROR_STOP=1 -U rhaomi_int64_e2e -d rhaomi_int64_e2e >/dev/null <<'SQL'
UPDATE content_revision_state
SET content_revision = 9223372036854775807
WHERE singleton_key = 1;

UPDATE publish_generation_state
SET publish_generation = 9223372036854775807
WHERE singleton_key = 1;

INSERT INTO publishing_outbox (
    id, kind, source_type, source_id, content_revision, available_at,
    state, publish_generation, attempt_count, claim_owner, claimed_at, lease_until
) VALUES (
    '42000000-0000-0000-0000-000000000003',
    'CONTENT_CHANGED', 'SHOP_SETTINGS', '42000000-0000-0000-0000-000000000001',
    9223372036854775807, CURRENT_TIMESTAMP - INTERVAL '1 minute',
    'PROCESSING', 9223372036854775807, 1, 'int64-e2e-publisher',
    CURRENT_TIMESTAMP - INTERVAL '30 seconds', CURRENT_TIMESTAMP + INTERVAL '30 minutes'
);
SQL

run_node_validation 9223372036854775807

echo "PostgreSQL/HTTP/Node int64 V2 E2E validation passed."
