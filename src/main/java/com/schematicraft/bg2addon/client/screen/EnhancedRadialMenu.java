package com.schematicraft.bg2addon.client.screen;
import com.schematicraft.lib.core.LibraryState;
import com.schematicraft.lib.client.screen.ApiKeyScreen;
import com.schematicraft.lib.core.ApiJsonParser;
import com.schematicraft.lib.core.SchematicEntry;
import com.schematicraft.lib.core.BundleEntry;

import com.direwolf20.buildinggadgets2.client.KeyBindings;
import com.direwolf20.buildinggadgets2.client.screen.ModeRadialMenu;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.items.GadgetCutPaste;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.schematicraft.SchematiCraftMod;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.bg2addon.core.*;
import com.schematicraft.bg2addon.network.LoadClipboardPayload;
import com.schematicraft.bg2addon.network.SchematiCraftAPIWrapper;
import com.schematicraft.lib.network.ServerMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EnhancedRadialMenu extends Screen {

    private final ModeRadialMenu innerRadial;
    private final ItemStack gadgetStack;
    private int animationTime = 0;

    private static final int PANEL_W = 160;
    private int leftX, rightX, panelTop, panelH;

    // Left panel
    private enum LeftTab { LIBRARY, SEARCH }
    private LeftTab leftTab = LeftTab.LIBRARY;
    private SchematicListWidget leftList;
    private EditBox searchField;
    private boolean searchLoading = false;
    private String statusText = "";
    private long statusClearAt = 0; // Auto-clear status text after this timestamp

    // Right panel modes
    private enum RightMode { CLIPBOARD, UPLOAD_FORM, CREATE_BUNDLE }
    private RightMode rightMode = RightMode.CLIPBOARD;

    // Clipboard
    private SchematicListWidget clipboardList;
    private ClipboardEntry hoveredClipboardEntry = null;
    private ClipboardEntry selectedPreviewEntry = null; // Explicitly selected by click

    // Upload form fields (rendered in right panel)
    private ClipboardEntry uploadTarget = null;
    private EditBox uploadTitle;
    private EditBox uploadDesc;
    private int selectedBundleIndex = 0;
    private final List<Path> uploadImages = new ArrayList<>();

    // Create bundle field
    private EditBox bundleNameField;
    private Button bundleButton;

    // Search debounce
    private long lastSearchTime = 0;
    private String pendingSearch = null;

    // Toggle
    private boolean gWasDown = true;

    // Static state for camera mode round-trip
    private static ClipboardEntry pendingUploadReopen = null;
    private static List<Path> pendingUploadImages = null;
    private static String pendingUploadTitle = null;
    private static String pendingUploadDesc = null;
    private static int pendingBundleIndex = 0;

    private final SchematiCraftState state = SchematiCraftState.get();

    public EnhancedRadialMenu(ItemStack stack) {
        super(Component.literal(""));
        this.gadgetStack = stack;
        this.innerRadial = new ModeRadialMenu(stack);
    }

    @Override
    protected void init() {
        innerRadial.init(minecraft, width, height);

        leftX = 4;
        rightX = width - PANEL_W - 4;
        panelTop = 10;
        panelH = height - 30;

        // Camera mode round-trip restore
        if (pendingUploadReopen != null) {
            uploadTarget = pendingUploadReopen;
            rightMode = RightMode.UPLOAD_FORM;
            uploadImages.addAll(pendingUploadImages);
            selectedBundleIndex = pendingBundleIndex;
            pendingUploadReopen = null;
            pendingUploadImages = null;
        }

        initLeftPanel();
        initRightPanel();

        if (!state.isLibraryLoaded() && !state.isLibraryLoading() && ModConfig.hasApiKey()) {
            SchematiCraftAPIWrapper.get().loadLibrary().thenRun(() ->
                    Minecraft.getInstance().execute(this::rebuildLeftList));
        }

        rebuildLeftList();

        if (rightMode == RightMode.UPLOAD_FORM && pendingUploadTitle != null) {
            if (uploadTitle != null) uploadTitle.setValue(pendingUploadTitle);
            if (uploadDesc != null && pendingUploadDesc != null) uploadDesc.setValue(pendingUploadDesc);
            pendingUploadTitle = null;
            pendingUploadDesc = null;
        }

        // Auto-focus title field when upload form is shown
        if (rightMode == RightMode.UPLOAD_FORM && uploadTitle != null) {
            setFocused(uploadTitle);
        }
        // Auto-focus bundle name field when create bundle form is shown
        if (rightMode == RightMode.CREATE_BUNDLE && bundleNameField != null) {
            setFocused(bundleNameField);
        }
    }


    private void initLeftPanel() {
        int y = panelTop;

        // Header: clickable "Schematicraft" link + logout button
        addRenderableWidget(Button.builder(Component.literal("\u00a7b\u2601 Schematicraft"),
                b -> net.minecraft.Util.getPlatform().openUri("https://www.schematicraft.com"))
                .bounds(leftX, y, PANEL_W - 14, 12).build());

        if (ModConfig.hasApiKey()) {
            addRenderableWidget(Button.builder(Component.literal("\u00a7c\u2716"),
                    b -> {
                        ModConfig.setApiKey("");
                        rebuildWidgets();
                    })
                    .bounds(leftX + PANEL_W - 12, y, 12, 12).build());
        }

        y += 14;

        if (!ModConfig.hasApiKey()) {
            addRenderableWidget(Button.builder(Component.literal("Set API Key"),
                    b -> minecraft.setScreen(new ApiKeyScreen(this)))
                    .bounds(leftX, y, PANEL_W, 16).build());
            addRenderableWidget(Button.builder(Component.literal("\u00a79Get key at schematicraft.com"),
                    b -> net.minecraft.Util.getPlatform().openUri("https://www.schematicraft.com/account#ingame-api-keys"))
                    .bounds(leftX, y + 20, PANEL_W, 16).build());
            return;
        }

        Button libBtn = Button.builder(Component.literal("Library"),
                b -> { leftTab = LeftTab.LIBRARY; rebuildWidgets(); })
                .bounds(leftX, y, PANEL_W / 2 - 1, 14).build();
        Button srchBtn = Button.builder(Component.literal("Search"),
                b -> { leftTab = LeftTab.SEARCH; rebuildWidgets(); })
                .bounds(leftX + PANEL_W / 2 + 1, y, PANEL_W / 2 - 1, 14).build();
        libBtn.active = leftTab != LeftTab.LIBRARY;
        srchBtn.active = leftTab != LeftTab.SEARCH;
        addRenderableWidget(libBtn);
        addRenderableWidget(srchBtn);
        y += 16;

        if (leftTab == LeftTab.SEARCH) {
            searchField = new EditBox(font, leftX, y, PANEL_W, 14, Component.literal(""));
            searchField.setHint(Component.literal("Search schematics..."));
            searchField.setMaxLength(100);
            searchField.setResponder(t -> { if (t.length() < 2) { rebuildLeftList(); return; } pendingSearch = t; lastSearchTime = System.currentTimeMillis(); });
            addRenderableWidget(searchField);
            y += 16;
        }

        leftList = new SchematicListWidget(minecraft, PANEL_W, panelH - (y - panelTop) - 14, y, leftX, this::onLeftClicked);
        addRenderableWidget(leftList);
    }

    private void rebuildLeftList() {
        if (leftList == null) return;
        leftList.clearEntries();
        if (!ModConfig.hasApiKey()) return;

        if (leftTab == LeftTab.LIBRARY) {
            if (state.isLibraryLoading()) { leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, "Loading...")); return; }
            if (state.getLibraryError() != null) { leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, state.getLibraryError())); return; }
            for (BundleEntry b : state.getBundles()) {
                leftList.addEntry(new SchematicListWidget.HeaderEntry(leftList, "\u00a7e" + b.name()));
                for (SchematicEntry s : b.schematics()) {
                    leftList.addEntry(new SchematicListWidget.SchematicEntry(leftList, s.id(),
                            s.title() != null ? s.title() : "Untitled", "", s.thumbnailUrl()));
                }
            }
            if (!state.getUnbundled().isEmpty()) {
                leftList.addEntry(new SchematicListWidget.HeaderEntry(leftList, "\u00a77Unbundled"));
                for (SchematicEntry s : state.getUnbundled())
                    leftList.addEntry(new SchematicListWidget.SchematicEntry(leftList, s.id(),
                            s.title() != null ? s.title() : "Untitled", "", s.thumbnailUrl()));
            }
            if (state.getBundles().isEmpty() && state.getUnbundled().isEmpty())
                leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, "Library empty"));
        } else {
            if (searchLoading) leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, "Searching..."));
            else if (searchField != null && searchField.getValue().length() < 2) leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, "Type to search..."));
        }
    }

    private void onLeftClicked(String id, String title) {
        if (!ServerMode.isDirectModeAvailable()) {
            statusText = "\u00a7eUse the Template Manager to load schematics";
            return;
        }
        statusText = "Downloading...";
        SchematiCraftAPIWrapper.get().downloadSchematic(id).thenAccept(result -> {
            Minecraft.getInstance().execute(() -> {
                try {
                    byte[] data = Files.readAllBytes(result.file);
                    Files.deleteIfExists(result.file);
                    if (com.schematicraft.bg2addon.integration.BG2GadgetHelper.loadTemplateIntoGadget(minecraft.player, data)) {
                        statusText = "\u00a7aLoaded: " + title;
                        SchematiCraftAPIWrapper.get().submitSuccessFeedback(result.downloadId);
                        onClose();
                    } else {
                        statusText = "\u00a7cFailed to parse template";
                        SchematiCraftAPIWrapper.get().submitFailureFeedback(result.downloadId,
                                "import_error_or_crash",
                                "BG2 template parsing failed. loadTemplateIntoGadget returned false. " +
                                "Client: schematicraft-bg2/0.1.0, MC: 1.21.1, Loader: neoforge");
                    }
                } catch (Exception e) {
                    statusText = "\u00a7c" + e.getMessage();
                    SchematiCraftAPIWrapper.get().submitFailureFeedback(result.downloadId,
                            "import_error_or_crash",
                            "Exception during template load: " + e.getClass().getSimpleName() + ": " + e.getMessage() +
                            ". Client: schematicraft-bg2/0.1.0, MC: 1.21.1, Loader: neoforge");
                }
            });
        }).exceptionally(ex -> {
            Minecraft.getInstance().execute(() -> {
                Throwable c = ex;
                while (c.getCause() != null) c = c.getCause();
                String errorMsg = c.getMessage() != null ? c.getMessage() : "Unknown error";
                statusText = "\u00a7c" + errorMsg;

                String category = "other";
                if (errorMsg.contains("analysis") || errorMsg.contains("pending")) {
                    category = "other";
                } else if (errorMsg.contains("quota") || errorMsg.contains("Quota")) {
                    category = "other";
                } else if (errorMsg.contains("HTTP 4") || errorMsg.contains("HTTP 5")) {
                    category = "file_seems_corrupt";
                } else if (errorMsg.contains("convert") || errorMsg.contains("format")) {
                    category = "import_error_or_crash";
                }

                SchematiCraftMod.LOGGER.warn("Download failed for schematic {}: {}", id, errorMsg);
                statusClearAt = System.currentTimeMillis() + 4000;
            });
            return null;
        });
    }


    private void initRightPanel() {
        int y = panelTop + 14;
        int pw = PANEL_W;

        switch (rightMode) {
            case CLIPBOARD -> initClipboardPanel(y, pw);
            case UPLOAD_FORM -> initUploadFormPanel(y, pw);
            case CREATE_BUNDLE -> initCreateBundlePanel(y, pw);
        }
    }

    private void initClipboardPanel(int y, int pw) {
        int listHeight = (panelH - 28) / 2;
        clipboardList = new SchematicListWidget(minecraft, pw, listHeight, y, rightX, this::onClipboardLoad);
        addRenderableWidget(clipboardList);
        rebuildClipboardList();
    }

    private void rebuildClipboardList() {
        if (clipboardList == null) return;
        clipboardList.clearEntries();
        List<ClipboardEntry> clips = state.getClipboard();
        if (clips.isEmpty()) {
            clipboardList.addEntry(new SchematicListWidget.MessageEntry(clipboardList, "No copies yet"));
            clipboardList.addEntry(new SchematicListWidget.MessageEntry(clipboardList, "Copy with gadget to add"));
            return;
        }
        for (ClipboardEntry clip : clips) {
            var widget = new SchematicListWidget.ClipboardEntryWidget(
                    clipboardList, clip, this::onClipboardLoad, this::onClipboardSave);
            widget.setOnHover(this::onClipboardHover);
            clipboardList.addEntry(widget);
        }
    }

    private void onClipboardLoad(String uuid, String title) {
        if (!ServerMode.isDirectModeAvailable()) {
            statusText = "\u00a7eUse the Template Manager to load schematics";
            return;
        }
        try {
            UUID gadgetUuid = UUID.fromString(uuid);
            for (ClipboardEntry clip : state.getClipboard()) {
                if (clip.getGadgetUuid().toString().equals(uuid)) {
                    selectedPreviewEntry = clip;
                    com.schematicraft.bg2addon.client.ClipboardPreviewRenderer.get().prepareForEntry(clip);
                    break;
                }
            }
            PacketDistributor.sendToServer(new LoadClipboardPayload(gadgetUuid));
            statusText = "\u00a7aLoading from clipboard...";
            onClose();
        } catch (Exception e) { statusText = "\u00a7c" + e.getMessage(); }
    }

    private void onClipboardSave(ClipboardEntry clip) {
        uploadTarget = clip;
        uploadImages.clear();
        // Auto-select the active bundle if on a pinned tab
        com.schematicraft.lib.core.PaletteState palette = com.schematicraft.lib.core.PaletteState.get();
        String activeBundleId = palette.getActiveBundleId();
        if (activeBundleId != null) {
            java.util.List<com.schematicraft.lib.core.LibraryState.BundleOption> opts = state.getBundleOptions();
            selectedBundleIndex = 0;
            for (int i = 0; i < opts.size(); i++) {
                if (activeBundleId.equals(opts.get(i).id())) {
                    selectedBundleIndex = i;
                    break;
                }
            }
        } else {
            selectedBundleIndex = 0;
        }
        rightMode = RightMode.UPLOAD_FORM;
        rebuildWidgets();
    }

    private void onClipboardHover(ClipboardEntry clip) {
        if (clip != selectedPreviewEntry) {
            selectedPreviewEntry = clip;
            com.schematicraft.bg2addon.client.ClipboardPreviewRenderer.get().prepareForEntry(clip);
        }
    }


    private void initUploadFormPanel(int y, int pw) {
        int x = rightX;

        uploadTitle = new EditBox(font, x, y + 4, pw, 14, Component.literal(""));
        uploadTitle.setHint(Component.literal("Title (required)"));
        uploadTitle.setMaxLength(200);
        if (uploadTarget != null && uploadTarget.getTitle() != null) uploadTitle.setValue(uploadTarget.getTitle());
        addRenderableWidget(uploadTitle);

        uploadDesc = new EditBox(font, x, y + 22, pw, 14, Component.literal(""));
        uploadDesc.setHint(Component.literal("Description"));
        uploadDesc.setMaxLength(500);
        addRenderableWidget(uploadDesc);

        List<com.schematicraft.lib.core.LibraryState.BundleOption> opts = state.getBundleOptions();
        String bl = selectedBundleIndex < opts.size() ? opts.get(selectedBundleIndex).name() : "Unbundled";
        bundleButton = Button.builder(Component.literal("Bundle: " + bl), b -> {
            selectedBundleIndex = (selectedBundleIndex + 1) % opts.size();
            pendingUploadTitle = uploadTitle != null ? uploadTitle.getValue() : null;
            pendingUploadDesc = uploadDesc != null ? uploadDesc.getValue() : null;
            rebuildWidgets();
        }).bounds(x, y + 40, pw, 14).build();
        addRenderableWidget(bundleButton);

        addRenderableWidget(Button.builder(Component.literal("+ New Bundle"), b -> {
            pendingUploadTitle = uploadTitle != null ? uploadTitle.getValue() : null;
            pendingUploadDesc = uploadDesc != null ? uploadDesc.getValue() : null;
            rightMode = RightMode.CREATE_BUNDLE;
            rebuildWidgets();
        }).bounds(x, y + 58, pw / 2 - 1, 14).build());

        String camLabel = uploadImages.isEmpty()
                ? "\u00a7e\u2B1C Take Screenshots"
                : "\u00a7a\u2713 " + uploadImages.size() + " photo" + (uploadImages.size() > 1 ? "s" : "");
        addRenderableWidget(Button.builder(Component.literal(camLabel), b -> {
            pendingUploadReopen = uploadTarget;
            pendingUploadImages = new ArrayList<>(uploadImages);
            pendingUploadTitle = uploadTitle.getValue();
            pendingUploadDesc = uploadDesc.getValue();
            pendingBundleIndex = selectedBundleIndex;
            com.schematicraft.lib.client.CameraMode.start(pendingUploadImages, () ->
                    Minecraft.getInstance().execute(() ->
                            minecraft.setScreen(new EnhancedRadialMenu(gadgetStack))));
            minecraft.setScreen(null);
        }).bounds(x, y + 78, pw, 18).build());

        // Reserve space for image thumbnails (wraps to multiple rows if needed)
        // Must match render positions: label at (y+114-2), thumbs at (y+114+10)
        int imageStripY = y + 102;
        if (!uploadImages.isEmpty()) {
            for (int i = 0; i < uploadImages.size(); i++) {
                com.schematicraft.lib.client.ThumbnailCache.get()
                        .registerLocalFile("upload_" + i, uploadImages.get(i));
            }
            int thumbH = 14;
            int thumbW = (int)(thumbH * 16.0 / 9.0);
            int thumbsPerRow = Math.max(1, pw / (thumbW + 2));
            int rows = (uploadImages.size() + thumbsPerRow - 1) / thumbsPerRow;
            // y+114 = label area, +10 = label height, then rows of thumbs, +8 gap
            imageStripY = y + 114 + 10 + (rows * (thumbH + 2)) + 8;
        }

        Button saveBtn = Button.builder(Component.literal("\u00a7aSave"), b -> doUpload())
                .bounds(x, imageStripY, pw / 2 - 1, 18).build();
        addRenderableWidget(saveBtn);

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
            rightMode = RightMode.CLIPBOARD;
            uploadTarget = null;
            uploadImages.clear();
            rebuildWidgets();
        }).bounds(x + pw / 2 + 1, imageStripY, pw / 2 - 1, 18).build());
    }

    private void doUpload() {
        if (uploadTarget == null) return;
        String title = uploadTitle.getValue().trim();
        if (title.isEmpty()) { statusText = "\u00a7cTitle required"; return; }

        SchematiCraftMod.LOGGER.info("doUpload (client-side): {} images to include", uploadImages.size());

        List<com.schematicraft.lib.core.LibraryState.BundleOption> opts = state.getBundleOptions();
        String bundleId = selectedBundleIndex < opts.size() ? opts.get(selectedBundleIndex).id() : null;
        String description = uploadDesc.getValue().trim();

        UUID clipboardUuid = uploadTarget.getGadgetUuid();
        ArrayList<com.direwolf20.buildinggadgets2.util.datatypes.StatePos> statePosList =
                com.schematicraft.bg2addon.client.ClipboardPreviewRenderer.get().getClientData(clipboardUuid);

        if (statePosList == null || statePosList.isEmpty()) {
            statusText = "\u00a7cNo template data found for this copy";
            return;
        }

        List<Path> imagePaths = new ArrayList<>(uploadImages);

        uploadTarget.setTitle(title);
        uploadTarget.setUploadedId("pending");
        rightMode = RightMode.CLIPBOARD;
        ClipboardEntry savedTarget = uploadTarget;
        uploadTarget = null;
        uploadImages.clear();
        statusText = "\u00a7eUploading...";
        rebuildWidgets();

        // API key stays on client
        SchematiCraftAPIWrapper.get().uploadFromClient(
                statePosList, title, description, bundleId, imagePaths
        ).thenAccept(isDuplicate -> {
            Minecraft.getInstance().execute(() -> {
                if (isDuplicate) {
                    statusText = "\u00a7eDuplicate skipped, already in library";
                } else {
                    statusText = "\u00a7aUploaded!";
                }
                SchematiCraftAPIWrapper.get().refreshLibrary().thenRun(() ->
                        Minecraft.getInstance().execute(this::rebuildLeftList));
                statusClearAt = System.currentTimeMillis() + 3000;
            });
        }).exceptionally(ex -> {
            Minecraft.getInstance().execute(() -> {
                Throwable c = ex; while (c.getCause() != null) c = c.getCause();
                statusText = "\u00a7cUpload failed: " + (c.getMessage() != null ? c.getMessage() : "Unknown error");
                SchematiCraftMod.LOGGER.error("Client-side upload failed: {}", c.getMessage(), ex);
            });
            return null;
        });
    }

    private void createBundleAction(String name) {
        SchematiCraftAPIWrapper.get().createBundle(name, null).thenAccept(json -> {
            String newBundleId = null;
            int idStart = json.indexOf("\"id\":\"");
            if (idStart >= 0) {
                idStart += 6;
                int idEnd = json.indexOf("\"", idStart);
                if (idEnd > idStart) newBundleId = json.substring(idStart, idEnd);
            }
            final String createdBundleId = newBundleId;

            Minecraft.getInstance().execute(() -> {
                rightMode = RightMode.UPLOAD_FORM;
                SchematiCraftAPIWrapper.get().refreshLibrary().thenRun(() ->
                        Minecraft.getInstance().execute(() -> {
                            if (createdBundleId != null) {
                                List<com.schematicraft.lib.core.LibraryState.BundleOption> opts = state.getBundleOptions();
                                for (int i = 0; i < opts.size(); i++) {
                                    if (createdBundleId.equals(opts.get(i).id())) {
                                        selectedBundleIndex = i;
                                        break;
                                    }
                                }
                            }
                            rebuildWidgets();
                        }));
            });
        }).exceptionally(ex -> {
            Minecraft.getInstance().execute(() -> statusText = "\u00a7cFailed to create bundle");
            return null;
        });
    }

    private void initCreateBundlePanel(int y, int pw) {
        int x = rightX;

        bundleNameField = new EditBox(font, x, y + 4, pw, 14, Component.literal(""));
        bundleNameField.setHint(Component.literal("Bundle name"));
        bundleNameField.setMaxLength(100);
        addRenderableWidget(bundleNameField);

        Button createBtn = Button.builder(Component.literal("\u00a7aCreate"), b -> {
            String name = bundleNameField.getValue().trim();
            if (name.isEmpty()) return;
            b.active = false;
            b.setMessage(Component.literal("\u00a77Creating..."));
            createBundleAction(name);
        }).bounds(x, y + 24, pw / 2 - 1, 16).build();
        addRenderableWidget(createBtn);

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
            rightMode = RightMode.UPLOAD_FORM;
            rebuildWidgets();
        }).bounds(x + pw / 2 + 1, y + 24, pw / 2 - 1, 16).build());
    }


    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // Panel backgrounds
        g.fill(0, panelTop - 4, PANEL_W + 8, height - 16, 0xD0080808);
        g.fill(PANEL_W + 8, panelTop - 4, PANEL_W + 9, height - 16, 0x40FFFFFF);
        g.fill(rightX - 5, panelTop - 4, width, height - 16, 0xD0080808);
        g.fill(rightX - 6, panelTop - 4, rightX - 5, height - 16, 0x40FFFFFF);

        // Inner radial
        innerRadial.render(g, mx, my, pt);

        super.render(g, mx, my, pt);

        // Headers
        // Left panel underline (header is now a button)
        g.fill(leftX, panelTop + 10, leftX + PANEL_W, panelTop + 11, 0x30FFFFFF);

        String rightHeader = switch (rightMode) {
            case CLIPBOARD -> "\u00a7a\u2702 Clipboard";
            case UPLOAD_FORM -> "\u00a7e\u2B06 Save Schematic";
            case CREATE_BUNDLE -> "\u00a7e\u2795 New Bundle";
        };
        g.drawString(font, rightHeader, rightX, panelTop, 0xFFAAFFAA);
        g.fill(rightX, panelTop + 10, rightX + PANEL_W, panelTop + 11, 0x30FFFFFF);

        // Upload form image thumbnails (wraps to multiple rows)
        if (rightMode == RightMode.UPLOAD_FORM && !uploadImages.isEmpty()) {
            int thumbStartY = panelTop + 14 + 114;
            int thumbH = 14;
            int thumbW = (int)(thumbH * 16.0 / 9.0);
            int thumbsPerRow = Math.max(1, PANEL_W / (thumbW + 2));
            int thumbX = rightX;
            int thumbY = thumbStartY;
            g.drawString(font, "\u00a78" + uploadImages.size() + " screenshot" + (uploadImages.size() > 1 ? "s" : "") + ":", rightX, thumbStartY - 2, 0x666666);
            thumbY += 10;
            for (int i = 0; i < uploadImages.size(); i++) {
                if (i > 0 && i % thumbsPerRow == 0) {
                    thumbX = rightX;
                    thumbY += thumbH + 2;
                }
                var tex = com.schematicraft.lib.client.ThumbnailCache.get()
                        .getLocalTexture("upload_" + i);
                if (tex != null) {
                    g.blit(tex, thumbX, thumbY, 0, 0, thumbW, thumbH, thumbW, thumbH);
                } else {
                    g.fill(thumbX, thumbY, thumbX + thumbW, thumbY + thumbH, 0xFF222222);
                    g.fill(thumbX, thumbY, thumbX + thumbW, thumbY + 1, 0x20FFFFFF);
                }
                thumbX += thumbW + 2;
            }
        }

        // Clipboard 3D preview
        if (rightMode == RightMode.CLIPBOARD) {
            int previewY = panelTop + 14 + (panelH - 28) / 2 + 4;
            int previewH = (panelH - 28) / 2 - 8;

            // Show selected entry, or most recent copy
            ClipboardEntry previewEntry = selectedPreviewEntry != null
                    ? selectedPreviewEntry
                    : (state.getClipboard().isEmpty() ? null : state.getClipboard().get(0));

            if (previewEntry != hoveredClipboardEntry) {
                hoveredClipboardEntry = previewEntry;
                com.schematicraft.bg2addon.client.ClipboardPreviewRenderer.get().prepareForEntry(previewEntry);
            }

            if (hoveredClipboardEntry != null) {
                g.fill(rightX - 2, previewY - 2, rightX + PANEL_W + 2, previewY + previewH + 2, 0x60000000);
                g.fill(rightX - 2, previewY - 2, rightX + PANEL_W + 2, previewY - 1, 0x30FFFFFF);
                g.drawString(font, "\u00a78Preview", rightX, previewY + 2, 0x555555);

                g.flush();
                com.schematicraft.bg2addon.client.ClipboardPreviewRenderer.get()
                        .render(g, rightX, previewY, PANEL_W, previewH);
            }
        }

        if (!statusText.isEmpty())
            g.drawCenteredString(font, statusText, width / 2, height - 52, 0xCCCCCC);

        g.drawCenteredString(font, "\u00a78Esc to close", width / 2, height - 36, 0x666666);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {}


    @Override
    public void tick() {
        // Auto-clear status text
        if (statusClearAt > 0 && System.currentTimeMillis() >= statusClearAt) {
            statusText = "";
            statusClearAt = 0;
        }

        if (pendingSearch != null && System.currentTimeMillis() - lastSearchTime > 500) {
            String q = pendingSearch; pendingSearch = null; searchLoading = true; rebuildLeftList();
            SchematiCraftAPIWrapper.get().search(q).thenAccept(json -> {
                Minecraft.getInstance().execute(() -> {
                    searchLoading = false;
                    var results = ApiJsonParser.parseSearch(json);
                    if (leftList != null) {
                        leftList.clearEntries();
                        for (var r : results) { SchematicEntry s = r.schematic();
                            leftList.addEntry(new SchematicListWidget.SchematicEntry(leftList, s.id(),
                                    s.title() != null ? s.title() : "Untitled",
                                    s.ownerName() != null ? s.ownerName() : "",
                                    s.thumbnailUrl())); }
                        if (results.isEmpty()) leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, "No results"));
                    }
                });
            });
        }

        if (rightMode == RightMode.CLIPBOARD) rebuildClipboardList();

        animationTime++;
        try {
            java.lang.reflect.Field f = ModeRadialMenu.class.getDeclaredField("timeIn");
            f.setAccessible(true); f.setInt(innerRadial, animationTime);
        } catch (Exception ignored) {}

        boolean anyFieldFocused = (searchField != null && searchField.isFocused())
                || (uploadTitle != null && uploadTitle.isFocused())
                || (uploadDesc != null && uploadDesc.isFocused())
                || (bundleNameField != null && bundleNameField.isFocused());

        if (!anyFieldFocused) {
            boolean gDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                    Minecraft.getInstance().getWindow().getWindow(), KeyBindings.menuSettings.getKey().getValue());
            if (!gDown) gWasDown = false;
            else if (!gWasDown) { onClose(); return; }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // Right-click on bundle button cycles backward
        if (btn == 1 && bundleButton != null && rightMode == RightMode.UPLOAD_FORM
                && mx >= bundleButton.getX() && mx <= bundleButton.getX() + bundleButton.getWidth()
                && my >= bundleButton.getY() && my <= bundleButton.getY() + bundleButton.getHeight()) {
            List<com.schematicraft.lib.core.LibraryState.BundleOption> opts = state.getBundleOptions();
            selectedBundleIndex = (selectedBundleIndex - 1 + opts.size()) % opts.size();
            pendingUploadTitle = uploadTitle != null ? uploadTitle.getValue() : null;
            pendingUploadDesc = uploadDesc != null ? uploadDesc.getValue() : null;
            rebuildWidgets();
            return true;
        }

        if (mx < PANEL_W + 8 || mx > rightX - 4) return super.mouseClicked(mx, my, btn);

        boolean innerHandled = innerRadial.mouseClicked(mx, my, btn);
        if (innerHandled) return true;

        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        // Scroll wheel on bundle button cycles through bundles
        if (bundleButton != null && rightMode == RightMode.UPLOAD_FORM
                && mx >= bundleButton.getX() && mx <= bundleButton.getX() + bundleButton.getWidth()
                && my >= bundleButton.getY() && my <= bundleButton.getY() + bundleButton.getHeight()) {
            List<com.schematicraft.lib.core.LibraryState.BundleOption> opts = state.getBundleOptions();
            if (scrollY > 0) {
                selectedBundleIndex = (selectedBundleIndex - 1 + opts.size()) % opts.size();
            } else if (scrollY < 0) {
                selectedBundleIndex = (selectedBundleIndex + 1) % opts.size();
            }
            pendingUploadTitle = uploadTitle != null ? uploadTitle.getValue() : null;
            pendingUploadDesc = uploadDesc != null ? uploadDesc.getValue() : null;
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        // Two-level Esc: first back to clipboard from upload/bundle form, then close menu
        if (key == 256) { // Esc
            if (rightMode == RightMode.CREATE_BUNDLE) {
                rightMode = RightMode.UPLOAD_FORM;
                rebuildWidgets();
                return true;
            }
            if (rightMode == RightMode.UPLOAD_FORM) {
                rightMode = RightMode.CLIPBOARD;
                uploadTarget = null;
                uploadImages.clear();
                rebuildWidgets();
                return true;
            }
        }

        // Enter in title field triggers save
        if (key == 257 && uploadTitle != null && uploadTitle.isFocused() && rightMode == RightMode.UPLOAD_FORM) {
            doUpload();
            return true;
        }

        // Enter in bundle name field triggers create
        if (key == 257 && bundleNameField != null && bundleNameField.isFocused() && rightMode == RightMode.CREATE_BUNDLE) {
            // Find and click the create button
            String name = bundleNameField.getValue().trim();
            if (!name.isEmpty()) {
                bundleNameField.setFocused(false);
                // Trigger the create action directly
                createBundleAction(name);
            }
            return true;
        }

        if (searchField != null && searchField.visible && searchField.isFocused()) return super.keyPressed(key, scan, mod);
        if (uploadTitle != null && uploadTitle.isFocused()) return super.keyPressed(key, scan, mod);
        if (uploadDesc != null && uploadDesc.isFocused()) return super.keyPressed(key, scan, mod);
        if (bundleNameField != null && bundleNameField.isFocused()) return super.keyPressed(key, scan, mod);
        return super.keyPressed(key, scan, mod);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
