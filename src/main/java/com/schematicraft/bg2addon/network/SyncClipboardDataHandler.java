package com.schematicraft.bg2addon.network;

import com.direwolf20.buildinggadgets2.common.worlddata.BG2Data;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.schematicraft.bg2addon.SchematiCraftBG2;
import com.schematicraft.bg2addon.client.ClipboardPreviewRenderer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;

/**
 * Client-side handler for SyncClipboardDataPayload.
 * Parses the NBT into a StatePos list and caches it for 3D preview rendering.
 */
public class SyncClipboardDataHandler {
    public static final SyncClipboardDataHandler INSTANCE = new SyncClipboardDataHandler();

    public void handle(final SyncClipboardDataPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                ArrayList<StatePos> statePosList = BG2Data.statePosListFromNBTMapArray(payload.statePosNbt());
                ClipboardPreviewRenderer.get().cacheClientData(payload.clipboardUuid(), statePosList);

                // The client owns its clipboard list. Populating it here rather
                // than from server state keeps entries per player and makes the
                // list work on a dedicated server.
                com.schematicraft.bg2addon.core.SchematiCraftState.get().addToClipboard(
                        new com.schematicraft.bg2addon.core.ClipboardEntry(
                                payload.clipboardUuid(), payload.copyUuid(), statePosList.size()));
                SchematiCraftBG2.LOGGER.debug("Cached clipboard preview data: {} ({} blocks)",
                        payload.clipboardUuid().toString().substring(0, 8), statePosList.size());
            } catch (Exception e) {
                SchematiCraftBG2.LOGGER.error("Failed to parse clipboard sync data: {}", e.getMessage());
            }
        });
    }
}
