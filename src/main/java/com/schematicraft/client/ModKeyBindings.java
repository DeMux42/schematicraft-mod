package com.schematicraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.schematicraft.SchematiCraftMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Shared controls available regardless of which editor is installed. */
public final class ModKeyBindings {
    // 26.1: the key-mapping category is a KeyMapping.Category record keyed by an
    // Identifier, not a String translation key. Register it once here so it sorts
    // into the controls screen under the mod's "key.<modid>.category" translation.
    // KeyMapping.Category.register(Identifier) is @Deprecated in favour of
    // RegisterKeyMappingsEvent#registerCategory, but that event method is not
    // present in this NeoForge build, so the static register is the working path.
    @SuppressWarnings("deprecation")
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(SchematiCraftMod.MODID, "category"));

    public static final KeyMapping OPEN_SCHEMATICRAFT = new KeyMapping(
            "key." + SchematiCraftMod.MODID + ".open_schematicraft",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY);

    public static final KeyMapping OPEN_API_KEY_SCREEN = new KeyMapping(
            "key." + SchematiCraftMod.MODID + ".open_api_key",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    private ModKeyBindings() {}
}
