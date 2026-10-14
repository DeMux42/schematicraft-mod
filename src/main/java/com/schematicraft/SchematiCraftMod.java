package com.schematicraft;

import com.mojang.logging.LogUtils;
import com.schematicraft.lib.SchematiCraftLib;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Schematicraft cloud library connector for Minecraft, Forge 1.20.1 build.
 *
 * One mod, one jar. Editor integrations are detected at runtime and only the
 * ones whose editor is installed are activated. On this build Create is the
 * supported editor; it is optional and the mod loads and browses without it.
 *
 * Event subscribers for editor-specific classes are registered manually here
 * (not via @Mod.EventBusSubscriber) because those classes reference the editor's
 * own types. Annotation scanning would load them even when the editor is
 * absent, which would crash on missing classes.
 */
@Mod(SchematiCraftMod.MODID)
public class SchematiCraftMod {
    public static final String MODID = "schematicraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SchematiCraftMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        LOGGER.info("Schematicraft initializing");
        SchematiCraftLib.init();

        // Identify this client to the API. Set here, not only on the client,
        // so server-side requests are not left reporting the library's fallback.
        com.schematicraft.lib.network.SchematiCraftAPIWrapper.get()
                .setClientIdentifier("schematicraft/" + modVersion() + " (forge)");

        boolean hasCreate = ModList.get().isLoaded("create");

        if (!hasCreate) {
            LOGGER.warn("No supported editor found (Create). "
                    + "Schematicraft will load but has nothing to integrate with.");
        }

        // Create: purely client-side. Downloads are written to Create's
        // schematics folder, so no server component and no packets are needed.
        if (hasCreate && FMLEnvironment.dist.isClient()) {
            LOGGER.info("Create detected, activating Create integration");
            modEventBus.addListener(
                com.schematicraft.create.client.CreateClientSetup::onClientSetup);
            MinecraftForge.EVENT_BUS.addListener(
                com.schematicraft.create.client.SchematicTableIntegration::onScreenInit);
            MinecraftForge.EVENT_BUS.addListener(
                com.schematicraft.create.client.SchematicTableIntegration::onKeyPressed);
        }

        if (FMLEnvironment.dist.isClient()) {
            // Shared controls and browsing work even when only a future editor
            // integration is installed.
            modEventBus.addListener(
                com.schematicraft.client.ClientSetup::registerKeyMappings);
            MinecraftForge.EVENT_BUS.addListener(
                com.schematicraft.client.GlobalClientEvents::onKeyInput);
            MinecraftForge.EVENT_BUS.addListener(
                com.schematicraft.client.GlobalClientEvents::onDisconnect);
        }
    }

    /**
     * Mod version as reported by the loader.
     *
     * Read from the loaded mod metadata rather than hardcoded, so it cannot drift
     * from the jar the user actually installed. The version lives in one place,
     * mod_version in gradle.properties, which the mods.toml template consumes.
     */
    private static String modVersion() {
        String version = ModList.get().getModContainerById(MODID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse(null);

        if (version == null) {
            LOGGER.warn("Could not read own mod version from the loader. "
                    + "Requests will identify as an unknown version.");
            return "unknown";
        }
        return version;
    }
}
