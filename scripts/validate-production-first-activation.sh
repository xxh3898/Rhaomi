#!/bin/sh

set -eu
umask 077

main() {
  repository_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
  lifecycle_core="$repository_root/ops/production/production-lifecycle-core.sh"
  activation_core="$repository_root/ops/production/first-activate-rhaomi-core.sh"
  fake_docker_source="$repository_root/scripts/fixtures/fake-production-first-activation-docker.sh"
  evidence_dir=${RHAOMI_PRODUCTION_FIRST_ACTIVATION_EVIDENCE_DIR:-}
  release_sha=$(git -C "$repository_root" rev-parse HEAD)
  image_digest="sha256:$(printf 'a%.0s' $(seq 1 64))"
  image_reference="ghcr.io/xxh3898/rhaomi@${image_digest}"
  image_id="sha256:$(printf 'b%.0s' $(seq 1 64))"
  sbom_reference="sha256:$(printf 'c%.0s' $(seq 1 64))"
  validation_parent=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/rhaomi-first-activation.XXXXXX")
  validation_parent=$(cd "$validation_parent" && pwd -P)
  validation_marker="$validation_parent/.rhaomi-first-activation-validation"
  printf '%s\n' "$release_sha" >"$validation_marker"
  validation_succeeded=false
  trap cleanup EXIT HUP INT TERM

  printf '%s' "$release_sha" | grep -Eq '^[0-9a-f]{40}$'
  if [ -z "$evidence_dir" ]; then
    evidence_dir="$validation_parent/evidence"
  elif [ -d "$evidence_dir" ] &&
    [ -n "$(find "$evidence_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    fail FIRST_ACTIVATION_EVIDENCE_NOT_EMPTY
  fi
  mkdir -p "$evidence_dir"
  chmod 700 "$evidence_dir"

  validate_compose_render
  validate_verified_empty_matrix
  validate_bootstrap_failures
  validate_recovery_failures
  validate_complete_lifecycle
  validate_contract_sources

  printf '%s\n' \
    'contract=rhaomi-first-production-activation-v1' \
    "gitHead=$release_sha" \
    'verifiedEmptyMatrix=verified' \
    'partialBootstrapReentry=blocked' \
    'recoveryAcceptanceFailures=blocked' \
    'recoveryComposeRender=verified' \
    'fullReadIsolatedRestore=contract-verified' \
    'flywayVersion=10' \
    'steadyStateTransition=verified-once' \
    'steadyStateBackupDeployInvariant=verified' \
    'publicIngressActivated=0' \
    'productionPathMutation=0' \
    'dockerVolumeDeletion=0' \
    'dockerImageDeletion=0' \
    'status=success' \
    >"$evidence_dir/first-production-activation-contract.txt"
  validation_succeeded=true
  printf '%s\n' 'production first-activation contract validation: PASS'
}

prepare_case() {
  case_name=$1
  case_root="$validation_parent/$case_name/root"
  case_repository="$validation_parent/$case_name/repository"
  case_state="$validation_parent/$case_name/docker-state"
  case_bin="$validation_parent/$case_name/bin"
  case_log="$validation_parent/$case_name/docker.log"
  case_output="$validation_parent/$case_name/output.log"
  mkdir -m 700 "$validation_parent/$case_name"
  mkdir -p \
    "$case_root" \
    "$case_root/app" \
    "$case_root/app/bin" \
    "$case_root/app/docker" \
    "$case_root/app/nginx" \
    "$case_root/data" \
    "$case_root/data/media" \
    "$case_root/public" \
    "$case_root/public/releases" \
    "$case_root/state" \
    "$case_root/state/deploy" \
    "$case_root/state/locks" \
    "$case_root/state/publisher" \
    "$case_root/state/publisher/build-workspace" \
    "$case_root/logs" \
    "$case_repository" \
    "$case_repository/sets" \
    "$case_state" \
    "$case_bin"
  chmod 700 \
    "$case_root" \
    "$case_root/app" \
    "$case_root/app/bin" \
    "$case_root/app/docker" \
    "$case_root/app/nginx" \
    "$case_root/data" \
    "$case_root/data/media" \
    "$case_root/state" \
    "$case_root/state/deploy" \
    "$case_root/state/locks" \
    "$case_root/state/publisher" \
    "$case_root/state/publisher/build-workspace" \
    "$case_root/logs" \
    "$case_repository" \
    "$case_repository/sets" \
    "$case_state" \
    "$case_bin"
  chmod 755 "$case_root/public" "$case_root/public/releases"
  cp "$repository_root/compose.production.yaml" "$case_root/app/compose.production.yaml"
  cp "$repository_root/compose.production.first-activation.yaml" \
    "$case_root/app/compose.production.first-activation.yaml"
  cp "$repository_root/compose.production.first-activation.validation.yaml" \
    "$case_root/app/compose.production.first-activation.validation.yaml"
  cp "$repository_root/infra/nginx/production.conf" "$case_root/app/nginx/production.conf"
  printf '%s\n' '{}' >"$case_root/app/docker/config.json"
  printf '%s\n' \
    'RHAOMI_PRODUCTION_COMPOSE_PROJECT=rhaomi-first-activation-validation' \
    "RHAOMI_BACKUP_REPOSITORY_ROOT=$case_repository" \
    'RHAOMI_WEB_LOOPBACK_PORT=18076' \
    'RHAOMI_POSTGRES_DB=rhaomi_first_activation' \
    'RHAOMI_POSTGRES_USER=rhaomi_first_activation' \
    'RHAOMI_POSTGRES_PASSWORD=synthetic-first-activation-database-marker' \
    'RHAOMI_BUILD_SERVICE_TOKEN=synthetic-first-activation-build-marker' \
    'RHAOMI_WEBAUTHN_RP_ID=validation.invalid' \
    'RHAOMI_WEBAUTHN_ORIGIN=https://validation.invalid' \
    'RHAOMI_WEBAUTHN_RP_NAME=Rhaomi Validation Admin' \
    'RHAOMI_PUBLISHER_OWNER=first-activation-validation' \
    'RHAOMI_PUBLIC_SITE_URL=https://validation.invalid' \
    >"$case_root/app/production.env"
  printf '%s\n' rhaomi-backup-repository-v1 \
    >"$case_repository/.rhaomi-backup-repository"
  chmod 600 \
    "$case_root/app/production.env" \
    "$case_root/app/docker/config.json" \
    "$case_repository/.rhaomi-backup-repository"
  cp "$fake_docker_source" "$case_bin/docker"
  chmod 700 "$case_bin/docker"
  : >"$case_log"
}

run_activation() {
  failure_stage=$1
  mode=$2
  requested_release=${3:-$release_sha}
  PATH="$case_bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    RHAOMI_FIRST_ACTIVATION_TEST_LOG="$case_log" \
    RHAOMI_FIRST_ACTIVATION_TEST_STATE_DIR="$case_state" \
    RHAOMI_FIRST_ACTIVATION_TEST_RELEASE_SHA="$release_sha" \
    RHAOMI_FIRST_ACTIVATION_TEST_IMAGE_REFERENCE="$image_reference" \
    RHAOMI_FIRST_ACTIVATION_TEST_IMAGE_ID="$image_id" \
    RHAOMI_FIRST_ACTIVATION_TEST_FAIL_STAGE="$failure_stage" \
    RHAOMI_FIRST_ACTIVATION_VALIDATION_COMPOSE_FILE="$case_root/app/compose.production.first-activation.validation.yaml" \
    RHAOMI_CLEANUP_TASK=76-first-production-activation-gate \
    RHAOMI_CLEANUP_GIT_HEAD="$release_sha" \
    /bin/sh -eu -c '
      . "$1"
      . "$2"
      first_activate_rhaomi "$3" \
        --mode "$4" \
        --release-sha "$5" \
        --image "$6" \
        --sbom "$7"
    ' sh "$lifecycle_core" "$activation_core" "$case_root" "$mode" \
      "$requested_release" "$image_reference" "$sbom_reference"
}

assert_activation_fails() {
  failure_stage=$1
  mode=$2
  expected=$3
  requested_release=${4:-$release_sha}
  if run_activation "$failure_stage" "$mode" "$requested_release" >"$case_output" 2>&1; then
    printf 'expected failure succeeded: %s/%s\n' "$case_name" "$mode" >&2
    exit 1
  fi
  grep -Fq "$expected" "$case_output"
}

validate_compose_render() {
  command -v docker >/dev/null 2>&1 || fail FIRST_ACTIVATION_HOST_INVALID
  env \
    RHAOMI_FIRST_ACTIVATION_RECOVERY_PROJECT=rhaomi-first-activation-render \
    RHAOMI_PRODUCTION_IMAGE="$image_reference" \
    RHAOMI_FIRST_ACTIVATION_BACKUP_REPOSITORY="$validation_parent/render-repository" \
    RHAOMI_FIRST_ACTIVATION_RECOVERY_ROOT="$validation_parent/render-recovery" \
    RHAOMI_FIRST_ACTIVATION_APP_ROOT="$validation_parent/render-app" \
    RHAOMI_POSTGRES_DB=rhaomi_first_activation_render \
    RHAOMI_POSTGRES_USER=rhaomi_first_activation_render \
    RHAOMI_POSTGRES_PASSWORD=synthetic-first-activation-render-password \
    RHAOMI_WEBAUTHN_RP_ID=validation.invalid \
    RHAOMI_WEBAUTHN_ORIGIN=https://validation.invalid \
    RHAOMI_WEBAUTHN_RP_NAME='Rhaomi First Activation Validation' \
    RHAOMI_BUILD_SERVICE_TOKEN=synthetic-first-activation-render-token \
    RHAOMI_PUBLIC_SITE_URL=https://validation.invalid \
    RHAOMI_CODE_SHA="$release_sha" \
    RHAOMI_CODE_IMAGE_TAG="ghcr.io/xxh3898/rhaomi:${release_sha}" \
    RHAOMI_CODE_IMAGE_DIGEST="$image_digest" \
    RHAOMI_FLYWAY_VERSION=10 \
    RHAOMI_SBOM_REFERENCE="$sbom_reference" \
    RHAOMI_FIRST_ACTIVATION_PROBE_MARKER="first-activation-${release_sha}" \
    RHAOMI_CLEANUP_TASK=76-first-production-activation-gate \
    RHAOMI_CLEANUP_GIT_HEAD="$release_sha" \
    docker compose \
      --project-directory "$repository_root" \
      --file "$repository_root/compose.production.first-activation.yaml" \
      --file "$repository_root/compose.production.first-activation.validation.yaml" \
      --profile first-activation-recovery \
      config --quiet || fail FIRST_ACTIVATION_COMPOSE_INVALID
}

assert_state() {
  expected=$1
  state_file="$case_root/state/deploy/production-lifecycle.env"
  [ -f "$state_file" ] && [ ! -L "$state_file" ]
  [ "$(sed -n '2s/^state=//p' "$state_file")" = "$expected" ]
  [ "$(wc -l <"$state_file" | tr -d '[:space:]')" = 7 ]
}

create_backup_candidate() {
  backup_set_id=20260902T000000Z-767676767676
  set_root="$case_repository/sets/$backup_set_id"
  mkdir -p "$set_root/media"
  printf '%s' PGDMPsynthetic-first-activation >"$set_root/postgres.dump"
  printf '%s\n' '{"contract":"synthetic-first-activation-backup"}' \
    >"$set_root/backup-manifest.json"
  chmod 400 "$set_root/postgres.dump" "$set_root/backup-manifest.json"
  chmod 500 "$set_root" "$set_root/media"
  manifest_sha=$(openssl dgst -sha256 "$set_root/backup-manifest.json" | awk '{print $NF}')
  candidate="$case_root/state/deploy/first-activation-backup.json"
  printf '%s\n' \
    '{' \
    '  "schemaVersion": 1,' \
    '  "status": "RECOVERY_BACKUP_VERIFIED",' \
    "  \"releaseSha\": \"${release_sha}\"," \
    "  \"imageDigest\": \"${image_digest}\"," \
    "  \"backupSetId\": \"${backup_set_id}\"," \
    "  \"backupManifestSha256\": \"${manifest_sha}\"," \
    '  "verifiedAt": "2026-09-02T00:00:00Z"' \
    '}' >"$candidate"
  chmod 600 "$candidate"
  candidate_sha=$(openssl dgst -sha256 "$candidate" | awk '{print $NF}')
  printf '%s\n' \
    'schemaVersion=1' \
    'status=RECOVERY_BACKUP_VERIFIED' \
    "releaseSha=$release_sha" \
    "imageDigest=$image_digest" \
    "backupSetId=$backup_set_id" \
    "backupManifestSha256=$manifest_sha" \
    "evidenceSha256=$candidate_sha" \
    >"$case_root/state/deploy/first-activation-backup.env"
  chmod 600 "$case_root/state/deploy/first-activation-backup.env"
}

bootstrap_case() {
  run_activation '' bootstrap >"$case_output"
  grep -Fq '"state": "RECOVERY_ACCEPTANCE_REQUIRED"' "$case_output"
  assert_state RECOVERY_ACCEPTANCE_REQUIRED
}

validate_verified_empty_matrix() {
  prepare_case truly-empty
  bootstrap_case
  [ ! -e "$case_root/public/current" ]
  [ ! -f "$case_state/primary-rhaomi-web" ]
  [ ! -d "$case_root/state/locks/rhaomi-deploy.lock" ]
  initial_pull_count=$(grep -c "^pull $image_reference$" "$case_log")
  assert_activation_fails '' bootstrap FIRST_ACTIVATION_STATE_INVALID
  [ "$(grep -c "^pull $image_reference$" "$case_log")" = "$initial_pull_count" ]

  for fixture in \
    current previous deploy-marker backup-eligibility complete-backup media public-release \
    backend-container publisher-container web-container postgres-container postgres-volume; do
    prepare_case "not-empty-$fixture"
    case "$fixture" in
      current) ln -s releases/missing "$case_root/public/current" ;;
      previous) ln -s releases/missing "$case_root/public/previous" ;;
      deploy-marker) printf '%s\n' stale >"$case_root/state/deploy/stale-marker" ;;
      backup-eligibility) printf '%s\n' stale >"$case_root/state/deploy/backup-eligible.env" ;;
      complete-backup) mkdir -m 500 "$case_repository/sets/20260901T000000Z-111111111111" ;;
      media) printf '%s\n' existing >"$case_root/data/media/existing.bin" ;;
      public-release) mkdir -m 755 "$case_root/public/releases/existing" ;;
      backend-container) printf '%s\n' running >"$case_state/primary-backend" ;;
      publisher-container) printf '%s\n' running >"$case_state/primary-publisher" ;;
      web-container) printf '%s\n' running >"$case_state/primary-rhaomi-web" ;;
      postgres-container) printf '%s\n' running >"$case_state/primary-postgres" ;;
      postgres-volume) : >"$case_state/postgres-volume" ;;
    esac
    assert_activation_fails '' bootstrap FIRST_ACTIVATION_STATE_INVALID
    [ ! -f "$case_root/state/deploy/production-lifecycle.env" ]
  done

  prepare_case insecure-state
  chmod 755 "$case_root/state/deploy"
  assert_activation_fails '' bootstrap FIRST_ACTIVATION_HOST_INVALID

  prepare_case symlink-state
  rmdir "$case_root/state/deploy"
  mkdir -m 700 "$validation_parent/symlink-state-target"
  ln -s "$validation_parent/symlink-state-target" "$case_root/state/deploy"
  assert_activation_fails '' bootstrap FIRST_ACTIVATION_HOST_INVALID

  prepare_case unknown-state
  assert_activation_fails state-unknown bootstrap FIRST_ACTIVATION_STATE_UNKNOWN

  prepare_case unknown-volume-state
  assert_activation_fails volume-unknown bootstrap FIRST_ACTIVATION_STATE_UNKNOWN

  prepare_case unknown-media-tree
  mkdir "$case_root/data/media/unreadable"
  chmod 000 "$case_root/data/media/unreadable"
  assert_activation_fails '' bootstrap FIRST_ACTIVATION_STATE_UNKNOWN
}

validate_bootstrap_failures() {
  for stage in \
    image-revision migration schema-validate backend-start backend-health \
    publisher-start publisher-running; do
    prepare_case "bootstrap-failure-$stage"
    assert_activation_fails "$stage" bootstrap FIRST_ACTIVATION_
    assert_state FIRST_ACTIVATION_BOOTSTRAPPING
    ! grep -Fq 'state=STEADY_STATE' "$case_root/state/deploy/production-lifecycle.env"
    assert_activation_fails '' bootstrap FIRST_ACTIVATION_STATE_INVALID
  done

  prepare_case bootstrap-evidence-tamper
  bootstrap_case
  printf '%s\n' tampered >>"$case_root/state/deploy/first-activation-bootstrap.json"
  create_backup_candidate
  assert_activation_fails '' accept-recovery FIRST_ACTIVATION_STATE_INVALID

  prepare_case recovery-release-mismatch
  bootstrap_case
  wrong_release_sha=$(printf 'd%.0s' $(seq 1 40))
  assert_activation_fails \
    '' accept-recovery FIRST_ACTIVATION_STATE_INVALID "$wrong_release_sha"
}

validate_recovery_failures() {
  for stage in \
    backup-full-read database-restore media-restore recovery-flyway recovery-schema \
    recovery-backend-health recovery-publisher-running static-smoke; do
    prepare_case "recovery-failure-$stage"
    bootstrap_case
    create_backup_candidate
    assert_activation_fails "$stage" accept-recovery FIRST_ACTIVATION_
    assert_state RECOVERY_ACCEPTANCE_IN_PROGRESS
    ! grep -Fq 'state=STEADY_STATE' "$case_root/state/deploy/production-lifecycle.env"
    assert_activation_fails '' accept-recovery FIRST_ACTIVATION_STATE_INVALID
  done

  prepare_case recovery-failure-quiescence
  bootstrap_case
  create_backup_candidate
  assert_activation_fails \
    recovery-down accept-recovery FIRST_ACTIVATION_RECOVERY_QUIESCENCE_UNCONFIRMED
  assert_state RECOVERY_ACCEPTANCE_IN_PROGRESS
  [ -d "$case_root/state/locks/rhaomi-deploy.lock" ]
}

validate_complete_lifecycle() {
  prepare_case complete-lifecycle
  bootstrap_case
  create_backup_candidate
  run_activation '' accept-recovery >"$case_output"
  grep -Fq '"state": "STEADY_STATE"' "$case_output"
  assert_state STEADY_STATE
  grep -Fq '"flywayVersion": "10"' \
    "$case_root/state/deploy/first-activation-recovery.json"
  [ ! -d "$case_root/state/locks/rhaomi-deploy.lock" ]
  [ -f "$case_root/state/recovery-acceptance/public/current/index.html" ]
  assert_activation_fails '' accept-recovery FIRST_ACTIVATION_STATE_INVALID
  assert_activation_fails '' bootstrap FIRST_ACTIVATION_STATE_INVALID
}

validate_contract_sources() {
  grep -Fq 'deployment_mode:' "$repository_root/.github/workflows/production-release.yml"
  grep -Fq "inputs.deployment_mode == 'first-activation'" \
    "$repository_root/.github/workflows/production-release.yml"
  grep -Fq -- '--mode first-activation' "$repository_root/.github/workflows/production-release.yml"
  grep -Fq -- '--mode predeploy' "$repository_root/.github/workflows/production-release.yml"
  grep -Fq 'validate_deploy_lifecycle' "$repository_root/ops/production/deploy-rhaomi-core.sh"
  grep -Fq 'validate_backup_lifecycle' "$repository_root/ops/production/backup-rhaomi-core.sh"
  [ "$(find "$repository_root/backend/src/main/resources/db/migration" -maxdepth 1 -type f -name 'V10__*.sql' | wc -l | tr -d ' ')" = 1 ]
  [ -z "$(find "$repository_root/backend/src/main/resources/db/migration" -maxdepth 1 -type f -name 'V1[1-9]__*.sql' -print -quit)" ]
}

cleanup() {
  cleanup_result=$?
  trap - EXIT HUP INT TERM
  if [ "$cleanup_result" -ne 0 ] && [ "$validation_succeeded" != true ] &&
    [ -n "$evidence_dir" ] && [ -d "$evidence_dir" ] && [ ! -L "$evidence_dir" ]; then
    printf 'FIRST_ACTIVATION_VALIDATION_FAILED:%s\n' "${case_name:-initialization}" >&2
    printf '%s\n' \
      'contract=rhaomi-first-production-activation-v1' \
      "gitHead=$release_sha" \
      'productionPathMutation=0' \
      'dockerVolumeDeletion=0' \
      'dockerImageDeletion=0' \
      'status=failure' \
      >"$evidence_dir/first-production-activation-failure.txt"
  fi
  [ -f "$validation_marker" ] && [ ! -L "$validation_marker" ] || exit "$cleanup_result"
  case "$validation_parent" in
    "${RUNNER_TEMP:-${TMPDIR:-/tmp}}"/rhaomi-first-activation.*) ;;
    *) exit "$cleanup_result" ;;
  esac
  find "$validation_parent" -type d -exec chmod u+rwx {} + 2>/dev/null || true
  find "$validation_parent" -type f -exec chmod u+rw {} + 2>/dev/null || true
  find "$validation_parent" -depth -delete || cleanup_result=1
  exit "$cleanup_result"
}

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

main "$@"
