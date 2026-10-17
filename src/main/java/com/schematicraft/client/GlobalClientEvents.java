package com.schematicraft.client;

import com.schematicraft.lib.client.gui.EditorJourney;
import com.schematicraft.lib.client.gui.LibraryScreen;
import com.schematicraft.lib.client.screen.ApiKeyScreen;
import com.schematicraft.lib.config.ModConfig;
import net.minecraft.client.MinecraftClient;

/** Global key handling shared by every editor integration. */
public final class GlobalClientEvents {
    private GlobalClientEvents() {}

    /**
     * Poll the shared keybinds. Called once per client tick by the Fabric client
     * initializer (ClientTickEvents.END_CLIENT_TICK), which is the Fabric
     * equivalent of the NeoForge InputEvent.Key handler this replaces. Behaviour
     * is unchanged: only act with a player in world and no screen open.
     */
    public static void onClientTick(MinecraftClient mc) {
        if (mc.player == null || mc.currentScreen != null) return;

        if (ModKeyBindings.OPEN_SCHEMATICRAFT.wasPressed()) {
            if (!ModConfig.hasApiKey()) {
                mc.setScreen(new ApiKeyScreen(null));
            } else {
                mc.setScreen(new LibraryScreen(EditorJourney.resolveHeld()));
            }
        }

        if (ModKeyBindings.OPEN_API_KEY_SCREEN.wasPressed()) {
            mc.setScreen(new ApiKeyScreen(null));
        }
    }

    /**
     * Clear pending upload state on disconnect. Called by the Fabric client
     * initializer (ClientPlayConnectionEvents.DISCONNECT), the equivalent of the
     * NeoForge ClientPlayerNetworkEvent.LoggingOut handler this replaces.
     */
    public static void onDisconnect() {
        com.schematicraft.lib.client.gui.UploadScreen.clearPendingState();
    }
}
