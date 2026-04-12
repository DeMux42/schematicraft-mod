package com.schematicraft.bg2addon.client;
import com.schematicraft.lib.core.ApiJsonParser;
import com.schematicraft.lib.core.SchematicEntry;
import com.schematicraft.lib.core.BundleEntry;

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
import com.schematicraft.lib.network.ServerMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
 * This is the primary integration path when the server does not have the
 * Schematicraft mod installed (client-only mode). The Template Manager is a
 * vanilla BG2 block that handles template storage via SendPastePayload, a
 * packet that BG2's server always processes regardless of addon mods.
 *
 * Server mode: renders a small hint ("Open gadget (G) for Schematicraft")
 * since the Copy/Paste gadget's EnhancedRadialMenu handles everything.
 *
 * Client-only mode: injects full panels via ScreenEvent.Init.Post (for
 * interactive widgets) and ScreenEvent.Render.Post (for backgrounds drawn
 * on top of the container's own rendering). Left panel has library/search.
 * Right panel has clipboard (populated when a Copy/Paste gadget is in
 * the Template Manager's tool slot).
 *
 * BG2 Integration Points:
 * - TemplateManagerGUI: detected via instanceof, container accessed via getMenu()
 * - Container slot 0: tool/gadget slot, checked for GadgetCopyPaste/GadgetCutPaste
 * - SendPastePayload: BG2's own packet for writing template data into the
 *   Template Manager's storage. Used for both library downloads and clipboard loads.
 * - BG2Data.statePosListToNBTMapArray: converts clipboard StatePos data to the
 *   NBT format that SendPastePayload expects
 *
 * Rendering approach:
 * The Template Manager is an AbstractContainerScreen. Its render() draws the
 * container background texture, slot overlays, and a 3D preview panel on top
 * of all widgets. To prevent our panels from being hidden behind the container
 * background, we draw panel backgrounds in Render.Post (after the container
 * finishes rendering), then re-render our widgets (lists, buttons, search field)
 * on top of those backgrounds.
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME, modid = SchematiCraftMod.MODID)
public class TemplateManagerIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PANEL_W = 160;

    private static enum LeftTab { LIBRARY, SEARCH }
    private static LeftTab leftTab = LeftTab.LIBRARY;
    private static SchematicListWidget leftList;
    private static EditBox searchField;
    private static boolean searchLoading = false;
    private static String pendingSearch = null;
    private static long lastSearchTime = 0;

    private static SchematicListWidget clipboardList;
    private static String statusText = "";
    private static boolean lastGadgetState = false;
    private static Button libButton, srchButton, headerButton, logoutButton;
    private static ClipboardEntry hoveredClipboardEntry = null;

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TemplateManagerGUI gui)) return;

        Minecraft mc = Minecraft.getInstance();
        statusText = "";
        leftList = null;
        clipboardList = null;
        searchField = null;

        if (ServerMode.isDirectModeAvailable()) {
            // Server mode: no panels needed, just a hint rendered in Render.Post
            return;
        }

        // Client-only mode: inject full panels
        if (!ModConfig.hasApiKey()) {
            initNoKeyWidgets(event, gui, mc);
            return;
        }

        initLeftPanel(event, gui, mc);
        initRightPanel(event, gui, mc);

        SchematiCraftState state = SchematiCraftState.get();
        if (!state.isLibraryLoaded() && !state.isLibraryLoading()) {
            SchematiCraftAPIWrapper.get().loadLibrary().thenRun(() ->
                    mc.execute(TemplateManagerIntegration::rebuildLeftList));
        }
    }

    private static void initNoKeyWidgets(ScreenEvent.Init.Post event, TemplateManagerGUI gui, Minecraft mc) {
        int leftX = 4;
        int y = 24;
        event.addListener(Button.builder(Component.literal("Set API Key"),
                b -> mc.setScreen(new ApiKeyScreen(gui)))
                .bounds(leftX, y, PANEL_W, 16).build());
        event.addListener(Button.builder(Component.literal("\u00a79Get key at schematicraft.com"),
                b -> net.minecraft.Util.getPlatform().openUri("https://www.schematicraft.com/account#ingame-api-keys"))
                .bounds(leftX, y + 20, PANEL_W, 16).build());
    }

    private static void initLeftPanel(ScreenEvent.Init.Post event, TemplateManagerGUI gui, Minecraft mc) {
        int leftX = 4;
        int y = 10;

        // Header: clickable "Schematicraft" link + logout button
        headerButton = Button.builder(Component.literal("\u00a7b\u2601 Schematicraft"),
                b -> net.minecraft.Util.getPlatform().openUri("https://www.schematicraft.com"))
                .bounds(leftX, y, PANEL_W - 14, 12).build();
        event.addListener(headerButton);

        logoutButton = Button.builder(Component.literal("\u00a7c\u2716"),
                b -> { ModConfig.setApiKey(""); mc.setScreen(gui); })
                .bounds(leftX + PANEL_W - 12, y, 12, 12).build();
        event.addListener(logoutButton);
        y += 14;

        // Library / Search tabs
        Button libBtn = Button.builder(Component.literal("Library"),
                b -> { leftTab = LeftTab.LIBRARY; mc.setScreen(gui); })
                .bounds(leftX, y, PANEL_W / 2 - 1, 14).build();
        Button srchBtn = Button.builder(Component.literal("Search"),
                b -> { leftTab = LeftTab.SEARCH; mc.setScreen(gui); })
                .bounds(leftX + PANEL_W / 2 + 1, y, PANEL_W / 2 - 1, 14).build();
        libBtn.active = leftTab != LeftTab.LIBRARY;
        srchBtn.active = leftTab != LeftTab.SEARCH;
        libButton = libBtn;
        srchButton = srchBtn;
        event.addListener(libBtn);
        event.addListener(srchBtn);
        y += 16;

        if (leftTab == LeftTab.SEARCH) {
            searchField = new EditBox(mc.font, leftX, y, PANEL_W, 14, Component.literal(""));
            searchField.setHint(Component.literal("Search schematics..."));
            searchField.setMaxLength(100);
            searchField.setResponder(t -> {
                if (t.length() < 2) { rebuildLeftList(); return; }
                pendingSearch = t;
                lastSearchTime = System.currentTimeMillis();
            });
            event.addListener(searchField);
            y += 16;
        }

        int listH = gui.height - y - 16;
        leftList = new SchematicListWidget(mc, PANEL_W, listH, y, leftX,
                TemplateManagerIntegration::onSchematicClicked);
        event.addListener(leftList);
        rebuildLeftList();
    }

    private static void initRightPanel(ScreenEvent.Init.Post event, TemplateManagerGUI gui, Minecraft mc) {
        int rightX = gui.width - PANEL_W - 4;
        int y = 24;

        // Clipboard takes top half, preview takes bottom half
        int clipListH = (gui.height - y - 16) / 2;
        clipboardList = new SchematicListWidget(mc, PANEL_W, clipListH, y, rightX,
                TemplateManagerIntegration::onClipboardClicked);
        event.addListener(clipboardList);
        lastGadgetState = false;
        hoveredClipboardEntry = null;
        rebuildClipboardList(false);
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof TemplateManagerGUI)) return;
        if (searchField != null && searchField.isFocused()) {
            // Suppress inventory close key (E) and other game keys while typing
            int key = event.getKeyCode();
            // Let Esc (256) and Enter (257) through, block everything else that isn't a text key
            if (key != 256 && key != 257) {
                // Forward to the search field and consume the event
                searchField.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers());
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (!(event.getScreen() instanceof TemplateManagerGUI)) return;
        if (searchField != null && searchField.isFocused()) {
            searchField.charTyped(event.getCodePoint(), event.getModifiers());
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof TemplateManagerGUI gui)) return;

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        int leftX = 4;
        int rightX = gui.width - PANEL_W - 4;

        // Visual grouping: subtle warm tint behind the gadget (tool) slot
        // to visually separate it from the template slot + render panel.
        // The gadget slot is at container x=132, y=18. Container offset is leftPos-20, topPos-12.
        int guiLeft = (gui.width - 176) / 2;  // leftPos
        int guiTop = (gui.height - 166) / 2;   // topPos
        // Gadget slot background: snug around the slot with padding
        // Gadget slot highlight with rounded left corners (2px radius)
        int hx1 = guiLeft + 126, hy1 = guiTop + 12, hx2 = guiLeft + 152, hy2 = guiTop + 61;
        int hc = 0x28CC8844;
        g.fill(hx1 + 2, hy1, hx2, hy1 + 1, hc);     // top row: 2px inset left
        g.fill(hx1 + 1, hy1 + 1, hx2, hy1 + 2, hc);  // second row: 1px inset left
        g.fill(hx1, hy1 + 2, hx2, hy2 - 2, hc);       // main body: full width
        g.fill(hx1 + 1, hy2 - 2, hx2, hy2 - 1, hc);  // second-to-last row: 1px inset
        g.fill(hx1 + 2, hy2 - 1, hx2, hy2, hc);       // bottom row: 2px inset

        if (ServerMode.isDirectModeAvailable()) {
            // Server mode: just a small hint
            String hint = "Open Copy/Paste gadget (G) for Schematicraft";
            int hintW = mc.font.width(hint) + 12;
            g.fill(gui.width - hintW - 4, 6, gui.width, 24, 0xD0080808);
            g.drawString(mc.font, "\u00a77" + hint, gui.width - hintW, 10, 0x999999);
            return;
        }

        if (!ModConfig.hasApiKey()) {
            g.fill(0, 6, PANEL_W + 8, 70, 0xD0080808);
            g.drawString(mc.font, "\u00a7b\u2601 Schematicraft", leftX, 12, 0xFFFFFF);
            g.fill(leftX, 20, leftX + PANEL_W, 21, 0x30FFFFFF);
            return;
        }

        // Panel backgrounds (drawn on top of the container background)
        g.fill(0, 6, PANEL_W + 8, gui.height - 6, 0xD0080808);
        g.fill(PANEL_W + 8, 6, PANEL_W + 9, gui.height - 6, 0x40FFFFFF);
        g.fill(rightX - 5, 6, gui.width, gui.height - 6, 0xD0080808);
        g.fill(rightX - 6, 6, rightX - 5, gui.height - 6, 0x40FFFFFF);

        // Left header underline
        g.fill(4, 20, 4 + PANEL_W, 21, 0x30FFFFFF);

        // Right header
        g.drawString(mc.font, "\u00a7a\u2702 Clipboard", rightX, 12, 0xFFAAFFAA);
        g.fill(rightX, 20, rightX + PANEL_W, 21, 0x30FFFFFF);

        // Status text above hotbar area
        if (!statusText.isEmpty()) {
            g.drawCenteredString(mc.font, statusText, gui.width / 2, gui.height - 14, 0xCCCCCC);
        }

        // Re-render lists on top of panel backgrounds (they were drawn under the container bg)
        int mx = event.getMouseX();
        int my = event.getMouseY();
        float pt = event.getPartialTick();
        if (leftList != null) leftList.render(g, mx, my, pt);
        if (clipboardList != null) clipboardList.render(g, mx, my, pt);
        if (searchField != null) searchField.render(g, mx, my, pt);
        if (libButton != null) libButton.render(g, mx, my, pt);
        if (srchButton != null) srchButton.render(g, mx, my, pt);
        if (headerButton != null) headerButton.render(g, mx, my, pt);
        if (logoutButton != null) logoutButton.render(g, mx, my, pt);

        // Clipboard 3D preview (bottom half of right panel)
        if (clipboardList != null && lastGadgetState) {
            int clipBottom = clipboardList.getBottom();
            int previewY = clipBottom + 4;
            int previewH = gui.height - previewY - 12;

            if (previewH > 30) {
                // Detect hovered entry by mouse position
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
                    g.fill(rightX - 2, previewY - 2, rightX + PANEL_W + 2, previewY + previewH + 2, 0x60000000);
                    g.fill(rightX - 2, previewY - 2, rightX + PANEL_W + 2, previewY - 1, 0x30FFFFFF);
                    g.drawString(mc.font, "\u00a78Preview", rightX, previewY + 2, 0x555555);

                    g.flush();
                    ClipboardPreviewRenderer.get().render(g, rightX, previewY, PANEL_W, previewH);
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

            // Our panels (client-only mode)
            if (!ServerMode.isDirectModeAvailable()) {
                if (leftList != null) {
                    for (var child : leftList.children()) {
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

        // Search debounce
        if (pendingSearch != null && System.currentTimeMillis() - lastSearchTime > 500) {
            String q = pendingSearch;
            pendingSearch = null;
            searchLoading = true;
            rebuildLeftList();
            SchematiCraftAPIWrapper.get().search(q).thenAccept(json -> {
                mc.execute(() -> {
                    searchLoading = false;
                    var results = ApiJsonParser.parseSearch(json);
                    if (leftList != null) {
                        leftList.clearEntries();
                        for (var r : results) {
                            SchematicEntry s = r.schematic();
                            leftList.addEntry(new SchematicListWidget.SchematicEntry(leftList, s.id(),
                                    s.title() != null ? s.title() : "Untitled",
                                    s.ownerName() != null ? s.ownerName() : "",
                                    s.thumbnailUrl()));
                        }
                        if (results.isEmpty())
                            leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, "No results"));
                    }
                });
            });
        }

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

    private static void rebuildLeftList() {
        if (leftList == null) return;
        leftList.clearEntries();
        SchematiCraftState state = SchematiCraftState.get();

        if (leftTab == LeftTab.LIBRARY) {
            if (state.isLibraryLoading()) {
                leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, "Loading..."));
                return;
            }
            if (state.getLibraryError() != null) {
                leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, state.getLibraryError()));
                return;
            }
            for (BundleEntry b : state.getBundles()) {
                leftList.addEntry(new SchematicListWidget.HeaderEntry(leftList, "\u00a7e" + b.name()));
                for (SchematicEntry s : b.schematics()) {
                    leftList.addEntry(new SchematicListWidget.SchematicEntry(leftList, s.id(),
                            s.title() != null ? s.title() : "Untitled", "", s.thumbnailUrl()));
                }
            }
            if (!state.getUnbundled().isEmpty()) {
                leftList.addEntry(new SchematicListWidget.HeaderEntry(leftList, "\u00a77Unbundled"));
                for (SchematicEntry s : state.getUnbundled()) {
                    leftList.addEntry(new SchematicListWidget.SchematicEntry(leftList, s.id(),
                            s.title() != null ? s.title() : "Untitled", "", s.thumbnailUrl()));
                }
            }
            if (state.getBundles().isEmpty() && state.getUnbundled().isEmpty()) {
                leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, "Library empty"));
            }
        } else {
            if (searchLoading) {
                leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, "Searching..."));
            } else if (searchField != null && searchField.getValue().length() < 2) {
                leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, "Type to search..."));
            }
        }
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
        statusText = "Downloading...";
        SchematiCraftAPIWrapper.get().downloadSchematic(id).thenAccept(result -> {
            Minecraft.getInstance().execute(() -> {
                try {
                    byte[] data = Files.readAllBytes(result.file);
                    Files.deleteIfExists(result.file);
                    if (loadViaTemplateManager(data)) {
                        statusText = "\u00a7aLoaded: " + title;
                        SchematiCraftAPIWrapper.get().submitSuccessFeedback(result.downloadId);
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
