/* Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

/** Manual thermal profile ids accepted by the backend. */
final class ThermalProfiles {
    static final int AUTO = -1;

    static boolean selectable(int id) {
        return id == AUTO || id == 0 || id == 1 || id == 6 || id == 7 || id == 9
                || id == 10 || id == 11 || id == 12 || id == 15 || id == 19
                || id == 20 || id == 25;
    }

    static int frameHint(int profile) {
        switch (profile) {
            case 19: case 20: case 25: return AppModePolicy.GAME_HINT;
            case 15: return AppModePolicy.CAMERA_HINT;
            default: return 0;
        }
    }

    static int gpuProfile(int profile) {
        return profile == 6 || profile == 20 || profile == 25 ? profile : 0;
    }

    static int dramFloorOpp(int profile) {
        return profile == 6 || profile == 25 ? GamingPowerPolicy.EXTREME_DRAM_OPP : -1;
    }

    private ThermalProfiles() {}
}
