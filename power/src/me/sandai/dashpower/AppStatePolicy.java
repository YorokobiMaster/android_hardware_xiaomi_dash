/* Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resumed activities, including multiple windows/users. Owned by the thermal worker. */
final class AppStatePolicy {
    interface Transport {
        void notify(Activity activity, boolean resumed) throws Exception;
    }

    static final class Activity {
        final String packageName;
        final String activityName;
        final int pid;
        final int uid;
        final int activityId;

        Activity(String packageName, String activityName, int pid, int uid, int activityId) {
            this.packageName = packageName;
            this.activityName = activityName;
            this.pid = pid;
            this.uid = uid;
            this.activityId = activityId;
        }

        long key() {
            return ((long) pid << 32) | (activityId & 0xffffffffL);
        }
    }

    private final Map<Long, Activity> resumed = new LinkedHashMap<>();
    private Transport transport;

    void update(Activity activity, boolean isResumed) throws Exception {
        Activity previous = isResumed ? resumed.putIfAbsent(activity.key(), activity)
                : resumed.remove(activity.key());
        if (isResumed ? previous != null : previous == null) return;
        send(isResumed ? activity : previous, isResumed);
    }

    /** A restarted HAL gets only activities still resumed, never earlier launches or pauses. */
    void connected(Transport value) throws Exception {
        transport = value;
        if (value != null) {
            for (Activity activity : resumed.values()) send(activity, true);
        }
    }

    /** Match the focused component and full user UID; reject ambiguous process identities. */
    int resumedPid(String packageName, String activityName, int uid) {
        int pid = 0;
        for (Activity activity : resumed.values()) {
            if (activity.uid != uid || !activity.packageName.equals(packageName)
                    || !activity.activityName.equals(activityName)) continue;
            if (activity.pid <= 0 || (pid != 0 && pid != activity.pid)) return 0;
            pid = activity.pid;
        }
        return pid;
    }

    int resumedCount() {
        return resumed.size();
    }

    private void send(Activity activity, boolean isResumed) throws Exception {
        if (transport == null) return;
        try {
            transport.notify(activity, isResumed);
        } catch (Exception e) {
            // Keep current lifecycle state for a new HAL, not the failed connection's history.
            transport = null;
            throw e;
        }
    }
}
