package com.schematicraft.create.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.utility.CreatePaths;
import com.schematicraft.lib.client.gui.EditorJourney;
import com.schematicraft.lib.client.gui.LibraryScreen;
import com.schematicraft.lib.client.gui.LoadLimits;
import com.schematicraft.lib.client.gui.TargetCatalog;
import com.schematicraft.lib.client.gui.TargetDevice;
import com.schematicraft.lib.client.gui.UploadSource;
import com.schematicraft.lib.network.SchematiCraftAPIWrapper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Registers Create's file-based journey. */
public final class CreateClientSetup {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final TargetDevice.Type TARGET =
            TargetDevice.Type.of("create:schematic_table");

    private CreateClientSetup() {}

    public static void onClientSetup(FMLClientSetupEvent event) {
        LibraryScreen.setLoadHandler(TARGET,
                (target, data, schematicName) -> loadIntoCreate(data, schematicName));
        LibraryScreen.setLoadLimits(TARGET,
                new LoadLimits(0, 0, 64 * 1024 * 1024,
                        "very large structures stall the client"));

        TargetCatalog.register(new TargetCatalog.Entry(
                TARGET, "Schematic Table", "create:schematic_table",
                "Open a Schematic Table", "Add to Create",
                "Written to Create's schematics folder", "Create",
                "nbt", "Create"));

        LOGGER.info("Schematicraft Create client setup complete");
    }

    public static EditorJourney tableJourney(Screen table,
                                               @Nullable String selectedFile) {
        Path file = resolveSchematicFile(selectedFile);
        UploadSource source = file == null ? null : new CreateFileUploadSource(file);
        return new EditorJourney(
                TargetDevice.of(TARGET, TargetDevice.Mode.CLIENT_ONLY),
                source, table, null);
    }

    /** Resolve a current Create picker value without permitting path escape. */
    @Nullable
    static Path resolveSchematicFile(@Nullable String selectedFile) {
        if (selectedFile == null || selectedFile.isBlank()) return null;

        try {
            Path root = CreatePaths.SCHEMATICS_DIR.toAbsolutePath().normalize();
            Path candidate = root.resolve(selectedFile).toAbsolutePath().normalize();
            if (!candidate.startsWith(root)
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }

            Path realRoot = root.toRealPath();
            Path realCandidate = candidate.toRealPath();
            return realCandidate.startsWith(realRoot)
                    && Files.isRegularFile(realCandidate, LinkOption.NOFOLLOW_LINKS)
                    ? realCandidate : null;
        } catch (Exception e) {
            LOGGER.debug("Rejected Create schematic selection '{}': {}",
                    selectedFile, e.getClass().getSimpleName());
            return null;
        }
    }

    public static LibraryScreen.LoadResult loadIntoCreate(byte[] data, String title) {
        Path saved = SchematicFileHelper.saveToSchematicsFolder(title, data);
        if (saved == null) {
            return LibraryScreen.LoadResult.failure(
                    "Could not write to Create's schematics folder");
        }

        refreshCreateFileList();
        SchematicTableIntegration.selectAfterRefresh(saved.getFileName().toString());
        LOGGER.info("Loaded schematic into Create: {}", saved.getFileName());
        return LibraryScreen.LoadResult.success(0);
    }

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

    private static final class CreateFileUploadSource implements UploadSource {
        private final Path file;

        private CreateFileUploadSource(Path file) {
            this.file = file;
        }

        @Override
        public String displayName() {
            return file.getFileName().toString();
        }

        @Override
        public ItemStack icon() {
            var item = BuiltInRegistries.ITEM.get(
                    ResourceLocation.fromNamespaceAndPath("create", "schematic"));
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        }

        @Override public boolean isReady() {
            return currentFile() != null;
        }

        @Override
        public List<Candidate> listCandidates() {
            Path current = currentFile();
            return current != null
                    ? List.of(new Candidate(current.toString(), displayName(), "Create file"))
                    : List.of();
        }

        @Override
        public String emptyHint() {
            return "Select a schematic file in Create first";
        }

        @Override
        public CompletableFuture<Boolean> upload(
                String candidateId, String title, String description,
                String bundleId, List<Path> images) {
            Path current = currentFile();
            if (current == null || !current.toString().equals(candidateId)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "Create selection changed. Reopen upload and try again."));
            }
            return SchematiCraftAPIWrapper.get().uploadFile(
                    current, title, description, "1.21.1", "neoforge",
                    bundleId, images);
        }

        @Nullable
        private Path currentFile() {
            try {
                Path realRoot = CreatePaths.SCHEMATICS_DIR.toRealPath();
                Path realFile = file.toRealPath();
                return realFile.startsWith(realRoot)
                        && Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)
                        ? realFile : null;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
