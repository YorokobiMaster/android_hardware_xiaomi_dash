/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashwake;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.hardware.SensorPrivacyManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import me.sandai.dashwake.aov.IDashAovBridge;
import me.sandai.dashwake.aov.IDashAovCallback;

final class GazeWakeController {
    private static final String TAG = "DashWake";
    private static final String GAZE_ENABLED = "dash_gaze_wake_enabled";
    private static final ComponentName AOV_BRIDGE = new ComponentName(
            "me.sandai.dashwake", "me.sandai.dashwake.aov.DashAovBridgeService");

    private final Context mContext;
    private final Handler mHandler;
    private final PowerManager mPowerManager;
    private final SensorPrivacyManager mPrivacyManager;
    private final DisplayManager mDisplayManager;
    private final PocketController mPocketController;
    private AovConnection mAovConnection;

    GazeWakeController(Context context, PocketController pocketController) {
        mContext = context;
        mPocketController = pocketController;
        mHandler = new Handler(context.getMainLooper());
        mPowerManager = context.getSystemService(PowerManager.class);
        mPrivacyManager = context.getSystemService(SensorPrivacyManager.class);
        mDisplayManager = context.getSystemService(DisplayManager.class);
    }

    void start() {
        ContentObserver settingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                update();
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(GAZE_ENABLED),
                false, settingsObserver, UserHandle.USER_ALL);
        mPrivacyManager.addSensorPrivacyListener(SensorPrivacyManager.Sensors.CAMERA,
                (sensor, enabled) -> update());
        mDisplayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {}

            @Override
            public void onDisplayRemoved(int displayId) {}

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY) update();
            }
        }, mHandler);
        update();
    }

    void onScreenChanged() {
        update();
    }

    void onUserSwitched() {
        stopAov();
        update();
    }

    private boolean canDetectGaze() {
        if (mPowerManager.isInteractive() || !WakeSettings.isEnabled(mContext.getContentResolver(),
                GAZE_ENABLED, ActivityManager.getCurrentUser())
                || mPrivacyManager.areAnySensorPrivacyTogglesEnabled(
                        SensorPrivacyManager.Sensors.CAMERA)) {
            return false;
        }
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        int state = display == null ? Display.STATE_UNKNOWN : display.getState();
        // A visible Doze pulse uses STATE_ON while PowerManager remains noninteractive.
        return state == Display.STATE_OFF || state == Display.STATE_ON || state == Display.STATE_DOZE
                || state == Display.STATE_DOZE_SUSPEND;
    }

    private void update() {
        if (canDetectGaze()) {
            if (mAovConnection == null) {
                AovConnection connection = new AovConnection();
                mAovConnection = connection;
                if (!mContext.bindService(new Intent().setComponent(AOV_BRIDGE), connection,
                        Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT)) {
                    mAovConnection = null;
                    Log.e(TAG, "Unable to bind AOV source");
                }
            }
        } else {
            stopAov();
        }
    }

    private void wake(String source) {
        if (mPowerManager.isInteractive()) return;
        if (mPocketController.isInPocket()) {
            Log.i(TAG, "Suppressing wake source=" + source + " while NonUI is active");
            return;
        }
        Log.i(TAG, "Wake source=" + source);
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE,
                "me.sandai.dashwake:" + source);
        update();
    }

    private void stopAov() {
        AovConnection connection = mAovConnection;
        if (connection == null) return;
        mAovConnection = null;
        if (connection.bridge != null) {
            try {
                connection.bridge.stop();
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to stop AOV source", e);
            }
        }
        mContext.unbindService(connection);
    }

    private final class AovConnection implements ServiceConnection {
        IDashAovBridge bridge;
        IDashAovCallback callback;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (mAovConnection != this) return;
            bridge = IDashAovBridge.Stub.asInterface(service);
            if (!canDetectGaze()) {
                stopAov();
                return;
            }
            callback = new IDashAovCallback.Stub() {
                @Override
                public void onPresenceDetected() {
                    mHandler.post(() -> {
                        if (mAovConnection != AovConnection.this || callback != this
                                || !canDetectGaze()) {
                            return;
                        }
                        callback = null;
                        wake("gaze");
                    });
                }
            };
            try {
                bridge.start(callback);
            } catch (RemoteException e) {
                callback = null;
                Log.w(TAG, "Unable to start AOV gaze detection", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (mAovConnection != this) return;
            bridge = null;
            callback = null;
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (mAovConnection != this) return;
            stopAov();
            update();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            if (mAovConnection == this) stopAov();
        }
    }
}
