/* Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.util.Slog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Supplies the retained 305 HAL's read/notify trigger without running thermal_core's policies. */
final class MtkThermalNotifier {
    private static final LocalSocketAddress ADDRESS = new LocalSocketAddress(
            "/dev/socket/thermal_hal_socket", LocalSocketAddress.Namespace.FILESYSTEM);
    private static final byte[][] MESSAGES = messages();
    private final PowerManager power;
    private Handler handler;
    private volatile long sentCycles;
    private volatile String error = "not started";

    MtkThermalNotifier(PowerManager power) {
        this.power = power;
    }

    void start() {
        HandlerThread thread = new HandlerThread("DashThermalNotify", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::notifyTemperatures);
    }

    long sentCycles() { return sentCycles; }
    String error() { return error; }

    private void notifyTemperatures() {
        try {
            for (byte[] message : MESSAGES) {
                try (LocalSocket socket = new LocalSocket()) {
                    // Create the fd before setting the send/receive timeout, including connect.
                    OutputStream output = socket.getOutputStream();
                    socket.setSoTimeout(500);
                    socket.connect(ADDRESS);
                    output.write(message);
                }
            }
            sentCycles++;
            error = "";
        } catch (IOException e) {
            String next = e.toString();
            if (!next.equals(error)) Slog.w("DashThermal", "Temperature notification failed", e);
            error = next;
        } finally {
            // No wake lock: a suspended device resumes reporting when its worker runs again.
            handler.postDelayed(this::notifyTemperatures, power.isInteractive() ? 2000 : 5000);
        }
    }

    private static byte[][] messages() {
        String[] names = {"CPU", "GPU", "BATTERY", "SKIN", "POWER_AMPLIFIER", "NPU", "TPU", "SOC"};
        byte[][] messages = new byte[names.length][128];
        for (int i = 0; i < names.length; i++) {
            byte[] command = ("TYPE=thermal_hal_notify NAME=" + names[i])
                    .getBytes(StandardCharsets.US_ASCII);
            // Match thermal_core's fixed-size, zero-padded message. No temperature/threshold writes.
            System.arraycopy(command, 0, messages[i], 0, command.length);
        }
        return messages;
    }
}
