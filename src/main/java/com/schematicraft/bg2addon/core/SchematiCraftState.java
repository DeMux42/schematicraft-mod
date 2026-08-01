package com.schematicraft.bg2addon.core;

import com.schematicraft.lib.core.BundleEntry;
import com.schematicraft.lib.core.LibraryState;
import com.schematicraft.lib.core.SchematicEntry;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BG2-specific state. Manages clipboard entries (BG2 copy/paste gadget feature).
 * Library state is delegated to the shared LibraryState in schematicraft-lib.
 *
 * <p>The clipboard list here is the local player's view. It is populated from
 * {@code SyncClipboardDataPayload}, which the server sends only to the player who
 * made the copy. Server-side code must not write to it: this is a process-wide
 * singleton, so on an integrated server it is shared with the server thread, and
 * writing from both sides would mix players and duplicate entries.
 */
public class SchematiCraftState {
    private static final SchematiCraftState INSTANCE = new SchematiCraftState();
    private static final int MAX_CLIPBOARD = 50;

    private final CopyOnWriteArrayList<ClipboardEntry> clipboard = new CopyOnWriteArrayList<>();

    private SchematiCraftState() {}

    public static SchematiCraftState get() { return INSTANCE; }

    // Clipboard (BG2-specific)

    /** Add a local clipboard entry, ignoring a snapshot that is already present. */
    public void addToClipboard(ClipboardEntry entry) {
        if (entry == null) {
            return;
        }
        for (ClipboardEntry existing : clipboard) {
            if (existing.getGadgetUuid().equals(entry.getGadgetUuid())) {
                return;
            }
        }
        clipboard.add(0, entry);
        if (clipboard.size() > MAX_CLIPBOARD) {
            clipboard.remove(clipboard.size() - 1);
        }
    }

    /** Drop all local clipboard entries, for example on disconnect. */
    public void clearClipboard() {
        clipboard.clear();
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
