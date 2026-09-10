#!/bin/sh

set -eu
umask 077

PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
export PATH
unset \
  CDPATH ENV BASH_ENV \
  COMPOSE_FILE COMPOSE_ENV_FILES COMPOSE_PROFILES COMPOSE_PROJECT_NAME \
  DOCKER_CONFIG DOCKER_CONTEXT DOCKER_DEFAULT_PLATFORM DOCKER_HOST DOCKER_TLS_VERIFY \
  RHAOMI_INITIAL_ADMIN_EMAIL RHAOMI_INITIAL_ADMIN_PASSWORD \
  RHAOMI_BOOTSTRAP_ADMIN_EMAIL RHAOMI_BOOTSTRAP_ADMIN_PASSWORD

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$script_dir/production-lifecycle-core.sh"
. "$script_dir/provision-initial-admin-rhaomi-core.sh"

provision_initial_admin_rhaomi /private/var/lib/rhaomi "$@"
