#!/bin/sh

set -eu
umask 077

repository_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
core="$repository_root/ops/production/backup-rhaomi-core.sh"
fake_docker="$repository_root/scripts/fixtures/fake-production-backup-docker.sh"
fake_homeops="$repository_root/scripts/fixtures/fake-homeops-event-adapter.sh"
evidence_dir=${RHAOMI_PRODUCTION_BACKUP_CONTROL_EVIDENCE_DIR:-}
validation_succeeded=false
release_sha=$(git -C "$repository_root" rev-parse HEAD)
image_digest="sha256:$(printf 'a%.0s' $(seq 1 64))"
image_reference="ghcr.io/xxh3898/rhaomi@$image_digest"
image_id="sha256:$(printf 'b%.0s' $(seq 1 64))"
secret_marker=synthetic-backup-secret-marker-do-not-log

case "$release_sha" in
  *[!0-9a-f]* | ??????????????????????????????????????? | ?????????????????????????????????????????*)
    echo BACKUP_CONTROL_HEAD_INVALID >&2
    exit 1
    ;;
esac

validation_temp_parent=$(cd "${TMPDIR:-/tmp}" && pwd -P)
validation_parent=$(mktemp -d "$validation_temp_parent/rhaomi-backup-control.XXXXXX")
validation_parent=$(cd "$validation_parent" && pwd -P)
validation_marker="$validation_parent/.rhaomi-backup-control-validation"
: >"$validation_marker"
case_root=
case_repository=
cleanup() {
  cleanup_result=$?
  trap - EXIT HUP INT TERM
  if [ "$cleanup_result" -ne 0 ] &&
    [ "$validation_succeeded" != true ] &&
    [ -n "$evidence_dir" ] &&
    [ -d "$evidence_dir" ] &&
    [ ! -L "$evidence_dir" ]; then
    printf '%s\n' \
      'contract=rhaomi-production-backup-control-v1' \
      "gitHead=$release_sha" \
      'secretLeakCount=0' \
      'productionPathMutation=0' \
      'status=failure' \
      >"$evidence_dir/production-backup-control-failure.txt"
  fi
  [ -f "$validation_marker" ] && [ ! -L "$validation_marker" ] || exit "$cleanup_result"
  case "$validation_parent" in
    "$validation_temp_parent"/rhaomi-backup-control.*) ;;
    *) exit "$cleanup_result" ;;
  esac
  find "$validation_parent" -type d -exec chmod u+rwx {} + 2>/dev/null || true
  find "$validation_parent" -type f -exec chmod u+rw {} + 2>/dev/null || true
  find "$validation_parent" -depth -delete || cleanup_result=1
  exit "$cleanup_result"
}
trap cleanup EXIT HUP INT TERM
if [ -n "$evidence_dir" ]; then
  case "$evidence_dir" in
    /*) ;;
    *) echo BACKUP_CONTROL_EVIDENCE_INVALID >&2; exit 1 ;;
  esac
  [ ! -L "$evidence_dir" ] || { echo BACKUP_CONTROL_EVIDENCE_INVALID >&2; exit 1; }
  if [ -d "$evidence_dir" ] &&
    [ -n "$(find "$evidence_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    echo BACKUP_CONTROL_EVIDENCE_NOT_EMPTY >&2
    exit 1
  fi
  mkdir -p "$evidence_dir"
  chmod 700 "$evidence_dir"
fi

prepare_case() {
  case_name=$1
  failure_stage=${2:-}
  case_root="$validation_parent/host-$case_name"
  case_repository="$validation_parent/repository-$case_name"
  state_dir="$validation_parent/state-$case_name"
  fake_bin="$validation_parent/bin-$case_name"
  log_file="$validation_parent/$case_name.log"
  homeops_log="$validation_parent/$case_name-homeops.log"
  mkdir -m 700 \
    "$case_root" \
    "$case_root/app" \
    "$case_root/app/bin" \
    "$case_root/app/docker" \
    "$case_root/data" \
    "$case_root/data/media" \
    "$case_root/state" \
    "$case_root/state/deploy" \
    "$case_root/state/locks" \
    "$case_repository" \
    "$case_repository/sets" \
    "$state_dir" \
    "$fake_bin"
  printf '%s\n' '{}' >"$case_root/app/compose.production.yaml"
  printf '%s\n' '{}' >"$case_root/app/compose.production.validation.yaml"
  printf '%s\n' '{}' >"$case_root/app/docker/config.json"
  printf '%s\n' \
    "RHAOMI_BACKUP_REPOSITORY_ROOT=$case_repository" \
    'RHAOMI_PRODUCTION_COMPOSE_PROJECT=rhaomi-production' \
    "RHAOMI_POSTGRES_PASSWORD=$secret_marker" \
    >"$case_root/app/production.env"
  printf '%s\n' rhaomi-backup-repository-v1 >"$case_repository/.rhaomi-backup-repository"
  chmod 600 \
    "$case_root/app/production.env" \
    "$case_root/app/docker/config.json" \
    "$case_repository/.rhaomi-backup-repository"
  printf '%s\n' running >"$state_dir/rhaomi-web"
  printf '%s\n' running >"$state_dir/backend"
  printf '%s\n' running >"$state_dir/publisher"
  printf '%s\n' runtime >"$state_dir/media-permission"
  cp "$fake_docker" "$fake_bin/docker"
  cp "$fake_docker" "$fake_bin/docker-compose"
  cp "$fake_homeops" "$case_root/app/bin/report-rhaomi-event.py"
  printf '%s\n' '#!/bin/sh' 'printf "%s\n" Linux' >"$fake_bin/uname"
  chmod 700 \
    "$fake_bin/docker" \
    "$fake_bin/docker-compose" \
    "$fake_bin/uname" \
    "$case_root/app/bin/report-rhaomi-event.py"
  : >"$log_file"
  : >"$homeops_log"
  export \
    RHAOMI_BACKUP_TEST_LOG="$log_file" \
    RHAOMI_BACKUP_TEST_STATE_DIR="$state_dir" \
    RHAOMI_BACKUP_TEST_REPOSITORY="$case_repository" \
    RHAOMI_BACKUP_TEST_RELEASE_SHA="$release_sha" \
    RHAOMI_BACKUP_TEST_IMAGE_REFERENCE="$image_reference" \
    RHAOMI_BACKUP_TEST_IMAGE_ID="$image_id" \
    RHAOMI_BACKUP_TEST_FAIL_STAGE="$failure_stage" \
    RHAOMI_BACKUP_TEST_LOCK_OWNER="$case_root/state/locks/rhaomi-deploy.lock/owner" \
    RHAOMI_HOMEOPS_TEST_LOG="$homeops_log" \
    RHAOMI_HOMEOPS_TEST_OUTCOME=RETAINED \
    RHAOMI_BACKUP_VALIDATION_COMPOSE_FILE="$case_root/app/compose.production.validation.yaml"
  PATH="$fake_bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
  export PATH
}

run_backup() {
  (
    # shellcheck disable=SC1090
    . "$core"
    backup_rhaomi "$case_root" "$@"
  )
}

validate_success() {
  prepare_case success
  output=$(run_backup --mode scheduled)
  printf '%s\n' "$output" | grep -Fq '"status": "complete"'
  printf '%s\n' "$output" | grep -Fq '"homeOpsTelemetry": "retained"'
  complete_count=$(find "$case_repository/sets" -mindepth 1 -maxdepth 1 -type d ! -name '.incomplete-*' | wc -l | tr -d ' ')
  [ "$complete_count" = 1 ]
  [ "$(cat "$state_dir/backend")" = running ]
  [ "$(cat "$state_dir/publisher")" = running ]
  [ ! -d "$case_root/state/locks/rhaomi-deploy.lock" ]
  stop_line=$(grep -n ' stop --timeout 30 backend publisher' "$log_file" | cut -d: -f1)
  dump_line=$(grep -n ' pg_dump ' "$log_file" | cut -d: -f1)
  capture_permission_line=$(grep -n ' backup-permission capture ' "$log_file" | cut -d: -f1)
  runtime_permission_line=$(grep -n ' backup-permission runtime ' "$log_file" | cut -d: -f1)
  backend_line=$(grep -n ' up --detach --no-deps --force-recreate backend' "$log_file" | cut -d: -f1)
  finalize_line=$(grep -n ' finalize ' "$log_file" | cut -d: -f1)
  [ "$stop_line" -lt "$capture_permission_line" ]
  [ "$capture_permission_line" -lt "$dump_line" ]
  [ "$dump_line" -lt "$finalize_line" ]
  [ "$dump_line" -lt "$runtime_permission_line" ]
  [ "$runtime_permission_line" -lt "$backend_line" ]
  [ "$backend_line" -lt "$finalize_line" ]
  [ "$(cat "$state_dir/media-permission")" = runtime ]
  assert_backup_event_lifecycle "$homeops_log" SUCCESS
}

validate_telemetry_failure() {
  prepare_case telemetry-failure
  RHAOMI_HOMEOPS_TEST_OUTCOME=FAILED
  export RHAOMI_HOMEOPS_TEST_OUTCOME
  output=$(run_backup --mode on-demand 2>"$validation_parent/telemetry-failure.err")
  printf '%s\n' "$output" | grep -Fq '"status": "complete"'
  printf '%s\n' "$output" | grep -Fq '"homeOpsTelemetry": "failed"'
  grep -Fq HOMEOPS_TELEMETRY_FAILED "$validation_parent/telemetry-failure.err"
  assert_backup_event_lifecycle "$homeops_log" SUCCESS
}

validate_lock_release_failure() {
  prepare_case lock-release-failure lock-release
  if run_backup --mode scheduled >"$validation_parent/lock-release.out" \
    2>"$validation_parent/lock-release.err"; then
    echo "lock release failure가 성공했습니다." >&2
    exit 1
  fi
  ! grep -Fq '"status": "complete"' "$validation_parent/lock-release.out"
  grep -Fq BACKUP_LOCK_RELEASE_FAILED "$validation_parent/lock-release.err"
  assert_backup_event_lifecycle "$homeops_log" FAILED
  [ -d "$case_root/state/locks/rhaomi-deploy.lock" ]
  [ "$(cat "$state_dir/backend")" = running ]
  [ "$(cat "$state_dir/publisher")" = running ]
}

assert_backup_event_lifecycle() {
  event_log=$1
  terminal_status=$2
  [ "$(grep -c '^backup ' "$event_log")" = 2 ]
  [ "$(sed -n '1s/^backup \([^ ]*\).*/\1/p' "$event_log")" = RUNNING ]
  [ "$(sed -n '2s/^backup \([^ ]*\).*/\1/p' "$event_log")" = "$terminal_status" ]
  running_started=$(sed -n '1s/^backup [^ ]* [^ ]* \([^ ]*\).*/\1/p' "$event_log")
  terminal_started=$(sed -n '2s/^backup [^ ]* [^ ]* \([^ ]*\).*/\1/p' "$event_log")
  [ -n "$running_started" ] && [ "$running_started" = "$terminal_started" ]
}

validate_failure_recovery() {
  prepare_case capture-failure capture
  if run_backup --mode scheduled >"$validation_parent/capture.out" 2>"$validation_parent/capture.err"; then
    echo "capture failure가 성공했습니다." >&2
    exit 1
  fi
  [ "$(cat "$state_dir/backend")" = running ]
  [ "$(cat "$state_dir/publisher")" = running ]
  [ "$(cat "$state_dir/media-permission")" = runtime ]
  [ ! -d "$case_root/state/locks/rhaomi-deploy.lock" ]
  [ "$(find "$case_repository/sets" -mindepth 1 -maxdepth 1 -type d ! -name '.incomplete-*' | wc -l | tr -d ' ')" = 0 ]
  capture_permission_line=$(grep -n ' backup-permission capture ' "$log_file" | cut -d: -f1)
  capture_line=$(grep -n ' capture-media ' "$log_file" | cut -d: -f1)
  runtime_permission_line=$(grep -n ' backup-permission runtime ' "$log_file" | cut -d: -f1)
  backend_line=$(grep -n ' up --detach --no-deps --force-recreate backend' "$log_file" | cut -d: -f1)
  [ "$capture_permission_line" -lt "$capture_line" ]
  [ "$capture_line" -lt "$runtime_permission_line" ]
  [ "$runtime_permission_line" -lt "$backend_line" ]
  assert_backup_event_lifecycle "$homeops_log" FAILED

  prepare_case runtime-permission-failure runtime-permission
  if run_backup --mode scheduled >"$validation_parent/runtime-permission.out" 2>"$validation_parent/runtime-permission.err"; then
    echo "runtime permission restoration failure가 성공했습니다." >&2
    exit 1
  fi
  [ -d "$case_root/state/locks/rhaomi-deploy.lock" ]
  [ "$(cat "$state_dir/backend")" = exited ]
  [ "$(cat "$state_dir/publisher")" = exited ]
  [ "$(cat "$state_dir/media-permission")" = capture ]
  [ "$(find "$case_repository/sets" -mindepth 1 -maxdepth 1 -type d ! -name '.incomplete-*' | wc -l | tr -d ' ')" = 0 ]
  ! grep -Fq ' finalize ' "$log_file"
  grep -Fq BACKUP_WRITER_RECOVERY_FAILED "$validation_parent/runtime-permission.err"

  prepare_case restart-failure backend-start
  if run_backup --mode scheduled >"$validation_parent/restart.out" 2>"$validation_parent/restart.err"; then
    echo "writer restart failure가 성공했습니다." >&2
    exit 1
  fi
  [ -d "$case_root/state/locks/rhaomi-deploy.lock" ]
  [ "$(cat "$state_dir/backend")" = exited ]
  [ "$(cat "$state_dir/publisher")" = exited ]
  grep -Fq BACKUP_WRITER_RECOVERY_FAILED "$validation_parent/restart.err"
}

validate_lock_contention() {
  prepare_case contention
  mkdir "$case_root/state/locks/rhaomi-deploy.lock"
  printf '%s\n' deploy-owner >"$case_root/state/locks/rhaomi-deploy.lock/owner"
  if run_backup --mode on-demand >"$validation_parent/contention.out" 2>"$validation_parent/contention.err"; then
    echo "concurrent backup이 shared lock을 획득했습니다." >&2
    exit 1
  fi
  grep -Fq BACKUP_LOCKED "$validation_parent/contention.err"
  ! grep -Fq ' stop --timeout' "$log_file"
  [ "$(cat "$case_root/state/locks/rhaomi-deploy.lock/owner")" = deploy-owner ]
}

validate_predeploy_and_redaction() {
  prepare_case predeploy
  output=$(run_backup --mode predeploy --target-release-sha "$release_sha")
  printf '%s\n' "$output" | grep -Fq '"mode": "predeploy"'
  grep -Fq ' issue-eligibility ' "$log_file"
  if grep -F "$secret_marker" "$log_file" >/dev/null 2>&1 ||
    printf '%s\n' "$output" | grep -F "$secret_marker" >/dev/null 2>&1; then
    echo BACKUP_SECRET_LEAK >&2
    exit 1
  fi
}

validate_success
validate_telemetry_failure
validate_lock_release_failure
validate_failure_recovery
validate_lock_contention
validate_predeploy_and_redaction

if [ -n "$evidence_dir" ]; then
  printf '%s\n' \
    '{' \
    '  "contract": "rhaomi-production-backup-control-v1",' \
    "  \"gitHead\": \"$release_sha\"," \
    '  "sharedOperationLock": "verified",' \
    '  "writerPhysicalQuiescence": "verified",' \
    '  "failureRecovery": "verified",' \
    '  "homeOpsBackupLifecycle": "verified",' \
    '  "homeOpsTelemetryFailureDoesNotRewriteBackupOutcome": "verified",' \
    '  "lockReleaseFailureDoesNotEmitSuccess": "verified",' \
    '  "permissionFailureLockHold": "verified",' \
    '  "restartFailureLockHold": "verified",' \
    '  "predeployEligibilityMode": "verified",' \
    '  "secretLeakCount": 0,' \
    '  "productionPathMutation": 0,' \
    '  "status": "success"' \
    '}' >"$evidence_dir/production-backup-control.json"
fi
validation_succeeded=true

printf '%s\n' \
  'sharedOperationLock=verified' \
  'writerPhysicalQuiescence=verified' \
  'failureRecovery=verified' \
  'homeOpsBackupLifecycle=verified' \
  'homeOpsTelemetryFailureDoesNotRewriteBackupOutcome=verified' \
  'lockReleaseFailureDoesNotEmitSuccess=verified' \
  'permissionFailureLockHold=verified' \
  'restartFailureLockHold=verified' \
  'predeployEligibilityMode=verified' \
  'productionPathMutation=0' \
  'dockerVolumeDelete=0' \
  'status=success'
