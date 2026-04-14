package com.schematicraft.bg2addon.client;
import com.schematicraft.lib.client.screen.PanelLayout;
import com.schematicraft.lib.core.SchematicEntry;

import com.direwolf20.buildinggadgets2.client.screen.TemplateManagerGUI;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.items.GadgetCutPaste;
import com.direwolf20.buildinggadgets2.common.network.data.SendPastePayload;
import com.direwolf20.buildinggadgets2.common.worlddata.BG2Data;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.schematicraft.SchematiCraftMod;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.client.screen.ApiKeyScreen;
import com.schematicraft.lib.client.screen.SchematicListWidget;
import com.schematicraft.bg2addon.core.*;
import com.schematicraft.bg2addon.network.SchematiCraftAPIWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/**
 * Injects Schematicraft panels into BG2's Template Manager GUI.
 *
 * Layout (both client-only and server mode):
 *   Left: Clipboard list + 3D preview (BG2-specific)
 *   Center: BG2's native Template Manager GUI
 *   Right: PalettePanel (cloud library, filter, pinned tabs)
 *
 * The Template Manager always shows our panels regardless of server mode.
 * The Copy/Paste gadget's EnhancedRadialMenu is the one that's disabled
 * in client-only mode, not the Template Manager.
 */
public class TemplateManagerIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PANEL_W = PanelLayout.PANEL_W;

    // Right panel: shared PalettePanel (cloud library)
    private static com.schematicraft.lib.client.screen.PalettePanel palettePanel;

    // Left panel: clipboard (BG2-specific)
    private static SchematicListWidget clipboardList;
    private static String statusText = "";
    private static boolean lastGadgetState = false;
    private static ClipboardEntry hoveredClipboardEntry = null;

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TemplateManagerGUI gui)) return;

        Minecraft mc = Minecraft.getInstance();
        statusText = "";
        palettePanel = null;
        clipboardList = null;

        if (!ModConfig.hasApiKey()) {
            initNoKeyWidgets(event, gui, mc);
            return;
        }

        // Right panel: PalettePanel (cloud library)
        palettePanel = new com.schematicraft.lib.client.screen.PalettePanel(
                TemplateManagerIntegration::onSchematicClicked,
                com.schematicraft.lib.client.screen.PalettePanel.Side.RIGHT);
        palettePanel.init(event, gui);

        // Left panel: clipboard + preview
        initLeftPanel(event, gui, mc);
    }

    private static void initNoKeyWidgets(ScreenEvent.Init.Post event, TemplateManagerGUI gui, Minecraft mc) {
        int leftX = PanelLayout.LEFT_X;
        int y = 24;
        event.addListener(Button.builder(Component.literal("Set API Key"),
                b -> mc.setScreen(new ApiKeyScreen(gui)))
                .bounds(leftX, y, PANEL_W, 16).build());
        event.addListener(Button.builder(Component.literal("\u00a79Get key at schematicraft.com"),
                b -> net.minecraft.Util.getPlatform().openUri("https://www.schematicraft.com/account#ingame-api-keys"))
                .bounds(leftX, y + 20, PANEL_W, 16).build());
    }

    private static void initLeftPanel(ScreenEvent.Init.Post event, TemplateManagerGUI gui, Minecraft mc) {
        int leftX = PanelLayout.LEFT_X;
        int y = PanelLayout.BELOW_HEADER_Y; // below header + underline

        // Clipboard takes top portion, preview takes fixed 160px at bottom
        int previewH = PanelLayout.PREVIEW_H;
        int clipListH = gui.height - y - 16 - previewH;
        clipboardList = new SchematicListWidget(mc, PANEL_W, clipListH, y, leftX,
                TemplateManagerIntegration::onClipboardClicked);
        event.addListener(clipboardList);
        lastGadgetState = false;
        hoveredClipboardEntry = null;
        rebuildClipboardList(false);
    }

    @SubscribeEvent
    public static void onMouseClicked(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof TemplateManagerGUI gui)) return;
        if (event.getButton() == 1 && palettePanel != null && palettePanel.getListWidget() != null) {
            double mx = event.getMouseX();
            double my = event.getMouseY();
            if (palettePanel.getListWidget().isMouseOver(mx, my)) {
                if (palettePanel.getListWidget().mouseClicked(mx, my, 1)) {
                    event.setCanceled(true);
                }
            }
        }
        // Forward left-clicks to clipboard preview for drag control
        if (event.getButton() == 0 && clipboardList != null && lastGadgetState) {
            int previewY = clipboardList.getBottom() + 4;
            int previewH = gui.height - previewY - 12;
            if (ClipboardPreviewRenderer.get().onMousePressed(
                    event.getMouseX(), event.getMouseY(), 6, previewY, PANEL_W, previewH)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!(event.getScreen() instanceof TemplateManagerGUI)) return;
        if (event.getButton() == 0) {
            ClipboardPreviewRenderer.get().onMouseReleased();
        }
    }

    @SubscribeEvent
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!(event.getScreen() instanceof TemplateManagerGUI)) return;
        ClipboardPreviewRenderer.get().onMouseDragged(event.getMouseX(), event.getMouseY());
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof TemplateManagerGUI)) return;
        if (palettePanel != null && palettePanel.getFilterField() != null && palettePanel.getFilterField().isFocused()) {
            int key = event.getKeyCode();
            if (key != 256) { // Let Esc through
                if (palettePanel.onKeyPressed(key, event.getScanCode(), event.getModifiers())) {
                    event.setCanceled(true);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (!(event.getScreen() instanceof TemplateManagerGUI)) return;
        if (palettePanel != null && palettePanel.onCharTyped(event.getCodePoint(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof TemplateManagerGUI gui)) return;

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        int leftX = PanelLayout.LEFT_X;
        int rightX = PanelLayout.rightX(gui.width);

        // Visual grouping: subtle warm tint behind the gadget (tool) slot
        // to visually separate it from the template slot + render panel.
        // The gadget slot is at container x=132, y=18. Container offset is leftPos-20, topPos-12.
        int guiLeft = (gui.width - 176) / 2;
        int guiTop = (gui.height - 166) / 2;
        // Gadget slot highlight with rounded left corners (2px radius)
        int hx1 = guiLeft + 126, hy1 = guiTop + 12, hx2 = guiLeft + 152, hy2 = guiTop + 61;
        int hc = 0x28CC8844;
        g.fill(hx1 + 2, hy1, hx2, hy1 + 1, hc);
        g.fill(hx1 + 1, hy1 + 1, hx2, hy1 + 2, hc);
        g.fill(hx1, hy1 + 2, hx2, hy2 - 2, hc);
        g.fill(hx1 + 1, hy2 - 2, hx2, hy2 - 1, hc);
        g.fill(hx1 + 2, hy2 - 1, hx2, hy2, hc);

        if (!ModConfig.hasApiKey()) {
            g.fill(6, 6, PANEL_W + 10, 70, 0xD0080808);
            g.drawString(mc.font, "\u00a7b\u2601 Schematicraft", leftX, 12, 0xFFFFFF);
            g.fill(leftX, PanelLayout.HEADER_LINE_Y, leftX + PANEL_W, PanelLayout.HEADER_LINE_Y + 1, 0x30FFFFFF);
            return;
        }

        // Push Z level above BG2's container overlay so our panels aren't dimmed
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);

        // Left panel background (clipboard)
        g.fill(PanelLayout.LEFT_BG_LEFT, PanelLayout.SCREEN_MARGIN, PanelLayout.LEFT_BG_RIGHT, gui.height - PanelLayout.SCREEN_MARGIN, 0xD0080808);
        g.fill(PanelLayout.LEFT_SEPARATOR_X, PanelLayout.SCREEN_MARGIN, PanelLayout.LEFT_SEPARATOR_X + 1, gui.height - PanelLayout.SCREEN_MARGIN, 0x40FFFFFF);

        // Left header
        g.drawString(mc.font, "\u00a7a\u2702 Clipboard", leftX + PanelLayout.LEFT_HEADER_INSET, 12, 0xFFAAFFAA);
        g.fill(leftX, PanelLayout.HEADER_LINE_Y, leftX + PANEL_W, PanelLayout.HEADER_LINE_Y + 1, 0x30FFFFFF);

        // Right panel: PalettePanel handles its own background and widget rendering
        int mx = event.getMouseX();
        int my = event.getMouseY();
        float pt = event.getPartialTick();
        if (palettePanel != null) {
            palettePanel.render(g, gui, mx, my, pt);
        }

        // Status text above hotbar area
        if (!statusText.isEmpty()) {
            g.drawCenteredString(mc.font, statusText, gui.width / 2, gui.height - 14, 0xCCCCCC);
        }

        // Re-render left panel widgets on top of backgrounds
        if (clipboardList != null) clipboardList.render(g, mx, my, pt);

        // Clipboard 3D preview (bottom half of left panel)
        if (clipboardList != null && lastGadgetState) {
            int clipBottom = clipboardList.getBottom();
            int previewY = clipBottom + 4;
            int previewH = gui.height - previewY - 12;

            if (previewH > 30) {
                ClipboardEntry newHover = null;
                for (var child : clipboardList.children()) {
                    if (child instanceof SchematicListWidget.SchematicEntry se && child.isMouseOver(mx, my)) {
                        try {
                            java.util.UUID uuid = java.util.UUID.fromString(se.getSchematicId());
                            for (ClipboardEntry clip : SchematiCraftState.get().getClipboard()) {
                                if (clip.getGadgetUuid().equals(uuid)) { newHover = clip; break; }
                            }
                        } catch (Exception ignored) {}
                    }
                }

                if (newHover == null && !SchematiCraftState.get().getClipboard().isEmpty()) {
                    newHover = SchematiCraftState.get().getClipboard().get(0);
                }

                if (newHover != hoveredClipboardEntry) {
                    hoveredClipboardEntry = newHover;
                    ClipboardPreviewRenderer.get().prepareForEntry(hoveredClipboardEntry);
                }

                if (hoveredClipboardEntry != null) {
                    g.fill(leftX, previewY - 2, leftX + PANEL_W, previewY + previewH + 2, 0x60000000);
                    g.fill(leftX, previewY - 2, leftX + PANEL_W, previewY - 1, 0x30FFFFFF);
                    g.drawString(mc.font, "\u00a78Preview", leftX + 2, previewY + 2, 0x555555);

                    g.flush();
                    ClipboardPreviewRenderer.get().render(g, leftX, previewY, PANEL_W, previewH);
                }
            }
        }

        // Template slot highlight: pulse green when hovering items that load INTO the template.
        // BG2 already highlights the template slot (white) for Save and Paste hover.
        // We add green for our library/clipboard entries and BG2's Copy button,
        // which all write data into the template slot.
        {
            boolean hoveringOurPanels = false;
            boolean hoveringCopyBtn = false;

            // Our panels
            if (palettePanel != null && palettePanel.getListWidget() != null) {
                for (var child : palettePanel.getListWidget().children()) {
                    if (child instanceof SchematicListWidget.SchematicEntry && child.isMouseOver(mx, my)) {
                        hoveringOurPanels = true; break;
                    }
                }
            }
            if (!hoveringOurPanels && clipboardList != null) {
                for (var child : clipboardList.children()) {
                    if (child instanceof SchematicListWidget.SchematicEntry && child.isMouseOver(mx, my)) {
                        hoveringOurPanels = true; break;
                    }
                }
            }

            // BG2's Copy button (both modes) - detection shifted down to align with actual button
            if (!hoveringOurPanels) {
                int btnX = guiLeft - 20 + 180;
                int copyY = guiTop - 12 + 60; // +10 from original to align with button
                if (mx >= btnX && mx <= btnX + 60 && my >= copyY && my <= copyY + 15) {
                    hoveringCopyBtn = true;
                }
            }

            if (hoveringOurPanels || hoveringCopyBtn) {
                float pulse = (float)(Math.sin(System.currentTimeMillis() / 300.0) * 0.5 + 0.5);
                int alpha = (int)(0x50 + pulse * 0x40);
                int slotX = guiLeft + 132;
                int slotY = guiTop + 63;
                int color = hoveringOurPanels ? ((alpha << 24) | 0x4488FF) : ((alpha << 24) | 0x44FF44);
                g.fill(slotX, slotY, slotX + 16, slotY + 17, color);
            }
        }

        g.pose().popPose(); // Restore Z level

        // Refresh clipboard when gadget state changes
        boolean hasGadget = hasInsertedCopyPasteGadget(gui);
        if (hasGadget != lastGadgetState) {
            lastGadgetState = hasGadget;
            rebuildClipboardList(hasGadget);
        }
    }

    private static boolean hasInsertedCopyPasteGadget(TemplateManagerGUI gui) {
        var container = gui.getMenu();
        if (container.slots.isEmpty()) return false;
        ItemStack slotStack = container.slots.get(0).getItem();
        return slotStack.getItem() instanceof GadgetCopyPaste
                || slotStack.getItem() instanceof GadgetCutPaste;
    }

    private static void rebuildClipboardList(boolean hasGadget) {
        if (clipboardList == null) return;
        clipboardList.clearEntries();

        if (!hasGadget) {
            clipboardList.addEntry(new SchematicListWidget.MessageEntry(clipboardList, "Insert a Copy/Paste gadget"));
            clipboardList.addEntry(new SchematicListWidget.MessageEntry(clipboardList, "to see your clipboard"));
            return;
        }

        List<ClipboardEntry> clips = SchematiCraftState.get().getClipboard();
        if (clips.isEmpty()) {
            clipboardList.addEntry(new SchematicListWidget.MessageEntry(clipboardList, "No copies yet"));
            clipboardList.addEntry(new SchematicListWidget.MessageEntry(clipboardList, "Copy with gadget to add"));
            return;
        }
        for (ClipboardEntry clip : clips) {
            String label = clip.getTitle() != null ? clip.getTitle() : clip.getBlockCount() + " blocks";
            clipboardList.addEntry(new SchematicListWidget.SchematicEntry(clipboardList,
                    clip.getGadgetUuid().toString(), label, "Click to load", null));
        }
    }

    private static void onSchematicClicked(String id, String title) {
        // Check file cache first for instant load
        com.schematicraft.lib.core.SchematicFileCache cache = com.schematicraft.lib.core.SchematicFileCache.get();
        byte[] cached = cache.readCached(id);
        if (cached != null) {
            if (loadViaTemplateManager(cached)) {
                statusText = "\u00a7aLoaded: " + title;
            } else {
                statusText = "\u00a7cFailed to parse template";
            }
            return;
        }

        // Cache miss: download from API
        statusText = "Downloading...";
        SchematiCraftAPIWrapper.get().downloadSchematic(id).thenAccept(result -> {
            Minecraft.getInstance().execute(() -> {
                try {
                    byte[] data = Files.readAllBytes(result.file);
                    // Cache the downloaded file for next time
                    cache.store(id, "json", data);
                    Files.deleteIfExists(result.file);
                    if (loadViaTemplateManager(data)) {
                        statusText = "\u00a7aLoaded: " + title;
                        SchematiCraftAPIWrapper.get().submitSuccessFeedback(result.downloadId);
                        // Rebuild list to show cache indicator
                        if (palettePanel != null) palettePanel.rebuildList();
                    } else {
                        statusText = "\u00a7cFailed to parse template";
                    }
                } catch (Exception e) {
                    statusText = "\u00a7c" + e.getMessage();
                }
            });
        }).exceptionally(ex -> {
            Minecraft.getInstance().execute(() -> {
                Throwable c = ex;
                while (c.getCause() != null) c = c.getCause();
                statusText = "\u00a7c" + (c.getMessage() != null ? c.getMessage() : "Unknown error");
            });
            return null;
        });
    }

    private static void onClipboardClicked(String uuid, String title) {
        try {
            UUID gadgetUuid = UUID.fromString(uuid);
            var statePosList = ClipboardPreviewRenderer.get().getClientData(gadgetUuid);
            if (statePosList == null || statePosList.isEmpty()) {
                statusText = "\u00a7cNo template data for this copy";
                return;
            }
            CompoundTag nbtMap = BG2Data.statePosListToNBTMapArray(statePosList);
            PacketDistributor.sendToServer(new SendPastePayload(UUID.randomUUID(), nbtMap));
            statusText = "\u00a7aLoaded into template";
            LOGGER.info("Loaded clipboard entry via SendPastePayload");
        } catch (Exception e) {
            statusText = "\u00a7c" + e.getMessage();
        }
    }

    public static boolean loadViaTemplateManager(byte[] bg2JsonData) {
        try {
            String json = new String(bg2JsonData);
            var root = JsonParser.parseString(json).getAsJsonObject();

            CompoundTag nbtData;
            if (root.has("statePosArrayList")) {
                nbtData = TagParser.parseTag(root.get("statePosArrayList").getAsString());
            } else {
                LOGGER.warn("Unknown template format");
                return false;
            }

            if (!nbtData.contains("blockstatemap") || !nbtData.contains("statelist")) {
                LOGGER.warn("Template NBT missing required fields");
                return false;
            }

            PacketDistributor.sendToServer(new SendPastePayload(UUID.randomUUID(), nbtData));
            LOGGER.info("Sent template via BG2 SendPastePayload");
            return true;
        } catch (Exception e) {
            LOGGER.error("Template Manager load failed: {}", e.getMessage());
            return false;
        }
    }
}
