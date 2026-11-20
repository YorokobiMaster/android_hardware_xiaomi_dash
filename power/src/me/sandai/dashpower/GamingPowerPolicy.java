/* Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Worker-owned GPU and bandwidth leases for gaming and evaluation profiles. */
final class GamingPowerPolicy {
    static final int GPT_RESOURCE = 0x10000600;
    static final int DRAM_RESOURCE = 0x01000000;
    // MT6991 required-opps has nine entries, highest first. Leave two higher levels
    // available to DVFSRC instead of holding a maximum-bandwidth (OPP 0) request.
    static final int EXTREME_DRAM_OPP = 2;
    static final int LEASE_MS = 4000;
    static final int RENEW_MS = 2000;

    interface Transport {
        int acquire(int durationMs, int[] resources) throws Exception;
        boolean release(int token) throws Exception;
    }

    interface State {
        String load() throws Exception;
        String capture() throws Exception;
        void save(String value) throws Exception;
        void restore(String value) throws Exception;
        void clear() throws Exception;
    }

    private final Transport transport;
    private final State state;
    private final LongSupplier clock;
    private boolean loaded;
    private String saved;
    private boolean discardUnsentRecord;
    private int token;
    private int appliedProfile;
    private long expires;
    private long renewAt;
    private long settleUntil;
    private String error = "";

    GamingPowerPolicy(Transport transport, State state, LongSupplier clock) {
        this.transport = transport;
        this.state = state;
        this.clock = clock;
    }

    static int level(int profile) {
        return ThermalProfiles.gpuProfile(profile);
    }

    static int[] resources(int profile) {
        if (level(profile) == 0) return new int[0];
        int floor = ThermalProfiles.dramFloorOpp(profile);
        if (floor >= 0) {
            return new int[] {GPT_RESOURCE, 1, DRAM_RESOURCE, floor};
        }
        return new int[] {GPT_RESOURCE, 1};
    }

    /** Returns the next delay, or zero when no further worker callback is needed. */
    long update(int profile) {
        return update(profile, () -> true);
    }

    long update(int profile, BooleanSupplier current) {
        profile = level(profile);
        boolean allowed = profile != 0;
        long now = clock.getAsLong();
        try {
            if (discardUnsentRecord) {
                state.clear();
                discardUnsentRecord = false;
            }
            if (!loaded) {
                saved = state.load();
                loaded = true;
                // A previous system_server may still own a finite vendor lease.
                if (saved != null) settleUntil = now + LEASE_MS;
            }
            if (now < settleUntil) return settleUntil - now;
            if (saved != null && (!allowed || token == 0 || now >= expires)) {
                if (token != 0 && now < expires && !transport.release(token)) {
                    token = 0;
                    settleUntil = expires;
                    return Math.max(1, settleUntil - clock.getAsLong());
                }
                token = 0;
                state.restore(saved);
                state.clear();
                saved = null;
            }
            if (!allowed) {
                error = "";
                return 0;
            }
            if (token != 0 && now < renewAt && appliedProfile == profile) return renewAt - now;
            // Retained HAL callbacks reset nodes on release, including with overlapping locks.
            if (token != 0 && !transport.release(token)) {
                token = 0;
                settleUntil = expires;
                return Math.max(1, settleUntil - clock.getAsLong());
            }
            token = 0;
            if (!current.getAsBoolean()) return 0;
            boolean preparing = saved == null;
            if (preparing) {
                String captured;
                try {
                    captured = state.capture();
                } catch (Exception e) {
                    // No resource request has been sent; GPU startup placeholders may clear soon.
                    error = e.toString();
                    return 100;
                }
                try {
                    state.save(captured); // Durable before any request can change the node.
                } catch (ThermalConfigStore.PublishedWriteException e) {
                    error = e.toString();
                    // The record is visible, but this request cannot have reached the HAL.
                    discardPreparedRecord();
                    return LEASE_MS;
                } catch (Exception e) {
                    error = e.toString();
                    return LEASE_MS;
                }
                saved = captured;
            }
            if (!current.getAsBoolean()) {
                if (preparing) discardPreparedRecord();
                return 0;
            }
            int acquired;
            try {
                acquired = transport.acquire(LEASE_MS, resources(profile));
            } catch (MtkPowerClient.RequestNotSentException e) {
                error = e.toString();
                if (preparing) discardPreparedRecord();
                return LEASE_MS;
            }
            if (acquired <= 0) throw new IllegalStateException("Profile resources rejected");
            token = acquired;
            appliedProfile = profile;
            // This is an upper bound on vendor expiry, including IPC/persistence latency.
            long acquiredAt = clock.getAsLong();
            expires = acquiredAt + LEASE_MS;
            renewAt = acquiredAt + RENEW_MS;
            error = "";
            return RENEW_MS;
        } catch (Exception e) {
            error = e.toString();
            token = 0;
            // A failed IPC may have applied a request. Let its finite timeout finish before
            // restoring, so its later unset cannot overwrite the restored thresholds.
            settleUntil = clock.getAsLong() + LEASE_MS;
            return !loaded || discardUnsentRecord || saved != null || allowed ? LEASE_MS : 0;
        }
    }

    private void discardPreparedRecord() throws Exception {
        saved = null;
        settleUntil = 0;
        discardUnsentRecord = true;
        state.clear();
        discardUnsentRecord = false;
    }

    void disconnected() {
        token = 0;
        if (saved != null) settleUntil = clock.getAsLong() + LEASE_MS;
        error = "MTK power disconnected";
    }

    long activeUntil() { return token == 0 ? 0 : expires; }
    int activeProfile() { return appliedProfile; }

    boolean requested() { return token != 0 && clock.getAsLong() < expires; }
    int profile() { return requested() ? appliedProfile : 0; }
    int dramFloorOpp() { return ThermalProfiles.dramFloorOpp(profile()); }
    boolean restorePending() { return saved != null && token == 0; }
    String error() { return error; }
}
