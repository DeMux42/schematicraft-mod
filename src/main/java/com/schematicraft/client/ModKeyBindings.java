package com.schematicraft.client;

import com.schematicraft.SchematiCraftRef;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Shared controls available regardless of which editor is installed. */
public final class ModKeyBindings {
    private static final String CATEGORY = "key." + SchematiCraftRef.MODID + ".category";

    public static final KeyBinding OPEN_SCHEMATICRAFT = new KeyBinding(
            "key." + SchematiCraftRef.MODID + ".open_schematicraft",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY);

    public static final KeyBinding OPEN_API_KEY_SCREEN = new KeyBinding(
            "key." + SchematiCraftRef.MODID + ".open_api_key",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    private ModKeyBindings() {}
}
