package com.schematicraft.lib.core;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Manages the palette panel state: pinned bundle tabs, active tab selection,
 * filter text, and filtered results.
 *
 * Tab model:
 *   Slots 1..N-1 are pinnable to individual bundles.
 *   Slot N (last) is always "Home" showing all bundles collapsed.
 *   Ctrl+1..Ctrl+N switches tabs. Filter is always active and scoped
 *   to the selected tab's bundle (or all bundles on Home).
 *
 * All filtering happens against the local index cache (zero network latency).
 * The cloud search API is only used when the user explicitly switches to
 * the "Cloud" scope (not part of the tab strip).
 */
public class PaletteState {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PaletteState INSTANCE = new PaletteState();
    public static final int MAX_PINNED_SLOTS = 7; // slots 1-7 are pinnable, slot 8 is Home

    private final String[] pinnedBundleIds = new String[MAX_PINNED_SLOTS];
    private final String[] pinnedBundleNames = new String[MAX_PINNED_SLOTS];
    private int activeTab = MAX_PINNED_SLOTS; // default to Home (last tab, 0-indexed as MAX_PINNED_SLOTS)
    private String filterText = "";
    private List<FilteredEntry> filteredResults = new ArrayList<>();
    private boolean cloudMode = false;

    private PaletteState() {}
    public static PaletteState get() { return INSTANCE; }

    // Tab management

    public int getActiveTab() { return activeTab; }

    public void setActiveTab(int tab) {
        if (tab < 0 || tab > MAX_PINNED_SLOTS) return;
        this.activeTab = tab;
        refilter();
    }

    public boolean isHomeTab() { return activeTab == MAX_PINNED_SLOTS; }

    public String getActiveBundleId() {
        if (isHomeTab()) return null;
        return pinnedBundleIds[activeTab];
    }

    public String getActiveBundleName() {
        if (isHomeTab()) return "Home";
        String name = pinnedBundleNames[activeTab];
        return name != null ? name : "Empty";
    }

    // Pin management

    public void pinBundle(int slot, String bundleId, String bundleName) {
        if (slot < 0 || slot >= MAX_PINNED_SLOTS) return;
        pinnedBundleIds[slot] = bundleId;
        pinnedBundleNames[slot] = bundleName;
    }

    public void unpinBundle(int slot) {
        if (slot < 0 || slot >= MAX_PINNED_SLOTS) return;
        pinnedBundleIds[slot] = null;
        pinnedBundleNames[slot] = null;
        if (activeTab == slot) activeTab = MAX_PINNED_SLOTS;
    }

    public String getPinnedBundleId(int slot) {
        if (slot < 0 || slot >= MAX_PINNED_SLOTS) return null;
        return pinnedBundleIds[slot];
    }

    public String getPinnedBundleName(int slot) {
        if (slot < 0 || slot >= MAX_PINNED_SLOTS) return null;
        return pinnedBundleNames[slot];
    }

    public boolean isSlotPinned(int slot) {
        if (slot < 0 || slot >= MAX_PINNED_SLOTS) return false;
        return pinnedBundleIds[slot] != null;
    }

    public int getFirstEmptySlot() {
        for (int i = 0; i < MAX_PINNED_SLOTS; i++) {
            if (pinnedBundleIds[i] == null) return i;
        }
        return -1;
    }

    // Filter

    public String getFilterText() { return filterText; }

    public void setFilterText(String text) {
        this.filterText = text != null ? text : "";
        refilter();
    }

    public void clearFilter() {
        this.filterText = "";
        refilter();
    }

    public boolean isCloudMode() { return cloudMode; }
    public void setCloudMode(boolean cloud) { this.cloudMode = cloud; }

    public List<FilteredEntry> getFilteredResults() {
        return filteredResults;
    }

    /**
     * Rebuild filtered results from the library state.
     * Filters by active tab's bundle scope and filter text.
     * Runs entirely against in-memory data (sub-millisecond).
     */
    public void refilter() {
        filteredResults = new ArrayList<>();
        LibraryState lib = LibraryState.get();
        if (!lib.isLibraryLoaded()) return;

        String query = filterText.toLowerCase(Locale.ROOT).trim();
        String scopeBundleId = getActiveBundleId();

        if (isHomeTab()) {
            // Home tab: show all bundles, collapsible
            for (BundleEntry bundle : lib.getBundles()) {
                List<SchematicEntry> matches = filterSchematics(bundle.schematics(), query);
                if (!matches.isEmpty() || query.isEmpty()) {
                    filteredResults.add(new FilteredEntry(FilteredEntry.Type.BUNDLE_HEADER, null, bundle, matches.size()));
                    for (SchematicEntry s : matches) {
                        filteredResults.add(new FilteredEntry(FilteredEntry.Type.SCHEMATIC, s, bundle, 0));
                    }
                }
            }
            // Unbundled
            List<SchematicEntry> unbundledMatches = filterSchematics(lib.getUnbundled(), query);
            if (!unbundledMatches.isEmpty()) {
                filteredResults.add(new FilteredEntry(FilteredEntry.Type.BUNDLE_HEADER, null,
                        new BundleEntry("__unbundled", "Unbundled", null, lib.getUnbundled()),
                        unbundledMatches.size()));
                for (SchematicEntry s : unbundledMatches) {
                    filteredResults.add(new FilteredEntry(FilteredEntry.Type.SCHEMATIC, s, null, 0));
                }
            }
        } else if (scopeBundleId != null) {
            // Pinned bundle tab: show only that bundle's schematics
            BundleEntry targetBundle = null;
            for (BundleEntry b : lib.getBundles()) {
                if (scopeBundleId.equals(b.id())) { targetBundle = b; break; }
            }
            if (targetBundle != null) {
                List<SchematicEntry> matches = filterSchematics(targetBundle.schematics(), query);
                for (SchematicEntry s : matches) {
                    filteredResults.add(new FilteredEntry(FilteredEntry.Type.SCHEMATIC, s, targetBundle, 0));
                }
                if (matches.isEmpty() && !query.isEmpty()) {
                    filteredResults.add(new FilteredEntry(FilteredEntry.Type.MESSAGE, null, null, 0));
                }
            }
        }
    }

    private List<SchematicEntry> filterSchematics(List<SchematicEntry> schematics, String query) {
        if (query.isEmpty()) return new ArrayList<>(schematics);
        List<SchematicEntry> matches = new ArrayList<>();
        for (SchematicEntry s : schematics) {
            if (matchesQuery(s, query)) {
                matches.add(s);
            }
        }
        return matches;
    }

    private boolean matchesQuery(SchematicEntry s, String query) {
        if (s.title() != null && s.title().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (s.description() != null && s.description().toLowerCase(Locale.ROOT).contains(query)) return true;
        return false;
    }

    /**
     * Load pinned state from config string.
     * Format: "bundleId1|name1,bundleId2|name2,..."
     */
    public void loadPinnedFromConfig(String configValue) {
        if (configValue == null || configValue.isEmpty()) return;
        String[] parts = configValue.split(",", -1);
        for (int i = 0; i < Math.min(parts.length, MAX_PINNED_SLOTS); i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) continue;
            int pipe = part.indexOf('|');
            if (pipe > 0) {
                pinnedBundleIds[i] = part.substring(0, pipe);
                pinnedBundleNames[i] = part.substring(pipe + 1);
            }
        }
    }

    /**
     * Serialize pinned state for config storage.
     */
    public String savePinnedToConfig() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_PINNED_SLOTS; i++) {
            if (i > 0) sb.append(",");
            if (pinnedBundleIds[i] != null && pinnedBundleNames[i] != null) {
                sb.append(pinnedBundleIds[i]).append("|").append(pinnedBundleNames[i]);
            }
        }
        return sb.toString();
    }

    // Filtered entry types

    public static class FilteredEntry {
        public enum Type { BUNDLE_HEADER, SCHEMATIC, MESSAGE }

        private final Type type;
        private final SchematicEntry schematic;
        private final BundleEntry bundle;
        private final int matchCount;

        public FilteredEntry(Type type, SchematicEntry schematic, BundleEntry bundle, int matchCount) {
            this.type = type;
            this.schematic = schematic;
            this.bundle = bundle;
            this.matchCount = matchCount;
        }

        public Type getType() { return type; }
        public SchematicEntry getSchematic() { return schematic; }
        public BundleEntry getBundle() { return bundle; }
        public int getMatchCount() { return matchCount; }
    }
}
