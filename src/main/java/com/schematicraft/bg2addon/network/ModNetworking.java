package com.schematicraft.bg2addon.network;

import com.mojang.logging.LogUtils;
import com.schematicraft.SchematiCraftMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD, modid = SchematiCraftMod.MODID)
public class ModNetworking {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // All packets are optional so the mod works client-only on servers without it.
        // When the server has the mod, direct mode is used (packets work).
        // When client-only, the Template Manager fallback path is used instead.
        PayloadRegistrar registrar = event.registrar(SchematiCraftMod.MODID)
                .versioned("1")
                .optional();

        registrar.playToServer(
                LoadTemplatePayload.TYPE,
                LoadTemplatePayload.STREAM_CODEC,
                LoadTemplateHandler.INSTANCE::handle
        );

        registrar.playToServer(
                ExportAndUploadPayload.TYPE,
                ExportAndUploadPayload.STREAM_CODEC,
                ExportAndUploadHandler.INSTANCE::handle
        );

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
