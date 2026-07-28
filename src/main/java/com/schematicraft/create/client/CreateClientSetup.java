package com.schematicraft.create.client;

import com.mojang.logging.LogUtils;
import com.schematicraft.lib.client.gui.LibraryScreen;
import com.schematicraft.lib.client.gui.LoadLimits;
import com.schematicraft.lib.client.gui.TargetDevice;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Registers the Create integration into the shared library screen.
 *
 * Create's workflow is file based: a downloaded schematic is written into
 * Create's schematics folder and the Schematic Table's file list is refreshed.
 * That needs no server-side component, so this path works on any server.
 *
 * Without this registration the library screen has no load handler for Create
 * and loading silently fails, which is exactly what used to happen.
 */
public class CreateClientSetup {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void onClientSetup(FMLClientSetupEvent event) {
        // Registered only for the Create target, so Building Gadgets can coexist
        // in the same jar without either integration clobbering the other.
        LibraryScreen.setLoadHandler(TargetDevice.Type.CREATE_SCHEMATIC_TABLE,
                (target, data) -> loadIntoCreate(data, "schematicraft_import"));

        // No block-count limit. Create's path writes a file on this machine and
        // never sends the schematic over the network, so the packet NBT ceiling
        // that constrains Building Gadgets does not apply.
        //
        // A byte cap still applies. Create's own loader has to read the file, and
        // a multi-hundred-megabyte structure would stall or crash the client
        // regardless of how it got there.
        LibraryScreen.setLoadLimits(TargetDevice.Type.CREATE_SCHEMATIC_TABLE,
                new LoadLimits(0, 0, 64 * 1024 * 1024,
                        "very large structures stall the client"));

        com.schematicraft.lib.client.gui.TargetCatalog.register(
                new com.schematicraft.lib.client.gui.TargetCatalog.Entry(
                        TargetDevice.Type.CREATE_SCHEMATIC_TABLE,
                        "Schematic Table",
                        "create:schematic_table",
                        "Open a Schematic Table"));

        LOGGER.info("Schematicraft Create client setup complete");
    }

    /**
     * Write schematic bytes into Create's schematics folder and refresh the
     * file list so the new file shows up in the Schematic Table immediately.
     */
    public static LibraryScreen.LoadResult loadIntoCreate(byte[] data, String title) {
        Path saved = SchematicFileHelper.saveToSchematicsFolder(title, data);
        if (saved == null) {
            LOGGER.warn("Failed to write schematic into Create's schematics folder");
            return LibraryScreen.LoadResult.failure(
                    "Could not write to Create's schematics folder");
        }

        refreshCreateFileList();
        LOGGER.info("Loaded schematic into Create: {}", saved.getFileName());
        return LibraryScreen.LoadResult.success(0);
    }

    /**
     * Ask Create to rescan its schematics folder.
     * Done reflectively so a Create internal rename degrades to "file is on disk
     * but the list needs reopening" instead of breaking the whole load.
     */
    private static void refreshCreateFileList() {
        try {
            Class<?> createClient = Class.forName("com.simibubi.create.CreateClient");
            Object sender = createClient.getField("SCHEMATIC_SENDER").get(null);
            sender.getClass().getMethod("refresh").invoke(sender);
        } catch (Throwable t) {
            LOGGER.debug("Could not refresh Create's schematic list ({}). "
                    + "The file is saved; reopen the Schematic Table to see it.",
                    t.getClass().getSimpleName());
        }
    }
}
