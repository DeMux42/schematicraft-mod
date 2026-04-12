package com.schematicraft.bg2addon.integration;

import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.items.GadgetCutPaste;
import com.direwolf20.buildinggadgets2.common.worlddata.BG2Data;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.schematicraft.SchematiCraftMod;
import com.schematicraft.bg2addon.core.ClipboardEntry;
import com.schematicraft.bg2addon.core.SchematiCraftState;
import com.schematicraft.bg2addon.network.SyncClipboardDataPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

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

    private static UUID lastSeenCopyUuid = null;

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
            if (copyUuid.equals(lastSeenCopyUuid)) return;

            lastSeenCopyUuid = copyUuid;

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

                ClipboardEntry entry = new ClipboardEntry(clipboardUuid, copyUuid, blockCount);
                SchematiCraftState.get().addToClipboard(entry);

                // Sync to client for 3D preview rendering
                if (player instanceof ServerPlayer serverPlayer) {
                    CompoundTag nbtMap = BG2Data.statePosListToNBTMapArray(new ArrayList<>(list));
                    serverPlayer.connection.send(new SyncClipboardDataPayload(clipboardUuid, nbtMap));
                }

                SchematiCraftMod.LOGGER.info("Added copy to clipboard: {} blocks (snapshot {})",
                        blockCount, clipboardUuid.toString().substring(0, 8));
            }
        } catch (Exception e) {
            SchematiCraftMod.LOGGER.debug("Clipboard check error: {}", e.getMessage());
        }
    }
}
