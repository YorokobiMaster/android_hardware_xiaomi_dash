/* Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import java.util.HashMap;

/** Worker-owned leases. Callers never receive a reusable vendor handle. */
final class PowerHandleRegistry<T> {
    static final class Lease<T> {
        final T owner;
        final int handle;
        final long expires;

        Lease(T owner, int handle, long expires) {
            this.owner = owner;
            this.handle = handle;
            this.expires = expires;
        }
    }

    private final HashMap<Integer, Lease<T>> leases = new HashMap<>();
    private int nextToken = 1;

    int add(T owner, int handle, long expires) {
        if (handle <= 0) return handle;
        leases.values().removeIf(old -> old.owner == owner && old.handle == handle);
        while (nextToken <= 0 || leases.containsKey(nextToken)) {
            if (nextToken <= 0) nextToken = 1;
            else ++nextToken;
        }
        int token = nextToken++;
        leases.put(token, new Lease<>(owner, handle, expires));
        return token;
    }

    Lease<T> take(int token, long now) {
        Lease<T> lease = leases.remove(token);
        return lease != null && now < lease.expires ? lease : null;
    }

    void forget(int token) {
        leases.remove(token);
    }

    void disconnected(T owner) {
        leases.values().removeIf(lease -> lease.owner == owner);
    }
}
