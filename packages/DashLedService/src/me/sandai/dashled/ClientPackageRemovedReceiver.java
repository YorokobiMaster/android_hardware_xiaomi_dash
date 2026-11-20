/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** A control grant belongs to the current installation in this user. */
public final class ClientPackageRemovedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())
                || intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            return;
        }
        Uri data = intent.getData();
        if (data == null || !"package".equals(data.getScheme())) {
            return;
        }
        String packageName = data.getSchemeSpecificPart();
        if (DashLedPrefs.isClientPackageAllowed(context, packageName)) {
            DashLedPrefs.setClientPackageAllowed(context, packageName, false);
        }
    }
}
