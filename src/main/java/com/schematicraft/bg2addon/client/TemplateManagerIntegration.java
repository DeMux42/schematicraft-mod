package com.schematicraft.bg2addon.client;

import com.direwolf20.buildinggadgets2.client.screen.TemplateManagerGUI;
import com.schematicraft.bg2addon.SchematiCraftBG2;
import com.schematicraft.lib.client.gui.LibraryScreen;
import com.schematicraft.lib.client.gui.TargetDevice;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.client.screen.ApiKeyScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Injects a single "Schematicraft" button into BG2's Template Manager GUI.
 *
 * Clicking the button opens the standalone LibraryScreen with
 * OpenContext.BG2_TEMPLATE_MANAGER, which uses BG2's native SendPastePayload
 * to load schematics into the template slot. Works client-only, no server
 * mod required.
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
                        new LibraryScreen(TargetDevice.OpenContext.BG2_TEMPLATE_MANAGER))
        ).bounds(btnX, btnY, btnW, btnH).build());
    }
}
