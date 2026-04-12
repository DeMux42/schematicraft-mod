package com.schematicraft.bg2addon.network;

import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.items.GadgetCutPaste;
import com.direwolf20.buildinggadgets2.common.worlddata.BG2Data;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.schematicraft.SchematiCraftMod;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side handler for LoadTemplatePayload.
 * Parses the BG2 NBT data and loads it into the player's held CopyPaste gadget.
 */
public class LoadTemplateHandler {
    public static final LoadTemplateHandler INSTANCE = new LoadTemplateHandler();

    public void handle(final LoadTemplatePayload payload, final IPayloadContext context) {
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
                        Component.literal("\u00a7cHold a Copy/Paste gadget to load templates."), true);
                return;
            }

            try {
                ArrayList<StatePos> buildList = BG2Data.statePosListFromNBTMapArray(payload.templateData());

                if (buildList.isEmpty()) {
                    player.displayClientMessage(
                            Component.literal("\u00a7cTemplate is empty or invalid."), true);
                    return;
                }

                UUID gadgetUUID = GadgetNBT.getUUID(gadgetStack);
                BG2Data bg2Data = BG2Data.get(
                        Objects.requireNonNull(player.level().getServer()).overworld());

                bg2Data.addToCopyPaste(gadgetUUID, buildList);

                GadgetNBT.setCopyUUID(gadgetStack);

                var modes = com.direwolf20.buildinggadgets2.api.gadgets.GadgetModes.INSTANCE
                        .getModesForGadget(((BaseGadget) gadgetStack.getItem()).gadgetTarget());
                modes.stream()
                        .filter(m -> m.getId().getPath().equals("paste"))
                        .findFirst()
                        .ifPresent(pasteMode -> GadgetNBT.setMode(gadgetStack, pasteMode));

                player.displayClientMessage(
                        Component.literal("\u00a7aTemplate loaded (" + buildList.size() + " blocks). Ready to paste."),
                        true);

                SchematiCraftMod.LOGGER.info("Loaded template with {} blocks for player {}",
                        buildList.size(), player.getName().getString());

            } catch (Exception e) {
                SchematiCraftMod.LOGGER.error("Failed to load template: {}", e.getMessage(), e);
                player.displayClientMessage(
                        Component.literal("\u00a7cFailed to load template: " + e.getMessage()), true);
            }
        });
    }
}
