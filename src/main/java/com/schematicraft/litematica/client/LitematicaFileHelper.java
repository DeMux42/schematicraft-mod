package com.schematicraft.litematica.client;

import com.mojang.logging.LogUtils;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Manages schematic files in Litematica's local schematics directory.
 *
 * Downloads from Schematicraft are saved here so they appear in Litematica's
 * "Load Schematics" browser. Uploads read from here. This mirrors the Create
 * integration's SchematicFileHelper, with Litematica's directory and the
 * .litematic extension instead of Create's folder and .nbt.
 *
 * The directory comes from Litematica's own DataManager
 * (getSchematicsBaseDirectory), and the extension from Litematica's own
 * LitematicaSchematic.FILE_EXTENSION, so this never hard-codes either. The cloud
 * delivers a valid .litematic (the server converts on download), so this writes
 * the bytes as received and does not construct a LitematicaSchematic.
 */
public final class LitematicaFileHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

    private LitematicaFileHelper() {}

    /** Litematica's schematics directory. */
    public static Path schematicsDir() {
        return DataManager.getSchematicsBaseDirectory().toPath();
    }

    /**
     * Save downloaded schematic data to Litematica's schematics folder. The file
     * appears in Litematica's Load browser the next time it is opened.
     *
     * @param filename target name (the schematic title; extension is enforced)
     * @param data     raw .litematic bytes as delivered by the cloud
     * @return path to the saved file, or null on failure
     */
    public static Path saveToSchematicsFolder(String filename, byte[] data) {
        try {
            Path dir = schematicsDir();
            Files.createDirectories(dir);

            // Sanitize filename. Schematic titles are user text, so this has to
            // survive punctuation, emoji, and a title that reduces to nothing.
            String safe = filename == null ? "" : filename.trim();
            safe = safe.replaceAll("[^a-zA-Z0-9._\\- ]", "_").trim();
            // Long titles exist and some filesystems cap a path segment at 255.
            if (safe.length() > 60)
                safe = safe.substring(0, 60).trim();
            if (safe.isEmpty() || safe.equals("."))
                safe = "schematic";
            String ext = LitematicaSchematic.FILE_EXTENSION; // ".litematic"
            if (!safe.endsWith(ext))
                safe += ext;

            Path target = dir.resolve(safe);

            // Avoid overwriting an existing file: suffix with a counter.
            int counter = 1;
            String base = safe.substring(0, safe.length() - ext.length());
            while (Files.exists(target)) {
                target = dir.resolve(base + "_" + counter + ext);
                counter++;
            }

            Files.write(target, data);
            LOGGER.info("Saved schematic to Litematica folder: {}", target.getFileName());
            return target;
        } catch (IOException e) {
            LOGGER.error("Failed to save schematic to Litematica folder: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Copy a file into Litematica's schematics folder (used when an upload source
     * needs the file in-place). Returns the destination, or null on failure.
     */
    public static Path copyIntoSchematicsFolder(Path source) {
        try {
            Path dir = schematicsDir();
            Files.createDirectories(dir);
            Path target = dir.resolve(source.getFileName());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            LOGGER.error("Failed to copy schematic into Litematica folder: {}", e.getMessage());
            return null;
        }
    }
}
