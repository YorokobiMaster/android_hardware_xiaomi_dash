/* Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import android.content.ComponentName;
import android.os.Handler;
import android.os.IBinder;
import android.os.IServiceCallback;
import android.os.ServiceManager;
import android.util.Slog;

import vendor.mediatek.hardware.mtkpower_applist.IMtkpower_applist;

/** Binder calls and lifecycle replay stay on DashThermal, outside the WM lock. */
final class MtkAppListClient {
    private static final String NAME =
            "vendor.mediatek.hardware.mtkpower_applist.IMtkpower_applist/default";
    private final Handler worker;
    private final Runnable activitiesChanged;
    private final AppStatePolicy policy = new AppStatePolicy();
    private IBinder binder;
    private IBinder.DeathRecipient death;
    private volatile boolean connected;
    private volatile int resumedCount;
    private volatile String error = "not started";

    MtkAppListClient(Handler worker, Runnable activitiesChanged) {
        this.worker = worker;
        this.activitiesChanged = activitiesChanged;
    }

    void start() {
        try {
            ServiceManager.registerForNotifications(NAME, new IServiceCallback.Stub() {
                @Override public void onRegistration(String name, IBinder service) {
                    worker.post(() -> connect(service));
                }
            });
            error = "waiting for MTK applist";
        } catch (Exception e) {
            failed(e);
        }
    }

    void changed(ComponentName component, int pid, int uid, int activityId, boolean resumed) {
        // This ingress is called under the WM lock: only immutable values cross to the worker.
        worker.post(() -> {
            try {
                policy.update(new AppStatePolicy.Activity(component.getPackageName(),
                        component.getClassName(), pid, uid, activityId), resumed);
            } catch (Exception e) {
                failed(e);
            } finally {
                resumedCount = policy.resumedCount();
                activitiesChanged.run();
            }
        });
    }

    private void connect(IBinder service) {
        if (service == binder) return;
        if (binder != null) binder.unlinkToDeath(death, 0);
        binder = service;
        death = () -> worker.post(() -> {
            if (binder != service) return;
            binder = null;
            connected = false;
            try {
                policy.connected(null);
            } catch (Exception e) {
                failed(e);
            }
            error = "MTK applist died; waiting for registration";
        });
        try {
            service.linkToDeath(death, 0);
            IMtkpower_applist target = IMtkpower_applist.Stub.asInterface(service);
            policy.connected((activity, resumed) -> target.notifyAppStateInfo(
                    activity.packageName, activity.activityName, activity.pid,
                    activity.activityId, resumed ? 1 : 0, activity.uid));
            connected = true;
            error = "";
        } catch (Exception e) {
            failed(e);
        }
    }

    private void failed(Exception e) {
        connected = false;
        error = e.toString();
        Slog.e("DashThermal", "MTK application state notification failed", e);
    }

    // Called only on the thermal worker, like policy.update().
    int resumedPid(ComponentName component, int uid) {
        return component == null ? 0 : policy.resumedPid(
                component.getPackageName(), component.getClassName(), uid);
    }

    boolean isConnected() { return connected; }
    int resumedCount() { return resumedCount; }
    String error() { return error; }
}
