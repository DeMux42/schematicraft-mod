package com.schematicraft.bg2addon.client;

import com.direwolf20.buildinggadgets2.client.KeyBindings;
import com.schematicraft.lib.client.gui.LibraryScreen;
import com.schematicraft.lib.client.gui.SchematicraftScreen;
import com.schematicraft.lib.client.screen.ApiKeyScreen;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.network.ServerMode;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Keybind and connection-lifecycle handling for the Building Gadgets integration.
 * Registered manually from the mod entrypoint.
 */
public class ClientEvents {

    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        if (ModKeyBindings.OPEN_SCHEMATICRAFT.consumeClick()) {
            if (!ModConfig.hasApiKey()) {
                mc.setScreen(new ApiKeyScreen(null));
            } else {
                mc.setScreen(new LibraryScreen());
            }
        }

        if (ModKeyBindings.OPEN_API_KEY_SCREEN.consumeClick()) {
            mc.setScreen(new ApiKeyScreen(null));
        }
    }

    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ServerMode.reset();
        com.schematicraft.lib.client.gui.UploadScreen.clearPendingState();
    }

    /**
     * Stops Building Gadgets keybinds from firing while a Schematicraft screen is
     * open, so typing in our text fields cannot trigger gadget actions.
     *
     * Building Gadgets reads its keybinds with {@code consumeClick()} from
     * {@code ClientTickEvent.Post} and, apart from the radial menu, does not check
     * whether a screen is open. Consuming the key in our screen therefore has no
     * effect, because BG2 never asks the screen. Instead we drain the queued
     * clicks here on {@code Pre}, which runs before BG2's {@code Post} handler and
     * leaves it nothing to consume.
     *
     * Draining rather than suppressing keeps this contained: we do not touch BG2,
     * and normal gadget keybinds work again the moment our screen closes.
     */
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof SchematicraftScreen)) return;

        drain(KeyBindings.undo);
        drain(KeyBindings.anchor);
        drain(KeyBindings.range);
    }

    private static void drain(KeyMapping mapping) {
        if (mapping == null) return;
        while (mapping.consumeClick()) {
            // Discard clicks queued while our GUI had focus.
        }
    }
}
