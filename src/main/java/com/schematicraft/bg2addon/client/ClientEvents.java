package com.schematicraft.bg2addon.client;

import com.direwolf20.buildinggadgets2.client.KeyBindings;
import com.schematicraft.lib.client.gui.SchematicraftScreen;
import com.schematicraft.lib.network.ServerMode;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** BG2-specific connection cleanup and keybind isolation. */
public final class ClientEvents {
    private ClientEvents() {}

    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ServerMode.reset();
        // Clipboard entries and previews belong to the session that created them.
        com.schematicraft.bg2addon.core.SchematiCraftState.get().clearClipboard();
        ClipboardPreviewRenderer.get().clearClientData();
    }

    /** Drain BG2 actions while a Schematicraft screen owns keyboard input. */
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
