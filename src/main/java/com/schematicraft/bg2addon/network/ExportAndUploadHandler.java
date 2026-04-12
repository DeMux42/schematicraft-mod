package com.schematicraft.bg2addon.network;

import com.schematicraft.SchematiCraftMod;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** @deprecated Upload moved client-side in v0.1.0. Logs a warning if an old client sends this packet. */
@Deprecated
public class ExportAndUploadHandler {
    public static final ExportAndUploadHandler INSTANCE = new ExportAndUploadHandler();

    public void handle(final ExportAndUploadPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            SchematiCraftMod.LOGGER.warn("Received legacy ExportAndUploadPayload. Upload should happen client-side.");
            context.player().displayClientMessage(
                    Component.literal("\u00a7eUpload is now handled client-side. Please update your mod."), true);
        });
    }
}
