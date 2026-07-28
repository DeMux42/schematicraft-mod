package com.schematicraft.bg2addon.network;

import com.mojang.logging.LogUtils;
import com.schematicraft.bg2addon.SchematiCraftBG2;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/**
 * Building Gadgets 2 network payloads.
 * Registered manually from the mod entrypoint so this only happens when
 * Building Gadgets 2 is installed.
 */
public class ModNetworking {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // All packets are optional so the mod works client-only on servers without it.
        // When the server has the mod, direct mode is used (packets work).
        // When client-only, the Template Manager fallback path is used instead.
        PayloadRegistrar registrar = event.registrar(SchematiCraftBG2.MODID)
                .versioned("1")
                .optional();

        registrar.playToServer(
                LoadTemplatePayload.TYPE,
                LoadTemplatePayload.STREAM_CODEC,
                LoadTemplateHandler.INSTANCE::handle
        );

        // Upload runs entirely client-side, so there is no upload packet.

        registrar.playToServer(
                LoadClipboardPayload.TYPE,
                LoadClipboardPayload.STREAM_CODEC,
                LoadClipboardHandler.INSTANCE::handle
        );

        registrar.playToClient(
                SyncClipboardDataPayload.TYPE,
                SyncClipboardDataPayload.STREAM_CODEC,
                SyncClipboardDataHandler.INSTANCE::handle
        );

        LOGGER.debug("Registered Schematicraft network payloads (optional)");
    }
}
