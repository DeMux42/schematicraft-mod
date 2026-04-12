package com.schematicraft.bg2addon.client;

import com.schematicraft.SchematiCraftMod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

public class ClientSetup {

    public static void onClientSetup(FMLClientSetupEvent event) {
        SchematiCraftMod.LOGGER.info("Schematicraft BG2 client setup complete");
    }

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ModKeyBindings.OPEN_SCHEMATICRAFT);
        event.register(ModKeyBindings.OPEN_API_KEY_SCREEN);
    }
}
