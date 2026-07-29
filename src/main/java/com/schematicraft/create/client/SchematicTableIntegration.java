package com.schematicraft.create.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.schematics.table.SchematicTableScreen;
import com.schematicraft.lib.client.gui.LibraryScreen;
import com.schematicraft.lib.client.screen.ApiKeyScreen;
import com.schematicraft.lib.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.List;

/**
 * Injects a single "Schematicraft" button into Create's Schematic Table screen.
 *
 * Clicking the button opens the shared LibraryScreen with a journey containing
 * Create's folder target, the selected local file as upload source, and this
 * table as the native return screen. Downloads write .nbt files directly to
 * Create's schematics folder and refresh its file list.
 *
 * This replaces the previous full side-panel injection approach.
 *
 * Registered manually from the mod entrypoint so this class is only loaded when
 * Create is installed.
 */
public class SchematicTableIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Filename to preselect when Create exposes it in the Schematic Table.
     *
     * The name remains pending while Create refreshes its list. It is cleared
     * only after selection succeeds or the downloaded file no longer exists.
     */
    private static String pendingSelection = null;

    /** Ask the table to preselect this file the next time it opens. */
    public static void selectAfterRefresh(String fileName) {
        pendingSelection = fileName;
    }

    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof SchematicTableScreen screen)) return;

        applyPendingSelection(screen);

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
                b -> Minecraft.getInstance().setScreen(new LibraryScreen(
                        CreateClientSetup.tableJourney(
                                screen, selectedSchematicFile(screen))))
        ).bounds(btnX, btnY, btnW, btnH).build());
    }

    /** Selected local filename at the moment the Schematicraft button is used. */
    private static String selectedSchematicFile(SchematicTableScreen screen) {
        try {
            Object picker = findFilePicker(screen);
            if (picker == null) return null;
            int index = (int) picker.getClass().getMethod("getState").invoke(picker);

            Class<?> createClient = Class.forName("com.simibubi.create.CreateClient");
            Object sender = createClient.getField("SCHEMATIC_SENDER").get(null);
            Object listed = sender.getClass().getMethod("getAvailableSchematics").invoke(sender);
            if (!(listed instanceof List<?> available)
                    || index < 0 || index >= available.size()) return null;
            Object selected = available.get(index);
            return selected instanceof Component component
                    ? component.getString() : null;
        } catch (Throwable t) {
            LOGGER.debug("Could not read Create's selected schematic: {}",
                    t.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Move the table's file picker onto the schematic that was just downloaded.
     *
     * Create's own init() has already refreshed the list from disk and rebuilt the
     * scroll widget by the time this runs, so the widget and the list agree.
     *
     * The index cannot be predicted: Create sorts the list with a natural-order
     * comparator, so a new file can land anywhere. Matching on the exact filename
     * is what makes this survive the numeric suffix added on a name collision.
     */
    private static void applyPendingSelection(SchematicTableScreen screen) {
        applyPendingSelection(screen, true);
    }

    private static void applyPendingSelection(SchematicTableScreen screen,
                                              boolean allowDeferredRetry) {
        String wanted = pendingSelection;
        if (wanted == null) return;

        try {
            int index = indexOfSchematic(wanted);
            if (index < 0) {
                if (CreateClientSetup.resolveSchematicFile(wanted) == null) {
                    pendingSelection = null;
                    LOGGER.debug("Downloaded schematic {} is no longer available", wanted);
                } else if (allowDeferredRetry) {
                    // Create may publish the refreshed list just after screen init.
                    // Retry once on the client queue, then retain the filename for
                    // the next table init if the refresh is still incomplete.
                    Minecraft.getInstance().execute(() -> {
                        if (Minecraft.getInstance().screen == screen) {
                            applyPendingSelection(screen, false);
                        }
                    });
                } else {
                    LOGGER.debug("Downloaded schematic {} is not in Create's list yet", wanted);
                }
                return;
            }

            Object picker = findFilePicker(screen);
            if (picker == null) return;

            // setState clamps, updates the tooltip, and rewrites the visible label.
            picker.getClass().getMethod("setState", int.class).invoke(picker, index);
            pendingSelection = null;
            LOGGER.info("Preselected {} in the Schematic Table (index {})", wanted, index);
        } catch (Throwable t) {
            // Cosmetic convenience. Retain the pending name for a later table init.
            LOGGER.debug("Could not preselect the downloaded schematic: {}",
                    t.getClass().getSimpleName());
        }
    }

    /**
     * Position of a filename in Create's available schematics list, or -1.
     *
     * Read reflectively rather than by calling CreateClient directly. Create ships
     * through CurseMaven without its catnip dependency, so touching types whose
     * supertypes live in catnip does not compile here. Reflection also means a
     * Create internal rename degrades to skipping the preselect.
     */
    private static int indexOfSchematic(String fileName) throws Exception {
        Class<?> createClient = Class.forName("com.simibubi.create.CreateClient");
        Object sender = createClient.getField("SCHEMATIC_SENDER").get(null);
        Object listed = sender.getClass().getMethod("getAvailableSchematics").invoke(sender);
        if (!(listed instanceof List<?> available)) return -1;

        for (int i = 0; i < available.size(); i++) {
            if (available.get(i) instanceof Component entry
                    && entry.getString().equals(fileName)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The table's file picker widget.
     *
     * Create keeps it in a private field, so it is found by scanning the screen's
     * widgets for a ScrollInput rather than by field name. Bails unless there is
     * exactly one match, so if Create ever adds a second scroll input to this
     * screen the result is doing nothing rather than moving the wrong control.
     */
    private static Object findFilePicker(SchematicTableScreen screen) {
        Object found = null;
        for (GuiEventListener child : screen.children()) {
            if (!isScrollInput(child.getClass())) continue;
            if (found != null) {
                LOGGER.debug("Schematic Table has more than one scroll input, "
                        + "skipping preselection");
                return null;
            }
            found = child;
        }
        return found;
    }

    /** True if the class or any ancestor is Create's ScrollInput. */
    private static boolean isScrollInput(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getSimpleName().equals("ScrollInput")) return true;
        }
        return false;
    }
}
