package com.schematicraft.client;

import com.schematicraft.lib.client.gui.EditorJourney;
import com.schematicraft.lib.client.gui.LibraryScreen;
import com.schematicraft.lib.client.screen.ApiKeyScreen;
import com.schematicraft.lib.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;

/** Global key handling shared by every editor integration. */
public final class GlobalClientEvents {
    private GlobalClientEvents() {}

    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        if (ModKeyBindings.OPEN_SCHEMATICRAFT.consumeClick()) {
            if (!ModConfig.hasApiKey()) {
                mc.setScreen(new ApiKeyScreen(null));
            } else {
                mc.setScreen(new LibraryScreen(EditorJourney.resolveHeld()));
            }
        }

        if (ModKeyBindings.OPEN_API_KEY_SCREEN.consumeClick()) {
            mc.setScreen(new ApiKeyScreen(null));
        }
    }

    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        com.schematicraft.lib.client.gui.UploadScreen.clearPendingState();
    }
}
