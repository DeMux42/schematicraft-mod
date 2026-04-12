package com.schematicraft.bg2addon.client;

import com.direwolf20.buildinggadgets2.client.screen.ModeRadialMenu;
import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.items.GadgetCutPaste;
import com.mojang.logging.LogUtils;
import com.schematicraft.SchematiCraftMod;
import com.schematicraft.bg2addon.client.screen.EnhancedRadialMenu;
import com.schematicraft.lib.network.ServerMode;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Intercepts BG2's ModeRadialMenu opening for Copy/Paste and Cut/Paste gadgets.
 *
 * Server mode (singleplayer or server has this mod):
 *   Replaces BG2's radial menu with EnhancedRadialMenu, which adds Schematicraft
 *   library/search panels on the left and clipboard/upload panels on the right,
 *   while preserving BG2's mode selection wheel in the center.
 *
 * Client-only mode (multiplayer, server does not have this mod):
 *   Lets BG2's normal ModeRadialMenu open unchanged. Mode selection (copy, paste,
 *   build, destroy) still works. Shows a one-time chat message directing the player
 *   to use the Template Manager block for Schematicraft features, because loading
 *   templates into the gadget requires server-side mod support (see BG2GadgetHelper).
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME, modid = SchematiCraftMod.MODID)
public class ScreenInterceptor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean fallbackMessageShown = false;

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onScreenOpen(ScreenEvent.Opening event) {
        if (!(event.getScreen() instanceof ModeRadialMenu)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        ItemStack gadget = BaseGadget.getGadget(mc.player);
        if (gadget.isEmpty()) return;
        if (!(gadget.getItem() instanceof GadgetCopyPaste)
                && !(gadget.getItem() instanceof GadgetCutPaste)) return;

        if (ServerMode.isDirectModeAvailable()) {
            // Server mode: full enhanced radial with library, clipboard, upload
            event.setNewScreen(new EnhancedRadialMenu(gadget));
        } else {
            // Client-only mode: let BG2's normal radial open (mode selection still works).
            // Show a one-time message directing to the Template Manager.
            if (!fallbackMessageShown) {
                mc.player.displayClientMessage(
                        Component.literal(ServerMode.getFallbackMessage()), false);
                fallbackMessageShown = true;
            }
            // Don't intercept. Let the normal ModeRadialMenu show.
        }
    }
}
