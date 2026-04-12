package com.schematicraft.create.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.utility.CreatePaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Manages schematic files in Create's local schematics directory.
 * Downloads from Schematicraft are saved here so they appear in the
 * Schematic Table's file picker. Uploads read from here.
 */
public class SchematicFileHelper {
	private static final Logger LOGGER = LogUtils.getLogger();

	/**
	 * Save downloaded schematic data to Create's schematics folder.
	 * The file will appear in the Schematic Table after a refresh.
	 *
	 * @param filename Target filename (should end in .nbt)
	 * @param data     Raw schematic bytes (gzipped NBT)
	 * @return Path to the saved file, or null on failure
	 */
	public static Path saveToSchematicsFolder(String filename, byte[] data) {
		try {
			Path dir = CreatePaths.SCHEMATICS_DIR;
			Files.createDirectories(dir);

			// Sanitize filename
			String safe = filename.replaceAll("[^a-zA-Z0-9._\\- ]", "_");
			if (!safe.endsWith(".nbt"))
				safe += ".nbt";

			Path target = dir.resolve(safe);

			// Avoid overwriting existing files
			if (Files.exists(target)) {
				String base = safe.substring(0, safe.length() - 4);
				int i = 1;
				while (Files.exists(target)) {
					target = dir.resolve(base + "_" + i + ".nbt");
					i++;
				}
			}

			Files.write(target, data);
			LOGGER.info("Saved schematic to {}", target.getFileName());
			return target;
		} catch (IOException e) {
			LOGGER.error("Failed to save schematic: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * List all .nbt files in Create's schematics folder.
	 * Used for the upload picker.
	 */
	public static List<Path> listLocalSchematics() {
		List<Path> result = new ArrayList<>();
		try {
			Path dir = CreatePaths.SCHEMATICS_DIR;
			if (!Files.exists(dir))
				return result;

			try (Stream<Path> paths = Files.list(dir)) {
				paths.filter(p -> !Files.isDirectory(p) && p.toString().endsWith(".nbt"))
					.forEach(result::add);
			}
		} catch (IOException e) {
			LOGGER.error("Failed to list schematics: {}", e.getMessage());
		}
		return result;
	}
}
