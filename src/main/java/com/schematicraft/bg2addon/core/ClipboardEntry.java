package com.schematicraft.bg2addon.core;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A local clipboard entry representing a copy the player made in-game.
 * Portable, no MC dependencies.
 */
public class ClipboardEntry {
    private final UUID gadgetUuid;
    private final UUID copyUuid;
    private final long timestamp;
    private final int blockCount;
    private @Nullable String title;
    private @Nullable String uploadedId;
    private List<Path> imagePaths = new ArrayList<>();

    public ClipboardEntry(UUID gadgetUuid, UUID copyUuid, int blockCount) {
        this.gadgetUuid = gadgetUuid;
        this.copyUuid = copyUuid;
        this.timestamp = System.currentTimeMillis();
        this.blockCount = blockCount;
    }

    public UUID getGadgetUuid() { return gadgetUuid; }
    public UUID getCopyUuid() { return copyUuid; }
    public long getTimestamp() { return timestamp; }
    public int getBlockCount() { return blockCount; }

    public @Nullable String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public @Nullable String getUploadedId() { return uploadedId; }
    public void setUploadedId(String id) { this.uploadedId = id; }

    public boolean isUploaded() { return uploadedId != null; }

    public List<Path> getImagePaths() { return imagePaths; }
    public void setImagePaths(List<Path> paths) { this.imagePaths = paths; }
    public boolean hasImages() { return !imagePaths.isEmpty(); }
    public int getImageCount() { return imagePaths.size(); }

    public String getDisplayName() {
        if (title != null && !title.isEmpty()) return title;
        return "Copy (" + blockCount + " blocks)";
    }

    public String getTimeAgo() {
        long seconds = (System.currentTimeMillis() - timestamp) / 1000;
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        return (seconds / 3600) + "h ago";
    }
}
