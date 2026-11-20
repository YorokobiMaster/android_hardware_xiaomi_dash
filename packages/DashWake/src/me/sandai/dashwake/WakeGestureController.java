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
    private int mPickupUser = UserHandle.USER_NULL;

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
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int userId = ActivityManager.getCurrentUser();
                switch (intent.getAction()) {
                    case Intent.ACTION_USER_PRESENT:
                        if (getSendingUserId() != userId) return;
                        mPickupUser = UserHandle.USER_NULL;
                        break;
                    case Intent.ACTION_SCREEN_OFF:
                        mPickupUser = UserHandle.USER_NULL;
                        break;
                    case Intent.ACTION_USER_SWITCHED:
                        mPickupUser = UserHandle.USER_NULL;
                        WakeSettings.migrate(mContext.getContentResolver(), userId);
                        break;
                    case Intent.ACTION_USER_UNLOCKED:
                        if (intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL)
                                != userId) return;
                        WakeSettings.migrate(mContext.getContentResolver(), userId);
                        break;
                    default:
                        break;
                }
                update();
            }
        }, UserHandle.ALL, filter, null, mHandler, Context.RECEIVER_EXPORTED);
        update();
    }

    private boolean canWake() {
        return !mPowerManager.isInteractive();
    }

    private boolean isPickupEnabled(int userId) {
        return WakeSettings.isEnabled(mContext.getContentResolver(),
                WakeSettings.PICKUP_ENABLED, userId);
    }

    private boolean isPutDownEnabled(int userId) {
        return WakeSettings.isEnabled(mContext.getContentResolver(),
                WakeSettings.PUTDOWN_ENABLED, userId);
    }

    // Stock registration window: listen while the screen is off so a lift can
    // wake, and after a pickup wake keep listening while the keyguard is still
    // up so a put-down can end the session. Unlocking unregisters the sensor.
    private boolean shouldListenPickup() {
        final int userId = ActivityManager.getCurrentUser();
        if (canWake()) {
            return isPickupEnabled(userId);
        }
        return mPickupUser == userId && isPutDownEnabled(userId)
                && mKeyguardManager.isKeyguardLocked();
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
        final int userId = ActivityManager.getCurrentUser();
        float value = event.values[0];
        if (value == 1.0f && canWake() && isPickupEnabled(userId)) {
            wakeFromPickup(userId);
        } else if ((value == 2.0f || value == 0.0f) && mPickupUser == userId && !canWake()
                && isPutDownEnabled(userId) && mKeyguardManager.isKeyguardLocked()) {
            Log.i(TAG, "Put down after pickup wake; going to sleep");
            mPickupUser = UserHandle.USER_NULL;
            mPowerManager.goToSleep(SystemClock.uptimeMillis());
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void wakeFromPickup(int userId) {
        if (!canWake()) return;
        if (mPocketController.isInPocket()) {
            Log.i(TAG, "Suppressing wake source=pickup while NonUI is active");
            return;
        }
        Log.i(TAG, "Wake source=pickup");
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE,
                "me.sandai.dashwake:pickup");
        mPickupUser = userId;
        update();
    }
}
