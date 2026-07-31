package com.schematicraft.bg2addon.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side limits for client-initiated BG2 operations.
 *
 * <p>These packets can be sent by a modified client at any rate, and handling
 * them parses NBT, copies state lists, and writes to persistent BG2 world data.
 * The honest client applies its own pre-send checks, but the server cannot rely
 * on that, so every limit here is enforced after decode and before persistence.
 *
 * <p>Limits are sized for normal Building Gadgets use rather than as a general
 * anti-abuse system.
 */
public final class ServerOperationLimits {

    /** Maximum blocks accepted in a single template or clipboard load. */
    public static final int MAX_BLOCKS = 512_000;

    /** Maximum serialized NBT bytes accepted for a single template. */
    public static final int MAX_SERIALIZED_BYTES = 2 * 1024 * 1024;

    /** Minimum spacing between load operations from one player. */
    private static final long MIN_INTERVAL_MS = 500L;

    /** Maximum load operations per player within {@link #WINDOW_MS}. */
    private static final int MAX_OPS_PER_WINDOW = 20;
    private static final long WINDOW_MS = 60_000L;

    /** Safety bound so the tracking map cannot grow without limit. */
    private static final int MAX_TRACKED_PLAYERS = 512;

    private static final Map<UUID, Deque<Long>> HISTORY = new ConcurrentHashMap<>();

    private ServerOperationLimits() {}

    /**
     * Record an operation attempt and report whether it is permitted.
     * Enforces both a minimum interval and a per-window cap.
     */
    public static boolean allowOperation(UUID playerId) {
        if (playerId == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        Deque<Long> timestamps = HISTORY.computeIfAbsent(playerId, id -> new ArrayDeque<>());

        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > WINDOW_MS) {
                timestamps.pollFirst();
            }

            Long last = timestamps.peekLast();
            if (last != null && now - last < MIN_INTERVAL_MS) {
                return false;
            }

            if (timestamps.size() >= MAX_OPS_PER_WINDOW) {
                return false;
            }

            timestamps.addLast(now);
        }

        pruneIfLarge(now);
        return true;
    }

    /** Drop tracking for a player, for example on disconnect. */
    public static void forget(UUID playerId) {
        if (playerId != null) {
            HISTORY.remove(playerId);
        }
    }

    /**
     * Serialized size of a tag in bytes.
     *
     * <p>Counts bytes while writing and discards them, so measuring does not
     * retain a second copy of the payload.
     */
    public static int serializedSize(CompoundTag tag) throws IOException {
        CountingOutputStream counter = new CountingOutputStream();
        try (DataOutputStream out = new DataOutputStream(counter)) {
            NbtIo.write(tag, out);
        }
        return counter.count();
    }

    /**
     * Whether a template payload is within the accepted serialized size.
     * Returns false when the size cannot be determined, so unreadable input
     * fails closed rather than being passed to the parser.
     */
    public static boolean isWithinSizeLimit(CompoundTag tag) {
        try {
            return serializedSize(tag) <= MAX_SERIALIZED_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    private static void pruneIfLarge(long now) {
        if (HISTORY.size() <= MAX_TRACKED_PLAYERS) {
            return;
        }
        Iterator<Map.Entry<UUID, Deque<Long>>> it = HISTORY.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> timestamps = it.next().getValue();
            boolean stale;
            synchronized (timestamps) {
                Long last = timestamps.peekLast();
                stale = last == null || now - last > WINDOW_MS;
            }
            if (stale) {
                it.remove();
            }
        }
    }

    /** Counts bytes written and discards the data. */
    private static final class CountingOutputStream extends OutputStream {
        private int count;

        int count() {
            return count;
        }

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
        }
    }
}
