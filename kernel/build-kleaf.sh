#!/bin/bash
# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

if [[ ${1:-} != --workspace || -z ${2:-} ]]; then
    echo "Usage: $0 --workspace PATH [BAZEL_ARGS...]" >&2
    exit 2
fi
cd "$(readlink -f "$2")"
shift 2
export PROJECT=mgk_64_k66 MODE=user DEFCONFIG_OVERLAYS=dash.config
export KERNEL_VERSION=kernel-6.6
export KLEAF_USE_KLEAF_LOCALVERSION=true
export KLEAF_EXTRA_GIT_PROJECTS=kernel-6.6:kernel_device_modules-6.6

# Explicit Make targets still pass build labels and overrides directly.
command=build
if [[ ${1:-} == --run || ${1:-} == --info ]]; then
    command=${1#--}
    shift
fi
exec tools/bazel "$command" --config=stamp \
    --jobs="${JOBS:-8}" --make_jobs="${JOBS:-8}" \
    --//build/kernel/kleaf:kmi_symbol_list_violations_check=false "$@"
