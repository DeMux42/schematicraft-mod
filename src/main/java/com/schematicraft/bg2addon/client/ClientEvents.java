package com.schematicraft.bg2addon.client;

import com.schematicraft.lib.client.screen.ApiKeyScreen;
import com.schematicraft.bg2addon.client.screen.SchematiCraftScreen;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.network.ServerMode;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;

@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ClientEvents {

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        if (ModKeyBindings.OPEN_SCHEMATICRAFT.consumeClick()) {
            if (!ModConfig.hasApiKey()) {
                mc.setScreen(new ApiKeyScreen(null));
            } else {
                mc.setScreen(new SchematiCraftScreen());
            }
        }

        if (ModKeyBindings.OPEN_API_KEY_SCREEN.consumeClick()) {
            mc.setScreen(new ApiKeyScreen(null));
        }

        // Ctrl+1 through Ctrl+8: switch palette tab (works without panel open)
        if (event.getAction() == 1 && (event.getModifiers() & 2) != 0) { // GLFW_PRESS + CTRL
            int num = event.getKey() - 49; // GLFW_KEY_1 = 49, so 0-7
            if (num >= 0 && num <= 7) {
                com.schematicraft.lib.core.PaletteState state = com.schematicraft.lib.core.PaletteState.get();
                if (num == 7) {
                    // Ctrl+8 = Home tab
                    state.setActiveTab(com.schematicraft.lib.core.PaletteState.MAX_PINNED_SLOTS);
                } else if (state.isSlotPinned(num)) {
                    state.setActiveTab(num);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ServerMode.reset();
    }
}
