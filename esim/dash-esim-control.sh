#!/system/bin/sh
# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

# init supplies the vendor-internal read-only properties before the exec transition.
export DASH_ESIM_THIN_MD="$1"
export DASH_ESIM_MD_STATE_PATH1="$2"
export DASH_ESIM_MD_STATE_PATH2="$3"
export DASH_ESIM_MD_STATE_READY="$4"
export DASH_ESIM_MD_STATE_READY_KEY="$5"
export DASH_ESIM_MD_FLAG_READY_KEY="$6"
export DASH_ESIM_MD_CTRL_READY_KEY="$7"
export DASH_ESIM_MD_CTRL_PATH1="$8"
export DASH_ESIM_MD_CTRL_PATH2="$9"

request=$(/system/bin/getprop sys.dash.esim.request)
token=${request%%:*}
action=${request#*:}
case "$token" in
    ''|*[!0-9]*) exit 1 ;;
esac

reply() {
    /system/bin/setprop sys.dash.esim.response "$token:$1"
}

# Use the retained ODM hardware identity, not the ROM's marketing model.
case "$(/system/bin/getprop ro.product.odm.model)" in
    2602BPC18G|2602BPC18I) ;;
    *) reply unsupported; exit 1 ;;
esac

case "$action" in
    enable) command=5:1 ;;
    disable) command=5:2 ;;
    *) reply error; exit 1 ;;
esac

# Set the private library path after init's AT_SECURE domain transition.
# Process completion is only an acknowledgement. The app checks the actual
# HAL slot state; an empty-profile response is not itself a switch failure.
if LD_LIBRARY_PATH=/system_ext/lib64/dash-esim \
    /system/bin/timeout 15 /system_ext/bin/dash-esim-modem -c "$command" >/dev/null 2>&1; then
    reply done
else
    reply error
fi
