// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import me.sandai.dashpower.IDashThermalService;
import me.sandai.dashpower.ThermalServiceContract;

/** Synchronous access for provider Binder threads and the tile's worker only. No cached state. */
final class PerformanceModeClient {
    static final Uri STATE_URI = Uri.parse(IDashThermalService.PERFORMANCE_STATE_URI);

    private static IDashThermalService service() {
        IDashThermalService service = IDashThermalService.Stub.asInterface(
                ServiceManager.checkService(ThermalServiceContract.SERVICE_NAME));
        if (service == null) {
            throw new IllegalStateException("Performance mode backend unavailable");
        }
        return service;
    }

    private static Bundle read(IDashThermalService service, int user) throws RemoteException {
        int version = service.getApiVersion();
        if (version < 2) throw new IllegalStateException("Performance mode backend unavailable");
        return version >= 4 ? service.getPerformanceModeState(user) : service.getState(user);
    }

    static Bundle read() throws RemoteException {
        return read(service(), UserHandle.myUserId());
    }

    static void set(boolean enabled) throws RemoteException {
        IDashThermalService service = service();
        int user = UserHandle.myUserId();
        if (!available(read(service, user))) {
            throw new IllegalStateException("Thermal control unavailable");
        }
        service.setPerformanceMode(user, enabled);
    }

    static boolean available(Bundle state) {
        return state != null && state.getBoolean(ThermalServiceContract.KEY_READY)
                && state.getBoolean(ThermalServiceContract.KEY_ENABLED);
    }

    static boolean isEnabled(Bundle state) {
        return state.getBoolean(ThermalServiceContract.KEY_PERFORMANCE_MODE);
    }

    private PerformanceModeClient() {}
}
