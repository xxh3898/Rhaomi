#!/bin/sh

set -eu
umask 077

PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
export PATH
unset CDPATH ENV BASH_ENV

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
git_head=${RHAOMI_HOMEOPS_VALIDATION_GIT_HEAD:-}
if [ -z "$git_head" ]; then
  git_head=$(git -C "$repo_dir" rev-parse HEAD)
fi
evidence_dir=${RHAOMI_HOMEOPS_EVIDENCE_DIR:-}
temporary_evidence=

printf '%s' "$git_head" | grep -Eq '^[0-9a-f]{40}$'

if [ -z "$evidence_dir" ]; then
  temporary_evidence=$(mktemp -d "${TMPDIR:-/tmp}/rhaomi-homeops-evidence.XXXXXX")
  evidence_dir=$temporary_evidence
else
  [ -d "$evidence_dir" ] || mkdir -p "$evidence_dir"
  [ -z "$(find "$evidence_dir" -mindepth 1 -maxdepth 1 -print -quit)" ] || {
    echo HOMEOPS_VALIDATION_EVIDENCE_NOT_EMPTY >&2
    exit 1
  }
fi

cleanup() {
  if [ -n "$temporary_evidence" ] && [ -d "$temporary_evidence" ]; then
    find "$temporary_evidence" -type f -exec rm {} \;
    rmdir "$temporary_evidence"
  fi
}
trap cleanup EXIT HUP INT TERM

/usr/bin/python3 - \
  "$repo_dir/ops/production/rhaomi_homeops.py" \
  "$repo_dir/ops/production/report-rhaomi-event.py" \
  "$repo_dir/ops/production/status-rhaomi.py" \
  "$repo_dir/ops/production/recover-rhaomi-service.py" \
  "$repo_dir/scripts/validate-homeops-integration.py" <<'PY'
import pathlib
import sys

for source_path in sys.argv[1:]:
    source = pathlib.Path(source_path).read_text(encoding="utf-8")
    compile(source, source_path, "exec")
PY

/usr/bin/python3 "$repo_dir/scripts/validate-homeops-integration.py" \
  --git-head "$git_head" \
  --evidence-dir "$evidence_dir"

grep -Fq '"productionPathMutation": 0' "$evidence_dir/homeops-integration-evidence.json"
grep -Fq '"homeOpsRepositoryMutation": 0' "$evidence_dir/homeops-integration-evidence.json"
grep -Fq '"dockerVolumeDeletion": 0' "$evidence_dir/homeops-integration-evidence.json"
grep -Fq '"dockerImageDeletion": 0' "$evidence_dir/homeops-integration-evidence.json"
grep -Fq '"overallProductionReadiness": "HOLD"' "$evidence_dir/homeops-integration-evidence.json"
grep -Fq '"webMonitorSignal": "PUBLIC_HTTPS_STATUS"' "$evidence_dir/homeops-integration-evidence.json"
grep -Fq '"webExpectedStatusAuthority": "MONITORED_SERVICE_EXPECTED_STATUS"' "$evidence_dir/homeops-integration-evidence.json"
grep -Fq '"keywordBodyMatcherSupported": false' "$evidence_dir/homeops-integration-evidence.json"
grep -Fq '"webFailureThreshold": 3' "$evidence_dir/homeops-integration-evidence.json"
grep -Fq '"webTarget": "rhaomi-web"' "$evidence_dir/homeops-integration-evidence.json"
grep -Fq '"backendMapping": "ABSENT"' "$evidence_dir/homeops-integration-evidence.json"
grep -Fq '"mappingEnableCount": 0' "$evidence_dir/homeops-integration-evidence.json"
grep -Fq '"actualRestartOrDrillCount": 0' "$evidence_dir/homeops-integration-evidence.json"
