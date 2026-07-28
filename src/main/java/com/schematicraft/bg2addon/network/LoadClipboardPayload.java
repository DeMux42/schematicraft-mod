package com.schematicraft.bg2addon.network;

import com.schematicraft.bg2addon.SchematiCraftBG2;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client-to-server: request loading a clipboard entry's template data into the current gadget.
 */
public record LoadClipboardPayload(
        UUID sourceGadgetUuid
) implements CustomPacketPayload {

    public static final Type<LoadClipboardPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SchematiCraftBG2.MODID, "load_clipboard")
    );

    @Override
    public Type<LoadClipboardPayload> type() { return TYPE; }

    public static final StreamCodec<FriendlyByteBuf, LoadClipboardPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public LoadClipboardPayload decode(FriendlyByteBuf buf) {
                    return new LoadClipboardPayload(buf.readUUID());
                }

                @Override
                public void encode(FriendlyByteBuf buf, LoadClipboardPayload p) {
                    buf.writeUUID(p.sourceGadgetUuid);
                }
            };
}
