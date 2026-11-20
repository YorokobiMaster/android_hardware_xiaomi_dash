/* Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import java.util.StringJoiner;

/** Manual thermal profile ids accepted by the backend. */
final class ThermalProfiles {
    static final int AUTO = -1;

    private enum WritableProfile {
        AUTOMATIC(AUTO, "auto (clears override)"),
        NORMAL(0, "normal"),
        TRANSFER(1),
        EVALUATION(6),
        CLASS0(7, "class0"),
        ARVR(9),
        NAVIGATION(10),
        VIDEO(11, "video"),
        DEMO(12),
        CAMERA(15),
        MGAME(19, "mgame"),
        YUANSHEN(20, "yuanshen"),
        XINGTIE(25, "xingtie");

        final int id;
        final String name;

        WritableProfile(int id) { this(id, null); }

        WritableProfile(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final WritableProfile[] SELECTABLE = WritableProfile.values();

    static boolean selectable(int id) {
        for (WritableProfile profile : SELECTABLE) {
            if (profile.id == id) return true;
        }
        return false;
    }

    static int[] selectableIds() {
        int[] ids = new int[SELECTABLE.length];
        for (int i = 0; i < ids.length; i++) ids[i] = SELECTABLE[i].id;
        return ids;
    }

    static String help() {
        StringJoiner ids = new StringJoiner(", ");
        for (WritableProfile profile : SELECTABLE) {
            ids.add(profile.name == null ? Integer.toString(profile.id)
                    : profile.id + " " + profile.name);
        }
        return ids.toString();
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
