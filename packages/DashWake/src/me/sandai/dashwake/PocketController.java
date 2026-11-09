/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashwake;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.util.Log;

/** Bridges the vendor NonUI sensor to the touchscreen's suspend gesture policy. */
final class PocketController implements SensorEventListener {
    private static final String TAG = "DashPocket";
    private static final int NONUI_SENSOR_TYPE = 33171027;
    private static final int SENSOR_PERIOD_US = 20000;
    private static final long RETRY_DELAY_MS = 1000;
    private static final int MAX_RETRIES = 30;

    private final SensorManager mSensorManager;
    private final Handler mHandler;
    private Sensor mSensor;
    private boolean mRegistered;
    private int mNonUiMode;
    private int mGeneration;
    private volatile boolean mInPocket;

    PocketController(Context context, Handler handler) {
        mSensorManager = context.getSystemService(SensorManager.class);
        mHandler = handler;
    }

    void syncTouchMode(String reason) {
        mHandler.post(() -> {
            if (!mRegistered) {
                mSensor = mSensorManager.getDefaultSensor(NONUI_SENSOR_TYPE, true);
                if (mSensor != null) {
                    mRegistered = mSensorManager.registerListener(
                            this, mSensor, SENSOR_PERIOD_US, mHandler);
                }
                Log.i(TAG, "NonUI registered=" + mRegistered);
            }
            // Also clears a stale driver request after a process restart, before
            // the first sensor event. The sensor remains subscribed across sleep.
            applyMode(reason, ++mGeneration, MAX_RETRIES);
        });
    }

    boolean isInPocket() {
        return mInPocket;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!mRegistered || event.sensor != mSensor || event.values.length == 0) return;
        float value = event.values[0];
        if (value != 0.0f && value != 1.0f && value != 2.0f) {
            Log.w(TAG, "Ignoring unsupported NonUI value=" + value);
            return;
        }
        int mode = (int) value;
        if (mode == mNonUiMode) return;
        mNonUiMode = mode;
        mInPocket = mode != 0;
        // Preserve the vendor states: the dash driver treats >0 as tap
        // suppression and 2 as FOD suppression. Do not overwrite gesture settings.
        applyMode("sensor", ++mGeneration, MAX_RETRIES);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void applyMode(String reason, int generation, int retriesRemaining) {
        if (generation != mGeneration) return;
        if (nativeSetNonUiMode(mNonUiMode)) {
            int current = nativeGetNonUiMode();
            if (current == mNonUiMode) {
                Log.i(TAG, "reason=" + reason + " nonui=" + mNonUiMode
                        + " kernel=" + current);
                return;
            }
            Log.w(TAG, "NonUI readback=" + current + " requested=" + mNonUiMode);
        }
        if (retriesRemaining == 0) {
            Log.e(TAG, "Giving up NonUI synchronization for reason=" + reason);
            return;
        }
        mHandler.postDelayed(() -> applyMode(reason, generation, retriesRemaining - 1),
                RETRY_DELAY_MS);
    }

    private static native boolean nativeSetNonUiMode(int mode);
    private static native int nativeGetNonUiMode();
}
