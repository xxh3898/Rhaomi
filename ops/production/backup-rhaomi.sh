#!/bin/sh

set -eu
PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
export PATH
unset \
  RHAOMI_BACKUP_ROOT \
  RHAOMI_BACKUP_REPOSITORY_ROOT \
  RHAOMI_BACKUP_TEST_SET_ID \
  RHAOMI_BACKUP_VALIDATION_COMPOSE_FILE \
  RHAOMI_PRODUCTION_VALIDATION_ROOT \
  RHAOMI_BACKUP_RESTORE_MEDIA_ROOT \
  RHAOMI_CLEANUP_TASK \
  RHAOMI_CLEANUP_GIT_HEAD

. /private/var/lib/rhaomi/app/bin/backup-rhaomi-core.sh
backup_rhaomi /private/var/lib/rhaomi "$@"
