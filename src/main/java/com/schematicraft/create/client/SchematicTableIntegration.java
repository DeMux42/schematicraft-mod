package com.schematicraft.create.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.schematics.table.SchematicTableScreen;
import com.schematicraft.lib.client.gui.LibraryScreen;
import com.schematicraft.lib.client.gui.TargetDevice;
import com.schematicraft.lib.client.screen.ApiKeyScreen;
import com.schematicraft.lib.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Injects a single "Schematicraft" button into Create's Schematic Table screen.
 *
 * Clicking the button opens the standalone LibraryScreen with
 * OpenContext.CREATE_SCHEMATIC_TABLE. The load mechanism writes .nbt files
 * directly to Create's schematics folder and refreshes the file list.
 * Purely client-side, no server mod required.
 *
 * This replaces the previous full side-panel injection approach.
 *
 * Registered manually from the mod entrypoint so this class is only loaded when
 * Create is installed.
 */
public class SchematicTableIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof SchematicTableScreen screen)) return;

        // Position the button near Create's existing buttons (top-right area)
        int btnX = (screen.width / 2) + 90;
        int btnY = (screen.height / 2) - 70;
        int btnW = 70;
        int btnH = 16;

        if (!ModConfig.hasApiKey()) {
            event.addListener(Button.builder(
                    Component.literal("\u00a7bSchematicraft"),
                    b -> Minecraft.getInstance().setScreen(new ApiKeyScreen(screen))
            ).bounds(btnX, btnY, btnW, btnH).build());
            return;
        }

        event.addListener(Button.builder(
                Component.literal("\u00a7aSchematicraft"),
                b -> Minecraft.getInstance().setScreen(
                        new LibraryScreen(TargetDevice.OpenContext.CREATE_SCHEMATIC_TABLE))
        ).bounds(btnX, btnY, btnW, btnH).build());
    }
}
