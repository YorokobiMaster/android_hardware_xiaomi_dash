/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashcharging;

import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_GROUP_KEY;

import android.os.Bundle;

import com.android.settingslib.drawer.DynamicSummary;
import com.android.settingslib.drawer.EntriesProvider;
import com.android.settingslib.drawer.EntryController;
import com.android.settingslib.drawer.ProviderSwitch;

import java.util.Arrays;
import java.util.List;

/** Supplies the inline charging switches on Settings > Battery. */
public final class BypassChargingSettingsProvider extends EntriesProvider {
    @Override
    protected List<? extends EntryController> createEntryControllers() {
        return Arrays.asList(new BypassChargingController(), new ReverseChargingController());
    }

    private abstract static class ChargingSwitchController extends EntryController
            implements ProviderSwitch {
        final MetaData chargingEntry(int order, int title, int summary) {
            return new MetaData("com.android.settings.category.ia.battery") {
                @Override
                protected Bundle build() {
                    Bundle bundle = super.build();
                    bundle.putString(META_DATA_PREFERENCE_GROUP_KEY, "charging_category");
                    return bundle;
                }
            }.setOrder(order).setTitle(title).setSummary(summary);
        }
    }

    private final class BypassChargingController extends ChargingSwitchController {
        @Override
        public String getKey() {
            return BypassChargingSettings.ENABLED;
        }

        @Override
        protected MetaData getMetaData() {
            return chargingEntry(-10, R.string.bypass_charging_title,
                    R.string.bypass_charging_summary);
        }

        @Override
        public boolean isSwitchChecked() {
            return BypassChargingSettings.isEnabled(getContext());
        }

        @Override
        public boolean onSwitchCheckedChanged(boolean checked) {
            return BypassChargingSettings.setEnabled(getContext(), checked);
        }

        @Override
        public String getSwitchErrorMessage(boolean attemptedChecked) {
            return getContext().getString(R.string.bypass_charging_error);
        }
    }

    private final class ReverseChargingController extends ChargingSwitchController
            implements DynamicSummary {
        @Override
        public String getKey() {
            return ReverseChargingSettings.KEY;
        }

        @Override
        protected MetaData getMetaData() {
            return chargingEntry(-11, R.string.fast_reverse_charging_title,
                    R.string.fast_reverse_charging_summary);
        }

        @Override
        public String getDynamicSummary() {
            return getContext().getString(ReverseChargingSettings.isSupplyingDevice()
                    ? R.string.fast_reverse_charging_summary
                    : R.string.fast_reverse_charging_unavailable);
        }

        @Override
        public boolean isSwitchChecked() {
            return ReverseChargingSettings.isEnabled();
        }

        @Override
        public boolean onSwitchCheckedChanged(boolean checked) {
            return ReverseChargingSettings.setEnabled(checked);
        }

        @Override
        public String getSwitchErrorMessage(boolean attemptedChecked) {
            if (attemptedChecked && !ReverseChargingSettings.isSupplyingDevice()) {
                return getContext().getString(R.string.fast_reverse_charging_unavailable);
            }
            return getContext().getString(R.string.fast_reverse_charging_error);
        }
    }
}
