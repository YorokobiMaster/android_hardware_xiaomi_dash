// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0

package me.sandai.dashesim;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.TelephonyManager;
import android.telephony.UiccSlotInfo;

import com.android.internal.telephony.ISub;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class EsimController {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final int SHARED_SLOT = 1;

    static boolean isSupported(Context context) {
        String model = SystemProperties.get("ro.product.odm.model", "");
        return (model.equals("2602BPC18G") || model.equals("2602BPC18I"))
                && context.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_TELEPHONY_EUICC);
    }

    // POCO multiplexes eUICC and physical SIM 2 onto this physical slot.
    static UiccSlotInfo getSlot(Context context) {
        TelephonyManager manager = context.getSystemService(TelephonyManager.class);
        UiccSlotInfo[] slots = manager.getUiccSlotsInfo();
        if (slots == null || slots.length <= SHARED_SLOT || slots[SHARED_SLOT] == null) return null;
        UiccSlotInfo slot = slots[SHARED_SLOT];
        switch (slot.getCardStateInfo()) {
            case UiccSlotInfo.CARD_STATE_INFO_ABSENT:
            case UiccSlotInfo.CARD_STATE_INFO_PRESENT:
            case UiccSlotInfo.CARD_STATE_INFO_RESTRICTED:
                return slot;
            default:
                return null;
        }
    }

    static boolean hasPhysicalSim(UiccSlotInfo slot) {
        return !slot.getIsEuicc() && slot.getCardStateInfo() != UiccSlotInfo.CARD_STATE_INFO_ABSENT;
    }

    private static boolean hasActiveProfile(Context context) throws IOException, RemoteException {
        // SubscriptionManager's public list hides a failed Binder call as an empty list.
        ISub service = ISub.Stub.asInterface(TelephonyFrameworkInitializer.getTelephonyServiceManager()
                .getSubscriptionServiceRegisterer().tryGet());
        if (service == null) throw new IOException("Subscription service unavailable");
        List<SubscriptionInfo> subscriptions = service.getActiveSubscriptionInfoList(
                context.getOpPackageName(), context.getAttributionTag(), false);
        if (subscriptions == null) throw new IOException("Active subscriptions unavailable");
        return subscriptions.stream().anyMatch(SubscriptionInfo::isEmbedded);
    }

    static final class ActiveProfileException extends IOException {}

    static void setEnabled(Context context, boolean enabled, boolean disconnectSim2)
            throws IOException, RemoteException {
        if (!isSupported(context)) throw new IOException("Unsupported hardware");
        UiccSlotInfo slot = getSlot(context);
        if (slot == null) throw new IOException("SIM slot unavailable");
        if (slot.getIsEuicc() == enabled) return;
        if (enabled && hasPhysicalSim(slot) && !disconnectSim2) {
            throw new IOException("Physical SIM 2 needs confirmation");
        }
        if (!enabled && hasActiveProfile(context)) throw new ActiveProfileException();
        String token = Long.toString(SystemClock.elapsedRealtimeNanos());
        SystemProperties.set("sys.dash.esim.request", token + (enabled ? ":enable" : ":disable"));
        long deadline = SystemClock.elapsedRealtime() + 30_000;
        boolean acknowledged = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            String response = SystemProperties.get("sys.dash.esim.response", "");
            if (response.startsWith(token + ":")) {
                if (!response.equals(token + ":done")) throw new IOException("Modem command failed");
                acknowledged = true;
            }
            if (acknowledged) {
                slot = getSlot(context);
                if (slot != null && slot.getIsEuicc() == enabled) return;
            }
            SystemClock.sleep(200);
        }
        throw new IOException("Timed out waiting for modem slot update");
    }

    private EsimController() {}
}
