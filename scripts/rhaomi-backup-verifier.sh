#!/bin/sh

set -eu
umask 077

[ "$#" -eq 2 ] || {
  printf '%s\n' BACKUP_VERIFIER_INPUT_INVALID >&2
  exit 64
}

case "$1" in
  verify-eligibility)
    printf '%s' "$2" | grep -Eq '^[0-9a-f]{40}$' || {
      printf '%s\n' BACKUP_VERIFIER_INPUT_INVALID >&2
      exit 64
    }
    ;;
  verify-backup-set)
    printf '%s' "$2" | grep -Eq '^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$' || {
      printf '%s\n' BACKUP_VERIFIER_INPUT_INVALID >&2
      exit 64
    }
    set -- verify "$2" full-read
    ;;
  *)
    printf '%s\n' BACKUP_VERIFIER_INPUT_INVALID >&2
    exit 64
    ;;
esac

exec /usr/local/bin/node \
  /opt/rhaomi/source/scripts/rhaomi-backup-tool.mjs \
  "$@"
