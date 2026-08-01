package com.schematicraft.bg2addon.network;

import com.schematicraft.bg2addon.SchematiCraftBG2;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-to-server packet that sends BG2 template NBT data to be loaded
 * into the player's held CopyPaste gadget.
 *
 * The CompoundTag contains the BG2 format: blockstatemap, statelist, startpos, endpos.
 */
public record LoadTemplatePayload(
        CompoundTag templateData
) implements CustomPacketPayload {

    public static final Type<LoadTemplatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SchematiCraftBG2.MODID, "load_template")
    );

    @Override
    public Type<LoadTemplatePayload> type() {
        return TYPE;
    }

    public static final StreamCodec<FriendlyByteBuf, LoadTemplatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.COMPOUND_TAG, LoadTemplatePayload::templateData,
                    LoadTemplatePayload::new
            );
}
