package com.schematicraft.bg2addon.network;

import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.items.GadgetCutPaste;
import com.direwolf20.buildinggadgets2.common.worlddata.BG2Data;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.schematicraft.bg2addon.SchematiCraftBG2;
import com.schematicraft.bg2addon.integration.ServerClipboardRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side handler for LoadClipboardPayload.
 * Copies the StatePos list from the source clipboard UUID into the player's current gadget,
 * sets a new CopyUUID, and switches to paste mode.
 */
public class LoadClipboardHandler {
    public static final LoadClipboardHandler INSTANCE = new LoadClipboardHandler();

    public void handle(final LoadClipboardPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            ItemStack gadgetStack = BaseGadget.getGadget(player);

            if (gadgetStack.isEmpty()) {
                player.displayClientMessage(
                        Component.literal("\u00a7cNo gadget found. Hold a Copy/Paste gadget."), true);
                return;
            }

            if (!(gadgetStack.getItem() instanceof GadgetCopyPaste)
                    && !(gadgetStack.getItem() instanceof GadgetCutPaste)) {
                player.displayClientMessage(
                        Component.literal("\u00a7cHold a Copy/Paste gadget to load from clipboard."), true);
                return;
            }

            // This packet carries an arbitrary source UUID and can be sent by a
            // modified client, so rate limit before touching world data.
            if (!ServerOperationLimits.allowOperation(player.getUUID())) {
                player.displayClientMessage(
                        Component.literal("\u00a7cToo many clipboard loads. Wait a moment."), true);
                return;
            }

            // A UUID is an identifier, not an authorization. Only the player who
            // created the snapshot may load it.
            if (!ServerClipboardRegistry.isOwner(player.getUUID(), payload.sourceGadgetUuid())) {
                player.displayClientMessage(
                        Component.literal("\u00a7cClipboard entry not found."), true);
                SchematiCraftBG2.LOGGER.warn("Refused clipboard load for a snapshot not owned by player {}",
                        player.getName().getString());
                return;
            }

            try {
                BG2Data bg2Data = BG2Data.get(
                        Objects.requireNonNull(player.level().getServer()).overworld());

                // Get the stored StatePos list from the clipboard snapshot
                ArrayList<StatePos> list = bg2Data.getCopyPasteList(payload.sourceGadgetUuid(), false);

                if (list == null || list.isEmpty()) {
                    player.displayClientMessage(
                            Component.literal("\u00a7cClipboard entry not found or empty."), true);
                    return;
                }

                // Bound the copy before it is persisted under a new UUID.
                if (list.size() > ServerOperationLimits.MAX_BLOCKS) {
                    player.displayClientMessage(
                            Component.literal("\u00a7cClipboard entry exceeds the "
                                    + ServerOperationLimits.MAX_BLOCKS + " block limit."), true);
                    return;
                }

                // Snapshot the data under the current gadget's UUID
                UUID gadgetUuid = GadgetNBT.getUUID(gadgetStack);
                bg2Data.addToCopyPaste(gadgetUuid, new ArrayList<>(list));

                GadgetNBT.setCopyUUID(gadgetStack);

                var modes = com.direwolf20.buildinggadgets2.api.gadgets.GadgetModes.INSTANCE
                        .getModesForGadget(((BaseGadget) gadgetStack.getItem()).gadgetTarget());
                modes.stream()
                        .filter(m -> m.getId().getPath().equals("paste"))
                        .findFirst()
                        .ifPresent(pasteMode -> GadgetNBT.setMode(gadgetStack, pasteMode));

                player.displayClientMessage(
                        Component.literal("\u00a7aLoaded from clipboard (" + list.size() + " blocks). Ready to paste."),
                        true);

                SchematiCraftBG2.LOGGER.info("Loaded clipboard entry {} ({} blocks) for player {}",
                        payload.sourceGadgetUuid().toString().substring(0, 8),
                        list.size(), player.getName().getString());

            } catch (Exception e) {
                SchematiCraftBG2.LOGGER.error("Failed to load clipboard entry: {}", e.getMessage(), e);
                // Keep server-side detail in the log, not in the player message.
                player.displayClientMessage(
                        Component.literal("\u00a7cFailed to load from clipboard."), true);
            }
        });
    }
}
