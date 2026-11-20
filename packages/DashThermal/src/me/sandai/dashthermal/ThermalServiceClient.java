// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.sandai.dashpower.IDashThermalService;
import me.sandai.dashpower.ThermalServiceContract;

/**
 * Async client for the dash_thermal binder service. All binder traffic runs on a
 * single background thread; callbacks are posted to the main thread.
 */
final class ThermalServiceClient {

    private static final long RETRY_DELAY_MS = 3000;

    interface StateCallback {
        /** Called on the main thread. {@code state} is null when unavailable. */
        void onState(IBinder binder, Bundle state);
    }

    interface PolicyCallback {
        void onPolicy(Bundle policy);
        void onError();
    }

    interface OpCallback {
        void onResult(boolean success);
    }

    private final int mUserId = UserHandle.myUserId();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    // Only the executor touches the registered connection. Every death callback
    // carries its own identity, even if a binder is resolved again.
    private Connection mConnection;

    // Lifecycle and callback delivery are confined to the main thread.
    private boolean mStarted;
    private boolean mRetryScheduled;
    private volatile boolean mClosed;
    private int mSession;
    private StateCallback mStateCallback;

    private final class Connection implements IBinder.DeathRecipient {
        final IDashThermalService service;
        final IBinder binder;
        int apiVersion;

        Connection(IBinder binder) {
            this.binder = binder;
            service = IDashThermalService.Stub.asInterface(binder);
        }

        @Override
        public void binderDied() {
            postCallback(() -> {
                final int session = mSession;
                mExecutor.execute(() -> {
                    if (mConnection != this) {
                        return;
                    }
                    clearConnection();
                    publishState(session, null, null);
                });
            });
        }
    }

    private final Runnable mRetryTask = () -> {
        mRetryScheduled = false;
        if (mStarted) {
            refresh();
        }
    };

    void start(StateCallback callback) {
        mStateCallback = callback;
        mStarted = true;
        refresh();
    }

    void stop() {
        mSession++;
        mStarted = false;
        mStateCallback = null;
        cancelRetry();
    }

    void shutdown() {
        mClosed = true;
        stop();
        mExecutor.execute(this::clearConnection);
        mExecutor.shutdown();
    }

    private void clearConnection() {
        if (mConnection == null) {
            return;
        }
        final Connection old = mConnection;
        mConnection = null;
        try {
            old.binder.unlinkToDeath(old, 0);
        } catch (NoSuchElementException e) {
            // Binder already removed the registration on death.
        }
    }

    private Connection connect() throws RemoteException {
        if (mConnection != null && mConnection.binder.isBinderAlive()) {
            return mConnection;
        }
        clearConnection();
        if (mClosed) {
            return null;
        }
        final IBinder binder = ServiceManager.checkService(ThermalServiceContract.SERVICE_NAME);
        if (binder == null || mClosed) {
            return null;
        }
        final Connection connection = new Connection(binder);
        binder.linkToDeath(connection, 0);
        mConnection = connection;
        if (mClosed) {
            clearConnection();
            return null;
        }
        return connection;
    }

    /** Read scheduling settings, retaining compatibility with pre-v4 services. */
    void refresh() {
        if (!mStarted) {
            return;
        }
        final int session = mSession;
        mExecutor.execute(() -> {
            if (mClosed) {
                return;
            }
            try {
                final Connection connection = connect();
                if (connection != null) {
                    if (connection.apiVersion == 0) {
                        connection.apiVersion = connection.service.getApiVersion();
                    }
                    final Bundle state = connection.apiVersion >= 4
                            ? connection.service.getSchedulingState(mUserId)
                            : connection.service.getState(mUserId);
                    if (state != null && state.getBoolean(ThermalServiceContract.KEY_READY)) {
                        publishState(session, connection.binder, state);
                        return;
                    }
                }
            } catch (RemoteException | RuntimeException e) {
                // Report unavailable; a failed v4 read must not masquerade as legacy.
            }
            publishState(session, null, null);
        });
    }

    private void publishState(int session, IBinder binder, Bundle state) {
        postCallback(() -> {
            if (!mStarted || mSession != session) {
                return;
            }
            if (binder == null) {
                scheduleRetry();
            } else {
                cancelRetry();
            }
            mStateCallback.onState(binder, state);
        });
    }

    void getAppPolicy(String packageName, PolicyCallback callback) {
        mExecutor.execute(() -> {
            if (mClosed) {
                return;
            }
            final IDashThermalService service = mConnection == null ? null : mConnection.service;
            if (service == null) {
                postCallback(callback::onError);
                return;
            }
            try {
                final Bundle policy = service.getAppPolicy(mUserId, packageName);
                postCallback(() -> callback.onPolicy(policy));
            } catch (RemoteException | RuntimeException e) {
                postCallback(callback::onError);
            }
        });
    }

    void setAppProfile(String packageName, int profileId, OpCallback callback) {
        mExecutor.execute(() -> {
            final IDashThermalService service = mConnection == null ? null : mConnection.service;
            boolean success = false;
            if (service != null) {
                try {
                    service.setAppProfile(mUserId, packageName, profileId);
                    success = true;
                } catch (RemoteException | RuntimeException e) {
                    success = false;
                }
            }
            final boolean result = success;
            postCallback(() -> callback.onResult(result));
        });
    }

    private void postCallback(Runnable callback) {
        mHandler.post(() -> {
            if (!mClosed) {
                callback.run();
            }
        });
    }

    private void cancelRetry() {
        mHandler.removeCallbacks(mRetryTask);
        mRetryScheduled = false;
    }

    private void scheduleRetry() {
        if (mStarted && !mRetryScheduled) {
            mRetryScheduled = true;
            mHandler.postDelayed(mRetryTask, RETRY_DELAY_MS);
        }
    }
}
