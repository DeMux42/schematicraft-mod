package com.schematicraft.bg2addon.core;

import com.schematicraft.lib.core.BundleEntry;
import com.schematicraft.lib.core.LibraryState;
import com.schematicraft.lib.core.SchematicEntry;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BG2-specific state. Manages clipboard entries (BG2 copy/paste gadget feature).
 * Library state is delegated to the shared LibraryState in schematicraft-lib.
 */
public class SchematiCraftState {
    private static final SchematiCraftState INSTANCE = new SchematiCraftState();
    private static final int MAX_CLIPBOARD = 50;

    private final CopyOnWriteArrayList<ClipboardEntry> clipboard = new CopyOnWriteArrayList<>();

    private SchematiCraftState() {}

    public static SchematiCraftState get() { return INSTANCE; }

    // Clipboard (BG2-specific)

    public void addToClipboard(ClipboardEntry entry) {
        clipboard.add(0, entry);
        if (clipboard.size() > MAX_CLIPBOARD) {
            clipboard.remove(clipboard.size() - 1);
        }
    }

    public List<ClipboardEntry> getClipboard() {
        return Collections.unmodifiableList(clipboard);
    }

    public ClipboardEntry getClipboardEntry(UUID copyUuid) {
        return clipboard.stream()
                .filter(e -> e.getCopyUuid().equals(copyUuid))
                .findFirst().orElse(null);
    }

    // Library state delegation to shared LibraryState

    public List<BundleEntry> getBundles() { return LibraryState.get().getBundles(); }
    public List<SchematicEntry> getUnbundled() { return LibraryState.get().getUnbundled(); }
    public boolean isLibraryLoaded() { return LibraryState.get().isLibraryLoaded(); }
    public boolean isLibraryLoading() { return LibraryState.get().isLibraryLoading(); }
    public String getLibraryError() { return LibraryState.get().getLibraryError(); }
    public void invalidateLibrary() { LibraryState.get().invalidateLibrary(); }

    public List<LibraryState.BundleOption> getBundleOptions() {
        return LibraryState.get().getBundleOptions();
    }
}
