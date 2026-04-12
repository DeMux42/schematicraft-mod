package com.schematicraft.bg2addon.network;

import com.direwolf20.buildinggadgets2.common.worlddata.BG2Data;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.schematicraft.SchematiCraftMod;
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
                SchematiCraftMod.LOGGER.debug("Cached clipboard preview data: {} ({} blocks)",
                        payload.clipboardUuid().toString().substring(0, 8), statePosList.size());
            } catch (Exception e) {
                SchematiCraftMod.LOGGER.error("Failed to parse clipboard sync data: {}", e.getMessage());
            }
        });
    }
}
