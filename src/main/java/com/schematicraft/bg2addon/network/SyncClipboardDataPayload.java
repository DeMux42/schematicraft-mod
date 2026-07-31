package com.schematicraft.bg2addon.network;

import com.schematicraft.bg2addon.SchematiCraftBG2;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server-to-client: syncs clipboard StatePos data so the client can render a 3D preview.
 */
public record SyncClipboardDataPayload(
        UUID clipboardUuid,
        UUID copyUuid,
        CompoundTag statePosNbt
) implements CustomPacketPayload {

    public static final Type<SyncClipboardDataPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SchematiCraftBG2.MODID, "sync_clipboard_data")
    );

    @Override
    public Type<SyncClipboardDataPayload> type() { return TYPE; }

    public static final StreamCodec<FriendlyByteBuf, SyncClipboardDataPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SyncClipboardDataPayload decode(FriendlyByteBuf buf) {
                    UUID clipboardUuid = buf.readUUID();
                    UUID copyUuid = buf.readUUID();
                    CompoundTag nbt = buf.readNbt();
                    return new SyncClipboardDataPayload(clipboardUuid, copyUuid, nbt);
                }

                @Override
                public void encode(FriendlyByteBuf buf, SyncClipboardDataPayload p) {
                    buf.writeUUID(p.clipboardUuid);
                    buf.writeUUID(p.copyUuid);
                    buf.writeNbt(p.statePosNbt);
                }
            };
}
