#!/bin/sh

set -eu
umask 077

PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
export PATH
unset CDPATH ENV BASH_ENV

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$script_dir/deploy-rhaomi-core.sh"

deploy_rhaomi /private/var/lib/rhaomi "$@"
