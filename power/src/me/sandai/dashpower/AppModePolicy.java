/* Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import java.util.function.LongSupplier;

/** Worker-owned game/camera frame policies with bounded, PID-directed requests. */
final class AppModePolicy {
    static final int GAME_HINT = 47;
    static final int CAMERA_HINT = 51;
    static final int LEASE_MS = 30_000;
    static final int RENEW_MS = 15_000;

    interface Transport {
        int acquire(int hint, int pid, int durationMs) throws Exception;
        boolean release(int token) throws Exception;
    }

    private final Transport transport;
    private final LongSupplier clock;
    private int token;
    private int pid;
    private int hint;
    private long expires;
    private long renewAt;
    private long settleUntil;
    private String error = "";

    AppModePolicy(Transport transport, LongSupplier clock) {
        this.transport = transport;
        this.clock = clock;
        // A previous system_server may still own a finite request whose reply was lost.
        settleUntil = clock.getAsLong() + LEASE_MS;
    }

    static int select(boolean categoryGame, int defaultProfile, int overrideProfile) {
        if (overrideProfile != ThermalProfiles.AUTO) {
            return ThermalProfiles.frameHint(overrideProfile);
        }
        int selected = ThermalProfiles.frameHint(defaultProfile);
        if (selected != 0 || defaultProfile == 6) return selected;
        return categoryGame ? GAME_HINT : 0;
    }

    static int[] resources(int hint, int pid) {
        if (pid <= 0) throw new IllegalArgumentException("App PID required");
        // Retained V3 wrappers supply render PID and priority; game mode has an extra flag.
        if (hint == GAME_HINT) {
            return new int[] {0x02000500, pid, 0x03404200, 1, 0x0341c000, 6};
        }
        if (hint == CAMERA_HINT) return new int[] {0x02000500, pid, 0x0341c000, 6};
        throw new IllegalArgumentException("Unsupported app mode " + hint);
    }

    /** Returns when the worker must re-query current foreground state. */
    long update(int targetHint, int targetPid) {
        if (targetHint == 0 || targetPid <= 0) {
            targetHint = 0;
            targetPid = 0;
        }
        long now = clock.getAsLong();
        if (now < settleUntil) return settleUntil - now;
        try {
            if (token != 0 && now >= expires) {
                token = 0;
                pid = 0;
            }
            if (token != 0 && pid == targetPid && hint == targetHint && now < renewAt) {
                return renewAt - now;
            }
            if (token != 0) {
                if (!transport.release(token)) {
                    token = 0;
                    pid = 0;
                    settleUntil = expires;
                    return Math.max(1, settleUntil - clock.getAsLong());
                }
                token = 0;
                pid = 0;
            }
            if (targetPid == 0) {
                error = "";
                return 0;
            }
            int acquired = transport.acquire(targetHint, targetPid, LEASE_MS);
            if (acquired <= 0) throw new IllegalStateException("App mode rejected");
            token = acquired;
            pid = targetPid;
            hint = targetHint;
            // Upper bound includes time spent waiting for the acquire reply.
            long acquiredAt = clock.getAsLong();
            expires = acquiredAt + LEASE_MS;
            renewAt = acquiredAt + RENEW_MS;
            error = "";
            return RENEW_MS;
        } catch (Exception e) {
            error = e.toString();
            token = 0;
            pid = 0;
            // A lost reply may still have applied the request. Do not overlap a new PID.
            settleUntil = clock.getAsLong() + LEASE_MS;
            return LEASE_MS;
        }
    }

    void disconnected() {
        token = 0;
        pid = 0;
        settleUntil = clock.getAsLong() + LEASE_MS;
        error = "MTK power disconnected";
    }

    int requestedPid() { return token != 0 && clock.getAsLong() < expires ? pid : 0; }
    int requestedHint() { return requestedPid() != 0 ? hint : 0; }
    boolean settling() { return clock.getAsLong() < settleUntil; }
    String error() { return error; }
}
