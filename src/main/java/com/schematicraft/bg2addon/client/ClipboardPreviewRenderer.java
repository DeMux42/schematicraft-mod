package com.schematicraft.bg2addon.client;

import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.schematicraft.bg2addon.SchematiCraftBG2;
import com.schematicraft.bg2addon.core.ClipboardEntry;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders a 3D rotating preview of clipboard entries. Singleton. Maintains its
 * own client-side cache of StatePos data received from the server via
 * SyncClipboardDataPayload.
 *
 * <p><b>NeoForge 26.1 port status.</b> On 1.21.1 {@link #prepareForEntry} baked
 * BG2's {@code VBORenderer.generateRender} output into
 * {@code com.mojang.blaze3d.vertex.VertexBuffer}s and {@link #render} drew them
 * with the immediate-mode pipeline ({@code RenderSystem.viewport} /
 * {@code applyModelViewMatrix} / {@code VertexBuffer.drawWithShader} /
 * {@code getShader}, a 3D {@code PoseStack} from {@code GuiGraphics.pose()},
 * {@code GL11.glClear}). 26.1 removed all of that: {@code VertexBuffer} is gone,
 * those {@code RenderSystem} methods are gone, and {@code pose()} is a 2D matrix
 * stack. This mirrors the library's {@code SchematicPreviewRenderer} (piece 3a):
 * drawing an arbitrary block mesh in a GUI in 26.1 needs the new render-pipeline
 * / picture-in-picture render-state system, which is a design rewrite that only
 * a running client can validate, and the fleet host is headless.
 *
 * <p>So this port keeps the whole public API and all the non-GPU state (the
 * client StatePos cache, its bounding, {@code prepareForEntry} bounds
 * computation and prepared-uuid bookkeeping), and the GPU build and draw are a
 * guarded no-op: {@link #render} draws nothing. The clipboard preview panel is
 * blank on 26.1 until the draw path is reimplemented on the 26.1 pipeline and
 * checked in a dev client; everything else in the BG2 integration (loading into
 * a gadget, clipboard capture and upload) is unaffected. Deferred to a follow-up,
 * called out in piece note 565-neo-p3b.md.
 */
public class ClipboardPreviewRenderer {

    private static final ClipboardPreviewRenderer INSTANCE = new ClipboardPreviewRenderer();
    public static ClipboardPreviewRenderer get() { return INSTANCE; }

    /** Maximum cached previews. The server retains fewer snapshots per player. */
    private static final int MAX_CACHED_PREVIEWS = 32;

    /** Client-side cache of StatePos data keyed by clipboard UUID */
    private final Map<UUID, ArrayList<StatePos>> clientCache = new ConcurrentHashMap<>();

    /** Insertion order for bounding {@link #clientCache}. */
    private final java.util.Deque<UUID> cacheOrder = new java.util.ArrayDeque<>();

    private UUID preparedUuid = null;

    /** Midpoint of the current build for centering (kept for the follow-up 3D path). */
    private float midX, midY, midZ;

    /** Max extent for camera distance calculation (kept for the follow-up 3D path). */
    private float maxExtent;

    private ClipboardPreviewRenderer() {}

    public void cacheClientData(UUID uuid, ArrayList<StatePos> data) {
        if (uuid == null || data == null) {
            return;
        }

        // Bound the cache so repeated copies cannot grow it without limit.
        synchronized (cacheOrder) {
            if (clientCache.put(uuid, data) == null) {
                cacheOrder.addLast(uuid);
            }
            while (cacheOrder.size() > MAX_CACHED_PREVIEWS) {
                UUID oldest = cacheOrder.pollFirst();
                if (oldest != null && !oldest.equals(uuid)) {
                    clientCache.remove(oldest);
                }
            }
        }
    }

    public ArrayList<StatePos> getClientData(UUID uuid) {
        return clientCache.get(uuid);
    }

    /** Drop all cached preview data, for example on disconnect. */
    public void clearClientData() {
        synchronized (cacheOrder) {
            clientCache.clear();
            cacheOrder.clear();
        }
    }

    /**
     * Record which clipboard entry is prepared and compute its bounds.
     *
     * <p>The 26.1 GPU VBO build is deferred (see the class note), so this keeps
     * the bounds/prepared-uuid bookkeeping that the eventual 26.1 draw path needs
     * but does not build any GPU buffers.
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

            // GPU mesh build deferred on 26.1; see class note.
            preparedUuid = uuid;

        } catch (Exception e) {
            SchematiCraftBG2.LOGGER.debug("Failed to prepare preview for {}: {}", uuid, e.getMessage());
            preparedUuid = null;
        }
    }

    /**
     * Render the 3D clipboard preview.
     * No-op on 26.1 until the GPU draw path is reimplemented (see class note).
     */
    public void render(GuiGraphicsExtractor guiGraphics, int x, int y, int w, int h) {
        if (preparedUuid == null) return;
        // 26.1 3D draw deferred; see class note.
    }
}
