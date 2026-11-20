/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Arrays;

import me.sandai.dashled.aidl.IDashLedManager;

/** Built-in producer for the short white pulse on each camera countdown tick. */
public final class CameraCountdownReceiver extends BroadcastReceiver {

    public static final String ACTION_CAMERA_COUNTDOWN_TICK =
            "me.sandai.dashled.action.CAMERA_COUNTDOWN_TICK";
    public static final String EXTRA_DURATION_MS = "duration_ms";

    private static final int DEFAULT_DURATION_MS = 300;
    private static final int MAX_DURATION_MS = 1_000;
    private static final int COLOR_WHITE = 0xFFFFFF;
    private static final TimedLedPulse sPulse =
            new TimedLedPulse(IDashLedManager.CATEGORY_CAMERA_COUNTDOWN);

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_CAMERA_COUNTDOWN_TICK.equals(intent.getAction())) {
            return;
        }

        int durationMs = Math.max(1, Math.min(MAX_DURATION_MS,
                intent.getIntExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)));
        int[] colors = new int[Aw21024Backend.ZONE_COUNT];
        Arrays.fill(colors, COLOR_WHITE);
        sPulse.show(context, colors, 255, durationMs, goAsync());
    }
}
