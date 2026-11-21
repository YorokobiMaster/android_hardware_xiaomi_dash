/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class LedNotificationListener extends NotificationListenerService {

    private final Set<String> mActiveKeys = new HashSet<>();

    @Override
    public void onListenerConnected() {
        mActiveKeys.clear();
        // Seed the listener's visible notifications without playing anything already active.
        Collections.addAll(mActiveKeys, getCurrentRanking().getOrderedKeys());
    }

    @Override
    public void onListenerDisconnected() {
        mActiveKeys.clear();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        boolean update = !mActiveKeys.add(sbn.getKey());
        if (update && (sbn.getNotification().flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) {
            return;
        }
        NotificationLedController.onNotificationPosted(this, sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        mActiveKeys.remove(sbn.getKey());
    }
}
