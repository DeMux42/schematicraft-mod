package com.schematicraft.litematica.client;

import com.mojang.logging.LogUtils;
import com.schematicraft.lib.client.gui.EditorJourney;
import com.schematicraft.lib.client.gui.LibraryScreen;
import com.schematicraft.lib.client.gui.LoadLimits;
import com.schematicraft.lib.client.gui.TargetCatalog;
import com.schematicraft.lib.client.gui.TargetDevice;
import com.schematicraft.lib.client.gui.UploadSource;
import com.schematicraft.lib.network.SchematiCraftAPIWrapper;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Registers Litematica's file-based journey, the Fabric editor integration.
 *
 * Litematica is a client-side schematic mod with no server component and no
 * in-world table GUI to intercept (unlike Create), so the journey is: browse the
 * cloud library, download a .litematic into Litematica's schematics folder (it
 * appears in Litematica's Load browser), and upload a .litematic read back from
 * that folder. The file handoff mirrors the NeoForge Create glue; only the
 * directory (Litematica's DataManager) and the extension (.litematic) differ.
 *
 * Called from the Fabric client initializer only when Litematica is loaded, so
 * nothing here runs, and no Litematica class loads, when the editor is absent.
 */
public final class LitematicaClientSetup {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final TargetDevice.Type TARGET =
            TargetDevice.Type.of("litematica:schematics");

    /**
     * Device name shared by the download target and the upload source, so
     * "Download to" and "Upload from" name the same place identically. The
     * selected filename is content and belongs on the candidate, not here.
     */
    private static final String DEVICE = "Litematica";

    private LitematicaClientSetup() {}

    /** Register Litematica with the shared library. Litematica is confirmed loaded. */
    public static void register() {
        LibraryScreen.setLoadHandler(TARGET,
                (target, data, schematicName) -> loadIntoLitematica(data, schematicName));
        // Litematica loads a whole structure into memory; cap the download size as
        // Create does, so a very large schematic cannot stall the client.
        LibraryScreen.setLoadLimits(TARGET,
                new LoadLimits(0, 0, 64 * 1024 * 1024,
                        "very large structures stall the client"));

        TargetCatalog.register(new TargetCatalog.Entry(
                TARGET, DEVICE, "litematica:schematic",
                "Open Litematica's Load Schematics browser", "Add to Litematica",
                "Written to Litematica's schematics folder", "Litematica",
                "litematic", "Litematica"));

        // Litematica is not a held-item editor: it is the active editor whenever
        // it is installed, so the keybind-opened library always carries its
        // journey (the folder download target and the folder upload source).
        EditorJourney.registerHeldResolver(player -> journey());

        LOGGER.info("Schematicraft Litematica client setup complete");
    }

    /**
     * A browse journey whose upload source reads Litematica's schematics folder.
     * Litematica has no in-world GUI to hand a return screen, so the return is the
     * library's own default and the journey carries the folder upload source.
     */
    public static EditorJourney journey() {
        return new EditorJourney(
                TargetDevice.of(TARGET, TargetDevice.Mode.CLIENT_ONLY),
                new LitematicaFolderUploadSource(), null, null);
    }

    static LibraryScreen.LoadResult loadIntoLitematica(byte[] data, String title) {
        Path saved = LitematicaFileHelper.saveToSchematicsFolder(title, data);
        if (saved == null) {
            return LibraryScreen.LoadResult.failure(
                    "Could not write to Litematica's schematics folder");
        }
        LOGGER.info("Loaded schematic into Litematica: {}", saved.getFileName());
        return LibraryScreen.LoadResult.success(0);
    }

    /** Uploads any .litematic in Litematica's schematics folder, newest first. */
    private static final class LitematicaFolderUploadSource implements UploadSource {
        @Override
        public String displayName() {
            return DEVICE;
        }

        @Override
        public ItemStack icon() {
            Identifier id = Identifier.tryParse("litematica:schematic");
            if (id == null) return ItemStack.EMPTY;
            var item = Registries.ITEM.get(id);
            // Litematica registers no item under that id; the empty stack is the
            // honest fallback and the screen renders no icon, as with any source
            // whose item is absent.
            return new ItemStack(item);
        }

        @Override
        public boolean isReady() {
            return !listCandidates().isEmpty();
        }

        @Override
        public List<Candidate> listCandidates() {
            Path dir = LitematicaFileHelper.schematicsDir();
            if (!Files.isDirectory(dir)) return List.of();
            String ext = LitematicaSchematic.FILE_EXTENSION;
            List<Candidate> out = new ArrayList<>();
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> p.getFileName().toString().endsWith(ext))
                        .sorted(Comparator.comparingLong(LitematicaClientSetup::lastModified).reversed())
                        .forEach(p -> out.add(new Candidate(
                                p.toAbsolutePath().normalize().toString(),
                                p.getFileName().toString(), "Litematica file")));
            } catch (Exception e) {
                LOGGER.debug("Could not list Litematica schematics: {}", e.getMessage());
            }
            return out;
        }

        @Override
        public String emptyHint() {
            return "Save a schematic in Litematica first";
        }

        @Override
        public CompletableFuture<Boolean> upload(
                String candidateId, String title, String description,
                @Nullable String bundleId, List<Path> images) {
            Path current = resolveInFolder(candidateId);
            if (current == null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "That Litematica file is no longer available. Reopen upload and try again."));
            }
            return SchematiCraftAPIWrapper.get().uploadFile(
                    current, title, description, "1.21.1", "fabric",
                    bundleId, images);
        }
    }

    /** Resolve a candidate id to a real file inside Litematica's folder, no escape. */
    @Nullable
    static Path resolveInFolder(@Nullable String candidateId) {
        if (candidateId == null || candidateId.isBlank()) return null;
        try {
            Path root = LitematicaFileHelper.schematicsDir().toAbsolutePath().normalize();
            Path candidate = Path.of(candidateId).toAbsolutePath().normalize();
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
            LOGGER.debug("Rejected Litematica selection: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }
}
