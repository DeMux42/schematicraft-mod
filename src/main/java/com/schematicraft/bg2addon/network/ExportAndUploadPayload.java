package com.schematicraft.bg2addon.network;

import com.schematicraft.SchematiCraftMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** @deprecated Upload moved client-side in v0.1.0. Kept for wire compatibility with older clients. */
@Deprecated
public record ExportAndUploadPayload(
        UUID gadgetUuid,
        String title,
        String description,
        String bundleId, // empty string = unbundled
        List<String> imagePaths // absolute paths to screenshot PNGs
) implements CustomPacketPayload {

    public static final Type<ExportAndUploadPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SchematiCraftMod.MODID, "export_and_upload")
    );

    @Override
    public Type<ExportAndUploadPayload> type() { return TYPE; }

    public static final StreamCodec<FriendlyByteBuf, ExportAndUploadPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ExportAndUploadPayload decode(FriendlyByteBuf buf) {
                    UUID uuid = buf.readUUID();
                    String title = buf.readUtf(200);
                    String desc = buf.readUtf(5000);
                    String bundleId = buf.readUtf(100);
                    int imageCount = buf.readVarInt();
                    List<String> paths = new ArrayList<>();
                    for (int i = 0; i < imageCount; i++) paths.add(buf.readUtf(500));
                    return new ExportAndUploadPayload(uuid, title, desc, bundleId, paths);
                }

                @Override
                public void encode(FriendlyByteBuf buf, ExportAndUploadPayload p) {
                    buf.writeUUID(p.gadgetUuid);
                    buf.writeUtf(p.title, 200);
                    buf.writeUtf(p.description, 5000);
                    buf.writeUtf(p.bundleId, 100);
                    buf.writeVarInt(p.imagePaths.size());
                    for (String path : p.imagePaths) buf.writeUtf(path, 500);
                }
            };
}
