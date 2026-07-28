package com.schematicraft.bg2addon.network;

import com.schematicraft.api.SchematiCraftAPI;
import com.schematicraft.bg2addon.SchematiCraftBG2;
import com.schematicraft.lib.core.LibraryState;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * BG2-specific upload path: converts a gadget copy (StatePos list) into Building
 * Gadgets JSON, compresses the screenshots, and uploads.
 *
 * Everything generic (status, library, download, bundles, feedback) goes straight
 * to the lib wrapper. This class used to re-expose all of those as delegates,
 * which made it look like a second API client and left two ways to do the same
 * call. Only the BG2-specific piece belongs here.
 */
public class SchematiCraftAPIWrapper {
    private static final SchematiCraftAPIWrapper INSTANCE = new SchematiCraftAPIWrapper();
    private static final float JPEG_QUALITY = 0.92f;

    private SchematiCraftAPIWrapper() {}
    public static SchematiCraftAPIWrapper get() { return INSTANCE; }

    private com.schematicraft.lib.network.SchematiCraftAPIWrapper lib() {
        return com.schematicraft.lib.network.SchematiCraftAPIWrapper.get();
    }

    /**
     * Export StatePos data to BG2 JSON and upload it.
     * Runs entirely client-side. The API key never leaves the client.
     */
    public CompletableFuture<Boolean> uploadFromClient(
            ArrayList<com.direwolf20.buildinggadgets2.util.datatypes.StatePos> statePosList,
            String title, String description, String bundleId,
            List<Path> images) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                net.minecraft.nbt.CompoundTag nbtMap =
                        com.direwolf20.buildinggadgets2.common.worlddata.BG2Data.statePosListToNBTMapArray(statePosList);
                String snbt = nbtMap.toString();

                String bg2Json = "{\"name\":\"Schematicraft Export\",\"requiredItems\":{},\"statePosArrayList\":\""
                        + escapeJsonString(snbt) + "\"}";

                Path tempFile = Files.createTempFile("schematicraft_upload_", ".json");
                Files.writeString(tempFile, bg2Json);

                // Convert screenshots from PNG to JPEG
                List<Path> processedImages = new ArrayList<>();
                for (Path imgPath : images) {
                    if (!Files.exists(imgPath)) continue;
                    try {
                        BufferedImage original = ImageIO.read(imgPath.toFile());
                        if (original == null) continue;

                        BufferedImage rgb = new BufferedImage(
                                original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g2d = rgb.createGraphics();
                        g2d.drawImage(original, 0, 0, null);
                        g2d.dispose();

                        Path jpegFile = Files.createTempFile("sc_img_", ".jpg");
                        ImageWriter jpgWriter = ImageIO.getImageWritersByFormatName("jpg").next();
                        ImageWriteParam param = jpgWriter.getDefaultWriteParam();
                        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        param.setCompressionQuality(JPEG_QUALITY);
                        try (var fos = Files.newOutputStream(jpegFile)) {
                            jpgWriter.setOutput(ImageIO.createImageOutputStream(fos));
                            jpgWriter.write(null, new IIOImage(rgb, null, null), param);
                            jpgWriter.dispose();
                        }
                        processedImages.add(jpegFile);
                        SchematiCraftBG2.LOGGER.info("  image: {}x{} -> {} bytes JPEG",
                                original.getWidth(), original.getHeight(), Files.size(jpegFile));
                    } catch (Exception imgEx) {
                        SchematiCraftBG2.LOGGER.warn("Failed to process image: {}", imgEx.getMessage());
                    }
                }

                long totalBytes = Files.size(tempFile);
                for (Path img : processedImages) totalBytes += Files.size(img);
                SchematiCraftBG2.LOGGER.info("Client-side upload: title='{}', blocks={}, images={}, totalBytes={}",
                        title, statePosList.size(), processedImages.size(), totalBytes);
                String response = lib().createClient().upload(tempFile, title, description, "1.21.1", "neoforge", null,
                        false, bundleId, processedImages);

                // Cleanup
                Files.deleteIfExists(tempFile);
                for (Path img : processedImages) { try { Files.deleteIfExists(img); } catch (Exception ignored) {} }

                LibraryState.get().invalidateLibrary();

                boolean isDuplicate = response != null && response.contains("\"isDuplicate\":true");
                SchematiCraftBG2.LOGGER.info("Upload complete: '{}'{}", title, isDuplicate ? " (duplicate detected)" : "");
                return isDuplicate;
            } catch (Exception e) {
                String detail = e.getMessage();
                if (e instanceof SchematiCraftAPI.APIException apiEx) {
                    detail = "HTTP " + apiEx.statusCode + ": " + apiEx.body;
                } else if (e.getCause() instanceof SchematiCraftAPI.APIException apiEx) {
                    detail = "HTTP " + apiEx.statusCode + ": " + apiEx.body;
                }
                SchematiCraftBG2.LOGGER.error("Client-side upload failed: {}", detail, e);
                throw new RuntimeException(detail, e);
            }
        }, lib().getExecutor());
    }

    private static String escapeJsonString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
