/* Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashpower;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exact-node access and a same-boot recovery journal for the vendor's non-restoring unset. */
final class GptStateIo implements GamingPowerPolicy.State {
    private final ThermalConfigStore store;
    private final Path node;
    private final Path bootId;

    GptStateIo(Path journal) {
        this(journal, Path.of("/sys/kernel/thermal/gpt"),
                Path.of("/proc/sys/kernel/random/boot_id"));
    }

    GptStateIo(Path journal, Path node, Path bootId) {
        store = new ThermalConfigStore(journal);
        this.node = node;
        this.bootId = bootId;
    }

    @Override public String load() throws IOException {
        byte[] bytes = store.read();
        if (bytes == null || bytes.length == 0) return null;
        String[] record = new String(bytes, StandardCharsets.US_ASCII).split("\n", 2);
        if (record.length != 2) throw new IOException("Invalid GPT recovery record");
        if (!record[0].equals(Files.readString(bootId).trim())) {
            clear(); // A new kernel boot supplies its own baseline, not the prior boot's values.
            return null;
        }
        parse(record[1]);
        return record[1];
    }

    @Override public String capture() throws IOException {
        String value = Files.readString(node).trim();
        parse(value);
        return value;
    }

    @Override public void save(String value) throws IOException {
        store.write((Files.readString(bootId).trim() + "\n" + value)
                .getBytes(StandardCharsets.US_ASCII));
    }

    @Override public void restore(String value) throws IOException {
        int[] saved = parse(value);
        write("enable");
        write("gpt 1 " + saved[0] + " " + saved[1]);
        write("gpt 2 " + saved[2] + " " + saved[3]);
        int[] restored = parse(Files.readString(node).trim());
        if (restored[0] != saved[0] || restored[2] != saved[2]) {
            throw new IOException("GPT threshold restoration unconfirmed");
        }
    }

    @Override public void clear() throws IOException {
        store.write(new byte[0]);
    }

    private static int[] parse(String value) throws IOException {
        String[] fields = value.split(",", -1);
        if (fields.length != 5) throw new IOException("Invalid GPT state");
        int[] result = new int[5];
        try {
            for (int i = 0; i < result.length; i++) result[i] = Integer.parseInt(fields[i]);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid GPT state", e);
        }
        // Observed during GPU startup; OPP fields may retain this value at idle.
        if (result[0] == 666666666 || result[2] == 666666666) {
            throw new IOException("GPT thresholds unavailable");
        }
        if (result[4] == 0) throw new IOException("GPT already disabled by another owner");
        return result;
    }

    private void write(String value) throws IOException {
        try (FileOutputStream out = new FileOutputStream(node.toFile())) {
            out.write((value + "\n").getBytes(StandardCharsets.US_ASCII));
        }
    }
}
