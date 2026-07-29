package com.schematicraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.schematicraft.SchematiCraftMod;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Shared controls available regardless of which editor is installed. */
public final class ModKeyBindings {
    private static final String CATEGORY = "key." + SchematiCraftMod.MODID + ".category";

    public static final KeyMapping OPEN_SCHEMATICRAFT = new KeyMapping(
            "key." + SchematiCraftMod.MODID + ".open_schematicraft",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY);

    public static final KeyMapping OPEN_API_KEY_SCREEN = new KeyMapping(
            "key." + SchematiCraftMod.MODID + ".open_api_key",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    private ModKeyBindings() {}
}
