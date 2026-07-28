package com.schematicraft.bg2addon.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.schematicraft.SchematiCraftMod;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Keybinds for opening the Schematicraft library.
 *
 * Uses the unified {@code schematicraft} namespace so the translation keys match
 * the single mod's language file and all editors share one set of controls.
 */
public class ModKeyBindings {
    private static final String CATEGORY = "key." + SchematiCraftMod.MODID + ".category";

    /** Opens the library screen. Target is resolved from what the player holds. */
    public static final KeyMapping OPEN_SCHEMATICRAFT = new KeyMapping(
            "key." + SchematiCraftMod.MODID + ".open_schematicraft",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            CATEGORY
    );

    public static final KeyMapping OPEN_API_KEY_SCREEN = new KeyMapping(
            "key." + SchematiCraftMod.MODID + ".open_api_key",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, // No default binding
            CATEGORY
    );
}
