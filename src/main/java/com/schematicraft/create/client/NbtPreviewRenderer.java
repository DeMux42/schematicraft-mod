package com.schematicraft.create.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Renders a 3D rotating preview of .nbt structure files.
 * Standalone renderer with no BG2 dependencies. Parses vanilla structure
 * NBT format (palette + blocks list) and builds VBOs for rendering.
 *
 * Used in Create's left panel to preview local schematics.
 * Default: shows the schematic selected in Create's table.
 * On hover over a local file: temporarily shows that file instead.
 */
public class NbtPreviewRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final NbtPreviewRenderer INSTANCE = new NbtPreviewRenderer();
    public static NbtPreviewRenderer get() { return INSTANCE; }

    private String preparedFile = null;
    private Map<RenderType, VertexBuffer> vertexBuffers = null;
    private float midX, midY, midZ, maxExtent;
    private int blockCount = 0;

    // Animation state
    private static final float SPIN_SPEED = 0.4f; // degrees per frame during constant rotation
    private static final float INTRO_DURATION = 40f; // frames for the 180-degree intro spin
    private static final float RETURN_DELAY = 60f; // frames to wait after drag release before returning
    private static final float RETURN_DURATION = 30f; // frames for the return animation

    private float autoRotY = 0f; // current auto-rotation Y angle
    private float autoRotX = 20f; // fixed tilt
    private float dragRotX = 20f, dragRotY = 0f; // user-controlled rotation during drag
    private float displayRotX = 20f, displayRotY = 180f; // what's actually rendered

    private enum AnimState { SETTLE_WAIT, INTRO, SPINNING, DRAGGING, RETURN_WAIT, RETURNING }
    private AnimState animState = AnimState.SETTLE_WAIT;
    private float animTimer = 0f;
    private float returnStartX, returnStartY; // rotation at start of return animation
    private float returnTargetY; // target Y rotation to return to
    private static final float SETTLE_DELAY = 15f; // ~250ms at 60fps before intro spin starts

    // Drag tracking
    private boolean mouseDown = false;
    private double dragStartMX, dragStartMY;
    private float dragStartRotX, dragStartRotY;

    private NbtPreviewRenderer() {}

    public int getBlockCount() { return blockCount; }
    public String getPreparedFile() { return preparedFile; }

    /**
     * Prepare VBOs for a .nbt file. Call when selection changes.
     * Returns true if preparation succeeded.
     */
    public boolean prepare(Path nbtFile) {
        if (nbtFile == null) {
            preparedFile = null;
            return false;
        }

        String filePath = nbtFile.toString();
        if (filePath.equals(preparedFile)) return true; // already prepared

        try {
            List<BlockEntry> blocks = parseNbt(nbtFile);
            if (blocks.isEmpty()) {
                preparedFile = null;
                return false;
            }

            blockCount = blocks.size();
            buildVBOs(blocks);
            preparedFile = filePath;

            // Reset animation: start facing front, wait before intro spin
            autoRotY = 0f;
            displayRotY = 0f;
            displayRotX = 20f;
            animState = AnimState.SETTLE_WAIT;
            animTimer = 0f;

            return true;
        } catch (Exception e) {
            LOGGER.debug("Failed to prepare preview for {}: {}", nbtFile.getFileName(), e.getMessage());
            preparedFile = null;
            return false;
        }
    }

    /**
     * Clear the prepared preview.
     */
    public void clear() {
        preparedFile = null;
        blockCount = 0;
    }

    /**
     * Parse a vanilla .nbt structure file into block entries.
     */
    private List<BlockEntry> parseNbt(Path file) throws Exception {
        CompoundTag root;
        try (var fis = Files.newInputStream(file);
             var gis = new GZIPInputStream(fis);
             var dis = new DataInputStream(gis)) {
            root = NbtIo.read(dis);
        }

        // Parse palette
        ListTag paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < paletteTag.size(); i++) {
            CompoundTag entry = paletteTag.getCompound(i);
            palette.add(parseBlockState(entry));
        }

        // Parse blocks
        ListTag blocksTag = root.getList("blocks", Tag.TAG_COMPOUND);
        List<BlockEntry> blocks = new ArrayList<>();
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag entry = blocksTag.getCompound(i);
            int stateIndex = entry.getInt("state");
            ListTag posTag = entry.getList("pos", Tag.TAG_INT);
            BlockPos pos = new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2));
            BlockState state = stateIndex < palette.size() ? palette.get(stateIndex) : Blocks.STONE.defaultBlockState();
            if (!state.isAir()) {
                blocks.add(new BlockEntry(pos, state));
            }
        }
        return blocks;
    }

    @SuppressWarnings("unchecked")
    private BlockState parseBlockState(CompoundTag tag) {
        String name = tag.getString("Name");
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(name);
        if (rl == null) return Blocks.STONE.defaultBlockState();

        Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl);
        BlockState state = block.defaultBlockState();

        if (tag.contains("Properties", Tag.TAG_COMPOUND)) {
            CompoundTag props = tag.getCompound("Properties");
            for (String key : props.getAllKeys()) {
                Property<?> prop = state.getBlock().getStateDefinition().getProperty(key);
                if (prop != null) {
                    state = setProperty(state, prop, props.getString(key));
                }
            }
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> prop, String value) {
        Optional<T> parsed = prop.getValue(value);
        return parsed.map(t -> state.setValue(prop, t)).orElse(state);
    }

    private void buildVBOs(List<BlockEntry> blocks) {
        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
        RandomSource random = RandomSource.create();

        // Bounding box
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockEntry b : blocks) {
            minX = Math.min(minX, b.pos.getX()); maxX = Math.max(maxX, b.pos.getX());
            minY = Math.min(minY, b.pos.getY()); maxY = Math.max(maxY, b.pos.getY());
            minZ = Math.min(minZ, b.pos.getZ()); maxZ = Math.max(maxZ, b.pos.getZ());
        }
        midX = (minX + maxX) / 2f;
        midY = (minY + maxY) / 2f;
        midZ = (minZ + maxZ) / 2f;
        maxExtent = Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ) + 1;

        // Build a simple position lookup for neighbor queries
        Map<BlockPos, BlockState> blockMap = new HashMap<>();
        for (BlockEntry b : blocks) blockMap.put(b.pos, b.state);

        // Create byte buffer builders and buffer builders per render type
        Map<RenderType, ByteBufferBuilder> byteBuilders = RenderType.chunkBufferLayers().stream()
                .collect(Collectors.toMap(rt -> rt, rt -> new ByteBufferBuilder(rt.bufferSize())));
        Map<RenderType, BufferBuilder> builders = new HashMap<>();

        // Render each block
        PoseStack matrix = new PoseStack();
        for (BlockEntry b : blocks) {
            BakedModel model = dispatcher.getBlockModel(b.state);
            matrix.pushPose();
            matrix.translate(b.pos.getX(), b.pos.getY(), b.pos.getZ());

            for (RenderType rt : model.getRenderTypes(b.state, random, ModelData.EMPTY)) {
                BufferBuilder builder = builders.computeIfAbsent(rt,
                        r -> new BufferBuilder(byteBuilders.get(r), r.mode(), r.format()));
                try {
                    modelRenderer.tesselateBlock(
                            mc.level, model, b.state, b.pos.above(255),
                            matrix, builder, false, random,
                            b.state.getSeed(b.pos), OverlayTexture.NO_OVERLAY,
                            model.getModelData(mc.level, b.pos, b.state, ModelData.EMPTY), rt);
                } catch (Exception ignored) {}
            }
            matrix.popPose();
        }

        // Upload to vertex buffers
        var newBuffers = RenderType.chunkBufferLayers().stream()
                .collect(Collectors.toMap(rt -> rt, rt -> new VertexBuffer(VertexBuffer.Usage.STATIC)));

        for (var entry : builders.entrySet()) {
            RenderType rt = entry.getKey();
            BufferBuilder builder = entry.getValue();
            MeshData mesh = builder.build();
            if (mesh != null) {
                VertexBuffer vb = newBuffers.get(rt);
                vb.bind();
                vb.upload(mesh);
                VertexBuffer.unbind();
            }
        }

        // Clean up byte builders
        for (ByteBufferBuilder bb : byteBuilders.values()) bb.clear();

        this.vertexBuffers = newBuffers;
    }

    /**
     * Handle mouse press in the preview area. Returns true if consumed.
     */
    public boolean onMousePressed(double mx, double my, int previewX, int previewY, int previewW, int previewH) {
        if (mx >= previewX && mx <= previewX + previewW && my >= previewY && my <= previewY + previewH) {
            mouseDown = true;
            dragStartMX = mx;
            dragStartMY = my;
            dragStartRotX = displayRotX;
            dragStartRotY = displayRotY;
            dragRotX = displayRotX;
            dragRotY = displayRotY;
            animState = AnimState.DRAGGING;
            return true;
        }
        return false;
    }

    /**
     * Handle mouse release. Starts the return animation after a delay.
     */
    public void onMouseReleased() {
        if (mouseDown) {
            mouseDown = false;
            animState = AnimState.RETURN_WAIT;
            animTimer = 0f;
            returnStartX = displayRotX;
            returnStartY = displayRotY;
            // Target: return to the current auto-rotation angle with standard tilt
            returnTargetY = autoRotY;
        }
    }

    /**
     * Handle mouse drag. Updates the drag rotation.
     */
    public void onMouseDragged(double mx, double my) {
        if (mouseDown) {
            dragRotY = dragStartRotY + (float)(mx - dragStartMX) * 0.8f;
            dragRotX = dragStartRotX + (float)(my - dragStartMY) * 0.8f;
            // Clamp X rotation to avoid flipping
            dragRotX = Math.max(-80f, Math.min(80f, dragRotX));
        }
    }

    private void updateAnimation() {
        switch (animState) {
            case SETTLE_WAIT -> {
                // Hold static at front, wait before starting intro spin
                animTimer++;
                displayRotY = 0f;
                displayRotX = 20f;
                if (animTimer >= SETTLE_DELAY) {
                    animState = AnimState.INTRO;
                    animTimer = 0f;
                    displayRotY = 180f; // jump to back for the intro spin
                }
            }
            case INTRO -> {
                // Ease-in-out from 180 degrees behind to 0 (front)
                animTimer++;
                float t = Math.min(animTimer / INTRO_DURATION, 1f);
                float eased = easeInOut(t);
                displayRotY = 180f * (1f - eased);
                displayRotX = 20f;
                if (t >= 1f) {
                    animState = AnimState.SPINNING;
                    autoRotY = 0f;
                    displayRotY = 0f;
                }
            }
            case SPINNING -> {
                autoRotY += SPIN_SPEED;
                if (autoRotY >= 360f) autoRotY -= 360f;
                displayRotY = autoRotY;
                displayRotX = 20f;
            }
            case DRAGGING -> {
                displayRotX = dragRotX;
                displayRotY = dragRotY;
                // Keep auto-rotation tracking where it would be
                autoRotY += SPIN_SPEED;
                if (autoRotY >= 360f) autoRotY -= 360f;
            }
            case RETURN_WAIT -> {
                animTimer++;
                // Hold the drag position during the wait
                displayRotX = returnStartX;
                displayRotY = returnStartY;
                autoRotY += SPIN_SPEED;
                if (autoRotY >= 360f) autoRotY -= 360f;
                returnTargetY = autoRotY; // keep updating target
                if (animTimer >= RETURN_DELAY) {
                    animState = AnimState.RETURNING;
                    animTimer = 0f;
                    returnTargetY = autoRotY;
                }
            }
            case RETURNING -> {
                animTimer++;
                autoRotY += SPIN_SPEED;
                if (autoRotY >= 360f) autoRotY -= 360f;
                returnTargetY += SPIN_SPEED; // target moves with auto-rotation

                float t = Math.min(animTimer / RETURN_DURATION, 1f);
                float eased = easeInOut(t);
                displayRotX = returnStartX + (20f - returnStartX) * eased;
                displayRotY = returnStartY + shortestAngleDelta(returnStartY, returnTargetY) * eased;
                if (t >= 1f) {
                    animState = AnimState.SPINNING;
                    displayRotX = 20f;
                    displayRotY = autoRotY;
                }
            }
        }
    }

    /** Ease-in-out using a sine curve (smooth acceleration and deceleration). */
    private static float easeInOut(float t) {
        return (float)(0.5 - 0.5 * Math.cos(t * Math.PI));
    }

    /** Compute the shortest rotation delta between two angles (handles wrapping). */
    private static float shortestAngleDelta(float from, float to) {
        float delta = (to - from) % 360f;
        if (delta > 180f) delta -= 360f;
        if (delta < -180f) delta += 360f;
        return delta;
    }

    /**
     * Render the prepared preview in the given screen area.
     */
    public void render(GuiGraphics g, int x, int y, int w, int h) {
        if (preparedFile == null || vertexBuffers == null) return;

        Minecraft mc = Minecraft.getInstance();
        updateAnimation();

        try {

            double scale = mc.getWindow().getGuiScale();
            int vpX = (int) Math.round(x * scale);
            int vpY = (int) Math.round(mc.getWindow().getHeight() - (y + h) * scale);
            int vpW = (int) Math.round(w * scale);
            int vpH = (int) Math.round(h * scale);

            RenderSystem.viewport(vpX, vpY, vpW, vpH);
            RenderSystem.backupProjectionMatrix();

            Matrix4f proj = new Matrix4f();
            proj.setPerspective((float) Math.toRadians(60), (float) w / h, 0.1f, 1000f);
            RenderSystem.setProjectionMatrix(proj, VertexSorting.ORTHOGRAPHIC_Z);

            PoseStack ps = g.pose();
            ps.pushPose();
            ps.setIdentity();

            float zoom = -maxExtent * 1.8f;
            ps.translate(0, 0, zoom);
            ps.mulPose(new Quaternionf()
                    .rotateX((float) Math.toRadians(displayRotX))
                    .rotateY((float) Math.toRadians(displayRotY)));
            ps.translate(-midX, -midY, -midZ);

            RenderSystem.applyModelViewMatrix();
            org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT);

            RenderSystem.runAsFancy(() -> {
                try {
                    for (RenderType rt : new RenderType[]{
                            RenderType.solid(), RenderType.cutout(),
                            RenderType.cutoutMipped(), RenderType.translucent()}) {
                        VertexBuffer vb = vertexBuffers.get(rt);
                        if (vb == null || vb.getFormat() == null) continue;
                        RenderType drawType = RenderType.translucent();
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

    private record BlockEntry(BlockPos pos, BlockState state) {}
}
