package com.schematicraft.bg2addon.integration;

import com.schematicraft.bg2addon.network.ServerOperationLimits;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Watches for new Building Gadgets copies so they can be offered for upload.
 * Registered manually from the mod entrypoint, so no annotation here: an
 * annotation would double-register this handler.
 */
public class ServerEvents {

    private static int tickCounter = 0;
    private static final int CHECK_INTERVAL_TICKS = 20;

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

    /**
     * Release per-player server state on disconnect: copy tracking, rate-limit
     * history, and clipboard snapshots stored in BG2 world data.
     */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        var playerId = player.getUUID();
        ClipboardTracker.forgetPlayer(playerId);
        ServerOperationLimits.forget(playerId);

        var server = player.getServer();
        if (server != null) {
            ServerClipboardRegistry.forgetPlayer(playerId, server.overworld());
        }
    }
}
