/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashled;

import android.app.Notification;
import android.content.Intent;
import android.os.IBinder;
import android.service.notification.IDispatchCompletionListener;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationRankingUpdate;
import android.service.notification.StatusBarNotification;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class LedNotificationListener extends NotificationListenerService {

    private final Set<String> mActiveKeys = new HashSet<>();

    @Override
    public IBinder onBind(Intent intent) {
        if (mWrapper == null) {
            mWrapper = new NotificationListenerWrapper() {
                @Override
                public void onListenerConnected(NotificationRankingUpdate update,
                        IDispatchCompletionListener completionListener, long dispatchToken) {
                    // Seed on the same handler as posted/removed callbacks, using this
                    // transaction's snapshot before later posts update the shared ranking.
                    String[] keys = update.getRankingMap().getOrderedKeys();
                    getHandler().post(() -> {
                        mActiveKeys.clear();
                        Collections.addAll(mActiveKeys, keys);
                    });
                    super.onListenerConnected(update, completionListener, dispatchToken);
                }
            };
        }
        return super.onBind(intent);
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
