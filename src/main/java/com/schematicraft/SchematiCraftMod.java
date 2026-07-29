package com.schematicraft;

import com.mojang.logging.LogUtils;
import com.schematicraft.lib.SchematiCraftLib;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Schematicraft cloud library connector for Minecraft.
 *
 * One mod, one jar. Editor integrations are detected at runtime and only the
 * ones whose editor is installed are activated. Building Gadgets 2 and Create
 * are both optional.
 *
 * Event subscribers for editor-specific classes are registered manually here
 * (not via @EventBusSubscriber) because those classes reference the editor's
 * own types. Annotation scanning would load them even when the editor is
 * absent, which would crash on missing classes.
 */
@Mod(SchematiCraftMod.MODID)
public class SchematiCraftMod {
    public static final String MODID = "schematicraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SchematiCraftMod(IEventBus modEventBus) {
        LOGGER.info("Schematicraft initializing");
        SchematiCraftLib.init();

        boolean hasBG2 = ModList.get().isLoaded("buildinggadgets2");
        boolean hasCreate = ModList.get().isLoaded("create");

        if (!hasBG2 && !hasCreate) {
            LOGGER.warn("No supported editor found (Building Gadgets 2 or Create). "
                    + "Schematicraft will load but has nothing to integrate with.");
        }

        // Building Gadgets 2: needs both sides. The server half enables loading
        // straight into a gadget and reading clipboard copies for upload.
        if (hasBG2) {
            LOGGER.info("Building Gadgets 2 detected, activating BG2 integration");

            modEventBus.addListener(
                com.schematicraft.bg2addon.network.ModNetworking::registerPayloads);
            NeoForge.EVENT_BUS.addListener(
                com.schematicraft.bg2addon.integration.ServerEvents::onServerTick);

            if (FMLEnvironment.dist.isClient()) {
                modEventBus.addListener(
                    com.schematicraft.bg2addon.client.ClientSetup::onClientSetup);

                // Radial launcher and the Template Manager button.
                NeoForge.EVENT_BUS.addListener(
                    com.schematicraft.bg2addon.client.ScreenInterceptor::onScreenOpen);
                NeoForge.EVENT_BUS.addListener(
                    com.schematicraft.bg2addon.client.TemplateManagerIntegration::onScreenInit);

                // Server-mode reset and BG2 keybind isolation.
                NeoForge.EVENT_BUS.addListener(
                    com.schematicraft.bg2addon.client.ClientEvents::onDisconnect);
                // Keeps BG2 keybinds from firing while our screens are open.
                NeoForge.EVENT_BUS.addListener(
                    com.schematicraft.bg2addon.client.ClientEvents::onClientTickPre);
            }
        }

        // Create: purely client-side. Downloads are written to Create's
        // schematics folder, so no server component and no packets are needed.
        if (hasCreate && FMLEnvironment.dist.isClient()) {
            LOGGER.info("Create detected, activating Create integration");
            modEventBus.addListener(
                com.schematicraft.create.client.CreateClientSetup::onClientSetup);
            NeoForge.EVENT_BUS.addListener(
                com.schematicraft.create.client.SchematicTableIntegration::onScreenInit);
        }

        if (FMLEnvironment.dist.isClient()) {
            // Shared controls and browsing work even when only a future editor
            // integration is installed.
            modEventBus.addListener(
                com.schematicraft.client.ClientSetup::registerKeyMappings);
            NeoForge.EVENT_BUS.addListener(
                com.schematicraft.client.GlobalClientEvents::onKeyInput);
            NeoForge.EVENT_BUS.addListener(
                com.schematicraft.client.GlobalClientEvents::onDisconnect);

            com.schematicraft.lib.network.SchematiCraftAPIWrapper.get()
                .setClientIdentifier("schematicraft/0.3.0 (neoforge)");
        }
    }
}
