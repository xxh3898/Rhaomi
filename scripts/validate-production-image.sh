#!/bin/sh

set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
git_head=$(git -C "$repo_dir" rev-parse HEAD)
git_short=$(printf '%s' "$git_head" | cut -c1-12)
image_tag=${RHAOMI_PRODUCTION_IMAGE_TAG:-"rhaomi-production-validation:${git_head}"}
evidence_dir=${RHAOMI_PRODUCTION_EVIDENCE_DIR:-}
evidence_is_temporary=false

if ! printf '%s' "$git_head" | grep -Eq '^[0-9a-f]{40}$'; then
  echo "exact Git HEAD를 확인할 수 없습니다." >&2
  exit 1
fi

for command in docker git; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "production image 검증에 ${command}가 필요합니다." >&2
    exit 1
  fi
done

if [ -z "$evidence_dir" ]; then
  evidence_dir=$(mktemp -d)
  evidence_is_temporary=true
else
  if [ -d "$evidence_dir" ] &&
    [ -n "$(find "$evidence_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    echo "stale evidence 혼합을 막기 위해 비어 있는 새 evidence directory가 필요합니다." >&2
    exit 1
  fi
  mkdir -p "$evidence_dir"
fi

runtime_suffix="${git_short}-$$"
network_name="rhaomi-image-${runtime_suffix}"
postgres_name="rhaomi-image-postgres-${runtime_suffix}"
backend_name="rhaomi-image-backend-${runtime_suffix}"
archive_dir=$(mktemp -d)

cleanup() {
  docker stop "$backend_name" >/dev/null 2>&1 || true
  docker stop "$postgres_name" >/dev/null 2>&1 || true
  docker network rm "$network_name" >/dev/null 2>&1 || true
  rm -r "$archive_dir"
  if [ "$evidence_is_temporary" = true ]; then
    echo "검증 evidence는 task 임시 directory에 생성됐습니다. 보존하려면 RHAOMI_PRODUCTION_EVIDENCE_DIR를 지정하세요." >&2
  fi
}
trap cleanup EXIT HUP INT TERM

docker build \
  --file "$repo_dir/backend/Dockerfile.production" \
  --tag "$image_tag" \
  --build-arg "RHAOMI_GIT_HEAD=${git_head}" \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label io.homeserver.cleanup.task=49-production-decoder-image \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=${git_head}" \
  "$repo_dir"

image_id=$(docker image inspect "$image_tag" --format '{{.Id}}')
image_architecture=$(docker image inspect "$image_tag" --format '{{.Architecture}}')
image_revision=$(docker image inspect "$image_tag" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')
test "$image_revision" = "$git_head"
case "$image_architecture" in
  amd64 | arm64) ;;
  *)
    echo "지원하지 않는 production image architecture입니다: ${image_architecture}" >&2
    exit 1
    ;;
esac

docker run --rm --network none \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label io.homeserver.cleanup.task=49-production-decoder-image \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=${git_head}" \
  "$image_tag" sh -ec '
    java -version 2>&1 | grep '\''version "25\.'\''
    node --version | grep '\''^v24\.20\.0$'\''
    apk info -e libde265
    test "$(apk info -v libcrypto3)" = "libcrypto3-3.5.8-r0"
    test "$(apk info -v libssl3)" = "libssl3-3.5.8-r0"
    test "$(apk info -v openssl)" = "openssl-3.5.8-r0"
    if apk info -e x265-libs || apk info -e x265; then
      echo "x265 package가 final image에 있습니다." >&2
      exit 1
    fi
    for package in build-base cmake git gcc g++ make ninja pkgconf; do
      if apk info -e "$package" >/dev/null 2>&1; then
        echo "build package가 final image에 있습니다: $package" >&2
        exit 1
      fi
    done
    for executable in cc gcc g++ cmake git ninja make heif-convert heif-enc npm npx corepack yarn pnpm; do
      if command -v "$executable" >/dev/null 2>&1; then
        echo "금지된 final image executable이 있습니다: $executable" >&2
        exit 1
      fi
    done
    test -f /opt/rhaomi/backend.jar
    test -f /opt/rhaomi/source/scripts/publish-static-release.mts
    test -f /opt/rhaomi/source/package.json
    test -f /opt/rhaomi/source/package-lock.json
    test -x /opt/rhaomi/source/node_modules/next/dist/bin/next
    test -f /usr/local/lib/libheif.so.1
    test -f /usr/lib/libde265.so.0
    test ! -e /usr/local/include/libheif
    test ! -e /usr/local/lib/cmake/libheif
    test ! -e /usr/local/lib/pkgconfig/libheif.pc
    test ! -e /usr/src/libheif
    test ! -e /tmp/libheif-build
    test ! -e /root/.gradle
    test ! -e /root/.npm
    test ! -e /workspace
    test ! -e /opt/rhaomi/source/src/test
    test "$(find /opt/rhaomi/source/src -type f -name '\''*.test.*'\'' -print | wc -l | tr -d '\'' '\'')" -eq 0
    test "$(find /opt/rhaomi/source -type f -name '\''.env*'\'' -print | wc -l | tr -d '\'' '\'')" -eq 0
    test "$(find /opt /usr/local -type f \( -name '\''CMakeCache.txt'\'' -o -name '\''*.o'\'' -o -name '\''*.a'\'' \) -print | wc -l | tr -d '\'' '\'')" -eq 0
    test "$(find /usr/local/lib -type f \( -name '\''*plugin*.so*'\'' -o -path '\''*/lib/libheif/*.so*'\'' \) -print | wc -l | tr -d '\'' '\'')" -eq 0
    linkage=$(ldd /usr/local/lib/libheif.so.1)
    printf '\''%s\n'\'' "$linkage" | grep '\''libde265\.so\.0'\''
    if printf '\''%s\n'\'' "$linkage" | grep -Eiq '\''libx265|libx264|libaom|libdav1d|libkvazaar|libvvdec|libvvenc|libjxl|libjpeg|libavcodec'\''; then
      echo "승인되지 않은 codec linkage가 final image에 있습니다." >&2
      exit 1
    fi
    printf '\''finalImageSurface=verified\n'\''
  ' >"$evidence_dir/image-surface.txt"

docker run --rm --network none \
  --tmpfs /tmp:rw,exec,nosuid \
  --env HOME=/tmp/rhaomi-home \
  --env NEXT_TELEMETRY_DISABLED=1 \
  --env PUBLIC_SITE_URL=https://production-image.invalid \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label io.homeserver.cleanup.task=49-production-decoder-image \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=${git_head}" \
  "$image_tag" sh -ec '
    mkdir -p "$HOME"
    cd /opt/rhaomi/source
    node node_modules/next/dist/bin/next build
    test -f out/index.html
    printf '\''publisherStaticExport=verified\n'\''
  ' >"$evidence_dir/publisher-static-export.txt"

docker run --rm --network none \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label io.homeserver.cleanup.task=49-production-decoder-image \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=${git_head}" \
  "$image_tag" cat /opt/rhaomi/evidence/libheif-build-contract.txt \
  >"$evidence_dir/libheif-build-contract.txt"

for evidence_name in \
  libheif-LICENSE.txt \
  production-image-components.json \
  production-image-NOTICE.md; do
  docker run --rm --network none \
    --label io.homeserver.cleanup.environment=development \
    --label io.homeserver.cleanup.project=rhaomi \
    --label io.homeserver.cleanup.task=49-production-decoder-image \
    --label io.homeserver.cleanup.lifecycle=task \
    --label io.homeserver.cleanup.retain=false \
    --label "io.homeserver.cleanup.git-head=${git_head}" \
    "$image_tag" cat "/opt/rhaomi/evidence/${evidence_name}" \
    >"$evidence_dir/${evidence_name}"
done

docker run --rm --network none \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label io.homeserver.cleanup.task=49-production-decoder-image \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=${git_head}" \
  "$image_tag" sh -ec 'apk info -vv; ldd /usr/local/lib/libheif.so.1' \
  >"$evidence_dir/runtime-inventory.txt"

docker network create \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label io.homeserver.cleanup.task=49-production-decoder-image \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=${git_head}" \
  "$network_name" >/dev/null

docker run --detach --rm \
  --name "$postgres_name" \
  --network "$network_name" \
  --network-alias postgres \
  --tmpfs /var/lib/postgresql/data:rw,noexec,nosuid \
  --env PGDATA=/var/lib/postgresql/data/pgdata \
  --env POSTGRES_DB=rhaomi_image_test \
  --env POSTGRES_USER=rhaomi_image_test \
  --env POSTGRES_PASSWORD=rhaomi-image-test-database-password \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label io.homeserver.cleanup.task=49-production-decoder-image \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=${git_head}" \
  postgres:18.6-alpine3.23@sha256:697c180dbf244d3ce4a8f4cbc0156cde840af055c1bf8b76aebe422a4822086f \
  >/dev/null

postgres_ready=false
attempt=0
while [ "$attempt" -lt 60 ]; do
  if docker exec "$postgres_name" pg_isready \
    -U rhaomi_image_test -d rhaomi_image_test >/dev/null 2>&1; then
    postgres_ready=true
    break
  fi
  attempt=$((attempt + 1))
  sleep 1
done
if [ "$postgres_ready" != true ]; then
  echo "production image smoke PostgreSQL이 준비되지 않았습니다." >&2
  exit 1
fi

docker run --detach --rm \
  --name "$backend_name" \
  --network "$network_name" \
  --network-alias backend \
  --tmpfs /var/lib/rhaomi/media:rw,noexec,nosuid \
  --env SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/rhaomi_image_test \
  --env SPRING_DATASOURCE_USERNAME=rhaomi_image_test \
  --env SPRING_DATASOURCE_PASSWORD=rhaomi-image-test-database-password \
  --env RHAOMI_MEDIA_ROOT=/var/lib/rhaomi/media \
  --env RHAOMI_BOOTSTRAP_ADMIN_ENABLED=true \
  --env RHAOMI_BOOTSTRAP_ADMIN_EMAIL=admin.image-smoke@example.com \
  --env RHAOMI_BOOTSTRAP_ADMIN_PASSWORD=rhaomi-image-smoke-password-49 \
  --env RHAOMI_SESSION_COOKIE_SECURE=false \
  --env RHAOMI_BUILD_SERVICE_TOKEN=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label io.homeserver.cleanup.task=49-production-decoder-image \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=${git_head}" \
  "$image_tag" java -jar /opt/rhaomi/backend.jar \
  >/dev/null

media_smoke_output=$(docker run --rm \
  --network "$network_name" \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid \
  --volume "$repo_dir/scripts/validate-production-image-media.mjs:/validation/validate-production-image-media.mjs:ro" \
  --volume "$repo_dir/backend/src/test/resources/media:/fixtures:ro" \
  --env RHAOMI_BOOTSTRAP_ADMIN_EMAIL=admin.image-smoke@example.com \
  --env RHAOMI_BOOTSTRAP_ADMIN_PASSWORD=rhaomi-image-smoke-password-49 \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label io.homeserver.cleanup.task=49-production-decoder-image \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=${git_head}" \
  "$image_tag" node /validation/validate-production-image-media.mjs \
    http://backend:8080 /fixtures)
printf '%s\n' "$media_smoke_output" | tee "$evidence_dir/media-smoke.txt"

docker stop "$backend_name" >/dev/null
docker stop "$postgres_name" >/dev/null
docker network rm "$network_name" >/dev/null

docker save "$image_tag" --output "$archive_dir/production-image.tar"

syft_image='anchore/syft:v1.36.0@sha256:6733fa6ba7fb102d5b8eecae0e9ee7ee7091e613b8ce8d1fc9e6641335ab3962'
grype_image='anchore/grype:v0.104.1@sha256:e7d3cb36d2ebfb522141d83a5d0df9cda301f7e9f8747ee4af41f12c478fa77c'

docker run --rm --network none \
  --volume "$archive_dir/production-image.tar:/input/production-image.tar:ro" \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label io.homeserver.cleanup.task=49-production-decoder-image \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=${git_head}" \
  --env SYFT_CHECK_FOR_APP_UPDATE=false \
  "$syft_image" docker-archive:/input/production-image.tar \
    --output cyclonedx-json \
  >"$archive_dir/syft-raw.cdx.json"

docker run --rm --network none \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid \
  --volume "$repo_dir/scripts/finalize-production-sbom.mjs:/validation/finalize-production-sbom.mjs:ro" \
  --volume "$repo_dir/backend/production-image-components.json:/validation/components.json:ro" \
  --volume "$archive_dir:/input:ro" \
  --volume "$evidence_dir:/evidence" \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label io.homeserver.cleanup.task=49-production-decoder-image \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=${git_head}" \
  "$image_tag" node /validation/finalize-production-sbom.mjs \
    /input/syft-raw.cdx.json /validation/components.json \
    /evidence/production-sbom.cdx.json "$image_id" "$git_head"

docker run --rm \
  --volume "$evidence_dir/production-sbom.cdx.json:/evidence/production-sbom.cdx.json:ro" \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label io.homeserver.cleanup.task=49-production-decoder-image \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=${git_head}" \
  --env GRYPE_CHECK_FOR_APP_UPDATE=false \
  "$grype_image" sbom:/evidence/production-sbom.cdx.json --output json \
  >"$evidence_dir/grype-scan.json"

docker run --rm --network none \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid \
  --volume "$repo_dir/scripts/validate-production-supply-chain.mjs:/validation/validate-production-supply-chain.mjs:ro" \
  --volume "$repo_dir/backend/production-image-components.json:/validation/components.json:ro" \
  --volume "$evidence_dir:/evidence" \
  --label io.homeserver.cleanup.environment=development \
  --label io.homeserver.cleanup.project=rhaomi \
  --label io.homeserver.cleanup.task=49-production-decoder-image \
  --label io.homeserver.cleanup.lifecycle=task \
  --label io.homeserver.cleanup.retain=false \
  --label "io.homeserver.cleanup.git-head=${git_head}" \
  "$image_tag" node /validation/validate-production-supply-chain.mjs \
    /evidence/production-sbom.cdx.json /evidence/grype-scan.json \
    /validation/components.json /evidence/supply-chain-summary.json \
    "$image_id" "$git_head"

{
  printf 'gitHead=%s\n' "$git_head"
  printf 'imageId=%s\n' "$image_id"
  printf 'architecture=%s\n' "$image_architecture"
  printf 'productionDockerfile=backend/Dockerfile.production\n'
  printf 'mediaSmoke=passed\n'
  printf 'sbom=production-sbom.cdx.json\n'
  printf 'scanner=grype-scan.json\n'
} >"$evidence_dir/validation-summary.txt"

echo "Production image validation passed: architecture=${image_architecture} head=${git_head}"
