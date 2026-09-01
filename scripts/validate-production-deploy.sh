#!/bin/sh

set -eu

main() {
  repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
  core_script="$repo_dir/ops/production/deploy-rhaomi-core.sh"
  fake_docker_source="$repo_dir/scripts/fixtures/fake-production-docker.sh"
  fake_homeops_source="$repo_dir/scripts/fixtures/fake-homeops-event-adapter.sh"
  git_head=$(git -C "$repo_dir" rev-parse HEAD)
  evidence_dir=${RHAOMI_PRODUCTION_DEPLOY_EVIDENCE_DIR:-}
  validation_parent=${RUNNER_TEMP:-${TMPDIR:-/tmp}}
  validation_root=$(mktemp -d "${validation_parent%/}/rhaomi-production-deploy.XXXXXX")
  validation_root=$(cd "$validation_root" && pwd -P)
  validation_marker="$validation_root/.rhaomi-production-deploy-validation"
  printf '%s\n' "$git_head" >"$validation_marker"
  trap cleanup EXIT HUP INT TERM

  printf '%s' "$git_head" | grep -Eq '^[0-9a-f]{40}$'
  release_sha=$git_head
  image_digest="sha256:$(printf 'a%.0s' $(seq 1 64))"
  image_reference="ghcr.io/xxh3898/rhaomi@${image_digest}"
  image_id="sha256:$(printf 'c%.0s' $(seq 1 64))"
  sbom_reference="sha256:$(printf 'd%.0s' $(seq 1 64))"
  synthetic_db_marker="synthetic-db-credential-${release_sha}"
  synthetic_build_marker="synthetic-build-credential-${release_sha}"

  if [ -z "$evidence_dir" ]; then
    evidence_dir="$validation_root/evidence"
  elif [ -d "$evidence_dir" ] &&
    [ -n "$(find "$evidence_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    echo "stale evidence 혼합을 막기 위해 비어 있는 evidence directory가 필요합니다." >&2
    exit 1
  fi
  mkdir -p "$evidence_dir"

  validate_invalid_inputs
  validate_host_and_backup_gates
  validate_success_path
  validate_telemetry_failure
  validate_lock_contention
  validate_writer_stop_failure
  validate_failure_hold migration
  validate_failure_hold schema-validate
  validate_backend_health_failure
  validate_publisher_start_failure
  validate_runtime_image_mismatch backend
  validate_runtime_image_mismatch publisher
  validate_revision_mismatch
  validate_secret_redaction

  printf '%s\n' \
    "contract=production-deploy-v1" \
    "gitHead=${git_head}" \
    "exactDigestValidation=verified" \
    "hostPermissionGate=verified" \
    "backupEligibilityGate=verified" \
    "backupEnvelopeBeforePull=verified" \
    "readOnlyTargetVerifierBeforeWriterMutation=verified" \
    "staleEligibilityReplay=blocked" \
    "backupRepositoryMutation=0" \
    "writerQuiescenceBeforeMigration=verified" \
    "writerStopFailureBeforeMigration=verified" \
    "migrationTask=verified" \
    "schemaValidationTask=verified" \
    "publicStaticDuringMaintenance=covered-by-production-compose-runtime" \
    "migrationFailureMaintenanceHold=verified" \
    "schemaFailureMaintenanceHold=verified" \
    "backendHealthFailureBlocksPublisher=verified" \
    "publisherStartFailureMaintenanceHold=verified" \
    "runtimeBackendImageMismatchMaintenanceHold=verified" \
    "runtimePublisherImageMismatchMaintenanceHold=verified" \
    "failureQuiescence=verified" \
    "homeOpsDeploymentLifecycle=verified" \
    "homeOpsTelemetryFailureDoesNotRewriteDeployOutcome=verified" \
    "lockContention=verified" \
    "secretRedaction=verified" \
    "productionPathMutation=0" \
    "ghcrPush=0" \
    "tailscaleConnection=0" \
    "dockerVolumeDeletion=0" \
    "dockerImageDeletion=0" \
    >"$evidence_dir/production-deploy-contract.txt"

  echo "production deploy contract validation: PASS"
}

prepare_case() {
  case_name=$1
  case_root="$validation_root/$case_name/root"
  case_repository="$validation_root/$case_name/repository"
  case_state="$validation_root/$case_name/state"
  case_bin="$validation_root/$case_name/bin"
  case_log="$validation_root/$case_name/docker.log"
  case_output="$validation_root/$case_name/output.log"
  case_homeops_log="$validation_root/$case_name/homeops.log"
  mkdir -p \
    "$case_root/app" \
    "$case_root/app/bin" \
    "$case_root/app/docker" \
    "$case_root/state/deploy" \
    "$case_root/state/locks" \
    "$case_repository/sets" \
    "$case_state" \
    "$case_bin"
  chmod 700 "$case_repository" "$case_repository/sets"
  cp "$repo_dir/compose.production.yaml" "$case_root/app/compose.production.yaml"
  chmod 644 "$case_root/app/compose.production.yaml"
  printf '%s\n' \
    'RHAOMI_PRODUCTION_COMPOSE_PROJECT=rhaomi-validation' \
    'RHAOMI_WEB_LOOPBACK_PORT=18053' \
    'RHAOMI_POSTGRES_DB=rhaomi_validation' \
    'RHAOMI_POSTGRES_USER=rhaomi_validation' \
    "RHAOMI_POSTGRES_PASSWORD=${synthetic_db_marker}" \
    "RHAOMI_BUILD_SERVICE_TOKEN=${synthetic_build_marker}" \
    'RHAOMI_PUBLISHER_OWNER=production-deploy-validation' \
    'RHAOMI_PUBLIC_SITE_URL=https://validation.invalid' \
    "RHAOMI_BACKUP_REPOSITORY_ROOT=${case_repository}" \
    >"$case_root/app/production.env"
  chmod 600 "$case_root/app/production.env"
  printf '%s\n' '{}' >"$case_root/app/docker/config.json"
  chmod 700 "$case_root/app/docker"
  chmod 600 "$case_root/app/docker/config.json"
  printf '%s\n' rhaomi-backup-repository-v1 \
    >"$case_repository/.rhaomi-backup-repository"
  chmod 600 "$case_repository/.rhaomi-backup-repository"
  printf '%s\n' \
    '{' \
    '  "schemaVersion": 1,' \
    "  \"targetReleaseSha\": \"${release_sha}\"," \
    '  "backupSetId": "20260901T000000Z-111111111111",' \
    "  \"backupManifestSha256\": \"$(printf 'f%.0s' $(seq 1 64))\"," \
    "  \"sourceReleaseSha\": \"${release_sha}\"," \
    "  \"sourceImageDigest\": \"${image_digest}\"," \
    '  "sourceFlywayVersion": "9",' \
    '  "createdAt": "2026-09-01T00:00:00Z",' \
    '  "status": "eligible"' \
    '}' >"$case_root/state/deploy/backup-eligibility.json"
  chmod 600 "$case_root/state/deploy/backup-eligibility.json"
  evidence_hash=$(openssl dgst -sha256 \
    "$case_root/state/deploy/backup-eligibility.json" | awk '{print $NF}')
  printf '%s\n' \
    'schemaVersion=1' \
    'status=eligible' \
    "releaseSha=${release_sha}" \
    "evidenceSha256=${evidence_hash}" \
    >"$case_root/state/deploy/backup-eligible.env"
  chmod 600 "$case_root/state/deploy/backup-eligible.env"
  printf '%s\n' running >"$case_state/backend"
  printf '%s\n' running >"$case_state/publisher"
  printf '%s\n' running >"$case_state/rhaomi-web"
  : >"$case_log"
  cp "$fake_docker_source" "$case_bin/docker"
  chmod 700 "$case_bin/docker"
  cp "$fake_homeops_source" "$case_root/app/bin/report-rhaomi-event.py"
  chmod 700 "$case_root/app/bin/report-rhaomi-event.py"
  : >"$case_homeops_log"
}

run_case() {
  failure_stage=$1
  shift
  PATH="$case_bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    RHAOMI_DEPLOY_TEST_LOG="$case_log" \
    RHAOMI_DEPLOY_TEST_STATE_DIR="$case_state" \
    RHAOMI_DEPLOY_TEST_RELEASE_SHA="$release_sha" \
    RHAOMI_DEPLOY_TEST_IMAGE_REFERENCE="$image_reference" \
    RHAOMI_DEPLOY_TEST_IMAGE_ID="$image_id" \
    RHAOMI_DEPLOY_TEST_DEPLOY_STATE_DIR="$case_root/state/deploy" \
    RHAOMI_DEPLOY_TEST_FAIL_STAGE="$failure_stage" \
    RHAOMI_HOMEOPS_TEST_LOG="$case_homeops_log" \
    RHAOMI_HOMEOPS_TEST_OUTCOME="${RHAOMI_HOMEOPS_TEST_OUTCOME:-RETAINED}" \
    /bin/sh -eu -c '
      . "$1"
      shift
      task_root=$1
      shift
      deploy_rhaomi "$task_root" "$@"
    ' sh "$core_script" "$case_root" "$@"
}

validate_invalid_inputs() {
  prepare_case malformed-release-sha
  if run_case '' \
    --release-sha "$(printf 'A%.0s' $(seq 1 40))" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "malformed release SHA input이 성공했습니다." >&2
    exit 1
  fi
  [ ! -s "$case_log" ]

  prepare_case wrong-registry
  if run_case '' \
    --release-sha "$release_sha" \
    --image "registry.invalid/xxh3898/rhaomi@${image_digest}" \
    --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "wrong-registry input이 성공했습니다." >&2
    exit 1
  fi
  [ ! -s "$case_log" ]

  prepare_case malformed-digest
  if run_case '' \
    --release-sha "$release_sha" \
    --image 'ghcr.io/xxh3898/rhaomi@sha256:1234' \
    --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "malformed digest input이 성공했습니다." >&2
    exit 1
  fi
  [ ! -s "$case_log" ]

  prepare_case empty-sbom
  if run_case '' \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom '' >"$case_output" 2>&1; then
    echo "empty SBOM input이 성공했습니다." >&2
    exit 1
  fi
  [ ! -s "$case_log" ]

  prepare_case unknown-option
  if run_case '' \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" \
    --command 'arbitrary' >"$case_output" 2>&1; then
    echo "unknown option input이 성공했습니다." >&2
    exit 1
  fi
  [ ! -s "$case_log" ]

  prepare_case duplicate-option
  if run_case '' \
    --release-sha "$release_sha" \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "duplicate option input이 성공했습니다." >&2
    exit 1
  fi
  [ ! -s "$case_log" ]
}

validate_host_and_backup_gates() {
  prepare_case insecure-host-directory
  chmod 777 "$case_root/state/locks"
  if run_case '' \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "insecure host directory가 성공했습니다." >&2
    exit 1
  fi
  grep -Fq DEPLOY_HOST_INVALID "$case_output"
  [ ! -s "$case_log" ]

  prepare_case ineligible-backup
  chmod 644 "$case_root/state/deploy/backup-eligible.env"
  if run_case '' \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "ineligible backup gate가 성공했습니다." >&2
    exit 1
  fi
  grep -Fq DEPLOY_BACKUP_REQUIRED "$case_output"
  ! grep -Fq ' stop --timeout 30 backend publisher' "$case_log"
  [ ! -d "$case_root/state/locks/rhaomi-deploy.lock" ]

  prepare_case missing-backup-evidence
  rm "$case_root/state/deploy/backup-eligibility.json"
  repository_before=$(directory_digest "$case_repository")
  deploy_state_before=$(directory_digest "$case_root/state/deploy")
  if run_case '' \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "missing backup evidence가 성공했습니다." >&2
    exit 1
  fi
  grep -Fq DEPLOY_BACKUP_REQUIRED "$case_output"
  ! grep -Fq ' stop --timeout 30 backend publisher' "$case_log"
  [ "$(directory_digest "$case_repository")" = "$repository_before" ]
  [ "$(directory_digest "$case_root/state/deploy")" = "$deploy_state_before" ]
  [ ! -d "$case_root/state/locks/rhaomi-deploy.lock" ]

  validate_target_verifier_failure target-verifier-failure
  validate_target_verifier_failure malformed-backup-evidence

  prepare_case stale-same-target-eligibility
  repository_before=$(directory_digest "$case_repository")
  deploy_state_before=$(directory_digest "$case_root/state/deploy")
  update_evidence_created_at '2000-01-01T00:00:00Z'
  deploy_state_stale=$(directory_digest "$case_root/state/deploy")
  if run_case '' \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "same target SHA stale eligibility가 성공했습니다." >&2
    exit 1
  fi
  grep -Fq DEPLOY_BACKUP_REQUIRED "$case_output"
  grep -Fq "pull ${image_reference}" "$case_log"
  grep -Fq ' backup-verifier verify-eligibility ' "$case_log"
  ! grep -Fq ' stop --timeout 30 backend publisher' "$case_log"
  [ "$(directory_digest "$case_repository")" = "$repository_before" ]
  [ "$(directory_digest "$case_root/state/deploy")" = "$deploy_state_stale" ]
  [ "$deploy_state_before" != "$deploy_state_stale" ]
  [ ! -d "$case_root/state/locks/rhaomi-deploy.lock" ]
}

validate_target_verifier_failure() {
  case_label=$1
  prepare_case "$case_label"
  verifier_failure_stage=backup-verifier
  if [ "$case_label" = malformed-backup-evidence ]; then
    printf '%s\n' '{"schemaVersion":1' \
      >"$case_root/state/deploy/backup-eligibility.json"
    refresh_compatibility_hash
    verifier_failure_stage=
  fi
  repository_before=$(directory_digest "$case_repository")
  deploy_state_before=$(directory_digest "$case_root/state/deploy")
  if run_case "$verifier_failure_stage" \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "target-image backup verifier failure가 성공했습니다." >&2
    exit 1
  fi
  grep -Fq DEPLOY_BACKUP_REQUIRED "$case_output"
  grep -Fq "pull ${image_reference}" "$case_log"
  grep -Fq ' backup-verifier verify-eligibility ' "$case_log"
  ! grep -Fq ' stop --timeout 30 backend publisher' "$case_log"
  [ "$(directory_digest "$case_repository")" = "$repository_before" ]
  [ "$(directory_digest "$case_root/state/deploy")" = "$deploy_state_before" ]
  [ ! -d "$case_root/state/locks/rhaomi-deploy.lock" ]
}

update_evidence_created_at() {
  replacement=$1
  evidence_file="$case_root/state/deploy/backup-eligibility.json"
  sed "s/\"createdAt\": \"[^\"]*\"/\"createdAt\": \"${replacement}\"/" \
    "$evidence_file" >"$case_root/state/deploy/.backup-eligibility.tmp"
  mv "$case_root/state/deploy/.backup-eligibility.tmp" "$evidence_file"
  chmod 600 "$evidence_file"
  refresh_compatibility_hash
}

refresh_compatibility_hash() {
  evidence_file="$case_root/state/deploy/backup-eligibility.json"
  evidence_hash=$(openssl dgst -sha256 "$evidence_file" | awk '{print $NF}')
  printf '%s\n' \
    'schemaVersion=1' \
    'status=eligible' \
    "releaseSha=${release_sha}" \
    "evidenceSha256=${evidence_hash}" \
    >"$case_root/state/deploy/backup-eligible.env"
  chmod 600 "$case_root/state/deploy/backup-eligible.env"
}

directory_digest() {
  digest_root=$1
  find "$digest_root" -type f -print | LC_ALL=C sort | while IFS= read -r digest_file; do
    printf '%s ' "${digest_file#"$digest_root"/}"
    openssl dgst -sha256 "$digest_file" | awk '{print $NF}'
  done | openssl dgst -sha256 | awk '{print $NF}'
}

validate_success_path() {
  prepare_case success
  run_case '' \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$case_output" 2>&1
  grep -Fq '"status": "success"' "$case_output"
  grep -Fq '"homeOpsTelemetry": "retained"' "$case_output"
  [ "$(cat "$case_state/backend")" = running ]
  [ "$(cat "$case_state/publisher")" = running ]
  [ ! -e "$case_state/quiescence-violation" ]
  [ ! -d "$case_root/state/locks/rhaomi-deploy.lock" ]
  assert_command_order "$case_log"
  assert_deployment_event_lifecycle "$case_homeops_log" SUCCESS
}

validate_telemetry_failure() {
  prepare_case telemetry-failure
  RHAOMI_HOMEOPS_TEST_OUTCOME=FAILED run_case '' \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$case_output" 2>&1
  grep -Fq '"status": "success"' "$case_output"
  grep -Fq '"homeOpsTelemetry": "failed"' "$case_output"
  [ "$(grep -c '^deployment ' "$case_homeops_log")" = 2 ]
  assert_deployment_event_lifecycle "$case_homeops_log" SUCCESS
}

assert_deployment_event_lifecycle() {
  event_log=$1
  terminal_status=$2
  [ "$(grep -c '^deployment ' "$event_log")" = 2 ]
  [ "$(sed -n '1s/^deployment \([^ ]*\).*/\1/p' "$event_log")" = RUNNING ]
  [ "$(sed -n '2s/^deployment \([^ ]*\).*/\1/p' "$event_log")" = "$terminal_status" ]
  running_started=$(sed -n '1s/^deployment [^ ]* [^ ]* [^ ]* \([^ ]*\).*/\1/p' "$event_log")
  terminal_started=$(sed -n '2s/^deployment [^ ]* [^ ]* [^ ]* \([^ ]*\).*/\1/p' "$event_log")
  [ -n "$running_started" ] && [ "$running_started" = "$terminal_started" ]
}

assert_command_order() {
  command_log=$1
  pull_line=$(grep -n "^pull ${image_reference}$" "$command_log" | cut -d: -f1)
  verifier_line=$(grep -n ' backup-verifier verify-eligibility ' "$command_log" | cut -d: -f1)
  stop_line=$(grep -n ' stop --timeout 30 backend publisher$' "$command_log" | cut -d: -f1)
  migrate_line=$(grep -n ' run --rm --no-deps migration$' "$command_log" | cut -d: -f1)
  schema_line=$(grep -n ' run --rm --no-deps schema-validate$' "$command_log" | cut -d: -f1)
  backend_line=$(grep -n ' up --detach --no-deps --force-recreate backend$' "$command_log" | cut -d: -f1)
  publisher_line=$(grep -n ' up --detach --no-deps --force-recreate publisher$' "$command_log" | cut -d: -f1)
  [ "$pull_line" -lt "$verifier_line" ]
  [ "$verifier_line" -lt "$stop_line" ]
  [ "$stop_line" -lt "$migrate_line" ]
  [ "$migrate_line" -lt "$schema_line" ]
  [ "$schema_line" -lt "$backend_line" ]
  [ "$backend_line" -lt "$publisher_line" ]
}

validate_lock_contention() {
  prepare_case lock-contention
  pull_release="$validation_root/lock-contention/release-pull"
  PATH="$case_bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    RHAOMI_DEPLOY_TEST_LOG="$case_log" \
    RHAOMI_DEPLOY_TEST_STATE_DIR="$case_state" \
    RHAOMI_DEPLOY_TEST_RELEASE_SHA="$release_sha" \
    RHAOMI_DEPLOY_TEST_IMAGE_REFERENCE="$image_reference" \
    RHAOMI_DEPLOY_TEST_IMAGE_ID="$image_id" \
    RHAOMI_DEPLOY_TEST_DEPLOY_STATE_DIR="$case_root/state/deploy" \
    RHAOMI_DEPLOY_TEST_PULL_RELEASE_FILE="$pull_release" \
    RHAOMI_HOMEOPS_TEST_LOG="$case_homeops_log" \
    RHAOMI_HOMEOPS_TEST_OUTCOME=RETAINED \
    /bin/sh -eu -c '
      . "$1"
      shift
      task_root=$1
      shift
      deploy_rhaomi "$task_root" "$@"
    ' sh "$core_script" "$case_root" \
      --release-sha "$release_sha" \
      --image "$image_reference" \
      --sbom "$sbom_reference" >"$case_output" 2>&1 &
  first_pid=$!

  wait_for_file "$case_state/pull-started" 20
  contention_output="$validation_root/lock-contention/contention.log"
  if run_case '' \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$contention_output" 2>&1; then
    echo "concurrent deploy가 global lock을 획득했습니다." >&2
    exit 1
  fi
  grep -Fq DEPLOY_LOCKED "$contention_output"
  [ "$(grep -c '^pull ' "$case_log")" = 1 ]
  : >"$pull_release"
  wait "$first_pid"
  [ ! -d "$case_root/state/locks/rhaomi-deploy.lock" ]
}

validate_failure_hold() {
  failure_stage=$1
  prepare_case "${failure_stage}-failure"
  if run_case "$failure_stage" \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "${failure_stage} failure injection이 성공했습니다." >&2
    exit 1
  fi
  [ "$(cat "$case_state/backend")" = exited ]
  [ "$(cat "$case_state/publisher")" = exited ]
  if grep -Eq ' up --detach --no-deps --force-recreate (backend|publisher)$' "$case_log"; then
    echo "failure 뒤 writer가 자동 resume됐습니다." >&2
    exit 1
  fi
  assert_deployment_event_lifecycle "$case_homeops_log" FAILED
  [ ! -d "$case_root/state/locks/rhaomi-deploy.lock" ]
}

validate_writer_stop_failure() {
  prepare_case writer-stop-failure
  if run_case writer-stop \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "writer stop failure injection이 성공했습니다." >&2
    exit 1
  fi
  if grep -Eq ' run --rm --no-deps (migration|schema-validate)$' "$case_log"; then
    echo "writer stop 실패 뒤 production task가 실행됐습니다." >&2
    exit 1
  fi
  [ "$(cat "$case_state/backend")" = running ]
  [ "$(cat "$case_state/publisher")" = running ]
  grep -Fq DEPLOY_FAILURE_QUIESCENCE_UNCONFIRMED "$case_output"
  [ -d "$case_root/state/locks/rhaomi-deploy.lock" ]
}

validate_backend_health_failure() {
  prepare_case backend-health-failure
  if run_case backend-health \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "backend health failure injection이 성공했습니다." >&2
    exit 1
  fi
  if grep -Fq ' up --detach --no-deps --force-recreate publisher' "$case_log"; then
    echo "backend health 실패 뒤 publisher가 시작됐습니다." >&2
    exit 1
  fi
  [ "$(cat "$case_state/backend")" = exited ]
  [ "$(cat "$case_state/publisher")" = exited ]
  [ ! -d "$case_root/state/locks/rhaomi-deploy.lock" ]
}

validate_publisher_start_failure() {
  prepare_case publisher-start-failure
  if run_case publisher-start \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "publisher start failure injection이 성공했습니다." >&2
    exit 1
  fi
  [ "$(cat "$case_state/backend")" = exited ]
  [ "$(cat "$case_state/publisher")" = exited ]
  if grep -Fq '"status": "success"' "$case_output"; then
    echo "publisher start 실패이 success evidence로 오기록됐습니다." >&2
    exit 1
  fi
  [ ! -d "$case_root/state/locks/rhaomi-deploy.lock" ]
}

validate_runtime_image_mismatch() {
  runtime_service=$1
  prepare_case "runtime-${runtime_service}-image-mismatch"
  if run_case "runtime-${runtime_service}-image" \
    --release-sha "$release_sha" \
    --image "$image_reference" \
    --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "${runtime_service} runtime image mismatch injection이 성공했습니다." >&2
    exit 1
  fi
  grep -Fq DEPLOY_RUNTIME_IMAGE_INVALID "$case_output"
  [ "$(cat "$case_state/backend")" = exited ]
  [ "$(cat "$case_state/publisher")" = exited ]
  if grep -Fq '"status": "success"' "$case_output"; then
    echo "runtime image mismatch가 success evidence로 오기록됐습니다." >&2
    exit 1
  fi
  [ "$(grep -c ' stop --timeout 30 backend publisher$' "$case_log")" = 2 ]
  [ ! -d "$case_root/state/locks/rhaomi-deploy.lock" ]
}

validate_revision_mismatch() {
  prepare_case revision-mismatch
  wrong_revision=$(printf 'e%.0s' $(seq 1 40))
  if PATH="$case_bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    RHAOMI_DEPLOY_TEST_LOG="$case_log" \
    RHAOMI_DEPLOY_TEST_STATE_DIR="$case_state" \
    RHAOMI_DEPLOY_TEST_RELEASE_SHA="$release_sha" \
    RHAOMI_DEPLOY_TEST_REVISION_OVERRIDE="$wrong_revision" \
    RHAOMI_DEPLOY_TEST_IMAGE_REFERENCE="$image_reference" \
    RHAOMI_DEPLOY_TEST_IMAGE_ID="$image_id" \
    RHAOMI_DEPLOY_TEST_DEPLOY_STATE_DIR="$case_root/state/deploy" \
    RHAOMI_HOMEOPS_TEST_LOG="$case_homeops_log" \
    RHAOMI_HOMEOPS_TEST_OUTCOME=RETAINED \
    /bin/sh -eu -c '
      . "$1"
      shift
      task_root=$1
      shift
      deploy_rhaomi "$task_root" "$@"
    ' sh "$core_script" "$case_root" \
      --release-sha "$release_sha" \
      --image "$image_reference" \
      --sbom "$sbom_reference" >"$case_output" 2>&1; then
    echo "OCI revision mismatch가 성공했습니다." >&2
    exit 1
  fi
  [ "$(cat "$case_state/backend")" = running ]
  [ "$(cat "$case_state/publisher")" = running ]
  if grep -Fq ' stop --timeout 30 backend publisher' "$case_log"; then
    echo "image 검증 실패 뒤 writer mutation이 발생했습니다." >&2
    exit 1
  fi
}

validate_secret_redaction() {
  if grep -R -F "$synthetic_db_marker" "$validation_root" \
    --exclude=production.env >/dev/null 2>&1; then
    echo "synthetic DB credential marker가 validation output에 노출됐습니다." >&2
    exit 1
  fi
  if grep -R -F "$synthetic_build_marker" "$validation_root" \
    --exclude=production.env >/dev/null 2>&1; then
    echo "synthetic build credential marker가 validation output에 노출됐습니다." >&2
    exit 1
  fi
  printf '%s\n' REDACTED >"$evidence_dir/redaction-status.txt"
}

wait_for_file() {
  expected_file=$1
  maximum=$2
  attempt=0
  while [ "$attempt" -lt "$maximum" ]; do
    [ ! -f "$expected_file" ] || return 0
    attempt=$((attempt + 1))
    sleep 1
  done
  echo "lock contention fixture timeout" >&2
  exit 1
}

cleanup() {
  trap - EXIT HUP INT TERM
  if [ -f "$validation_marker" ] &&
    [ "$(sed -n '1p' "$validation_marker")" = "$git_head" ]; then
    find "$validation_root" -depth -delete
  fi
}

main "$@"
