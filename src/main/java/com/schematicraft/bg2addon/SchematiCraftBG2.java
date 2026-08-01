package com.schematicraft.bg2addon;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Constants for the Building Gadgets 2 integration.
 *
 * This is NOT a mod entrypoint. The Building Gadgets 2 integration ships inside
 * the unified Schematicraft mod, which owns the single {@code @Mod} entrypoint
 * ({@link com.schematicraft.SchematiCraftMod}) and activates this integration at
 * runtime only when Building Gadgets 2 is installed.
 *
 * MODID is kept as a namespace for keybind translation keys and log context so
 * existing language keys and user keybind settings stay valid.
 */
public final class SchematiCraftBG2 {
    /** Namespace used for keybind translation keys. Not a registered mod id. */
    public static final String MODID = "schematicraft_bg2";

    public static final Logger LOGGER = LogUtils.getLogger();

    private SchematiCraftBG2() {}
}
