#!/bin/sh

set -eu
umask 077

[ "$#" -eq 2 ] || {
  printf '%s\n' BACKUP_VERIFIER_INPUT_INVALID >&2
  exit 64
}
[ "$1" = verify-eligibility ] || {
  printf '%s\n' BACKUP_VERIFIER_INPUT_INVALID >&2
  exit 64
}
printf '%s' "$2" | grep -Eq '^[0-9a-f]{40}$' || {
  printf '%s\n' BACKUP_VERIFIER_INPUT_INVALID >&2
  exit 64
}

exec /usr/local/bin/node \
  /opt/rhaomi/source/scripts/rhaomi-backup-tool.mjs \
  verify-eligibility "$2"
