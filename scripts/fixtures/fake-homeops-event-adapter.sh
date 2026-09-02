#!/bin/sh

set -eu

event_log=${RHAOMI_HOMEOPS_TEST_LOG:?}
event_outcome=${RHAOMI_HOMEOPS_TEST_OUTCOME:-RETAINED}

case "${1:-}:$#:${2:-}" in
  deployment:7:RUNNING | deployment:7:SUCCESS | deployment:7:FAILED) ;;
  backup:6:RUNNING | backup:6:SUCCESS | backup:6:FAILED | backup:6:INCOMPLETE) ;;
  *) exit 1 ;;
esac

printf '%s\n' "$*" >>"$event_log"

case "$event_outcome" in
  RETAINED | NOT_CONFIGURED)
    printf '%s\n' "$event_outcome"
    ;;
  FAILED)
    printf '%s\n' FAILED
    exit 1
    ;;
  *) exit 1 ;;
esac
