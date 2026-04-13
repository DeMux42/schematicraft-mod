package com.schematicraft.lib.client.screen;

import com.mojang.logging.LogUtils;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.core.*;
import com.schematicraft.lib.network.SchematiCraftAPIWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Shared palette panel rendering for the Schematicraft side panel.
 * Used by both BG2 (TemplateManagerIntegration) and Create (SchematicTableIntegration).
 *
 * Layout (170px wide, left side of screen):
 *   Header: brand + item count + close
 *   Filter: always-on text field, auto-focused
 *   Tab strip: pinned bundle tabs (1-7) + Home tab (8)
 *   Schematic list: filtered results with bundle headers (Home) or flat list (pinned tab)
 *   Status bar: match count + keyboard hints
 *
 * All filtering is local (sub-millisecond). Cloud search is a separate mode.
 */
public class PalettePanel {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int PANEL_W = 170;

    private EditBox filterField;
    private SchematicListWidget listWidget;
    private final BiConsumer<String, String> onSchematicSelected;
    private int selectedIndex = 0;
    private boolean initialized = false;

    public PalettePanel(BiConsumer<String, String> onSchematicSelected) {
        this.onSchematicSelected = onSchematicSelected;
    }

    /**
     * Initialize the panel widgets. Call from ScreenEvent.Init.Post.
     */
    public void init(ScreenEvent.Init.Post event, Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        PaletteState state = PaletteState.get();

        // Load pinned state from config on first init
        if (!initialized) {
            state.loadPinnedFromConfig(ModConfig.getPinnedBundles());
            SchematicFileCache.get().init();
            initialized = true;
        }

        int leftX = 4;
        int y = 10;

        // Header: brand link + logout
        event.addListener(net.minecraft.client.gui.components.Button.builder(
                Component.literal("\u00a7b\u2601 Schematicraft"),
                b -> net.minecraft.Util.getPlatform().openUri("https://www.schematicraft.com"))
                .bounds(leftX, y, PANEL_W - 14, 12).build());
        event.addListener(net.minecraft.client.gui.components.Button.builder(
                Component.literal("\u00a7c\u2716"),
                b -> { ModConfig.setApiKey(""); mc.setScreen(screen); })
                .bounds(leftX + PANEL_W - 12, y, 12, 12).build());
        y += 14;

        // Filter field (always on, auto-focused)
        filterField = new EditBox(mc.font, leftX, y, PANEL_W, 14, Component.literal(""));
        filterField.setHint(Component.literal("Filter schematics..."));
        filterField.setMaxLength(100);
        filterField.setValue(state.getFilterText());
        filterField.setResponder(text -> {
            state.setFilterText(text);
            rebuildList();
        });
        filterField.setFocused(true);
        event.addListener(filterField);
        y += 16;

        // Tab strip: pinned bundles + Home
        y = initTabStrip(event, screen, mc, leftX, y);

        // Schematic list
        int listH = screen.height - y - 16;
        listWidget = new SchematicListWidget(mc, PANEL_W, listH, y, leftX, (id, title) -> {
            onSchematicSelected.accept(id, title);
        });
        event.addListener(listWidget);

        // Trigger initial filter
        state.refilter();
        rebuildList();

        // Load library if needed
        LibraryState lib = LibraryState.get();
        if (!lib.isLibraryLoaded() && !lib.isLibraryLoading()) {
            SchematiCraftAPIWrapper.get().loadLibrary().thenRun(() ->
                    mc.execute(() -> {
                        state.refilter();
                        rebuildList();
                    }));
        }
    }

    private int initTabStrip(ScreenEvent.Init.Post event, Screen screen, Minecraft mc, int leftX, int y) {
        PaletteState state = PaletteState.get();
        int tabW = PANEL_W / 8; // 8 tabs total (7 pinnable + Home)
        int tabH = 14;

        for (int i = 0; i < PaletteState.MAX_PINNED_SLOTS; i++) {
            final int slot = i;
            boolean pinned = state.isSlotPinned(i);
            boolean active = state.getActiveTab() == i;
            String label = pinned ? String.valueOf(i + 1) : "\u00a78" + (i + 1);
            if (active && pinned) label = "\u00a7b" + (i + 1);

            var btn = net.minecraft.client.gui.components.Button.builder(
                    Component.literal(label),
                    b -> {
                        state.setActiveTab(slot);
                        mc.setScreen(screen); // re-init to rebuild
                    })
                    .bounds(leftX + i * (tabW + 1), y, tabW, tabH).build();
            if (!pinned) btn.active = false;
            event.addListener(btn);
        }

        // Home tab (last slot)
        boolean homeActive = state.isHomeTab();
        var homeBtn = net.minecraft.client.gui.components.Button.builder(
                Component.literal(homeActive ? "\u00a7b\u2302" : "\u2302"),
                b -> {
                    state.setActiveTab(PaletteState.MAX_PINNED_SLOTS);
                    mc.setScreen(screen);
                })
                .bounds(leftX + PaletteState.MAX_PINNED_SLOTS * (tabW + 1), y, tabW, tabH).build();
        event.addListener(homeBtn);

        return y + tabH + 2;
    }

    /**
     * Rebuild the list widget from PaletteState's filtered results.
     */
    public void rebuildList() {
        if (listWidget == null) return;
        listWidget.clearEntries();

        PaletteState state = PaletteState.get();
        LibraryState lib = LibraryState.get();

        if (lib.isLibraryLoading()) {
            listWidget.addEntry(new SchematicListWidget.MessageEntry(listWidget, "Loading..."));
            return;
        }
        if (lib.getLibraryError() != null) {
            listWidget.addEntry(new SchematicListWidget.MessageEntry(listWidget, lib.getLibraryError()));
            return;
        }

        List<PaletteState.FilteredEntry> results = state.getFilteredResults();
        if (results.isEmpty()) {
            String msg = state.getFilterText().isEmpty() ? "No schematics" : "No matches";
            listWidget.addEntry(new SchematicListWidget.MessageEntry(listWidget, msg));
            return;
        }

        String query = state.getFilterText().toLowerCase().trim();
        SchematicFileCache fileCache = SchematicFileCache.get();

        for (PaletteState.FilteredEntry entry : results) {
            switch (entry.getType()) {
                case BUNDLE_HEADER -> {
                    BundleEntry b = entry.getBundle();
                    String headerText = "\u00a7e" + b.name();
                    if (!query.isEmpty()) {
                        headerText += " \u00a77(" + entry.getMatchCount() + ")";
                    }
                    listWidget.addEntry(new SchematicListWidget.HeaderEntry(listWidget, headerText));
                }
                case SCHEMATIC -> {
                    SchematicEntry s = entry.getSchematic();
                    String title = s.title() != null ? s.title() : "Untitled";

                    // Highlight matching text
                    if (!query.isEmpty()) {
                        title = highlightMatch(title, query);
                    }

                    // Cache indicator in subtitle
                    String subtitle = fileCache.isCached(s.id()) ? "\u00a7a\u25cf cached" : "";

                    listWidget.addEntry(new SchematicListWidget.SchematicEntry(
                            listWidget, s.id(), title, subtitle, s.thumbnailUrl()));
                }
                case MESSAGE -> {
                    listWidget.addEntry(new SchematicListWidget.MessageEntry(listWidget, "No matches"));
                }
            }
        }
    }

    /**
     * Highlight matching substring with Minecraft color codes.
     */
    private String highlightMatch(String text, String query) {
        String lower = text.toLowerCase();
        int idx = lower.indexOf(query);
        if (idx < 0) return text;
        return text.substring(0, idx)
                + "\u00a7b" + text.substring(idx, idx + query.length())
                + "\u00a7r" + text.substring(idx + query.length());
    }

    /**
     * Render the panel background and re-render widgets on top.
     * Call from ScreenEvent.Render.Post.
     */
    public void render(GuiGraphics g, Screen screen, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();

        // Panel background
        g.fill(0, 6, PANEL_W + 8, screen.height - 6, 0xD0080808);
        g.fill(PANEL_W + 8, 6, PANEL_W + 9, screen.height - 6, 0x40FFFFFF);

        // Header underline
        g.fill(4, 20, 4 + PANEL_W, 21, 0x30FFFFFF);

        // Active tab indicator
        PaletteState state = PaletteState.get();
        if (!state.isHomeTab() && state.getActiveBundleId() != null) {
            String bundleName = state.getActiveBundleName();
            g.drawString(mc.font, "\u00a7e" + bundleName, 6, screen.height - 26, 0xFFAA00);
        }

        // Status bar
        int totalCount = countTotalSchematics();
        int shownCount = countShownSchematics(state.getFilteredResults());
        String statusLeft = shownCount + " of " + totalCount;
        String statusRight = "\u2191\u2193 nav \u00b7 Enter load";
        g.drawString(mc.font, "\u00a78" + statusLeft, 6, screen.height - 12, 0x666666);
        int rightW = mc.font.width(statusRight);
        g.drawString(mc.font, "\u00a78" + statusRight, PANEL_W + 4 - rightW, screen.height - 12, 0x666666);

        // Re-render widgets on top of panel background
        if (listWidget != null) listWidget.render(g, mouseX, mouseY, partialTick);
        if (filterField != null) filterField.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * Handle key press events. Returns true if consumed.
     */
    public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        // Esc: clear filter first, then close panel
        if (keyCode == 256) { // Escape
            PaletteState state = PaletteState.get();
            if (!state.getFilterText().isEmpty()) {
                state.clearFilter();
                if (filterField != null) filterField.setValue("");
                rebuildList();
                return true;
            }
            return false; // let the screen handle close
        }

        // Arrow keys for list navigation
        if (keyCode == 264) { // Down
            moveSelection(1);
            return true;
        }
        if (keyCode == 265) { // Up
            moveSelection(-1);
            return true;
        }

        // Enter: load selected
        if (keyCode == 257) { // Enter
            loadSelected();
            return true;
        }

        // Tab: cycle tabs
        if (keyCode == 258) { // Tab
            cycleTab(modifiers);
            return true;
        }

        // Forward to filter field
        if (filterField != null && filterField.isFocused()) {
            filterField.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }

        return false;
    }

    public boolean onCharTyped(char codePoint, int modifiers) {
        if (filterField != null && filterField.isFocused()) {
            filterField.charTyped(codePoint, modifiers);
            return true;
        }
        return false;
    }

    private void moveSelection(int delta) {
        if (listWidget == null) return;
        var children = listWidget.children();
        if (children.isEmpty()) return;

        // Find next selectable entry (skip headers and messages)
        int newIndex = selectedIndex + delta;
        while (newIndex >= 0 && newIndex < children.size()) {
            if (children.get(newIndex) instanceof SchematicListWidget.SchematicEntry) {
                selectedIndex = newIndex;
                listWidget.setSelected(children.get(newIndex));
                return;
            }
            newIndex += delta;
        }
    }

    private void loadSelected() {
        if (listWidget == null) return;
        var children = listWidget.children();
        if (selectedIndex >= 0 && selectedIndex < children.size()) {
            var entry = children.get(selectedIndex);
            if (entry instanceof SchematicListWidget.SchematicEntry se) {
                onSchematicSelected.accept(se.getSchematicId(), se.getNarration().getString());
            }
        }
    }

    private void cycleTab(int modifiers) {
        PaletteState state = PaletteState.get();
        int current = state.getActiveTab();
        int next;
        if ((modifiers & 1) != 0) { // Shift+Tab = backwards
            next = current - 1;
            if (next < 0) next = PaletteState.MAX_PINNED_SLOTS;
        } else {
            next = current + 1;
            if (next > PaletteState.MAX_PINNED_SLOTS) next = 0;
        }
        // Skip unpinned slots
        int attempts = 0;
        while (attempts <= PaletteState.MAX_PINNED_SLOTS + 1) {
            if (next == PaletteState.MAX_PINNED_SLOTS || state.isSlotPinned(next)) {
                state.setActiveTab(next);
                selectedIndex = 0;
                rebuildList();
                return;
            }
            next = (modifiers & 1) != 0 ? next - 1 : next + 1;
            if (next < 0) next = PaletteState.MAX_PINNED_SLOTS;
            if (next > PaletteState.MAX_PINNED_SLOTS) next = 0;
            attempts++;
        }
    }

    private int countTotalSchematics() {
        LibraryState lib = LibraryState.get();
        int count = lib.getUnbundled().size();
        for (BundleEntry b : lib.getBundles()) count += b.schematics().size();
        return count;
    }

    private int countShownSchematics(List<PaletteState.FilteredEntry> results) {
        int count = 0;
        for (PaletteState.FilteredEntry e : results) {
            if (e.getType() == PaletteState.FilteredEntry.Type.SCHEMATIC) count++;
        }
        return count;
    }

    /**
     * Pin a bundle to the first available slot.
     * Called when right-clicking a bundle header in the list.
     */
    public void pinBundleToNextSlot(String bundleId, String bundleName) {
        PaletteState state = PaletteState.get();
        int slot = state.getFirstEmptySlot();
        if (slot < 0) return; // all slots full
        state.pinBundle(slot, bundleId, bundleName);
        ModConfig.setPinnedBundles(state.savePinnedToConfig());
        LOGGER.info("Pinned bundle '{}' to slot {}", bundleName, slot + 1);
    }

    public EditBox getFilterField() { return filterField; }
    public SchematicListWidget getListWidget() { return listWidget; }
}
