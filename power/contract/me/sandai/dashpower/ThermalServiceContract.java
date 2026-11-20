// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashpower;

/** Service name and Binder response keys shared by the backend and its clients. */
public final class ThermalServiceContract {
    public static final String SERVICE_NAME = "dash_thermal";

    // Shared settings fields in getState() and the compact reads.
    public static final String KEY_READY = "ready";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_PERFORMANCE_MODE = "performanceMode";
    public static final String KEY_ERROR = "error";
    public static final String KEY_OVERRIDES = "overrides";

    // getAppPolicy()
    public static final String KEY_OVERRIDE_PROFILE = "overrideProfile";
    public static final String KEY_SELECTED_PROFILE = "selectedProfile";
    public static final String KEY_STOCK_GROUP = "stockGroup";

    private ThermalServiceContract() {}
}
