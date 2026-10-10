/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;

import me.sandai.dashled.aidl.IDashLedManager;

/** Signature-protected, producer-neutral entry point for a short ring pulse. */
public final class LedPulseReceiver extends BroadcastReceiver {

    public static final String ACTION_PULSE = "me.sandai.dashled.action.PULSE";
    public static final String EXTRA_COLOR = "color";
    public static final String EXTRA_COLORS = "colors";
    public static final String EXTRA_BRIGHTNESS = "brightness";
    public static final String EXTRA_DURATION_MS = "duration_ms";

    private static final int DEFAULT_COLOR = 0xFFFFFF;
    private static final int DEFAULT_BRIGHTNESS = 255;
    private static final int DEFAULT_DURATION_MS = 300;
    private static final int MAX_DURATION_MS = 10_000;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static LedArbiter sArbiter;
    private static LedArbiter.Session sSession;
    private static Runnable sStop;
    private static PendingResult sPendingResult;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_PULSE.equals(intent.getAction())) {
            return;
        }

        int durationMs = clamp(intent.getIntExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS),
                1, MAX_DURATION_MS);
        int brightness = clamp(intent.getIntExtra(EXTRA_BRIGHTNESS, DEFAULT_BRIGHTNESS),
                0, 255);
        int[] colors = intent.getIntArrayExtra(EXTRA_COLORS);
        if (colors == null || colors.length != Aw21024Backend.ZONE_COUNT) {
            colors = new int[Aw21024Backend.ZONE_COUNT];
            Arrays.fill(colors, intent.getIntExtra(EXTRA_COLOR, DEFAULT_COLOR) & 0xFFFFFF);
        } else {
            colors = colors.clone();
            for (int i = 0; i < colors.length; i++) {
                colors[i] &= 0xFFFFFF;
            }
        }

        PendingResult pendingResult = goAsync();
        synchronized (LedPulseReceiver.class) {
            finishPulseLocked();

            sArbiter = LedCore.get(context).arbiter();
            sSession = sArbiter.acquire(IDashLedManager.CATEGORY_THIRD_PARTY);
            sArbiter.setFrame(sSession, colors, brightness);
            sPendingResult = pendingResult;

            LedArbiter.Session session = sSession;
            sStop = () -> {
                synchronized (LedPulseReceiver.class) {
                    if (sSession == session) {
                        finishPulseLocked();
                    }
                }
            };
            sHandler.postDelayed(sStop, durationMs);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void finishPulseLocked() {
        if (sStop != null) {
            sHandler.removeCallbacks(sStop);
            sStop = null;
        }
        if (sSession != null) {
            sArbiter.release(sSession);
            sSession = null;
            sArbiter = null;
        }
        if (sPendingResult != null) {
            sPendingResult.finish();
            sPendingResult = null;
        }
    }
}
