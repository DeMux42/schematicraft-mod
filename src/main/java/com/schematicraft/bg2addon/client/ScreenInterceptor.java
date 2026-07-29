package com.schematicraft.bg2addon.client;

import com.direwolf20.buildinggadgets2.client.screen.ModeRadialMenu;
import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.items.GadgetCutPaste;
import com.schematicraft.lib.client.gui.LibraryScreen;
import com.schematicraft.lib.client.gui.TargetDevice;
import com.schematicraft.lib.client.screen.ApiKeyScreen;
import com.schematicraft.lib.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Adds a Schematicraft entry to BG2's Copy/Paste gadget radial menu.
 *
 * The radial is a launcher, not a second library UI. BG2's own mode wheel is
 * left completely intact; we only add one button that opens the shared
 * {@link LibraryScreen} with the gadget as the load target. All browsing,
 * searching, palette apply, and upload live in that one screen.
 *
 * Registered manually from the mod entrypoint so this class is only loaded when
 * Building Gadgets 2 is installed.
 */
public class ScreenInterceptor {

    private static final int BTN_W = 96;
    private static final int BTN_H = 16;

    public static void onScreenOpen(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof ModeRadialMenu screen)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        var gadget = BaseGadget.getGadget(mc.player);
        if (gadget.isEmpty()) return;
        if (!(gadget.getItem() instanceof GadgetCopyPaste)
                && !(gadget.getItem() instanceof GadgetCutPaste)) return;

        // Bottom center, clear of BG2's mode wheel in the middle of the screen.
        int x = (screen.width - BTN_W) / 2;
        int y = screen.height - 30;

        if (!ModConfig.hasApiKey()) {
            event.addListener(Button.builder(
                    Component.literal("\u00a7bSchematicraft"),
                    b -> mc.setScreen(new ApiKeyScreen(null))
            ).bounds(x, y, BTN_W, BTN_H).build());
            return;
        }

        event.addListener(Button.builder(
                Component.literal("\u00a7a\u2601 Schematicraft"),
                b -> {
                    var journey = ClientSetup.resolveHeldJourney(mc.player);
                    mc.setScreen(new LibraryScreen(
                            journey != null ? journey
                                    : com.schematicraft.lib.client.gui.EditorJourney.browse()));
                }
        ).bounds(x, y, BTN_W, BTN_H).build());
    }
}
