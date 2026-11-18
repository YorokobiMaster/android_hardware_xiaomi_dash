// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package vendor.mediatek.hardware.mtkpower;

// Client-only subset of the retained OS3.0.305.0 V3 interface. Explicit IDs
// verified against its BpMtkPowerService; not a full frozen interface or server.
interface IMtkPowerService {
    int perfCusLockHint(int hint, int durationMs, int pid) = 0;
    int perfLockAcquire(int handle, int durationMs, in int[] resources, int pid, int tid) = 1;
    int perfLockReleaseSync(int handle, int reserved) = 3;
    int perfCusLockHintWithData(int hint, int durationMs, int pid, in int[] resources) = 11;
}
