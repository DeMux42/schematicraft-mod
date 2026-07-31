package com.schematicraft.bg2addon.client;

import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.items.GadgetCutPaste;
import com.direwolf20.buildinggadgets2.common.worlddata.BG2DataClient;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.schematicraft.bg2addon.SchematiCraftBG2;
import com.schematicraft.bg2addon.core.ClipboardEntry;
import com.schematicraft.bg2addon.core.SchematiCraftState;
import com.schematicraft.bg2addon.integration.BG2GadgetHelper;
import com.schematicraft.bg2addon.network.LoadTemplatePayload;
import com.schematicraft.lib.client.gui.EditorJourney;
import com.schematicraft.lib.client.gui.LibraryScreen;
import com.schematicraft.lib.client.gui.LoadLimits;
import com.schematicraft.lib.client.gui.TargetCatalog;
import com.schematicraft.lib.client.gui.TargetDevice;
import com.schematicraft.lib.client.gui.UploadSource;
import com.schematicraft.lib.network.ServerMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Registers BG2 targets, sources, and held-item journey resolution. */
public final class ClientSetup {
    public static final TargetDevice.Type GADGET_TARGET =
            TargetDevice.Type.of("buildinggadgets2:held_gadget");
    public static final TargetDevice.Type TEMPLATE_TARGET =
            TargetDevice.Type.of("buildinggadgets2:template_manager");

    /**
     * Device names shared by the download target and the upload source.
     *
     * One constant per device, used on both sides, so "Download to" and
     * "Upload from" can never drift apart again. These name the device, not the
     * content: the specific copy or template being uploaded is a candidate.
     *
     * Deliberately unqualified. "Held" and the mod name are already covered by
     * the how-to and destination hints, and repeating them here would duplicate
     * text visible on the same screen.
     */
    private static final String GADGET_DEVICE = "Copy/Paste Gadget";
    private static final String TEMPLATE_DEVICE = "Template Manager";

    private ClientSetup() {}

    public static void onClientSetup(FMLClientSetupEvent event) {
        LibraryScreen.setLoadHandler(TEMPLATE_TARGET,
                bg2LoadHandler(BG2GadgetHelper.Destination.TEMPLATE));
        LibraryScreen.setLoadHandler(GADGET_TARGET,
                bg2LoadHandler(BG2GadgetHelper.Destination.GADGET));

        LoadLimits limits = new LoadLimits(
                100_000, 500_000, 8 * 1024 * 1024,
                "Minecraft caps NBT in a packet at 2 MB");
        LibraryScreen.setLoadLimits(GADGET_TARGET, limits);
        LibraryScreen.setLoadLimits(TEMPLATE_TARGET, limits);
        TargetCatalog.register(new TargetCatalog.Entry(
                GADGET_TARGET, GADGET_DEVICE,
                "buildinggadgets2:gadget_copy_paste", "Hold a Copy/Paste or Cut/Paste Gadget",
                "Load into Gadget", "Goes into the gadget you are holding",
                "gadget", "json", "BuildingGadgets"));
        TargetCatalog.register(new TargetCatalog.Entry(
                TEMPLATE_TARGET, TEMPLATE_DEVICE,
                "buildinggadgets2:template_manager", "Open one with paper in slot 1",
                "Load into Template", "Goes into slot 1. No gadget needed.",
                "template", "json", "BuildingGadgets",
                new TargetCatalog.Receiver(
                        () -> BG2GadgetHelper.templateSlotContents(
                                Minecraft.getInstance().player),
                        "Template", "Needs paper")));

        EditorJourney.registerHeldResolver(ClientSetup::resolveHeldJourney);
        ServerMode.registerCapabilityProbe(() -> {
            var connection = Minecraft.getInstance().getConnection();
            return connection != null
                    && connection.hasChannel(LoadTemplatePayload.TYPE.id());
        });
        SchematiCraftBG2.LOGGER.info("Schematicraft BG2 client setup complete");
    }

    /** Journey used by both the global keybind and BG2's radial launcher. */
    @Nullable
    public static EditorJourney resolveHeldJourney(Player player) {
        ItemStack gadget = BaseGadget.getGadget(player);
        if (!isSupportedGadget(gadget)) return null;

        if (!ServerMode.isDirectModeAvailable()) {
            return new EditorJourney(TargetDevice.none(), null, null,
                    "Direct gadget loading needs Schematicraft on the server. "
                            + "Use a Template Manager instead.");
        }

        return new EditorJourney(
                TargetDevice.of(GADGET_TARGET, TargetDevice.Mode.SERVER),
                new GadgetUploadSource(gadget), null, null);
    }

    public static EditorJourney templateJourney(Screen manager) {
        return new EditorJourney(
                TargetDevice.of(TEMPLATE_TARGET, TargetDevice.Mode.CLIENT_ONLY),
                new TemplateUploadSource(), manager, null);
    }

    private static boolean isSupportedGadget(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof GadgetCopyPaste
                || stack.getItem() instanceof GadgetCutPaste);
    }

    private static LibraryScreen.LoadHandler bg2LoadHandler(
            BG2GadgetHelper.Destination destination) {
        return (target, data, schematicName) -> {
            Player player = Minecraft.getInstance().player;
            if (player == null) return LibraryScreen.LoadResult.failure("No player");

            List<String> droppedTypes = validateBlockNames(data);
            String error = BG2GadgetHelper.loadTemplate(player, data, destination);
            if (error != null) return LibraryScreen.LoadResult.failure(error);
            return droppedTypes.isEmpty()
                    ? LibraryScreen.LoadResult.dispatched(0)
                    : LibraryScreen.LoadResult.dispatchedPartial(0, droppedTypes);
        };
    }
    private static final class GadgetUploadSource implements UploadSource {
        private final UUID preferredGadget;
        private final ItemStack icon;

        private GadgetUploadSource(ItemStack gadget) {
            this.preferredGadget = GadgetNBT.getUUID(gadget);
            this.icon = gadget.copy();
        }

        // Names the device, matching the download target. The exact gadget is
        // still identifiable from the icon, and the specific copy from the
        // candidate list.
        @Override public String displayName() { return GADGET_DEVICE; }
        @Override public ItemStack icon() { return icon; }
        @Override public boolean isReady() {
            for (ClipboardEntry clip : SchematiCraftState.get().getClipboard()) {
                ArrayList<StatePos> data = ClipboardPreviewRenderer.get()
                        .getClientData(clip.getGadgetUuid());
                if (data != null && !data.isEmpty()) return true;
            }
            return false;
        }

        @Override
        public List<Candidate> listCandidates() {
            List<ClipboardEntry> clips = new ArrayList<>(
                    SchematiCraftState.get().getClipboard());
            clips.sort(Comparator.comparing(
                    clip -> !clip.getGadgetUuid().equals(preferredGadget)));
            List<Candidate> out = new ArrayList<>();
            for (ClipboardEntry clip : clips) {
                out.add(new Candidate(clip.getGadgetUuid().toString(),
                        clip.getDisplayName(), clip.getTimeAgo()));
            }
            return out;
        }

        @Override public String emptyHint() {
            return "Copy or cut a build with this gadget first";
        }

        @Override
        public CompletableFuture<Boolean> upload(
                String candidateId, String title, String description,
                String bundleId, List<Path> images) {
            UUID id;
            try {
                id = UUID.fromString(candidateId);
            } catch (IllegalArgumentException e) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Invalid gadget copy"));
            }
            ArrayList<StatePos> data = ClipboardPreviewRenderer.get().getClientData(id);
            return uploadStatePositions(data, title, description, bundleId, images,
                    "Re-copy the build and try again");
        }
    }

    private static final class TemplateUploadSource implements UploadSource {
        @Override public String displayName() { return TEMPLATE_DEVICE; }
        @Override public ItemStack icon() {
            return BG2GadgetHelper.templateSlotContents(Minecraft.getInstance().player).copy();
        }
        @Override public boolean isReady() {
            ItemStack stack = icon();
            if (stack.isEmpty()) return false;
            ArrayList<StatePos> data = BG2DataClient.getLookupFromUUID(
                    GadgetNBT.getUUID(stack));
            return data != null && !data.isEmpty();
        }

        @Override
        public List<Candidate> listCandidates() {
            ItemStack stack = icon();
            if (stack.isEmpty()) return List.of();
            UUID id = GadgetNBT.getUUID(stack);
            ArrayList<StatePos> data = BG2DataClient.getLookupFromUUID(id);
            if (data == null || data.isEmpty()) return List.of();
            return List.of(new Candidate(id.toString(),
                    stack.getHoverName().getString(), data.size() + " blocks"));
        }

        @Override public String emptyHint() {
            return "Save or load a template into slot 1 first";
        }

        @Override
        public CompletableFuture<Boolean> upload(
                String candidateId, String title, String description,
                String bundleId, List<Path> images) {
            UUID id;
            try {
                id = UUID.fromString(candidateId);
            } catch (IllegalArgumentException e) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Invalid template"));
            }

            ItemStack current = BG2GadgetHelper.templateSlotContents(
                    Minecraft.getInstance().player);
            if (current.isEmpty() || !id.equals(GadgetNBT.getUUID(current))) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "Template slot changed. Reopen upload and try again."));
            }

            ArrayList<StatePos> data = BG2DataClient.getLookupFromUUID(id);
            return uploadStatePositions(data, title, description, bundleId, images,
                    "Template data is not ready. Reopen the manager and try again.");
        }
    }
    private static CompletableFuture<Boolean> uploadStatePositions(
            ArrayList<StatePos> data, String title, String description,
            String bundleId, List<Path> images, String missingHint) {
        if (data == null || data.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(missingHint));
        }
        return com.schematicraft.bg2addon.network.SchematiCraftAPIWrapper.get()
                .uploadFromClient(data, title, description, bundleId, images);
    }

    private static List<String> validateBlockNames(byte[] templateData) {
        List<String> dropped = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(new String(templateData))
                    .getAsJsonObject();
            if (!root.has("statePosArrayList")) return dropped;

            CompoundTag nbt = TagParser.parseTag(
                    root.get("statePosArrayList").getAsString());
            ListTag blockstateMap = nbt.getList("blockstatemap", 10);
            for (int i = 0; i < blockstateMap.size(); i++) {
                String name = blockstateMap.getCompound(i).getString("Name");
                if (name.equals("minecraft:air")) continue;
                ResourceLocation id = ResourceLocation.tryParse(name);
                if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                    dropped.add(name);
                }
            }
        } catch (Exception e) {
            SchematiCraftBG2.LOGGER.debug(
                    "Block validation failed: {}", e.getMessage());
        }
        return dropped;
    }
}
