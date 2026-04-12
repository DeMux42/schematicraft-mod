package com.schematicraft.bg2addon.integration;

import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.items.GadgetCutPaste;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.schematicraft.bg2addon.client.TemplateManagerIntegration;
import com.schematicraft.bg2addon.network.LoadTemplatePayload;
import com.schematicraft.lib.network.ServerMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Routes template loading between two modes based on server capability.
 *
 * BG2 Integration Points:
 * - GadgetCopyPaste / GadgetCutPaste: item detection via instanceof
 * - BaseGadget.getGadget(player): finds the active gadget in player hands
 * - BG2 JSON format: {"statePosArrayList": "<SNBT>"} where the SNBT contains
 *   blockstatemap (palette) and statelist (block positions)
 *
 * Loading Modes:
 *
 * 1. Direct mode (server has this mod installed):
 *    Client sends LoadTemplatePayload (our custom packet) containing the parsed
 *    CompoundTag. The server handler writes it into BG2Data under the gadget's UUID,
 *    which updates the gadget's paste buffer. This works because our mod runs on
 *    both client and server, giving us access to BG2Data on the server side.
 *
 * 2. Template Manager fallback (client-only, server does NOT have this mod):
 *    Client sends BG2's own SendPastePayload, which is a vanilla BG2 packet that
 *    the BG2 server already knows how to handle. This is the same packet BG2's
 *    Template Manager GUI sends when you click "Paste". It writes the template
 *    data into BG2Data on the server.
 *
 * Why the Copy/Paste gadget cannot load templates in client-only mode:
 *    Loading a template into the gadget requires writing to BG2Data on the server.
 *    Our LoadTemplatePayload packet does this, but the server must have our mod
 *    installed to handle it. Without our mod on the server, the packet is silently
 *    dropped (registered as optional). BG2's own SendPastePayload works because
 *    BG2 is always on the server, but it only writes to the Template Manager's
 *    data slot, not directly to the gadget. The player must then use BG2's
 *    "Load" button in the Template Manager to copy from the template slot into
 *    the gadget. There is no BG2 packet that writes directly to a gadget's paste
 *    buffer from the client without server-side mod support.
 */
public class BG2GadgetHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

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
     * Load a BG2 JSON template into the player's gadget.
     * Uses direct mode (our packet) if the server has the mod,
     * or Template Manager fallback (BG2's own SendPastePayload) if client-only.
     */
    public static boolean loadTemplateIntoGadget(Player player, byte[] templateData) {
        if (ServerMode.isDirectModeAvailable()) {
            return loadViaDirect(templateData);
        } else {
            return TemplateManagerIntegration.loadViaTemplateManager(templateData);
        }
    }

    private static boolean loadViaDirect(byte[] templateData) {
        try {
            CompoundTag nbtData = parseBG2Json(templateData);
            if (nbtData == null) return false;

            PacketDistributor.sendToServer(new LoadTemplatePayload(nbtData));
            LOGGER.info("Sent template via direct mode ({} bytes NBT)", nbtData.sizeInBytes());
            return true;
        } catch (Exception e) {
            LOGGER.error("Direct template load failed: {}", e.getMessage());
            return false;
        }
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
