package com.schematicraft;

import com.mojang.logging.LogUtils;
import com.schematicraft.lib.SchematiCraftLib;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Schematicraft cloud library connector for Minecraft.
 * Detects installed editor mods and activates the appropriate integrations.
 * Currently supports Building Gadgets 2 and Create.
 */
@Mod(SchematiCraftMod.MODID)
public class SchematiCraftMod {
    public static final String MODID = "schematicraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SchematiCraftMod(IEventBus modEventBus) {
        LOGGER.info("Schematicraft initializing");
        SchematiCraftLib.init();

        if (FMLEnvironment.dist.isClient()) {
            com.schematicraft.lib.network.SchematiCraftAPIWrapper.get()
                .setClientIdentifier("schematicraft/0.2.0 (neoforge)");

            if (ModList.get().isLoaded("buildinggadgets2")) {
                LOGGER.info("Building Gadgets 2 detected, activating BG2 integration");
                modEventBus.addListener(com.schematicraft.bg2addon.client.ClientSetup::onClientSetup);
                modEventBus.addListener(com.schematicraft.bg2addon.client.ClientSetup::registerKeyMappings);
            }

            if (ModList.get().isLoaded("create")) {
                LOGGER.info("Create detected, activating Create integration");
            }
        }
    }
}
