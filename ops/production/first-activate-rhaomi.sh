#!/bin/sh

set -eu
umask 077

PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
export PATH
unset \
  CDPATH ENV BASH_ENV \
  RHAOMI_FIRST_ACTIVATION_VALIDATION_COMPOSE_FILE \
  RHAOMI_FIRST_ACTIVATION_APP_ROOT \
  RHAOMI_FIRST_ACTIVATION_RECOVERY_ROOT \
  RHAOMI_FIRST_ACTIVATION_RECOVERY_PROJECT \
  RHAOMI_FIRST_ACTIVATION_BACKUP_REPOSITORY \
  RHAOMI_FIRST_ACTIVATION_PROBE_MARKER

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$script_dir/production-lifecycle-core.sh"
. "$script_dir/first-activate-rhaomi-core.sh"

first_activate_rhaomi /private/var/lib/rhaomi "$@"
