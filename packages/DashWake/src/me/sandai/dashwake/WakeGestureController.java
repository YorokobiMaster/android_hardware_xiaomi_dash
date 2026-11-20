/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashwake;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

/** Process-lifetime wake gestures. Display content and authentication belong to SystemUI. */
final class WakeGestureController implements SensorEventListener {
    private static final String TAG = "DashWake";
    private static final int PICKUP_SENSOR_TYPE = 33171036;

    private final Context mContext;
    private final Handler mHandler;
    private final PowerManager mPowerManager;
    private final SensorManager mSensorManager;
    private final Sensor mPickupSensor;
    private final KeyguardManager mKeyguardManager;
    private final PocketController mPocketController;
    private boolean mPickupRegistered;
    private boolean mWokeByPickup;

    WakeGestureController(Context context, PocketController pocketController) {
        mContext = context;
        mPocketController = pocketController;
        mHandler = new Handler(context.getMainLooper());
        mPowerManager = context.getSystemService(PowerManager.class);
        mSensorManager = context.getSystemService(SensorManager.class);
        mPickupSensor = mSensorManager.getDefaultSensor(PICKUP_SENSOR_TYPE, true);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
    }

    void start() {
        WakeSettings.migrate(mContext.getContentResolver(), ActivityManager.getCurrentUser());
        ContentObserver settingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                update();
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(WakeSettings.PICKUP_ENABLED),
                false, settingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(WakeSettings.PUTDOWN_ENABLED),
                false, settingsObserver, UserHandle.USER_ALL);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())
                        || Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    mWokeByPickup = false;
                } else if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                    mWokeByPickup = false;
                    WakeSettings.migrate(mContext.getContentResolver(),
                            ActivityManager.getCurrentUser());
                }
                update();
            }
        }, filter, Context.RECEIVER_EXPORTED);
        update();
    }

    private boolean canWake() {
        return !mPowerManager.isInteractive();
    }

    private boolean isPickupEnabled() {
        return WakeSettings.isEnabled(mContext.getContentResolver(),
                WakeSettings.PICKUP_ENABLED, ActivityManager.getCurrentUser());
    }

    private boolean isPutDownEnabled() {
        return WakeSettings.isEnabled(mContext.getContentResolver(),
                WakeSettings.PUTDOWN_ENABLED, ActivityManager.getCurrentUser());
    }

    // Stock registration window: listen while the screen is off so a lift can
    // wake, and after a pickup wake keep listening while the keyguard is still
    // up so a put-down can end the session. Unlocking unregisters the sensor.
    private boolean shouldListenPickup() {
        if (canWake()) {
            return isPickupEnabled();
        }
        return mWokeByPickup && isPutDownEnabled() && mKeyguardManager.isKeyguardLocked();
    }

    private void update() {
        boolean pickup = mPickupSensor != null && shouldListenPickup();
        if (pickup && !mPickupRegistered) {
            mPickupRegistered = mSensorManager.registerListener(
                    this, mPickupSensor, SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            Log.i(TAG, "pickup registered=" + mPickupRegistered);
        } else if (!pickup && mPickupRegistered) {
            mSensorManager.unregisterListener(this, mPickupSensor);
            mPickupRegistered = false;
            Log.i(TAG, "pickup registered=false");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!mPickupRegistered || event.sensor != mPickupSensor || event.values.length == 0) {
            return;
        }
        float value = event.values[0];
        if (value == 1.0f && canWake() && isPickupEnabled()) {
            wake("pickup");
        } else if ((value == 2.0f || value == 0.0f) && mWokeByPickup && !canWake()
                && isPutDownEnabled() && mKeyguardManager.isKeyguardLocked()) {
            Log.i(TAG, "Put down after pickup wake; going to sleep");
            mWokeByPickup = false;
            mPowerManager.goToSleep(SystemClock.uptimeMillis());
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void wake(String source) {
        if (!canWake()) return;
        if (mPocketController.isInPocket()) {
            Log.i(TAG, "Suppressing wake source=" + source + " while NonUI is active");
            return;
        }
        Log.i(TAG, "Wake source=" + source);
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE,
                "me.sandai.dashwake:" + source);
        if ("pickup".equals(source)) {
            mWokeByPickup = true;
        }
        update();
    }
}
