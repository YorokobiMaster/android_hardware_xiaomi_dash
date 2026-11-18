/* Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Slog;

import vendor.mediatek.hardware.mtkpower.IMtkPowerService;

/** All calls run on the adapter worker. Old handles never cross a HAL restart. */
final class MtkPowerClient implements PowerPolicy.Transport {
    private static final String NAME =
            "vendor.mediatek.hardware.mtkpower.IMtkPowerService/default";
    private final PowerHandleRegistry<IMtkPowerService> leases = new PowerHandleRegistry<>();
    private final Handler worker;
    private final Runnable death;
    private IMtkPowerService service;

    MtkPowerClient(Handler worker, Runnable death) {
        this.worker = worker;
        this.death = death;
    }

    @Override
    public int acquire(int hint, int durationMs) throws RemoteException {
        requireDuration(durationMs);
        final IMtkPowerService owner = connect();
        final long expires = SystemClock.uptimeMillis() + durationMs;
        final int handle = owner.perfCusLockHint(hint, durationMs, Process.myPid());
        final int token = remember(owner, handle, expires);
        Slog.d("DashPower", "acquire hint=" + hint + " duration=" + durationMs
                + " handle=" + handle + " token=" + token);
        return token;
    }

    /** Resource/value pairs use the retained vendor's PERF_RES_* contract; duration is finite. */
    int acquireResources(int durationMs, int[] resources) throws RemoteException {
        requireDuration(durationMs);
        if (resources == null || resources.length == 0 || (resources.length & 1) != 0) {
            throw new IllegalArgumentException("Resource/value pairs required");
        }
        final int[] request = resources.clone();
        final IMtkPowerService owner = connect();
        final long expires = SystemClock.uptimeMillis() + durationMs;
        final int handle = owner.perfLockAcquire(0, durationMs, request,
                Process.myPid(), Process.myTid());
        final int token = remember(owner, handle, expires);
        Slog.d("DashPower", "acquire resources=" + (request.length / 2) + " duration="
                + durationMs + " handle=" + handle + " token=" + token);
        return token;
    }

    int acquireAppMode(int hint, int appPid, int durationMs) throws RemoteException {
        requireDuration(durationMs);
        final int[] request = AppModePolicy.resources(hint, appPid);
        final IMtkPowerService owner = connect();
        final long expires = SystemClock.uptimeMillis() + durationMs;
        final int handle = owner.perfCusLockHintWithData(
                hint, durationMs, Process.myPid(), request);
        final int token = remember(owner, handle, expires);
        Slog.d("DashPower", "acquire app mode=" + hint + " pid=" + appPid + " duration=" + durationMs
                + " handle=" + handle + " token=" + token);
        return token;
    }

    private static void requireDuration(int durationMs) {
        if (durationMs <= 0) throw new IllegalArgumentException("Positive duration in ms required");
    }

    // libpowerhal pwalltime uses CLOCK_MONOTONIC; match it across device suspend.
    private int remember(IMtkPowerService owner, int handle, long expires) {
        final int token = leases.add(owner, handle, expires);
        if (token > 0) {
            worker.postDelayed(() -> leases.forget(token),
                    Math.max(0, expires - SystemClock.uptimeMillis()));
        }
        return token;
    }

    private IMtkPowerService connect() throws RemoteException {
        if (service != null && !service.asBinder().isBinderAlive()) {
            disconnected(service);
            throw new RemoteException("MTK power restarted; discard this event");
        }
        if (service == null) {
            final IBinder binder = ServiceManager.checkService(NAME);
            if (binder == null) throw new RemoteException("MTK power service unavailable");
            final IMtkPowerService owner = IMtkPowerService.Stub.asInterface(binder);
            binder.linkToDeath(() -> worker.post(() -> disconnected(owner)), 0);
            service = owner;
        }
        return service;
    }

    private void disconnected(IMtkPowerService owner) {
        leases.disconnected(owner);
        if (service == owner) {
            service = null;
            death.run();
        }
    }

    @Override
    public void release(int token) throws RemoteException {
        releaseResources(token);
    }

    /** Whether the owner confirmed release; false can mean a locally expired handle. */
    boolean releaseResources(int token) throws RemoteException {
        final PowerHandleRegistry.Lease<IMtkPowerService> lease =
                leases.take(token, SystemClock.uptimeMillis());
        if (lease != null && lease.owner.asBinder().isBinderAlive()) {
            final int result = lease.owner.perfLockReleaseSync(lease.handle, 0);
            Slog.d("DashPower", "release handle=" + lease.handle + " token=" + token
                    + " result=" + result);
            if (result < 0) throw new RemoteException("MTK power release failed: " + result);
            return true;
        }
        return false;
    }
}
