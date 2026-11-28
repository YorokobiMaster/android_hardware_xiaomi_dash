/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.chargingspeed.ui;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_GROUP_KEY;

import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.android.settingslib.drawer.DynamicSummary;
import com.android.settingslib.drawer.EntriesProvider;
import com.android.settingslib.drawer.EntryController;
import com.android.settingslib.drawer.ProviderSwitch;

import java.util.List;

/** Supplies the charging speed switch in Settings > Battery. */
public abstract class ChargingSpeedSwitch extends EntriesProvider {
    private static final String TAG = "ChargingSpeedSettings";
    // Values in the frozen vendor.lineage.health.FastChargeMode contract.
    private static final int BALANCED = 2;
    private static final int TOP_SPEED = 4;

    protected abstract int getMode() throws RemoteException;
    protected abstract boolean setMode(int mode) throws RemoteException;

    protected boolean isSupported() {
        return true;
    }

    @Override
    protected List<? extends EntryController> createEntryControllers() {
        return List.of(new ChargingSpeedController());
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        // ContentProvider does not enforce individual call permissions.
        getContext().enforceCallingOrSelfPermission(WRITE_SECURE_SETTINGS, null);
        // Settings asks for entry data when discovering tiles. Recheck the native
        // feature here, not once at process startup before services are ready.
        if (METHOD_GET_ENTRY_DATA.equals(method) || METHOD_GET_SWITCH_DATA.equals(method)) {
            try {
                if (!isSupported()) return null;
            } catch (RuntimeException e) {
                Log.w(TAG, "Charging speed is unavailable", e);
                return null;
            }
        }
        return super.call(method, arg, extras);
    }

    private Integer readMode() {
        try {
            int mode = getMode();
            if (mode == BALANCED || mode == TOP_SPEED) return mode;
            Log.w(TAG, "Unexpected charging mode: " + mode);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Couldn’t read charging mode", e);
        }
        return null;
    }

    private final class ChargingSpeedController extends EntryController
            implements ProviderSwitch, DynamicSummary {
        @Override
        public String getKey() {
            return "dash_top_speed_charging";
        }

        @Override
        protected MetaData getMetaData() {
            return new MetaData("com.android.settings.category.ia.battery") {
                @Override
                protected Bundle build() {
                    Bundle bundle = super.build();
                    bundle.putString(META_DATA_PREFERENCE_GROUP_KEY, "charging_category");
                    return bundle;
                }
            }.setOrder(-12).setTitle(R.string.charging_speed_title)
                    .setSummary(R.string.charging_speed_summary);
        }

        @Override
        public boolean isSwitchChecked() {
            Integer mode = readMode();
            return mode != null && mode == TOP_SPEED;
        }

        @Override
        public String getDynamicSummary() {
            return getContext().getString(readMode() != null
                    ? R.string.charging_speed_summary : R.string.charging_speed_unavailable);
        }

        @Override
        public boolean onSwitchCheckedChanged(boolean checked) {
            int requested = checked ? TOP_SPEED : BALANCED;
            try {
                if (isSupported() && setMode(requested)) return true;
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "Couldn’t change charging mode", e);
            }
            notifySummaryChanged(getContext());
            return false;
        }

        @Override
        public String getSwitchErrorMessage(boolean attemptedChecked) {
            return getContext().getString(R.string.charging_speed_error);
        }
    }
}
