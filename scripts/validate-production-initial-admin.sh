#!/bin/sh

set -eu
umask 077

main() {
  repository_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
  lifecycle_core="$repository_root/ops/production/production-lifecycle-core.sh"
  initial_admin_core="$repository_root/ops/production/provision-initial-admin-rhaomi-core.sh"
  wrapper="$repository_root/ops/production/provision-initial-admin-rhaomi.sh"
  fake_docker_source="$repository_root/scripts/fixtures/fake-production-initial-admin-docker.sh"
  release_sha=$(git -C "$repository_root" rev-parse HEAD)
  image_digest="sha256:$(printf 'a%.0s' $(seq 1 64))"
  image_reference="ghcr.io/xxh3898/rhaomi@${image_digest}"
  image_id="sha256:$(printf 'b%.0s' $(seq 1 64))"
  validation_parent=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/rhaomi-initial-admin.XXXXXX")
  validation_marker="$validation_parent/.rhaomi-initial-admin-validation"
  printf '%s\n' "$release_sha" >"$validation_marker"
  trap cleanup EXIT HUP INT TERM

  validate_success
  validate_task_failure_recovery
  validate_recovery_failure_lock_hold
  validate_lifecycle_and_lock_fail_closed
  validate_source_identity_fail_closed
  validate_source_contract

  printf '%s\n' 'production initial-admin contract validation: PASS'
}

prepare_case() {
  case_name=$1
  case_root="$validation_parent/$case_name/root"
  case_state="$validation_parent/$case_name/docker-state"
  case_bin="$validation_parent/$case_name/bin"
  case_log="$validation_parent/$case_name/docker.log"
  case_output="$validation_parent/$case_name/output.log"
  mkdir -p \
    "$case_root/app/bin" \
    "$case_root/app/docker" \
    "$case_root/state/deploy" \
    "$case_root/state/locks" \
    "$case_state" \
    "$case_bin"
  chmod 700 \
    "$case_root" \
    "$case_root/app" \
    "$case_root/app/bin" \
    "$case_root/app/docker" \
    "$case_root/state" \
    "$case_root/state/deploy" \
    "$case_root/state/locks" \
    "$case_state" \
    "$case_bin"
  printf '%s\n' 'services: {}' >"$case_root/app/compose.production.yaml"
  printf '%s\n' 'RHAOMI_PRODUCTION_COMPOSE_PROJECT=rhaomi-initial-admin-validation' \
    >"$case_root/app/production.env"
  printf '%s\n' '{}' >"$case_root/app/docker/config.json"
  chmod 640 "$case_root/app/compose.production.yaml"
  chmod 600 "$case_root/app/production.env" "$case_root/app/docker/config.json"
  printf '%s\n' running >"$case_state/backend"
  printf '%s\n' running >"$case_state/publisher"
  cp "$fake_docker_source" "$case_bin/docker"
  chmod 700 "$case_bin/docker"
  : >"$case_log"
  write_lifecycle STEADY_STATE
}

write_lifecycle() {
  lifecycle_state=$1
  evidence="$case_root/state/deploy/first-activation-recovery.json"
  printf '%s\n' \
    '{' \
    "  \"releaseSha\": \"${release_sha}\"," \
    "  \"imageDigest\": \"${image_digest}\"," \
    "  \"state\": \"${lifecycle_state}\"" \
    '}' >"$evidence"
  chmod 600 "$evidence"
  evidence_sha=$(openssl dgst -sha256 "$evidence" | awk '{print $NF}')
  printf '%s\n' \
    'schemaVersion=1' \
    "state=${lifecycle_state}" \
    "releaseSha=${release_sha}" \
    "imageDigest=${image_digest}" \
    'updatedAt=2026-09-10T00:00:00Z' \
    'evidenceFile=first-activation-recovery.json' \
    "evidenceSha256=${evidence_sha}" \
    >"$case_root/state/deploy/production-lifecycle.env"
  chmod 600 "$case_root/state/deploy/production-lifecycle.env"
}

run_case() {
  failure_stage=$1
  test_image_reference=${2:-$image_reference}
  test_release_sha=${3:-$release_sha}
  PATH="$case_bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    RHAOMI_INITIAL_ADMIN_TEST_LOG="$case_log" \
    RHAOMI_INITIAL_ADMIN_TEST_STATE_DIR="$case_state" \
    RHAOMI_INITIAL_ADMIN_TEST_IMAGE_REFERENCE="$test_image_reference" \
    RHAOMI_INITIAL_ADMIN_TEST_IMAGE_ID="$image_id" \
    RHAOMI_INITIAL_ADMIN_TEST_RELEASE_SHA="$test_release_sha" \
    RHAOMI_INITIAL_ADMIN_TEST_FAIL_STAGE="$failure_stage" \
    /bin/sh -eu -c '
      . "$1"
      . "$2"
      initial_admin_require_interactive_terminal() { return 0; }
      provision_initial_admin_rhaomi "$3"
    ' sh "$lifecycle_core" "$initial_admin_core" "$case_root"
}

validate_source_identity_fail_closed() {
  prepare_case image-digest-mismatch
  mismatched_digest="sha256:$(printf 'c%.0s' $(seq 1 64))"
  if run_case '' "ghcr.io/xxh3898/rhaomi@${mismatched_digest}" \
    >"$case_output" 2>&1; then
    fail INITIAL_ADMIN_EXPECTED_FAILURE_MISSING
  fi
  grep -Fq INITIAL_ADMIN_SOURCE_IDENTITY_INVALID "$case_output"
  [ "$(cat "$case_state/backend")" = running ]
  [ "$(cat "$case_state/publisher")" = running ]
  if grep -Fq 'run --rm --no-deps initial-admin' "$case_log"; then
    fail INITIAL_ADMIN_IMAGE_MISMATCH_MUTATION
  fi

  prepare_case release-revision-mismatch
  mismatched_release_sha=$(printf 'd%.0s' $(seq 1 40))
  if run_case '' "$image_reference" "$mismatched_release_sha" \
    >"$case_output" 2>&1; then
    fail INITIAL_ADMIN_EXPECTED_FAILURE_MISSING
  fi
  grep -Fq INITIAL_ADMIN_SOURCE_IDENTITY_INVALID "$case_output"
  [ "$(cat "$case_state/backend")" = running ]
  [ "$(cat "$case_state/publisher")" = running ]
  if grep -Fq 'run --rm --no-deps initial-admin' "$case_log"; then
    fail INITIAL_ADMIN_REVISION_MISMATCH_MUTATION
  fi
}

validate_success() {
  prepare_case success
  run_case '' >"$case_output" 2>&1
  grep -Fq '"contract":"rhaomi-initial-admin-host-v1"' "$case_output"
  grep -Fq -- '--profile production-task run --rm --no-deps initial-admin' "$case_log"
  [ "$(cat "$case_state/backend")" = running ]
  [ "$(cat "$case_state/publisher")" = running ]
  [ ! -e "$case_root/state/locks/rhaomi-deploy.lock" ]
  if grep -Eqi 'initial.admin@example|synthetic-password|RHAOMI_INITIAL_ADMIN_(EMAIL|PASSWORD)' \
    "$case_log" "$case_output"; then
    fail INITIAL_ADMIN_CREDENTIAL_LEAK
  fi
}

validate_task_failure_recovery() {
  prepare_case task-failure
  if run_case task >"$case_output" 2>&1; then
    fail INITIAL_ADMIN_EXPECTED_FAILURE_MISSING
  fi
  grep -Fq INITIAL_ADMIN_TASK_FAILED "$case_output"
  [ "$(cat "$case_state/backend")" = running ]
  [ "$(cat "$case_state/publisher")" = running ]
  [ ! -e "$case_root/state/locks/rhaomi-deploy.lock" ]
  if grep -Fq '"contract":"rhaomi-initial-admin-host-v1"' "$case_output"; then
    fail INITIAL_ADMIN_FALSE_SUCCESS
  fi
}

validate_recovery_failure_lock_hold() {
  prepare_case recovery-failure
  if run_case backend-recovery >"$case_output" 2>&1; then
    fail INITIAL_ADMIN_EXPECTED_FAILURE_MISSING
  fi
  grep -Fq INITIAL_ADMIN_WRITER_RECOVERY_FAILED "$case_output"
  [ -f "$case_root/state/locks/rhaomi-deploy.lock/owner" ]
  if grep -Fq '"contract":"rhaomi-initial-admin-host-v1"' "$case_output"; then
    fail INITIAL_ADMIN_FALSE_SUCCESS
  fi
}

validate_lifecycle_and_lock_fail_closed() {
  prepare_case invalid-lifecycle
  write_lifecycle RECOVERY_ACCEPTANCE_REQUIRED
  if run_case '' >"$case_output" 2>&1; then
    fail INITIAL_ADMIN_EXPECTED_FAILURE_MISSING
  fi
  grep -Fq INITIAL_ADMIN_LIFECYCLE_INVALID "$case_output"
  if grep -Fq 'run --rm --no-deps initial-admin' "$case_log"; then
    fail INITIAL_ADMIN_LIFECYCLE_MUTATION
  fi

  prepare_case existing-lock
  mkdir "$case_root/state/locks/rhaomi-deploy.lock"
  chmod 700 "$case_root/state/locks/rhaomi-deploy.lock"
  if run_case '' >"$case_output" 2>&1; then
    fail INITIAL_ADMIN_EXPECTED_FAILURE_MISSING
  fi
  grep -Fq INITIAL_ADMIN_LOCKED "$case_output"
  if grep -Fq 'run --rm --no-deps initial-admin' "$case_log"; then
    fail INITIAL_ADMIN_LOCKED_MUTATION
  fi
}

validate_source_contract() {
  grep -Fq 'provision_initial_admin_rhaomi /private/var/lib/rhaomi "$@"' "$wrapper"
  grep -Fq 'initial_admin_require_interactive_terminal' "$initial_admin_core"
  grep -Fq 'lifecycle_image_digest' "$initial_admin_core"
  grep -Fq 'org.opencontainers.image.revision' "$initial_admin_core"
  grep -Fq 'RHAOMI_INITIAL_ADMIN_PASSWORD' "$wrapper"
  grep -Fq 'RHAOMI_BOOTSTRAP_ADMIN_PASSWORD' "$wrapper"
  grep -Fq 'DOCKER_CONFIG DOCKER_CONTEXT' "$wrapper"
  assert_order \
    'DOCKER_CONFIG=$initial_admin_docker_config_root' \
    'docker compose version' \
    "$initial_admin_core"
  if grep -Eq '(^|[[:space:]])(eval|source)[[:space:]]' "$initial_admin_core"; then
    fail INITIAL_ADMIN_FREE_FORM_EXECUTION
  fi
}

assert_order() {
  first=$1
  second=$2
  source_file=$3
  first_line=$(grep -Fn "$first" "$source_file" | head -n 1 | cut -d: -f1)
  second_line=$(grep -Fn "$second" "$source_file" | head -n 1 | cut -d: -f1)
  [ -n "$first_line" ] && [ -n "$second_line" ] && [ "$first_line" -lt "$second_line" ] ||
    fail INITIAL_ADMIN_SOURCE_ORDER_INVALID
}

cleanup() {
  if [ -n "${validation_parent:-}" ] &&
    [ -f "${validation_marker:-}" ] &&
    [ "$(sed -n '1p' "$validation_marker")" = "$release_sha" ]; then
    find "$validation_parent" -depth -delete
  fi
}

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

main "$@"
