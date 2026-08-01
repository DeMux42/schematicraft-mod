package com.schematicraft.bg2addon.integration;

import com.direwolf20.buildinggadgets2.common.containers.TemplateManagerContainer;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.items.GadgetCutPaste;
import com.direwolf20.buildinggadgets2.common.items.TemplateItem;
import com.direwolf20.buildinggadgets2.common.network.data.SendPastePayload;
import net.minecraft.world.item.Items;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.schematicraft.bg2addon.network.LoadTemplatePayload;
import com.schematicraft.lib.network.ServerMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Sends a downloaded schematic into Building Gadgets, routed by DESTINATION.
 *
 * BG2 Integration Points:
 * - GadgetCopyPaste / GadgetCutPaste: item detection via instanceof
 * - BaseGadget.getGadget(player): finds the active gadget in player hands
 * - BG2 JSON format: {"statePosArrayList": "<SNBT>"} where the SNBT contains
 *   blockstatemap (palette) and statelist (block positions)
 *
 * Two destinations, and the choice is driven by where the user opened the
 * library from, not by what the server supports:
 *
 * 1. {@link Destination#TEMPLATE}, the Template Manager's template slot.
 *    Sends BG2's own SendPastePayload, the same packet BG2's own Paste button
 *    sends. BG2's handler writes into slot 1 of the open Template Manager,
 *    converting plain paper into a Template item, and keys the block list on the
 *    TEMPLATE item's UUID. The Template Manager's 3D preview reads slot 1, so the
 *    schematic appears immediately, and the gadget slot is irrelevant. Works on
 *    any server because BG2 itself handles the packet.
 *
 * 2. {@link Destination#GADGET}, straight into the held gadget's paste buffer.
 *    Sends our own LoadTemplatePayload, which requires this mod on the server.
 *    There is no BG2 packet that writes a gadget's paste buffer from the client,
 *    so without the mod on the server this destination is unreachable and we say
 *    so instead of sending a packet that would be silently dropped.
 *
 * This used to branch on {@code ServerMode.isDirectModeAvailable()} instead, so
 * in singleplayer a load started from the Template Manager still went into the
 * gadget. That worked, but it made the Template Manager's own preview stay blank
 * and forced the user to keep a gadget in the slot for no reason.
 */
public class BG2GadgetHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Where a loaded schematic should end up. */
    public enum Destination {
        /** The Template Manager's template slot (paper or Template item). */
        TEMPLATE,
        /** The held gadget's paste buffer. Needs this mod on the server. */
        GADGET
    }

    /**
     * Minecraft refuses to decode an NBT tag larger than 2 MiB inside a packet
     * (see NbtAccounter). Both load paths send NBT over the network, so anything
     * above this cannot reach the server no matter which path we pick.
     *
     * We check before sending because the send itself cannot fail loudly:
     * PacketDistributor.sendToServer never throws, the server throws while
     * decoding, and the client would otherwise report a success that never
     * happened.
     *
     * A small margin is reserved for the packet's own framing and tag names.
     */
    private static final int NBT_PACKET_LIMIT_BYTES = 2 * 1024 * 1024;
    private static final int NBT_SAFETY_MARGIN_BYTES = 16 * 1024;
    static final int MAX_TEMPLATE_NBT_BYTES = NBT_PACKET_LIMIT_BYTES - NBT_SAFETY_MARGIN_BYTES;

    /** Template Manager slot indices, as laid out by TemplateManagerContainer. */
    private static final int TEMPLATE_SLOT = 1;

    public static boolean isHoldingCopyPaste(Player player) {
        try {
            ItemStack mainHand = player.getItemInHand(InteractionHand.MAIN_HAND);
            ItemStack offHand = player.getItemInHand(InteractionHand.OFF_HAND);
            return isCopyPasteItem(mainHand) || isCopyPasteItem(offHand);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isCopyPasteItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof GadgetCopyPaste
                || stack.getItem() instanceof GadgetCutPaste;
    }

    /**
     * Send a BG2 JSON template to the given destination.
     *
     * @return null on success, or a short user-facing reason on failure
     */
    public static String loadTemplate(Player player, byte[] templateData,
                                      Destination destination) {
        CompoundTag nbtData = parseBG2Json(templateData);
        if (nbtData == null) {
            return "This schematic could not be read as a Building Gadgets template";
        }

        // Must be checked before sending. The server throws while decoding, so a
        // send would otherwise look like it worked.
        int size = nbtData.sizeInBytes();
        if (size > MAX_TEMPLATE_NBT_BYTES) {
            LOGGER.warn("Template too large for Building Gadgets: {} bytes NBT, limit {}",
                    size, MAX_TEMPLATE_NBT_BYTES);
            return "Too big for Building Gadgets: " + formatSize(size)
                    + " (limit " + formatSize(MAX_TEMPLATE_NBT_BYTES) + ")";
        }

        String blocker = destination == Destination.TEMPLATE
                ? templateSlotBlocker(player)
                : gadgetBlocker();
        if (blocker != null) {
            return blocker;
        }

        try {
            if (destination == Destination.TEMPLATE) {
                // BG2's own packet. Its handler targets the open Template Manager's
                // slot 1 and turns paper into a Template item.
                PacketDistributor.sendToServer(new SendPastePayload(UUID.randomUUID(), nbtData));
                LOGGER.info("Sent template to the Template Manager slot ({} bytes NBT)", size);
            } else {
                PacketDistributor.sendToServer(new LoadTemplatePayload(nbtData));
                LOGGER.info("Sent template to the held gadget ({} bytes NBT)", size);
            }
            return null;
        } catch (Exception e) {
            LOGGER.error("Template load failed: {}", e.getMessage());
            return "Could not send the template to the server";
        }
    }

    /**
     * Live contents of the open Template Manager's template slot, or empty when no
     * Template Manager is open.
     *
     * The library screen renders this next to the Template Manager icon so the
     * user can see what the schematic is being written onto. Same source as the
     * preflight below, so what is shown and what is enforced cannot drift apart.
     */
    public static ItemStack templateSlotContents(@Nullable Player player) {
        if (player == null
                || !(player.containerMenu instanceof TemplateManagerContainer container)) {
            return ItemStack.EMPTY;
        }
        return container.getSlot(TEMPLATE_SLOT).getItem();
    }

    /**
     * Why the template slot cannot receive a schematic right now, or null if it can.
     *
     * BG2's paste handler returns silently when the container is wrong or the slot
     * is empty, so without this check the mod would report a success that never
     * happened. Checked on the client, where the slot contents are already synced.
     */
    private static String templateSlotBlocker(Player player) {
        if (!(player.containerMenu instanceof TemplateManagerContainer)) {
            // Closing the Template Manager while our screen is open would do this.
            return "Open a Template Manager to load into a template";
        }

        ItemStack slot = templateSlotContents(player);
        if (slot.isEmpty()) {
            return "Put paper in the Template Manager's template slot first";
        }
        if (!slot.is(Items.PAPER) && !(slot.getItem() instanceof TemplateItem)) {
            // Redprints share the slot but BG2's paste path does not register their
            // name, which would leave a half-made redprint behind.
            return "That slot needs paper or a template, not "
                    + slot.getHoverName().getString();
        }
        return null;
    }

    /** Why the held gadget cannot receive a schematic, or null if it can. */
    private static String gadgetBlocker() {
        if (!ServerMode.isDirectModeAvailable()) {
            // Say so rather than sending a packet no one will handle.
            return "This server does not have Schematicraft. "
                    + "Load into a Template Manager instead.";
        }
        return null;
    }

    /** Human-readable byte size for user-facing messages. */
    private static String formatSize(int bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return Math.max(1, bytes / 1024) + " KB";
    }

    static CompoundTag parseBG2Json(byte[] templateData) {
        try {
            String json = new String(templateData);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            CompoundTag nbtData;
            if (root.has("statePosArrayList")) {
                nbtData = TagParser.parseTag(root.get("statePosArrayList").getAsString());
            } else {
                LOGGER.warn("Unsupported template format");
                return null;
            }

            if (!nbtData.contains("blockstatemap") || !nbtData.contains("statelist")) {
                LOGGER.warn("Template NBT missing required fields");
                return null;
            }

            return nbtData;
        } catch (Exception e) {
            LOGGER.error("Failed to parse BG2 JSON: {}", e.getMessage());
            return null;
        }
    }
}
