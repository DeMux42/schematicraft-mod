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
 * Detects installed editor mods and activates the appropriate integrations.
 * Currently supports Building Gadgets 2 and Create.
 *
 * Event subscribers for editor-specific classes are registered manually
 * (not via @EventBusSubscriber) to avoid class loading failures when
 * the target editor mod is not installed.
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

        // BG2 integration: register network packets on MOD bus, game events on GAME bus
        if (hasBG2) {
            LOGGER.info("Building Gadgets 2 detected, activating BG2 integration");
            // MOD bus: network payload registration
            modEventBus.addListener(
                com.schematicraft.bg2addon.network.ModNetworking::registerPayloads);
            // GAME bus: server tick for clipboard tracking
            NeoForge.EVENT_BUS.addListener(
                com.schematicraft.bg2addon.integration.ServerEvents::onServerTick);

            if (FMLEnvironment.dist.isClient()) {
                // MOD bus: client setup and keybindings
                modEventBus.addListener(
                    com.schematicraft.bg2addon.client.ClientSetup::onClientSetup);
                modEventBus.addListener(
                    com.schematicraft.bg2addon.client.ClientSetup::registerKeyMappings);
                // GAME bus: screen interception, template manager, key/char events
                NeoForge.EVENT_BUS.addListener(
                    com.schematicraft.bg2addon.client.ScreenInterceptor::onScreenOpen);
                NeoForge.EVENT_BUS.addListener(
                    com.schematicraft.bg2addon.client.TemplateManagerIntegration::onScreenInit);
                NeoForge.EVENT_BUS.addListener(
                    com.schematicraft.bg2addon.client.TemplateManagerIntegration::onScreenRender);
                NeoForge.EVENT_BUS.addListener(
                    com.schematicraft.bg2addon.client.TemplateManagerIntegration::onKeyPressed);
                NeoForge.EVENT_BUS.addListener(
                    com.schematicraft.bg2addon.client.TemplateManagerIntegration::onCharTyped);
                NeoForge.EVENT_BUS.addListener(
                    com.schematicraft.bg2addon.client.TemplateManagerIntegration::onMouseClicked);
            }
        }

        // Create integration: purely client-side, no network packets
        if (hasCreate && FMLEnvironment.dist.isClient()) {
            LOGGER.info("Create detected, activating Create integration");
            NeoForge.EVENT_BUS.addListener(
                com.schematicraft.create.client.SchematicTableIntegration::onScreenInit);
            NeoForge.EVENT_BUS.addListener(
                com.schematicraft.create.client.SchematicTableIntegration::onScreenRender);
            NeoForge.EVENT_BUS.addListener(
                com.schematicraft.create.client.SchematicTableIntegration::onKeyPressed);
            NeoForge.EVENT_BUS.addListener(
                com.schematicraft.create.client.SchematicTableIntegration::onCharTyped);
            NeoForge.EVENT_BUS.addListener(
                com.schematicraft.create.client.SchematicTableIntegration::onMouseClicked);
            NeoForge.EVENT_BUS.addListener(
                com.schematicraft.create.client.SchematicTableIntegration::onMouseReleased);
            NeoForge.EVENT_BUS.addListener(
                com.schematicraft.create.client.SchematicTableIntegration::onMouseDragged);
        }

        if (FMLEnvironment.dist.isClient()) {
            com.schematicraft.lib.network.SchematiCraftAPIWrapper.get()
                .setClientIdentifier("schematicraft/0.2.0 (neoforge)");
        }
    }
}
