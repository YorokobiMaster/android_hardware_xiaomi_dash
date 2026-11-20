// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/** In-process cache of application icons, filled on background threads. */
final class IconCache {

    private static final Map<String, Drawable.ConstantState> CACHE = new HashMap<>();
    private static Drawable.ConstantState sDefaultIcon;
    private static Configuration sConfiguration;

    private IconCache() {
    }

    static synchronized Drawable get(Context context, String packageName) {
        final Configuration configuration = context.getResources().getConfiguration();
        if (!configuration.equals(sConfiguration)) {
            CACHE.clear();
            sDefaultIcon = null;
            sConfiguration = new Configuration(configuration);
        }
        final Drawable.ConstantState state = CACHE.get(packageName);
        return state == null ? null : state.newDrawable(context.getResources());
    }

    static synchronized void invalidate(String packageName) {
        CACHE.remove(packageName);
    }

    static synchronized void clear() {
        CACHE.clear();
        sDefaultIcon = null;
    }

    static synchronized void put(String packageName, Drawable icon) {
        if (icon != null) {
            CACHE.put(packageName, icon.getConstantState());
        }
    }

    static synchronized Drawable getDefault(Context context) {
        if (sDefaultIcon == null) {
            sDefaultIcon = context.getPackageManager().getDefaultActivityIcon().getConstantState();
        }
        return sDefaultIcon.newDrawable(context.getResources());
    }
}
