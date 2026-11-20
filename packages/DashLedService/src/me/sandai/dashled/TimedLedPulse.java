/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.content.BroadcastReceiver.PendingResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Each broadcast producer replaces only its own pulse. */
final class TimedLedPulse {
    private final int mCategory;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private LedArbiter mArbiter;
    private LedArbiter.Session mSession;
    private PendingResult mPendingResult;
    private Runnable mStop;

    TimedLedPulse(int category) {
        mCategory = category;
    }

    synchronized void show(Context context, int[] colors, int brightness, int durationMs,
            PendingResult pendingResult) {
        finishLocked();

        mArbiter = LedCore.get(context).arbiter();
        mSession = mArbiter.acquire(mCategory);
        mArbiter.setFrame(mSession, colors, brightness);
        mPendingResult = pendingResult;

        LedArbiter.Session session = mSession;
        mStop = () -> finishIfCurrent(session);
        mHandler.postDelayed(mStop, durationMs);
    }

    private synchronized void finishIfCurrent(LedArbiter.Session session) {
        if (mSession == session) {
            finishLocked();
        }
    }

    private void finishLocked() {
        if (mStop != null) {
            mHandler.removeCallbacks(mStop);
            mStop = null;
        }
        if (mSession != null) {
            mArbiter.release(mSession);
            mSession = null;
            mArbiter = null;
        }
        if (mPendingResult != null) {
            mPendingResult.finish();
            mPendingResult = null;
        }
    }
}
