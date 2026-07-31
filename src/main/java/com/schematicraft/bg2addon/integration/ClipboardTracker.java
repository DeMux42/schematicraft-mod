package com.schematicraft.bg2addon.integration;

import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.items.GadgetCutPaste;
import com.direwolf20.buildinggadgets2.common.worlddata.BG2Data;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.schematicraft.bg2addon.SchematiCraftBG2;
import com.schematicraft.bg2addon.network.SyncClipboardDataPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects new BG2 copy operations and snapshots them to the Schematicraft clipboard.
 * Runs server-side via a tick event (see ServerEvents).
 *
 * BG2 Integration Points:
 * - GadgetNBT.hasCopyUUID / getCopyUUID: checks if the gadget has new copy data
 * - GadgetNBT.getUUID: gets the gadget's persistent UUID (used as BG2Data key)
 * - BG2Data.getCopyPasteList: retrieves the StatePos list for a gadget UUID
 * - BG2Data.addToCopyPaste: stores a snapshot under a new UUID
 * - BG2Data.statePosListToNBTMapArray: serializes StatePos list to NBT for network sync
 *
 * Snapshot Strategy:
 * BG2 overwrites the gadget's paste buffer on every new copy. To preserve history,
 * each copy is snapshotted into BG2Data under a unique clipboard UUID. This UUID
 * is independent of the gadget UUID, so the snapshot survives when the player
 * makes another copy. The SyncClipboardDataPayload sends the NBT to the client
 * so the ClipboardPreviewRenderer can build a 3D preview without re-reading
 * from the server.
 */
public class ClipboardTracker {

    /**
     * Last copy UUID already snapshotted, per player.
     *
     * <p>This must be per player. A single shared field cannot deduplicate more
     * than one player: with two players holding gadgets with different copy
     * UUIDs, each tick would see a value different from the one slot and
     * re-snapshot both builds indefinitely.
     */
    private static final Map<UUID, UUID> LAST_SEEN_BY_PLAYER = new ConcurrentHashMap<>();

    /**
     * Check if the player's gadget has a new copy and snapshot it to the clipboard.
     * Snapshots persist even when the gadget's data is overwritten by the next copy.
     */
    public static void checkForNewCopy(Player player) {
        try {
            ItemStack gadget = BaseGadget.getGadget(player);
            if (gadget.isEmpty()) return;
            if (!(gadget.getItem() instanceof GadgetCopyPaste)
                    && !(gadget.getItem() instanceof GadgetCutPaste)) return;

            if (!GadgetNBT.hasCopyUUID(gadget)) return;

            UUID copyUuid = GadgetNBT.getCopyUUID(gadget);
            UUID playerId = player.getUUID();
            if (copyUuid.equals(LAST_SEEN_BY_PLAYER.get(playerId))) return;

            LAST_SEEN_BY_PLAYER.put(playerId, copyUuid);

            UUID gadgetUuid = GadgetNBT.getUUID(gadget);
            if (player.level() instanceof ServerLevel serverLevel) {
                BG2Data bg2Data = BG2Data.get(
                        Objects.requireNonNull(serverLevel.getServer()).overworld());
                ArrayList<StatePos> list = bg2Data.getCopyPasteList(gadgetUuid, false);
                int blockCount = list != null ? list.size() : 0;

                if (blockCount == 0) return;

                // Snapshot: store under a unique UUID so it survives the next copy
                UUID clipboardUuid = UUID.randomUUID();
                bg2Data.addToCopyPaste(clipboardUuid, new ArrayList<>(list));

                // Record the owner and enforce per-player retention. Evicted
                // snapshots are deleted from world data.
                ServerClipboardRegistry.record(playerId, clipboardUuid, serverLevel.getServer().overworld());

                // Send to the owning player only. The client builds its own
                // clipboard list from this packet, so the entry is never shared
                // through server state.
                if (player instanceof ServerPlayer serverPlayer) {
                    CompoundTag nbtMap = BG2Data.statePosListToNBTMapArray(new ArrayList<>(list));
                    serverPlayer.connection.send(
                            new SyncClipboardDataPayload(clipboardUuid, copyUuid, nbtMap));
                }

                SchematiCraftBG2.LOGGER.info("Added copy to clipboard: {} blocks (snapshot {})",
                        blockCount, clipboardUuid.toString().substring(0, 8));
            }
        } catch (Exception e) {
            SchematiCraftBG2.LOGGER.debug("Clipboard check error: {}", e.getMessage());
        }
    }

    /** Drop per-player copy tracking, for example on disconnect. */
    public static void forgetPlayer(UUID playerId) {
        if (playerId != null) {
            LAST_SEEN_BY_PLAYER.remove(playerId);
        }
    }
}
