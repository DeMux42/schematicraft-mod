package com.schematicraft.bg2addon.client;

import com.direwolf20.buildinggadgets2.client.screen.TemplateManagerGUI;
import com.schematicraft.lib.client.gui.LibraryScreen;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.client.screen.ApiKeyScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Injects a single "Schematicraft" button into BG2's Template Manager GUI.
 *
 * Clicking the button opens the shared LibraryScreen with a journey containing
 * the live slot 1 destination, slot 1 upload source, and this manager as the
 * native return screen. Downloads use BG2's SendPastePayload, so this route
 * remains client-only and does not require Schematicraft on the server.
 *
 * This replaces the previous full side-panel injection approach, which was
 * cramped, fought JEI exclusion zones, and imposed UI on users who didn't
 * want it.
 *
 * Registered manually from the mod entrypoint so this class is only loaded when
 * Building Gadgets 2 is installed.
 */
public class TemplateManagerIntegration {

    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TemplateManagerGUI gui)) return;

        // Position below the Template Manager GUI, centered
        // BG2's Template Manager is 176px wide, centered on screen
        int guiLeft = (gui.width - 176) / 2;
        int guiTop = (gui.height - 166) / 2;
        int btnW = 90;
        int btnH = 16;
        int btnX = guiLeft + 4; // Left-aligned under the GUI
        int btnY = guiTop + 166 + 10; // 10px below the GUI

        if (!ModConfig.hasApiKey()) {
            event.addListener(Button.builder(
                    Component.literal("\u00a7bSchematicraft"),
                    b -> Minecraft.getInstance().setScreen(new ApiKeyScreen(gui))
            ).bounds(btnX, btnY, btnW, btnH).build());
            return;
        }

        event.addListener(Button.builder(
                Component.literal("\u00a7a\u2601 Schematicraft"),
                b -> Minecraft.getInstance().setScreen(
                        new LibraryScreen(ClientSetup.templateJourney(gui)))
        ).bounds(btnX, btnY, btnW, btnH)
                .tooltip(Tooltip.create(Component.literal(
                        "Browse your cloud library.\n"
                        + "Loads into the template slot, so put paper there first. "
                        + "No gadget needed.")))
                .build());
    }
}
