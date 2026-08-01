package com.schematicraft.bg2addon.integration;

import com.direwolf20.buildinggadgets2.common.worlddata.BG2Data;
import com.schematicraft.bg2addon.SchematiCraftBG2;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side ownership and retention for Schematicraft clipboard snapshots.
 *
 * <p>Clipboard snapshots live in BG2 world data, which is keyed only by UUID and
 * has no concept of an owner. This registry adds the two things that storage
 * does not provide:
 *
 * <ul>
 *   <li><b>Ownership.</b> Every snapshot records the player who created it, so a
 *       load request for someone else's UUID can be refused.</li>
 *   <li><b>Retention.</b> Each player keeps a bounded number of snapshots, and
 *       evicted snapshots are deleted from world data rather than left behind.</li>
 * </ul>
 *
 * <p>Snapshots are session scoped. They are removed when the player disconnects,
 * because the client-side clipboard list is in memory only and does not survive
 * a restart either.
 */
public final class ServerClipboardRegistry {

    /** Snapshots retained per player before the oldest is deleted. */
    private static final int MAX_PER_PLAYER = 20;

    /** Insertion-ordered snapshot UUIDs per player. */
    private static final Map<UUID, Deque<UUID>> BY_PLAYER = new ConcurrentHashMap<>();

    /** Snapshot UUID to owning player, for constant-time authorization. */
    private static final Map<UUID, UUID> OWNERS = new ConcurrentHashMap<>();

    private ServerClipboardRegistry() {}

    /**
     * Record a new snapshot for a player and delete the oldest if the per-player
     * limit is exceeded.
     */
    public static void record(UUID playerId, UUID clipboardUuid, ServerLevel overworld) {
        if (playerId == null || clipboardUuid == null) {
            return;
        }

        OWNERS.put(clipboardUuid, playerId);
        Deque<UUID> owned = BY_PLAYER.computeIfAbsent(playerId, id -> new ArrayDeque<>());

        List<UUID> evicted = new ArrayList<>();
        synchronized (owned) {
            owned.addLast(clipboardUuid);
            while (owned.size() > MAX_PER_PLAYER) {
                UUID oldest = owned.pollFirst();
                if (oldest != null) {
                    evicted.add(oldest);
                }
            }
        }

        for (UUID oldest : evicted) {
            OWNERS.remove(oldest);
            deleteSnapshot(oldest, overworld);
        }
    }

    /** Whether the player created this snapshot. */
    public static boolean isOwner(UUID playerId, UUID clipboardUuid) {
        if (playerId == null || clipboardUuid == null) {
            return false;
        }
        return playerId.equals(OWNERS.get(clipboardUuid));
    }

    /**
     * Delete every snapshot owned by a player. Called on disconnect so world
     * data does not accumulate across sessions.
     */
    public static void forgetPlayer(UUID playerId, ServerLevel overworld) {
        if (playerId == null) {
            return;
        }

        Deque<UUID> owned = BY_PLAYER.remove(playerId);
        if (owned == null) {
            return;
        }

        List<UUID> toDelete;
        synchronized (owned) {
            toDelete = new ArrayList<>(owned);
            owned.clear();
        }

        for (UUID clipboardUuid : toDelete) {
            OWNERS.remove(clipboardUuid);
            deleteSnapshot(clipboardUuid, overworld);
        }

        if (!toDelete.isEmpty()) {
            SchematiCraftBG2.LOGGER.debug("Released {} clipboard snapshots on disconnect", toDelete.size());
        }
    }

    /**
     * Remove a snapshot from BG2 world data.
     * {@code getCopyPasteList(uuid, true)} removes the entry and marks the data dirty.
     */
    private static void deleteSnapshot(UUID clipboardUuid, ServerLevel overworld) {
        if (overworld == null) {
            return;
        }
        try {
            BG2Data.get(overworld).getCopyPasteList(clipboardUuid, true);
        } catch (Exception e) {
            SchematiCraftBG2.LOGGER.debug("Failed to delete clipboard snapshot: {}", e.getMessage());
        }
    }
}
