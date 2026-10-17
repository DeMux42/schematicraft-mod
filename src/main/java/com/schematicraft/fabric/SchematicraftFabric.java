package com.schematicraft.fabric;

import com.mojang.logging.LogUtils;
import com.schematicraft.SchematiCraftRef;
import com.schematicraft.lib.SchematiCraftLib;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/**
 * Fabric common entry point.
 *
 * Replaces the NeoForge @Mod constructor's common half. On Fabric the mod is
 * client-only (its one editor, Litematica, is a client-side mod), so this does
 * the loader-neutral setup: initialise the shared library and identify this
 * client to the API. The client-side wiring is in SchematicraftFabricClient.
 */
public final class SchematicraftFabric implements ModInitializer {
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOGGER.info("Schematicraft initializing (Fabric)");
        SchematiCraftLib.init();

        // Identify this client to the API, as the NeoForge entry point does.
        com.schematicraft.lib.network.SchematiCraftAPIWrapper.get()
                .setClientIdentifier("schematicraft/" + modVersion() + " (fabric)");
    }

    /**
     * Mod version as reported by the loader, read from the loaded metadata so it
     * cannot drift from the installed jar. The Fabric equivalent of the NeoForge
     * ModList version read.
     */
    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer(SchematiCraftRef.MODID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElseGet(() -> {
                    LOGGER.warn("Could not read own mod version from the loader. "
                            + "Requests will identify as an unknown version.");
                    return "unknown";
                });
    }
}
