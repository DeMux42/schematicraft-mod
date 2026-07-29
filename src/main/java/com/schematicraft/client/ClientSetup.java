package com.schematicraft.client;

import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** Registers controls that are not owned by any one editor. */
public final class ClientSetup {
    private ClientSetup() {}

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ModKeyBindings.OPEN_SCHEMATICRAFT);
        event.register(ModKeyBindings.OPEN_API_KEY_SCREEN);
    }
}
