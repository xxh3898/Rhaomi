#!/bin/sh

# Production lifecycle state는 deploy/backup/first-activation entrypoint가 공유한다.
# caller는 먼저 rhaomi_lifecycle_initialize <canonical-root>를 호출한다.

rhaomi_lifecycle_initialize() {
  rhaomi_lifecycle_root=$1
  rhaomi_lifecycle_state_dir="$rhaomi_lifecycle_root/state/deploy"
  rhaomi_lifecycle_state_file="$rhaomi_lifecycle_state_dir/production-lifecycle.env"
}

rhaomi_lifecycle_require_state() {
  expected_state=$1
  expected_release_sha=${2:-}
  expected_image_digest=${3:-}

  rhaomi_lifecycle_require_private_file "$rhaomi_lifecycle_state_file" || return 1
  [ "$(wc -l <"$rhaomi_lifecycle_state_file" | tr -d '[:space:]')" = 7 ] || return 1

  lifecycle_schema=$(sed -n '1s/^schemaVersion=//p' "$rhaomi_lifecycle_state_file")
  lifecycle_state=$(sed -n '2s/^state=//p' "$rhaomi_lifecycle_state_file")
  lifecycle_release_sha=$(sed -n '3s/^releaseSha=//p' "$rhaomi_lifecycle_state_file")
  lifecycle_image_digest=$(sed -n '4s/^imageDigest=//p' "$rhaomi_lifecycle_state_file")
  lifecycle_updated_at=$(sed -n '5s/^updatedAt=//p' "$rhaomi_lifecycle_state_file")
  lifecycle_evidence_name=$(sed -n '6s/^evidenceFile=//p' "$rhaomi_lifecycle_state_file")
  lifecycle_evidence_sha=$(sed -n '7s/^evidenceSha256=//p' "$rhaomi_lifecycle_state_file")

  [ "$lifecycle_schema" = 1 ] && [ "$lifecycle_state" = "$expected_state" ] || return 1
  printf '%s' "$lifecycle_release_sha" | grep -Eq '^[0-9a-f]{40}$' || return 1
  printf '%s' "$lifecycle_image_digest" | grep -Eq '^sha256:[0-9a-f]{64}$' || return 1
  printf '%s' "$lifecycle_updated_at" |
    grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' || return 1
  printf '%s' "$lifecycle_evidence_sha" | grep -Eq '^[0-9a-f]{64}$' || return 1

  case "$lifecycle_state" in
    FIRST_ACTIVATION_BOOTSTRAPPING)
      [ "$lifecycle_evidence_name" = first-activation-bootstrap.json ] || return 1
      lifecycle_evidence_state=RUNNING
      ;;
    RECOVERY_ACCEPTANCE_REQUIRED)
      [ "$lifecycle_evidence_name" = first-activation-bootstrap.json ] || return 1
      lifecycle_evidence_state=RECOVERY_ACCEPTANCE_REQUIRED
      ;;
    RECOVERY_ACCEPTANCE_IN_PROGRESS)
      [ "$lifecycle_evidence_name" = first-activation-recovery.json ] || return 1
      lifecycle_evidence_state=RUNNING
      ;;
    STEADY_STATE)
      [ "$lifecycle_evidence_name" = first-activation-recovery.json ] || return 1
      lifecycle_evidence_state=STEADY_STATE
      ;;
    *) return 1 ;;
  esac

  [ -z "$expected_release_sha" ] || [ "$lifecycle_release_sha" = "$expected_release_sha" ] || return 1
  [ -z "$expected_image_digest" ] || [ "$lifecycle_image_digest" = "$expected_image_digest" ] || return 1

  lifecycle_evidence_file="$rhaomi_lifecycle_state_dir/$lifecycle_evidence_name"
  rhaomi_lifecycle_require_private_file "$lifecycle_evidence_file" || return 1
  actual_evidence_sha=$(openssl dgst -sha256 "$lifecycle_evidence_file" | awk '{print $NF}')
  [ "$actual_evidence_sha" = "$lifecycle_evidence_sha" ] || return 1
  grep -Fq "\"releaseSha\": \"$lifecycle_release_sha\"" "$lifecycle_evidence_file" || return 1
  grep -Fq "\"imageDigest\": \"$lifecycle_image_digest\"" "$lifecycle_evidence_file" || return 1
  grep -Fq "\"state\": \"$lifecycle_evidence_state\"" "$lifecycle_evidence_file" || return 1
}

rhaomi_lifecycle_write_state() {
  new_state=$1
  new_release_sha=$2
  new_image_digest=$3
  new_updated_at=$4
  new_evidence_name=$5

  printf '%s' "$new_release_sha" | grep -Eq '^[0-9a-f]{40}$' || return 1
  printf '%s' "$new_image_digest" | grep -Eq '^sha256:[0-9a-f]{64}$' || return 1
  printf '%s' "$new_updated_at" |
    grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' || return 1

  case "$new_state:$new_evidence_name" in
    FIRST_ACTIVATION_BOOTSTRAPPING:first-activation-bootstrap.json | \
      RECOVERY_ACCEPTANCE_REQUIRED:first-activation-bootstrap.json | \
      RECOVERY_ACCEPTANCE_IN_PROGRESS:first-activation-recovery.json | \
      STEADY_STATE:first-activation-recovery.json) ;;
    *) return 1 ;;
  esac

  new_evidence_file="$rhaomi_lifecycle_state_dir/$new_evidence_name"
  rhaomi_lifecycle_require_private_file "$new_evidence_file" || return 1
  new_evidence_sha=$(openssl dgst -sha256 "$new_evidence_file" | awk '{print $NF}')
  printf '%s' "$new_evidence_sha" | grep -Eq '^[0-9a-f]{64}$' || return 1

  if [ -e "$rhaomi_lifecycle_state_file" ] || [ -L "$rhaomi_lifecycle_state_file" ]; then
    rhaomi_lifecycle_require_private_file "$rhaomi_lifecycle_state_file" || return 1
  fi
  state_temp=$(mktemp "$rhaomi_lifecycle_state_dir/.production-lifecycle.tmp.XXXXXX") || return 1
  chmod 600 "$state_temp" || {
    rm -f "$state_temp"
    return 1
  }
  if ! printf '%s\n' \
    'schemaVersion=1' \
    "state=$new_state" \
    "releaseSha=$new_release_sha" \
    "imageDigest=$new_image_digest" \
    "updatedAt=$new_updated_at" \
    "evidenceFile=$new_evidence_name" \
    "evidenceSha256=$new_evidence_sha" >"$state_temp"; then
    rm -f "$state_temp"
    return 1
  fi
  mv "$state_temp" "$rhaomi_lifecycle_state_file"
}

rhaomi_lifecycle_begin_evidence() {
  evidence_name=$1
  evidence_file="$rhaomi_lifecycle_state_dir/$evidence_name"
  if [ -e "$evidence_file" ] || [ -L "$evidence_file" ]; then
    return 1
  fi
  rhaomi_lifecycle_evidence_temp=$(mktemp "$rhaomi_lifecycle_state_dir/.${evidence_name}.tmp.XXXXXX") ||
    return 1
  chmod 600 "$rhaomi_lifecycle_evidence_temp" || {
    rm -f "$rhaomi_lifecycle_evidence_temp"
    return 1
  }
}

rhaomi_lifecycle_replace_evidence() {
  evidence_name=$1
  evidence_file="$rhaomi_lifecycle_state_dir/$evidence_name"
  rhaomi_lifecycle_require_private_file "$evidence_file" || return 1
  rhaomi_lifecycle_evidence_temp=$(mktemp "$rhaomi_lifecycle_state_dir/.${evidence_name}.tmp.XXXXXX") ||
    return 1
  chmod 600 "$rhaomi_lifecycle_evidence_temp" || {
    rm -f "$rhaomi_lifecycle_evidence_temp"
    return 1
  }
}

rhaomi_lifecycle_commit_evidence() {
  evidence_name=$1
  [ -n "${rhaomi_lifecycle_evidence_temp:-}" ] &&
    [ -f "$rhaomi_lifecycle_evidence_temp" ] &&
    [ ! -L "$rhaomi_lifecycle_evidence_temp" ] || return 1
  mv "$rhaomi_lifecycle_evidence_temp" "$rhaomi_lifecycle_state_dir/$evidence_name" || return 1
  rhaomi_lifecycle_evidence_temp=
}

rhaomi_lifecycle_require_private_file() {
  lifecycle_candidate=$1
  [ -f "$lifecycle_candidate" ] && [ ! -L "$lifecycle_candidate" ] || return 1
  [ "$(rhaomi_lifecycle_file_mode "$lifecycle_candidate")" = 600 ] || return 1
  [ "$(rhaomi_lifecycle_owner_id "$lifecycle_candidate")" = "$(id -u)" ] || return 1
}

rhaomi_lifecycle_file_mode() {
  if lifecycle_mode=$(stat -f '%Lp' "$1" 2>/dev/null); then
    printf '%s\n' "$lifecycle_mode"
  else
    stat -c '%a' "$1"
  fi
}

rhaomi_lifecycle_owner_id() {
  if lifecycle_owner=$(stat -f '%u' "$1" 2>/dev/null); then
    printf '%s\n' "$lifecycle_owner"
  else
    stat -c '%u' "$1"
  fi
}
