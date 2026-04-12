package com.schematicraft.bg2addon.integration;

import com.schematicraft.SchematiCraftMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(bus = EventBusSubscriber.Bus.GAME, modid = SchematiCraftMod.MODID)
public class ServerEvents {

    private static int tickCounter = 0;
    private static final int CHECK_INTERVAL_TICKS = 20;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        if (tickCounter < CHECK_INTERVAL_TICKS) return;
        tickCounter = 0;

        if (!BG2Integration.isBG2Loaded()) return;

        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        for (var player : server.getPlayerList().getPlayers()) {
            ClipboardTracker.checkForNewCopy(player);
        }
    }
}
