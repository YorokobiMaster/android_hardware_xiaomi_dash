/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashwake.aov;

import android.hardware.SensorPrivacyManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.service.attention.AttentionService;
import android.util.Log;

/** AOV-backed implementation of AOSP Screen attention. */
public final class DashAttentionService extends AttentionService {
    private static final String TAG = "DashAttention";
    private static final int CAMERA = SensorPrivacyManager.Sensors.CAMERA;
    private static final int START_ATTEMPTS = 6;
    private static final long START_RETRY_DELAY_MS = 100;

    private HandlerThread mWorkerThread;
    private Handler mWorker;
    private SensorPrivacyManager mPrivacyManager;
    private volatile boolean mDestroyed;
    private Check mCheck;

    @Override
    public void onCreate() {
        super.onCreate();
        mPrivacyManager = getSystemService(SensorPrivacyManager.class);
        mWorkerThread = new HandlerThread(TAG);
        mWorkerThread.start();
        mWorker = new Handler(mWorkerThread.getLooper());
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        mWorker.removeCallbacksAndMessages(null);
        mWorker.runWithScissors(() -> {
            Check check = mCheck;
            mCheck = null;
            if (check != null) check.close();
        }, 2_000);
        mWorkerThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public void onCheckAttention(AttentionCallback callback) {
        mWorker.post(() -> startCheck(callback));
    }

    @Override
    public void onCancelAttentionCheck(AttentionCallback callback) {
        mWorker.post(() -> {
            // AttentionService wraps the Binder callback anew for cancel. The framework
            // serializes check and cancel for its one outstanding request.
            if (mCheck != null) finishCheck(mCheck, ATTENTION_FAILURE_CANCELLED);
        });
    }

    private void startCheck(AttentionCallback callback) {
        if (mDestroyed) return;
        if (mCheck != null) finishCheck(mCheck, ATTENTION_FAILURE_PREEMPTED);
        if (mDestroyed) return;
        Check check = new Check(callback);
        mCheck = check;
        try {
            // Register before querying so a block between the query and START is observed.
            check.listening = true;
            mPrivacyManager.addSensorPrivacyListener(CAMERA, Runnable::run, check.privacyListener);
            if (!check.canUseCamera()) {
                finishCheck(check, ATTENTION_FAILURE_CANCELLED);
            } else if (!check.client.connect()) {
                finishCheck(check, ATTENTION_FAILURE_UNKNOWN);
            } else {
                mWorker.post(check.start);
            }
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Unable to open attention session", e);
            finishCheck(check, ATTENTION_FAILURE_UNKNOWN);
        }
    }

    private void finishCheck(Check check, int error) {
        finishCheck(check, false, error);
    }

    private void finishCheck(Check check, boolean present, int error) {
        if (mCheck != check) return;
        mCheck = null;
        try {
            if (mDestroyed) return;
            if (present && check.canUseCamera()) {
                check.callback.onSuccess(ATTENTION_SUCCESS_PRESENT, System.currentTimeMillis());
                Log.i(TAG, "attention present");
            } else {
                check.callback.onFailure(error);
                Log.i(TAG, "attention failure=" + error);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to report attention result", e);
        } finally {
            // Deliver the result before synchronous HAL cleanup so it can meet the deadline.
            check.close();
        }
    }

    private final class Check implements MtkAovClient.Listener {
        final AttentionCallback callback;
        final MtkAovClient client;
        final Runnable start = this::startAttempt;
        volatile boolean cameraBlocked;
        final SensorPrivacyManager.OnSensorPrivacyChangedListener privacyListener =
                (sensor, enabled) -> {
                    if (enabled) {
                        // Latch on receipt even if a HAL transaction is holding the worker.
                        cameraBlocked = true;
                        mWorker.post(() -> finishCheck(this, ATTENTION_FAILURE_CANCELLED));
                    }
                };

        boolean listening;
        int attempts;

        Check(AttentionCallback callback) {
            this.callback = callback;
            client = new MtkAovClient(mWorker, this, 0);
        }

        boolean canUseCamera() {
            if (cameraBlocked || mDestroyed) return false;
            try {
                return !mPrivacyManager.areAnySensorPrivacyTogglesEnabled(CAMERA)
                        && !cameraBlocked && !mDestroyed;
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to confirm attention camera privacy state", e);
                return false;
            }
        }

        void startAttempt() {
            if (mCheck != this) return;
            if (!canUseCamera()) {
                finishCheck(this, ATTENTION_FAILURE_CANCELLED);
                return;
            }
            try {
                attempts++;
                int result = client.start();
                if (!canUseCamera()) {
                    finishCheck(this, ATTENTION_FAILURE_CANCELLED);
                } else if (result != 0) {
                    if (attempts < START_ATTEMPTS) {
                        mWorker.postDelayed(start, START_RETRY_DELAY_MS);
                    } else {
                        Log.w(TAG, "IAovService start failed: " + result);
                        finishCheck(this, ATTENTION_FAILURE_UNKNOWN);
                    }
                }
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "Unable to start AOV", e);
                finishCheck(this, ATTENTION_FAILURE_UNKNOWN);
            }
        }

        @Override
        public void onPresenceDetected(MtkAovClient source) {
            finishCheck(this, true, ATTENTION_FAILURE_CANCELLED);
        }

        @Override
        public void onServiceDied(MtkAovClient source) {
            finishCheck(this, ATTENTION_FAILURE_UNKNOWN);
        }

        void close() {
            mWorker.removeCallbacks(start);
            try {
                if (listening) mPrivacyManager.removeSensorPrivacyListener(CAMERA, privacyListener);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to remove attention privacy listener", e);
            } finally {
                client.close();
            }
        }
    }
}
