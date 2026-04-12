package com.schematicraft.bg2addon.integration;

import com.schematicraft.SchematiCraftMod;
import net.neoforged.fml.ModList;

/**
 * Checks for BG2 presence and provides safe access to BG2 classes.
 */
public class BG2Integration {
    private static boolean bg2Loaded = false;
    private static boolean checked = false;

    public static boolean isBG2Loaded() {
        if (!checked) {
            bg2Loaded = ModList.get().isLoaded("buildinggadgets2");
            checked = true;
            if (bg2Loaded) {
                SchematiCraftMod.LOGGER.info("Building Gadgets 2 detected, integration enabled");
            } else {
                SchematiCraftMod.LOGGER.info("Building Gadgets 2 not found, running in standalone mode");
            }
        }
        return bg2Loaded;
    }

    /**
     * Isolated so BG2 classes are only loaded when BG2 is present.
     */
    public static boolean isHoldingCopyPasteGadget(net.minecraft.world.entity.player.Player player) {
        if (!isBG2Loaded()) return false;
        try {
            return BG2GadgetHelper.isHoldingCopyPaste(player);
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }
}
