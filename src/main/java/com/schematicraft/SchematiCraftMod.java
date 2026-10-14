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

        // Identify this client to the API. Set on both sides, not just the client,
        // so server-side requests are not left reporting the library's fallback.
        com.schematicraft.lib.network.SchematiCraftAPIWrapper.get()
                .setClientIdentifier("schematicraft/" + modVersion() + " (neoforge)");

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
            // Releases per-player clipboard snapshots and rate-limit state.
            NeoForge.EVENT_BUS.addListener(
                com.schematicraft.bg2addon.integration.ServerEvents::onPlayerLoggedOut);

            if (FMLEnvironment.getDist().isClient()) {
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

        // Create: no Minecraft 26.1 build exists, so the Create glue is compiled
        // out of this jar (build.gradle excludes com/schematicraft/create/** when
        // withCreate=false, the 26.1 default) and its runtime wiring is removed
        // here. hasCreate is therefore always false on 26.1. When a 26.1 Create
        // ships, flip withCreate's default to true and restore the Create event
        // registration block (CreateClientSetup::onClientSetup and
        // SchematicTableIntegration::onScreenInit/onKeyPressed), which is what the
        // 1.21.1 build registers. See piece note 565-neo-p3b.md.
        if (hasCreate) {
            LOGGER.warn("Create reported present, but the 26.1 build ships no Create "
                    + "integration; ignoring. Rebuild with -PwithCreate once a 26.1 "
                    + "Create exists.");
        }

        if (FMLEnvironment.getDist().isClient()) {
            // Shared controls and browsing work even when only a future editor
            // integration is installed.
            modEventBus.addListener(
                com.schematicraft.client.ClientSetup::registerKeyMappings);
            NeoForge.EVENT_BUS.addListener(
                com.schematicraft.client.GlobalClientEvents::onKeyInput);
            NeoForge.EVENT_BUS.addListener(
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
            // Should not happen: the container exists by the time this constructor
            // runs. Log rather than fail, but do not report a made-up version.
            LOGGER.warn("Could not read own mod version from the loader. "
                    + "Requests will identify as an unknown version.");
            return "unknown";
        }
        return version;
    }
}
