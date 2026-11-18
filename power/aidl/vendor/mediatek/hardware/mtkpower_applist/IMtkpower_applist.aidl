// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package vendor.mediatek.hardware.mtkpower_applist;

// Client-only subset of retained OS3.0.305.0 V3. Method ID 1 is binder transaction 2;
// parameter order and oneway flag verified against its BpMtkpower_applist.
interface IMtkpower_applist {
    oneway void notifyAppStateInfo(String packageName, String activityName, int pid,
            int activityId, int state, int uid) = 1;
}
