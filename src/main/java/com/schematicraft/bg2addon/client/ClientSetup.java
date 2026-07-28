package com.schematicraft.bg2addon.client;

import com.schematicraft.bg2addon.SchematiCraftBG2;
import com.schematicraft.bg2addon.core.ClipboardEntry;
import com.schematicraft.bg2addon.core.SchematiCraftState;
import com.schematicraft.bg2addon.integration.BG2GadgetHelper;
import com.schematicraft.lib.client.gui.LibraryScreen;
import com.schematicraft.lib.client.gui.LoadLimits;
import com.schematicraft.lib.client.gui.TargetCatalog;
import com.schematicraft.lib.client.gui.TargetDevice;
import com.schematicraft.lib.client.gui.UploadSource;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Registers the Building Gadgets 2 integration into the shared library screen.
 *
 * Two seams are registered here:
 * - a load handler, which puts a downloaded schematic into the gadget or the
 *   Template Manager slot depending on server capability
 * - an upload source, which exposes gadget copies so the shared upload screen
 *   can publish them without knowing anything about Building Gadgets
 */
public class ClientSetup {

    public static void onClientSetup(FMLClientSetupEvent event) {
        // Load: dispatch a downloaded schematic into BG2. Registered for both BG2
        // targets. BG2GadgetHelper picks direct gadget load or the Template
        // Manager route depending on server capability.
        LibraryScreen.LoadHandler bg2Load = (target, data) -> {
            var player = Minecraft.getInstance().player;
            if (player == null) {
                return LibraryScreen.LoadResult.failure("No player");
            }

            // Validate blocks against the registry before loading
            List<String> droppedTypes = validateBlockNames(data);

            String error = BG2GadgetHelper.loadTemplateIntoGadget(player, data);
            if (error != null) {
                return LibraryScreen.LoadResult.failure(error);
            }

            if (!droppedTypes.isEmpty()) {
                return LibraryScreen.LoadResult.partial(0, droppedTypes);
            }
            return LibraryScreen.LoadResult.success(0);
        };
        LibraryScreen.setLoadHandler(TargetDevice.Type.BG2_GADGET, bg2Load);
        LibraryScreen.setLoadHandler(TargetDevice.Type.BG2_TEMPLATE_MANAGER, bg2Load);

        // Block-count limits, so the library can warn or refuse before downloading.
        //
        // soft = Building Gadgets' own copy limit (Copy.java hardcodes 100,000
        // positions). Above this a template loads, but it is outside what BG2 was
        // built and tested for, and paste costs power per block.
        //
        // hard = derived from Minecraft's 2 MiB packet NBT ceiling. Observed
        // encoding runs about 4 bytes per block, so roughly 500,000 blocks is the
        // most that can cross the network. It is an estimate, which is why the
        // exact byte check still runs after conversion.
        // maxBytes is the deterministic guard and needs no metadata. The template
        // has to fit in a 2 MiB NBT packet anyway, and the JSON form is larger than
        // the packed NBT, so 8 MB of JSON is already far past anything loadable.
        // Refusing here avoids the freeze from parsing a huge payload we could
        // never send.
        LoadLimits bg2Limits = new LoadLimits(
                100_000, 500_000, 8 * 1024 * 1024,
                "Minecraft caps NBT in a packet at 2 MB");
        LibraryScreen.setLoadLimits(TargetDevice.Type.BG2_GADGET, bg2Limits);
        LibraryScreen.setLoadLimits(TargetDevice.Type.BG2_TEMPLATE_MANAGER, bg2Limits);

        // Compatibility catalog: what the library screen shows as loadable.
        TargetCatalog.register(new TargetCatalog.Entry(
                TargetDevice.Type.BG2_GADGET,
                "Copy/Paste Gadget",
                "buildinggadgets2:gadget_copy_paste",
                "Hold the gadget"));
        TargetCatalog.register(new TargetCatalog.Entry(
                TargetDevice.Type.BG2_TEMPLATE_MANAGER,
                "Template Manager",
                "buildinggadgets2:template_manager",
                "Open a Template Manager"));

        // Upload: expose gadget copies to the shared upload screen.
        LibraryScreen.setUploadSource(new BG2UploadSource());

        SchematiCraftBG2.LOGGER.info("Schematicraft BG2 client setup complete");
    }

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ModKeyBindings.OPEN_SCHEMATICRAFT);
        event.register(ModKeyBindings.OPEN_API_KEY_SCREEN);
    }

    /**
     * Upload source backed by the Building Gadgets clipboard.
     * Candidate ids are the clipboard entry gadget UUIDs.
     */
    private static class BG2UploadSource implements UploadSource {

        @Override
        public List<Candidate> listCandidates() {
            List<Candidate> out = new ArrayList<>();
            for (ClipboardEntry clip : SchematiCraftState.get().getClipboard()) {
                out.add(new Candidate(
                        clip.getGadgetUuid().toString(),
                        clip.getDisplayName(),
                        clip.getTimeAgo()));
            }
            return out;
        }

        @Override
        public String emptyHint() {
            return "Copy a build with your Copy/Paste gadget first";
        }

        @Override
        public CompletableFuture<Boolean> upload(String candidateId, String title,
                                                 String description, String bundleId,
                                                 List<Path> images) {
            UUID gadgetUuid;
            try {
                gadgetUuid = UUID.fromString(candidateId);
            } catch (IllegalArgumentException e) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Invalid copy reference"));
            }

            var statePosList = ClipboardPreviewRenderer.get().getClientData(gadgetUuid);
            if (statePosList == null || statePosList.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "No block data for this copy. Re-copy the build and try again."));
            }

            return com.schematicraft.bg2addon.network.SchematiCraftAPIWrapper.get()
                    .uploadFromClient(statePosList, title, description, bundleId, images);
        }
    }

    /**
     * Validates block names in a BG2 JSON template against the game registry.
     * Returns a list of block names that don't exist in the current game.
     */
    private static List<String> validateBlockNames(byte[] templateData) {
        List<String> dropped = new ArrayList<>();
        try {
            String json = new String(templateData);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("statePosArrayList")) return dropped;

            CompoundTag nbt = TagParser.parseTag(root.get("statePosArrayList").getAsString());
            ListTag blockstateMap = nbt.getList("blockstatemap", 10);

            for (int i = 0; i < blockstateMap.size(); i++) {
                String name = blockstateMap.getCompound(i).getString("Name");
                if (name.equals("minecraft:air")) continue;
                ResourceLocation rl = ResourceLocation.tryParse(name);
                if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
                    dropped.add(name);
                }
            }
        } catch (Exception e) {
            SchematiCraftBG2.LOGGER.debug("Block validation failed: {}", e.getMessage());
        }
        return dropped;
    }
}
