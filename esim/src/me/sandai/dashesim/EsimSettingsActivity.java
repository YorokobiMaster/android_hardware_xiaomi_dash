// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0

package me.sandai.dashesim;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.telephony.UiccSlotInfo;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

public final class EsimSettingsActivity extends CollapsingToolbarBaseActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!EsimController.isSupported(this)) {
            finish();
            return;
        }
        if (state == null) {
            getSupportFragmentManager().beginTransaction().replace(
                    com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                    new EsimFragment()).commit();
        }
    }

    public static final class EsimFragment extends SettingsBasePreferenceFragment {
        private static final String LPA_PACKAGE = "im.angry.openeuicc";

        private MainSwitchPreference mSwitch;
        private Preference mManage;
        private Preference mStatus;
        private boolean mBusy;
        private final BroadcastReceiver mSlotReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!mBusy) refresh();
            }
        };

        @Override
        public void onCreatePreferences(Bundle state, String key) {
            setPreferencesFromResource(R.xml.esim_settings, key);
            mSwitch = findPreference("enabled");
            mManage = findPreference("manage");
            mStatus = findPreference("status");
            mSwitch.setOnPreferenceChangeListener((preference, value) -> {
                requestSwitch((Boolean) value);
                return false; // Only reflect the new state after the modem confirms it.
            });
            mManage.setOnPreferenceClickListener(preference -> {
                openLpa();
                return true;
            });
            findPreference("refresh").setOnPreferenceClickListener(preference -> {
                if (!mBusy) refresh();
                return true;
            });
        }

        @Override
        public void onResume() {
            super.onResume();
            requireContext().registerReceiver(mSlotReceiver,
                    new IntentFilter(TelephonyManager.ACTION_SIM_SLOT_STATUS_CHANGED),
                    Context.RECEIVER_EXPORTED);
            if (!mBusy) refresh();
        }

        @Override
        public void onPause() {
            requireContext().unregisterReceiver(mSlotReceiver);
            super.onPause();
        }

        private UiccSlotInfo readSlot() {
            try {
                return EsimController.getSlot(requireContext());
            } catch (RuntimeException e) {
                Log.e("DashEsim", "Cannot read modem slot state", e);
                return null;
            }
        }

        private void refresh() {
            UiccSlotInfo slot = readSlot();
            boolean enabled = slot != null && slot.getIsEuicc();
            mSwitch.setEnabled(slot != null);
            if (slot != null) mSwitch.setChecked(enabled);
            mManage.setEnabled(enabled);
            mStatus.setSummary(slot == null ? R.string.state_unavailable
                    : enabled ? R.string.state_enabled : R.string.state_disabled);
        }

        private void requestSwitch(boolean enabled) {
            if (mBusy) return;
            UiccSlotInfo slot = readSlot();
            if (slot == null || slot.getIsEuicc() == enabled) {
                refresh();
                return;
            }
            if (enabled && EsimController.hasPhysicalSim(slot)) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.disconnect_sim2_title)
                        .setMessage(R.string.disconnect_sim2_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.switch_to_esim,
                                (dialog, which) -> switchMode(true, true)).show();
                return;
            }
            switchMode(enabled, false);
        }

        private void openLpa() {
            if (mBusy) return;
            UiccSlotInfo slot = readSlot();
            if (slot == null || !slot.getIsEuicc()) {
                refresh();
                return;
            }
            try {
                // Use OpenEUICC's public launcher, not the system-only LUI.
                Intent launcher = requireContext().getPackageManager()
                        .getLaunchIntentForPackage(LPA_PACKAGE);
                if (launcher != null) {
                    startActivity(launcher);
                    return;
                }
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w("DashEsim", "Cannot launch OpenEUICC", e);
            }
            Toast.makeText(requireContext(), R.string.lpa_unavailable, Toast.LENGTH_LONG).show();
        }

        private void switchMode(boolean enabled, boolean disconnectSim2) {
            mBusy = true;
            mSwitch.setEnabled(false);
            mManage.setEnabled(false);
            mStatus.setSummary(R.string.switching);
            Context context = requireContext().getApplicationContext();
            EsimController.WORKER.execute(() -> {
                int result;
                try {
                    EsimController.setEnabled(context, enabled, disconnectSim2);
                    result = 0;
                } catch (EsimController.ActiveProfileException e) {
                    result = R.string.active_message;
                } catch (Exception e) {
                    Log.e("DashEsim", "Modem switch failed", e);
                    result = R.string.switch_failed;
                }
                final int error = result;
                android.app.Activity activity = getActivity();
                if (activity == null) return;
                activity.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    mBusy = false;
                    refresh();
                    if (error == R.string.active_message) {
                        new AlertDialog.Builder(requireContext())
                                .setTitle(R.string.active_title)
                                .setMessage(error)
                                .setPositiveButton(android.R.string.ok, null).show();
                    } else if (error != 0) {
                        Toast.makeText(requireContext(), R.string.switch_failed, Toast.LENGTH_LONG).show();
                    }
                });
            });
        }
    }
}
