package com.schematicraft;

/**
 * Shared mod identity constant.
 *
 * The NeoForge entry point SchematiCraftMod holds MODID there, but that class is
 * excluded from the Fabric source set (it is @Mod-annotated and loader-specific),
 * so the constant lives here where both the Fabric entry points and the shared
 * client code can reach it without pulling in a loader class.
 */
public final class SchematiCraftRef {
    public static final String MODID = "schematicraft";

    private SchematiCraftRef() {}
}
