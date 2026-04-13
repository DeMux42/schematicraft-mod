package com.schematicraft.lib.core;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * Local file cache for downloaded schematics. Keyed by schematic ID + format.
 * LRU eviction at MAX_FILES or MAX_BYTES, whichever is hit first.
 *
 * Cache directory: config/schematicraft/cache/
 * File naming: {schematicId}.{format} (e.g., abc123.json, def456.nbt)
 *
 * This eliminates repeat download latency. Second paste of the same
 * schematic is instant (~5ms file read vs 200-3000ms network).
 */
public class SchematicFileCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SchematicFileCache INSTANCE = new SchematicFileCache();
    private static final Path CACHE_DIR = Path.of("config/schematicraft/cache");
    private static final int MAX_FILES = 100;
    private static final long MAX_BYTES = 50L * 1024 * 1024; // 50MB

    // In-memory index: schematicId -> cached file path
    private final Map<String, Path> index = new HashMap<>();
    private boolean initialized = false;

    private SchematicFileCache() {}
    public static SchematicFileCache get() { return INSTANCE; }

    /**
     * Initialize the cache by scanning the cache directory.
     */
    public void init() {
        if (initialized) return;
        initialized = true;
        try {
            Files.createDirectories(CACHE_DIR);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(CACHE_DIR)) {
                for (Path file : stream) {
                    String name = file.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) {
                        String id = name.substring(0, dot);
                        index.put(id, file);
                    }
                }
            }
            LOGGER.info("Schematic file cache initialized: {} files in {}", index.size(), CACHE_DIR);
        } catch (IOException e) {
            LOGGER.warn("Failed to initialize schematic cache: {}", e.getMessage());
        }
    }

    /**
     * Check if a schematic is cached locally.
     */
    public boolean isCached(String schematicId) {
        init();
        Path path = index.get(schematicId);
        return path != null && Files.exists(path);
    }

    /**
     * Get the cached file path for a schematic, or null if not cached.
     */
    public Path getCachedFile(String schematicId) {
        init();
        Path path = index.get(schematicId);
        if (path != null && Files.exists(path)) {
            // Touch the file to update LRU ordering
            try { Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())); }
            catch (IOException ignored) {}
            return path;
        }
        return null;
    }

    /**
     * Read cached schematic file bytes, or null if not cached.
     */
    public byte[] readCached(String schematicId) {
        Path path = getCachedFile(schematicId);
        if (path == null) return null;
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            LOGGER.warn("Failed to read cached file {}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * Store a downloaded schematic file in the cache.
     * Runs LRU eviction if limits are exceeded.
     */
    public void store(String schematicId, String format, byte[] data) {
        init();
        try {
            Files.createDirectories(CACHE_DIR);
            String ext = format != null ? format : "json";
            Path file = CACHE_DIR.resolve(schematicId + "." + ext);
            Files.write(file, data);
            index.put(schematicId, file);
            LOGGER.debug("Cached schematic {}: {} bytes", schematicId, data.length);
            evictIfNeeded();
        } catch (IOException e) {
            LOGGER.warn("Failed to cache schematic {}: {}", schematicId, e.getMessage());
        }
    }

    /**
     * Store from a temp file (move into cache).
     */
    public void storeFromFile(String schematicId, String format, Path tempFile) {
        init();
        try {
            Files.createDirectories(CACHE_DIR);
            String ext = format != null ? format : "json";
            Path target = CACHE_DIR.resolve(schematicId + "." + ext);
            Files.copy(tempFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            index.put(schematicId, target);
            long size = Files.size(target);
            LOGGER.debug("Cached schematic {} from file: {} bytes", schematicId, size);
            evictIfNeeded();
        } catch (IOException e) {
            LOGGER.warn("Failed to cache schematic {}: {}", schematicId, e.getMessage());
        }
    }

    private void evictIfNeeded() {
        try {
            long totalSize = 0;
            int fileCount = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(CACHE_DIR)) {
                for (Path f : stream) { totalSize += Files.size(f); fileCount++; }
            }

            if (fileCount <= MAX_FILES && totalSize <= MAX_BYTES) return;

            // Sort by last modified (oldest first) and delete until under limits
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(CACHE_DIR)) {
                var files = StreamSupport.stream(stream.spliterator(), false)
                        .sorted(Comparator.comparingLong(f -> {
                            try { return Files.getLastModifiedTime(f).toMillis(); }
                            catch (IOException e) { return 0L; }
                        }))
                        .toList();

                for (Path f : files) {
                    if (fileCount <= MAX_FILES && totalSize <= MAX_BYTES) break;
                    long fSize = Files.size(f);
                    Files.delete(f);
                    String name = f.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) index.remove(name.substring(0, dot));
                    totalSize -= fSize;
                    fileCount--;
                    LOGGER.debug("Evicted cached file: {}", f.getFileName());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Cache eviction error: {}", e.getMessage());
        }
    }

    /**
     * Get the number of cached files.
     */
    public int size() {
        init();
        return index.size();
    }
}
