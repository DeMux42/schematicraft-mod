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
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ServerMode.reset();
    }
}
