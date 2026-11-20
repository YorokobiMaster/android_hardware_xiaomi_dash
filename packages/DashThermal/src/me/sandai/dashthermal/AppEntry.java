// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

/** One installed application row in the main list. */
final class AppEntry {

    final String packageName;
    final String label;

    int listPosition;
    boolean iconRequested;

    /** Saved override; -1 means automatic. */
    int overrideProfile = -1;
    boolean overrideUnconfirmed;

    /** Effective profile from getAppPolicy(); -1 until loaded. */
    int selectedProfile = -1;

    /** Lazily loaded through getAppPolicy(); null until loaded. */
    String stockGroup;
    boolean policyRequested;
    boolean policySettled;
    int policyRequest;

    int displayProfile() {
        return overrideUnconfirmed ? -1 : overrideProfile != -1 ? overrideProfile : selectedProfile;
    }

    AppEntry(String packageName, String label) {
        this.packageName = packageName;
        this.label = label;
    }
}
