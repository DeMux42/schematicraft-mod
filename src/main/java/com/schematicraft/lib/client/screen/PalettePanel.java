package com.schematicraft.lib.client.screen;

import com.mojang.logging.LogUtils;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.core.*;
import com.schematicraft.lib.network.SchematiCraftAPIWrapper;
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
    public static final int PANEL_W = PanelLayout.PANEL_W;

    private EditBox filterField;
    private SchematicListWidget listWidget;
    private final BiConsumer<String, String> onSchematicSelected;
    private int selectedIndex = 0;
    private boolean initialized = false;
    private int panelX = PanelLayout.LEFT_X;

    // Stored widget references for re-rendering on top of background
    private final java.util.List<net.minecraft.client.gui.components.AbstractWidget> headerWidgets = new java.util.ArrayList<>();
    private final java.util.List<net.minecraft.client.gui.components.AbstractWidget> tabWidgets = new java.util.ArrayList<>();

    public enum Side { LEFT, RIGHT }
    private Side side = Side.LEFT;

    public PalettePanel(BiConsumer<String, String> onSchematicSelected) {
        this(onSchematicSelected, Side.LEFT);
    }

    public PalettePanel(BiConsumer<String, String> onSchematicSelected, Side side) {
        this.onSchematicSelected = onSchematicSelected;
        this.side = side;
    }

    /**
     * Initialize the panel widgets. Call from ScreenEvent.Init.Post.
     */
    public void init(ScreenEvent.Init.Post event, Screen screen) {
        initWithAdder(event::addListener, screen);
    }

    /**
     * Initialize the panel widgets directly (for Screen subclasses we own).
     * The adder should call addRenderableWidget or equivalent.
     */
    public void initDirect(java.util.function.Consumer<net.minecraft.client.gui.components.events.GuiEventListener> adder, Screen screen) {
        initWithAdder(adder, screen);
    }

    private void initWithAdder(java.util.function.Consumer<net.minecraft.client.gui.components.events.GuiEventListener> adder, Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        PaletteState state = PaletteState.get();

        // Load pinned state from config on first init
        if (!initialized) {
            state.loadPinnedFromConfig(ModConfig.getPinnedBundles());
            SchematicFileCache.get().init();
            initialized = true;
        }

        panelX = (side == Side.RIGHT) ? PanelLayout.rightX(screen.width) : PanelLayout.LEFT_X;
        int y = PanelLayout.CONTENT_TOP;
        headerWidgets.clear();
        tabWidgets.clear();

        // Header: brand link + logout
        var brandBtn = net.minecraft.client.gui.components.Button.builder(
                Component.literal("\u00a7b\u2601 Schematicraft"),
                b -> net.minecraft.Util.getPlatform().openUri("https://www.schematicraft.com"))
                .bounds(panelX, y, PANEL_W - PanelLayout.CLOSE_BTN_MARGIN, 12).build();
        adder.accept(brandBtn);
        headerWidgets.add(brandBtn);

        var closeBtn = net.minecraft.client.gui.components.Button.builder(
                Component.literal("\u00a7c\u2716"),
                b -> { ModConfig.setApiKey(""); mc.setScreen(screen); })
                .bounds(panelX + PANEL_W - PanelLayout.CLOSE_BTN_INSET, y, PanelLayout.CLOSE_BTN_W, 12).build();
        adder.accept(closeBtn);
        headerWidgets.add(closeBtn);
        y += PanelLayout.HEADER_GAP;

        // Filter field (always on, auto-focused)
        filterField = new EditBox(mc.font, panelX, y, PANEL_W, 14, Component.literal(""));
        filterField.setHint(Component.literal(state.isSearchTab() ? "Search community..." : "Filter schematics..."));
        filterField.setMaxLength(100);
        filterField.setValue(state.getFilterText());
        filterField.setResponder(text -> {
            state.setFilterText(text);
            if (state.isSearchTab()) {
                // Cloud search: debounce and hit API
                triggerCloudSearch(text);
            } else {
                rebuildList();
            }
        });
        filterField.setFocused(true);
        adder.accept(filterField);
        y += 16;

        // Tab strip: pinned bundles + Home
        y = initTabStrip(adder, screen, mc, panelX, y);

        // Schematic list (leave space at bottom for status bar)
        int listH = screen.height - y - PanelLayout.STATUS_BAR_H;
        listWidget = new SchematicListWidget(mc, PANEL_W, listH, y, panelX, (id, title) -> {
            onSchematicSelected.accept(id, title);
        });
        adder.accept(listWidget);

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

    private int initTabStrip(java.util.function.Consumer<net.minecraft.client.gui.components.events.GuiEventListener> adder, Screen screen, Minecraft mc, int panelX, int y) {
        PaletteState state = PaletteState.get();
        int totalTabs = PaletteState.MAX_PINNED_SLOTS + 2; // 6 pinnable + Home + Search
        int gap = 1;
        int tabW = (PANEL_W - (totalTabs - 1) * gap) / totalTabs;
        int tabH = 14;

        // Pinnable tabs (1-6)
        for (int i = 0; i < PaletteState.MAX_PINNED_SLOTS; i++) {
            final int slot = i;
            boolean pinned = state.isSlotPinned(i);
            boolean active = state.getActiveTab() == i;
            String label = pinned ? String.valueOf(i + 1) : "\u00a78" + (i + 1);
            if (active && pinned) label = "\u00a7b" + (i + 1);

            var btn = net.minecraft.client.gui.components.Button.builder(
                    Component.literal(label),
                    b -> {
                        if (pinned && Screen.hasControlDown() && Screen.hasShiftDown()) {
                            for (int s = 0; s < PaletteState.MAX_PINNED_SLOTS; s++) {
                                state.unpinBundle(s);
                            }
                            ModConfig.setPinnedBundles(state.savePinnedToConfig());
                            mc.setScreen(screen);
                        } else if (pinned && Screen.hasControlDown()) {
                            state.unpinBundle(slot);
                            ModConfig.setPinnedBundles(state.savePinnedToConfig());
                            mc.setScreen(screen);
                        } else if (pinned) {
                            state.setActiveTab(slot);
                            mc.setScreen(screen);
                        }
                    })
                    .bounds(panelX + i * (tabW + gap), y, tabW, tabH).build();
            if (!pinned) btn.active = false;
            // Tooltip with bundle name and clear hints
            if (pinned) {
                String bundleName = state.getPinnedBundleName(i);
                btn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal(bundleName != null ? bundleName : "Pinned")
                                .append(Component.literal("\n\u00a77Ctrl+" + (slot + 1) + " to switch"))
                                .append(Component.literal("\n\u00a78Ctrl+click: clear"))
                                .append(Component.literal("\n\u00a78Ctrl+Shift: clear all"))));
            }
            adder.accept(btn);
            tabWidgets.add(btn);
        }

        // Home tab (slot 7)
        boolean homeActive = state.isHomeTab();
        var homeBtn = net.minecraft.client.gui.components.Button.builder(
                Component.literal(homeActive ? "\u00a7b\u2302" : "\u2302"),
                b -> {
                    state.setActiveTab(PaletteState.HOME_TAB);
                    mc.setScreen(screen);
                })
                .bounds(panelX + PaletteState.MAX_PINNED_SLOTS * (tabW + gap), y, tabW, tabH).build();
        homeBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal("Home (all bundles)")
                        .append(Component.literal("\n\u00a77Ctrl+7 to switch"))));
        adder.accept(homeBtn);
        tabWidgets.add(homeBtn);

        // Search tab (slot 8)
        boolean searchActive = state.isSearchTab();
        var searchBtn = net.minecraft.client.gui.components.Button.builder(
                Component.literal(searchActive ? "\u00a7b\u2604" : "\u2604"),
                b -> {
                    state.setActiveTab(PaletteState.SEARCH_TAB);
                    mc.setScreen(screen);
                })
                .bounds(panelX + (PaletteState.MAX_PINNED_SLOTS + 1) * (tabW + gap), y, tabW, tabH).build();
        searchBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal("Search community")
                        .append(Component.literal("\n\u00a77Ctrl+8 to switch"))));
        adder.accept(searchBtn);
        tabWidgets.add(searchBtn);

        return y + tabH + 2;
    }

    /**
     * Rebuild the list widget from PaletteState's filtered results.
     * After rebuilding, auto-selects the first schematic entry.
     */
    public void rebuildList() {
        if (listWidget == null) return;
        listWidget.clearEntries();
        selectedIndex = -1;

        PaletteState state = PaletteState.get();

        // Search tab: show cloud search results
        if (state.isSearchTab()) {
            if (state.isSearchLoading()) {
                listWidget.addEntry(new SchematicListWidget.MessageEntry(listWidget, "Searching..."));
                return;
            }
            if (state.getFilterText().trim().length() < 2) {
                listWidget.addEntry(new SchematicListWidget.MessageEntry(listWidget, "Type to search community..."));
                return;
            }
            List<SchematicEntry> results = state.getSearchResults();
            if (results.isEmpty()) {
                listWidget.addEntry(new SchematicListWidget.MessageEntry(listWidget, "No results"));
                return;
            }
            SchematicFileCache fileCache = SchematicFileCache.get();
            for (SchematicEntry s : results) {
                String title = s.title() != null ? s.title() : "Untitled";
                String subtitle = s.ownerName() != null ? s.ownerName() : "";
                if (fileCache.isCached(s.id())) subtitle = "\u00a7a\u25cf " + subtitle;
                listWidget.addEntry(new SchematicListWidget.SchematicEntry(
                        listWidget, s.id(), title, subtitle, s.thumbnailUrl()));
            }
            selectFirstSchematic();
            return;
        }

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
                    listWidget.addEntry(new SchematicListWidget.HeaderEntry(
                            listWidget, headerText, b.id(), b.name()));
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

        // Auto-select the first schematic entry
        selectFirstSchematic();
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
        tickSearch(); // Process search debounce

        // Panel background
        int bgLeft = (side == Side.RIGHT) ? PanelLayout.rightBgLeft(screen.width) : PanelLayout.LEFT_BG_LEFT;
        int bgRight = (side == Side.RIGHT) ? PanelLayout.rightBgRight(screen.width) : PanelLayout.LEFT_BG_RIGHT;
        g.fill(bgLeft, PanelLayout.SCREEN_MARGIN, bgRight, screen.height - PanelLayout.SCREEN_MARGIN, 0xD0080808);
        // Edge separator
        if (side == Side.RIGHT) {
            int sep = PanelLayout.rightSeparatorX(screen.width);
            g.fill(sep, PanelLayout.SCREEN_MARGIN, sep + 1, screen.height - PanelLayout.SCREEN_MARGIN, 0x40FFFFFF);
        } else {
            g.fill(PanelLayout.LEFT_SEPARATOR_X, PanelLayout.SCREEN_MARGIN,
                    PanelLayout.LEFT_SEPARATOR_X + 1, screen.height - PanelLayout.SCREEN_MARGIN, 0x40FFFFFF);
        }

        // Header underline
        g.fill(panelX, PanelLayout.HEADER_LINE_Y, panelX + PANEL_W, PanelLayout.HEADER_LINE_Y + 1, 0x30FFFFFF);

        // Status bar
        PaletteState state = PaletteState.get();
        int totalCount = state.isSearchTab() ? state.getSearchResults().size()
                : state.isHomeTab() ? countTotalSchematics() : countScopedSchematics(state);
        int shownCount = state.isSearchTab() ? state.getSearchResults().size()
                : countShownSchematics(state.getFilteredResults());
        String statusLeft = shownCount + (state.isSearchTab() ? " results" : " of " + totalCount);
        String statusRight = "\u2191\u2193 nav \u00b7 Enter load";
        int statusY = screen.height - PanelLayout.STATUS_TEXT_Y_OFFSET;
        g.drawString(mc.font, "\u00a78" + statusLeft, panelX + 2, statusY, 0x666666);
        int rightW = mc.font.width(statusRight);
        g.drawString(mc.font, "\u00a78" + statusRight, panelX + PANEL_W - rightW, statusY, 0x666666);

        // Re-render all widgets on top of panel background
        for (var w : headerWidgets) w.render(g, mouseX, mouseY, partialTick);
        for (var w : tabWidgets) w.render(g, mouseX, mouseY, partialTick);
        if (listWidget != null) listWidget.render(g, mouseX, mouseY, partialTick);
        if (filterField != null) filterField.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * Handle key press events. Returns true if consumed.
     */
    public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        // Ctrl+1 through Ctrl+8: switch tabs
        if ((modifiers & 2) != 0 && keyCode >= 49 && keyCode <= 56) { // CTRL + GLFW_KEY_1..8
            int num = keyCode - 49;
            PaletteState state = PaletteState.get();
            if (num == 7) {
                state.setActiveTab(PaletteState.SEARCH_TAB);
            } else if (num == 6) {
                state.setActiveTab(PaletteState.HOME_TAB);
            } else if (state.isSlotPinned(num)) {
                state.setActiveTab(num);
            }
            selectedIndex = 0;
            rebuildList();
            // Re-init screen to update tab button highlights
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) mc.setScreen(mc.screen);
            return true;
        }

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
            if ((modifiers & 2) != 0) { // Ctrl+Down: jump to last
                selectLastSchematic();
            } else {
                moveSelection(1);
            }
            return true;
        }
        if (keyCode == 265) { // Up
            if ((modifiers & 2) != 0) { // Ctrl+Up: jump to first
                selectFirstSchematic();
            } else {
                moveSelection(-1);
            }
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

    private void selectFirstSchematic() {
        if (listWidget == null) return;
        var children = listWidget.children();
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) instanceof SchematicListWidget.SchematicEntry) {
                selectedIndex = i;
                listWidget.setSelected(children.get(i));
                return;
            }
        }
    }

    private void selectLastSchematic() {
        if (listWidget == null) return;
        var children = listWidget.children();
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i) instanceof SchematicListWidget.SchematicEntry) {
                selectedIndex = i;
                listWidget.setSelected(children.get(i));
                return;
            }
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
        int maxTab = PaletteState.SEARCH_TAB;
        int next;
        if ((modifiers & 1) != 0) {
            next = current - 1;
            if (next < 0) next = maxTab;
        } else {
            next = current + 1;
            if (next > maxTab) next = 0;
        }
        int attempts = 0;
        while (attempts <= maxTab + 1) {
            if (next == PaletteState.HOME_TAB || next == PaletteState.SEARCH_TAB || state.isSlotPinned(next)) {
                state.setActiveTab(next);
                selectedIndex = 0;
                rebuildList();
                return;
            }
            next = (modifiers & 1) != 0 ? next - 1 : next + 1;
            if (next < 0) next = maxTab;
            if (next > maxTab) next = 0;
            attempts++;
        }
    }

    private int countTotalSchematics() {
        LibraryState lib = LibraryState.get();
        int count = lib.getUnbundled().size();
        for (BundleEntry b : lib.getBundles()) count += b.schematics().size();
        return count;
    }

    private int countScopedSchematics(PaletteState state) {
        String bundleId = state.getActiveBundleId();
        if (bundleId == null) return countTotalSchematics();
        for (BundleEntry b : LibraryState.get().getBundles()) {
            if (bundleId.equals(b.id())) return b.schematics().size();
        }
        return 0;
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

    // Cloud search debounce
    private long lastSearchTime = 0;
    private String pendingSearchQuery = null;

    private void triggerCloudSearch(String query) {
        if (query.trim().length() < 2) {
            PaletteState.get().setSearchResults(new java.util.ArrayList<>());
            rebuildList();
            return;
        }
        pendingSearchQuery = query;
        lastSearchTime = System.currentTimeMillis();
    }

    /**
     * Call from the host screen's render method to process search debounce.
     * Checks if a pending search query has been waiting long enough (500ms).
     */
    public void tickSearch() {
        if (pendingSearchQuery != null && System.currentTimeMillis() - lastSearchTime > 500) {
            String q = pendingSearchQuery;
            pendingSearchQuery = null;
            PaletteState state = PaletteState.get();
            state.setSearchLoading(true);
            state.setLastSearchQuery(q);
            rebuildList();

            SchematiCraftAPIWrapper.get().search(q).thenAccept(json -> {
                var results = ApiJsonParser.parseSearch(json);
                java.util.List<SchematicEntry> schematics = new java.util.ArrayList<>();
                for (var r : results) schematics.add(r.schematic());
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    state.setSearchLoading(false);
                    state.setSearchResults(schematics);
                    rebuildList();
                });
            }).exceptionally(ex -> {
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    state.setSearchLoading(false);
                    state.setSearchResults(new java.util.ArrayList<>());
                    rebuildList();
                });
                return null;
            });
        }
    }
}
