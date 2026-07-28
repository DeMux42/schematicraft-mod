package com.schematicraft.bg2addon.client;

import com.direwolf20.buildinggadgets2.client.renderer.OurRenderTypes;
import com.direwolf20.buildinggadgets2.client.renderer.VBORenderer;
import com.direwolf20.buildinggadgets2.common.items.BaseGadget;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.schematicraft.bg2addon.SchematiCraftBG2;
import com.schematicraft.bg2addon.core.ClipboardEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Renders a 3D rotating preview of clipboard entries using BG2's VBORenderer.
 * Singleton. Maintains its own client-side cache of StatePos data received
 * from the server via SyncClipboardDataPayload.
 */
public class ClipboardPreviewRenderer {

    private static final ClipboardPreviewRenderer INSTANCE = new ClipboardPreviewRenderer();
    public static ClipboardPreviewRenderer get() { return INSTANCE; }

    /** Client-side cache of StatePos data keyed by clipboard UUID */
    private final Map<UUID, ArrayList<StatePos>> clientCache = new ConcurrentHashMap<>();

    private UUID preparedUuid = null;
    private Map<RenderType, VertexBuffer> vertexBuffers = null;

    private float rotationAngle = 0f;

    /** Midpoint of the current build for centering */
    private float midX, midY, midZ;

    /** Max extent for camera distance calculation */
    private float maxExtent;

    private ClipboardPreviewRenderer() {}

    public void cacheClientData(UUID uuid, ArrayList<StatePos> data) {
        clientCache.put(uuid, data);
    }

    public ArrayList<StatePos> getClientData(UUID uuid) {
        return clientCache.get(uuid);
    }

    /**
     * Build VBOs for rendering a clipboard entry's 3D preview.
     */
    public void prepareForEntry(ClipboardEntry entry) {
        if (entry == null) {
            preparedUuid = null;
            return;
        }

        UUID uuid = entry.getGadgetUuid();
        if (uuid.equals(preparedUuid)) return;

        ArrayList<StatePos> data = clientCache.get(uuid);
        if (data == null || data.isEmpty()) {
            preparedUuid = null;
            return;
        }

        try {
            Minecraft mc = Minecraft.getInstance();
            // Try player's hand first, fall back to any CopyPaste gadget in inventory
            ItemStack gadgetStack = BaseGadget.getGadget(mc.player);
            if (gadgetStack.isEmpty()) {
                // In Template Manager mode, the gadget is in the container slot, not in hand.
                // VBORenderer needs a non-empty gadget stack but only uses it for isExchanging check.
                // Create a dummy CopyPaste gadget stack.
                gadgetStack = new ItemStack(com.direwolf20.buildinggadgets2.setup.Registration.CopyPaste_Gadget.get());
            }
            if (gadgetStack.isEmpty()) return;

            // Calculate bounding box for centering
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (StatePos sp : data) {
                if (sp.pos.getX() < minX) minX = sp.pos.getX();
                if (sp.pos.getY() < minY) minY = sp.pos.getY();
                if (sp.pos.getZ() < minZ) minZ = sp.pos.getZ();
                if (sp.pos.getX() > maxX) maxX = sp.pos.getX();
                if (sp.pos.getY() > maxY) maxY = sp.pos.getY();
                if (sp.pos.getZ() > maxZ) maxZ = sp.pos.getZ();
            }
            midX = (minX + maxX) / 2f;
            midY = (minY + maxY) / 2f;
            midZ = (minZ + maxZ) / 2f;
            maxExtent = Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ) + 1;

            var vertexBuffers = RenderType.chunkBufferLayers().stream()
                    .collect(Collectors.toMap(
                            rt -> rt, rt -> new VertexBuffer(
                                    VertexBuffer.Usage.STATIC)));
            VBORenderer.generateRender(mc.level, BlockPos.ZERO, gadgetStack, 1f, data, vertexBuffers);
            this.vertexBuffers = vertexBuffers;
            preparedUuid = uuid;

        } catch (Exception e) {
            SchematiCraftBG2.LOGGER.debug("Failed to prepare preview for {}: {}", uuid, e.getMessage());
            preparedUuid = null;
        }
    }

    public void render(GuiGraphics guiGraphics, int x, int y, int w, int h) {
        if (preparedUuid == null || vertexBuffers == null) return;

        Minecraft mc = Minecraft.getInstance();

        try {
            rotationAngle += 0.5f;
            if (rotationAngle >= 360f) rotationAngle -= 360f;

            double scale = mc.getWindow().getGuiScale();
            int vpX = (int) Math.round(x * scale);
            int vpY = (int) Math.round(mc.getWindow().getHeight() - (y + h) * scale);
            int vpW = (int) Math.round(w * scale);
            int vpH = (int) Math.round(h * scale);

            RenderSystem.viewport(vpX, vpY, vpW, vpH);
            RenderSystem.backupProjectionMatrix();

            Matrix4f proj = new Matrix4f();
            proj.setPerspective((float) Math.toRadians(60), (float) w / h, 0.1f, 1000f);
            RenderSystem.setProjectionMatrix(proj, com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);

            PoseStack ps = guiGraphics.pose();
            ps.pushPose();
            ps.setIdentity();

            float zoom = -maxExtent * 2.0f;
            ps.translate(0, 0, zoom);
            ps.mulPose(createRotation(20f, rotationAngle, 0f));
            ps.translate(-midX, -midY, -midZ);

            RenderSystem.applyModelViewMatrix();
            org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT);

            RenderSystem.runAsFancy(() -> {
                try {
                    for (RenderType rt : new RenderType[]{RenderType.solid(), RenderType.cutout(), RenderType.cutoutMipped(), RenderType.translucent()}) {
                        RenderType drawType = rt.equals(RenderType.cutout()) ? OurRenderTypes.RenderBlock : RenderType.translucent();
                        VertexBuffer vb = vertexBuffers.get(rt);
                        if (vb == null || vb.getFormat() == null) continue;
                        drawType.setupRenderState();
                        vb.bind();
                        vb.drawWithShader(ps.last().pose(), RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
                        VertexBuffer.unbind();
                        drawType.clearRenderState();
                    }
                } catch (Exception ignored) {}
            });

            ps.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
            RenderSystem.restoreProjectionMatrix();

        } catch (Exception e) {
            RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private static org.joml.Quaternionf createRotation(float xDeg, float yDeg, float zDeg) {
        return new org.joml.Quaternionf()
                .rotateX((float) Math.toRadians(xDeg))
                .rotateY((float) Math.toRadians(yDeg))
                .rotateZ((float) Math.toRadians(zDeg));
    }
}
