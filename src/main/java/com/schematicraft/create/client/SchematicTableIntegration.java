package com.schematicraft.create.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.schematics.table.SchematicTableScreen;
import com.simibubi.create.CreateClient;
import com.schematicraft.api.SchematiCraftAPI;
import com.schematicraft.SchematiCraftMod;
import com.schematicraft.lib.client.CameraMode;
import com.schematicraft.lib.client.screen.ApiKeyScreen;
import com.schematicraft.lib.client.screen.SchematicListWidget;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.lib.core.*;
import com.schematicraft.lib.network.SchematiCraftAPIWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Injects Schematicraft panels into Create's Schematic Table screen.
 *
 * Right panel: cloud library browser (download .nbt to local schematics folder).
 * Left panel: local schematics with upload-to-cloud capability.
 *
 * Purely client-side. No custom packets, no server mod required.
 */
public class SchematicTableIntegration {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int PANEL_W = 170;

	// Right panel: PalettePanel (replaces old Library/Search tabs)
	private static com.schematicraft.lib.client.screen.PalettePanel palettePanel;

	// Left panel: local schematics + upload
	private static SchematicListWidget leftList;
	private static boolean showUploadForm = false;
	private static Path selectedLocalFile = null;
	private static EditBox uploadTitle;
	private static EditBox uploadDesc;
	private static int selectedBundleIndex = 0;
	private static List<Path> uploadImages = new ArrayList<>();

	// Shared
	private static String statusText = "";
	private static long statusClearAt = 0;
	private static Button bundleBtn;

	@SubscribeEvent
	public static void onScreenInit(ScreenEvent.Init.Post event) {
		if (!(event.getScreen() instanceof SchematicTableScreen screen)) return;

		Minecraft mc = Minecraft.getInstance();
		statusText = "";
		palettePanel = null;
		leftList = null;
		uploadTitle = null;
		uploadDesc = null;

		if (!ModConfig.hasApiKey()) {
			int rightX = screen.width - PANEL_W - 6;
			event.addListener(Button.builder(Component.literal("\u00a7bSchematicraft"),
				b -> net.minecraft.Util.getPlatform().openUri("https://www.schematicraft.com"))
				.bounds(rightX, 10, PANEL_W, 12).build());
			event.addListener(Button.builder(Component.literal("Set API Key"),
				b -> mc.setScreen(new ApiKeyScreen(screen)))
				.bounds(rightX, 26, PANEL_W, 16).build());
			return;
		}

		// Right panel: PalettePanel for cloud library
		palettePanel = new com.schematicraft.lib.client.screen.PalettePanel(
				SchematicTableIntegration::onCloudSchematicClicked,
				com.schematicraft.lib.client.screen.PalettePanel.Side.RIGHT);
		palettePanel.init(event, screen);

		initLeftPanel(event, screen, mc);
	}

	private static void initLeftPanel(ScreenEvent.Init.Post event, SchematicTableScreen screen, Minecraft mc) {
		int leftX = 4;
		int y = 10;

		if (showUploadForm && selectedLocalFile != null) {
			// Upload form
			String fileName = selectedLocalFile.getFileName().toString().replace(".nbt", "");

			uploadTitle = new EditBox(mc.font, leftX, y + 4, PANEL_W, 14, Component.literal(""));
			uploadTitle.setHint(Component.literal("Title"));
			uploadTitle.setMaxLength(200);
			uploadTitle.setValue(fileName);
			event.addListener(uploadTitle);

			uploadDesc = new EditBox(mc.font, leftX, y + 22, PANEL_W, 14, Component.literal(""));
			uploadDesc.setHint(Component.literal("Description"));
			uploadDesc.setMaxLength(500);
			event.addListener(uploadDesc);

			// Bundle selector
			List<LibraryState.BundleOption> opts = LibraryState.get().getBundleOptions();
			String bl = selectedBundleIndex < opts.size() ? opts.get(selectedBundleIndex).name() : "Unbundled";
			bundleBtn = Button.builder(Component.literal("Bundle: " + bl), b -> {
				selectedBundleIndex = (selectedBundleIndex + 1) % opts.size();
				mc.setScreen(screen);
			}).bounds(leftX, y + 40, PANEL_W, 14).build();
			event.addListener(bundleBtn);

			// Screenshots button
			String camLabel = uploadImages.isEmpty()
				? "\u00a7e\u2B1C Take Screenshots"
				: "\u00a7a\u2713 " + uploadImages.size() + " photo" + (uploadImages.size() > 1 ? "s" : "");
			event.addListener(Button.builder(Component.literal(camLabel), b -> {
				CameraMode.start(uploadImages, () ->
					Minecraft.getInstance().execute(() -> mc.setScreen(screen)));
				mc.setScreen(null);
			}).bounds(leftX, y + 58, PANEL_W, 16).build());

			// Upload + Cancel
			event.addListener(Button.builder(Component.literal("\u00a7aUpload"), b -> doUpload(screen))
				.bounds(leftX, y + 80, PANEL_W / 2 - 1, 16).build());
			event.addListener(Button.builder(Component.literal("Cancel"), b -> {
				showUploadForm = false;
				selectedLocalFile = null;
				uploadImages.clear();
				mc.setScreen(screen);
			}).bounds(leftX + PANEL_W / 2 + 1, y + 80, PANEL_W / 2 - 1, 16).build());
		} else {
			// Local schematics list
			int listH = screen.height - y - 16;
			leftList = new SchematicListWidget(mc, PANEL_W, listH, y, leftX,
				SchematicTableIntegration::onLocalSchematicClicked);
			event.addListener(leftList);
			rebuildLeftList();
		}
	}

	private static void rebuildLeftList() {
		if (leftList == null) return;
		leftList.clearEntries();
		leftList.addEntry(new SchematicListWidget.HeaderEntry(leftList, "\u00a7eLocal Schematics"));
		leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, "Click to upload to cloud"));

		List<Path> locals = SchematicFileHelper.listLocalSchematics();
		if (locals.isEmpty()) {
			leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, "No .nbt files found"));
			leftList.addEntry(new SchematicListWidget.MessageEntry(leftList, "Use Schematic & Quill to capture"));
		} else {
			for (Path p : locals) {
				String name = p.getFileName().toString().replace(".nbt", "");
				long kb = 0;
				try { kb = Files.size(p) / 1024; } catch (Exception ignored) {}
				leftList.addEntry(new SchematicListWidget.SchematicEntry(leftList,
					p.toString(), name, kb + " KB", null));
			}
		}
	}

	private static void onLocalSchematicClicked(String pathStr, String title) {
		selectedLocalFile = Path.of(pathStr);
		showUploadForm = true;
		// Auto-select the active bundle if on a pinned tab
		com.schematicraft.lib.core.PaletteState palette = com.schematicraft.lib.core.PaletteState.get();
		String activeBundleId = palette.getActiveBundleId();
		if (activeBundleId != null) {
			List<LibraryState.BundleOption> opts = LibraryState.get().getBundleOptions();
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
		uploadImages.clear();
		Minecraft.getInstance().setScreen(Minecraft.getInstance().screen);
	}

	private static void doUpload(SchematicTableScreen screen) {
		if (selectedLocalFile == null || !Files.exists(selectedLocalFile)) {
			statusText = "\u00a7cFile not found";
			statusClearAt = System.currentTimeMillis() + 3000;
			return;
		}
		String title = uploadTitle != null ? uploadTitle.getValue().trim() : "";
		if (title.isEmpty()) {
			statusText = "\u00a7cTitle required";
			return;
		}

		String desc = uploadDesc != null ? uploadDesc.getValue().trim() : "";
		List<LibraryState.BundleOption> opts = LibraryState.get().getBundleOptions();
		String bundleId = selectedBundleIndex < opts.size() ? opts.get(selectedBundleIndex).id() : null;
		Path file = selectedLocalFile;
		List<Path> images = new ArrayList<>(uploadImages);

		showUploadForm = false;
		selectedLocalFile = null;
		uploadImages.clear();
		statusText = "\u00a7eUploading...";
		Minecraft.getInstance().setScreen(screen);

		SchematiCraftAPIWrapper.get().runAsync(() -> {
			SchematiCraftAPI client = SchematiCraftAPIWrapper.get().createClient();
			return client.upload(file, title, desc, "1.21.1", "neoforge", null,
				false, bundleId, images.isEmpty() ? null : images);
		}).thenAccept(response -> {
			Minecraft.getInstance().execute(() -> {
				boolean isDupe = response != null && response.contains("\"isDuplicate\":true");
				statusText = isDupe
					? "\u00a7eDuplicate skipped, already in library"
					: "\u00a7aUploaded!";
				statusClearAt = System.currentTimeMillis() + 3000;
				SchematiCraftAPIWrapper.get().refreshLibrary().thenRun(() ->
					Minecraft.getInstance().execute(() -> {
						if (palettePanel != null) {
							com.schematicraft.lib.core.PaletteState.get().refilter();
							palettePanel.rebuildList();
						}
					}));
			});
		}).exceptionally(ex -> {
			Minecraft.getInstance().execute(() -> {
				Throwable c = ex;
				while (c.getCause() != null) c = c.getCause();
				statusText = "\u00a7cUpload failed: " + (c.getMessage() != null ? c.getMessage() : "Unknown");
				statusClearAt = System.currentTimeMillis() + 4000;
			});
			return null;
		});
	}

	@SubscribeEvent
	public static void onMouseClicked(ScreenEvent.MouseButtonPressed.Pre event) {
		if (!(event.getScreen() instanceof SchematicTableScreen)) return;
		// Forward right-clicks in our panel area to the palette list
		if (event.getButton() == 1 && palettePanel != null && palettePanel.getListWidget() != null) {
			double mx = event.getMouseX();
			double my = event.getMouseY();
			if (palettePanel.getListWidget().isMouseOver(mx, my)) {
				if (palettePanel.getListWidget().mouseClicked(mx, my, 1)) {
					event.setCanceled(true);
				}
			}
		}
	}

	@SubscribeEvent
	public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
		if (!(event.getScreen() instanceof SchematicTableScreen)) return;
		// Check upload form fields first
		EditBox focused = null;
		if (uploadTitle != null && uploadTitle.isFocused()) focused = uploadTitle;
		if (uploadDesc != null && uploadDesc.isFocused()) focused = uploadDesc;
		if (focused != null) {
			int key = event.getKeyCode();
			if (key != 256 && key != 257) {
				focused.keyPressed(key, event.getScanCode(), event.getModifiers());
				event.setCanceled(true);
			}
			return;
		}
		// Delegate to palette panel
		if (palettePanel != null && palettePanel.getFilterField() != null && palettePanel.getFilterField().isFocused()) {
			int key = event.getKeyCode();
			if (key != 256) {
				if (palettePanel.onKeyPressed(key, event.getScanCode(), event.getModifiers())) {
					event.setCanceled(true);
				}
			}
		}
	}

	@SubscribeEvent
	public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
		if (!(event.getScreen() instanceof SchematicTableScreen)) return;
		EditBox focused = null;
		if (uploadTitle != null && uploadTitle.isFocused()) focused = uploadTitle;
		if (uploadDesc != null && uploadDesc.isFocused()) focused = uploadDesc;
		if (focused != null) {
			focused.charTyped(event.getCodePoint(), event.getModifiers());
			event.setCanceled(true);
			return;
		}
		if (palettePanel != null && palettePanel.onCharTyped(event.getCodePoint(), event.getModifiers())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public static void onScreenRender(ScreenEvent.Render.Post event) {
		if (!(event.getScreen() instanceof SchematicTableScreen screen)) return;
		if (!ModConfig.hasApiKey()) return;

		Minecraft mc = Minecraft.getInstance();
		GuiGraphics g = event.getGuiGraphics();
		int leftX = 4;
		int mx = event.getMouseX();
		int my = event.getMouseY();
		float pt = event.getPartialTick();

		// Right panel: PalettePanel handles its own background and rendering
		if (palettePanel != null) {
			palettePanel.render(g, screen, mx, my, pt);
		}

		// Left panel background
		g.fill(0, 6, PANEL_W + 8, screen.height - 6, 0xD0080808);
		g.fill(PANEL_W + 8, 6, PANEL_W + 9, screen.height - 6, 0x40FFFFFF);

		if (showUploadForm) {
			g.drawString(mc.font, "\u00a7e\u2B06 Upload Schematic", leftX, 12, 0xFFFFFF);
		} else {
			g.drawString(mc.font, "\u00a7a\u2702 Local Files", leftX, 12, 0xFFFFFF);
		}
		g.fill(leftX, 20, leftX + PANEL_W, 21, 0x30FFFFFF);

		// Re-render left panel widgets on top
		if (leftList != null) leftList.render(g, mx, my, pt);
		if (uploadTitle != null) uploadTitle.render(g, mx, my, pt);
		if (uploadDesc != null) uploadDesc.render(g, mx, my, pt);
		if (bundleBtn != null) bundleBtn.render(g, mx, my, pt);

		// Status text
		if (statusClearAt > 0 && System.currentTimeMillis() >= statusClearAt) {
			statusText = "";
			statusClearAt = 0;
		}
		if (!statusText.isEmpty()) {
			g.drawCenteredString(mc.font, statusText, screen.width / 2, screen.height - 14, 0xCCCCCC);
		}
	}

	private static void onCloudSchematicClicked(String id, String title) {
		// Check file cache first
		com.schematicraft.lib.core.SchematicFileCache cache = com.schematicraft.lib.core.SchematicFileCache.get();
		byte[] cached = cache.readCached(id);
		if (cached != null) {
			String filename = title != null ? title : "schematic";
			Path saved = SchematicFileHelper.saveToSchematicsFolder(filename, cached);
			if (saved != null) {
				statusText = "\u00a7aSaved: " + saved.getFileName();
				statusClearAt = System.currentTimeMillis() + 4000;
				CreateClient.SCHEMATIC_SENDER.refresh();
				rebuildLeftList();
			} else {
				statusText = "\u00a7cFailed to save file";
				statusClearAt = System.currentTimeMillis() + 4000;
			}
			return;
		}

		// Cache miss: download from API
		statusText = "\u00a7eDownloading...";

		SchematiCraftAPIWrapper.get().runAsync(() -> {
			var client = SchematiCraftAPIWrapper.get().createClient();
			Path tempFile = Files.createTempFile("schematicraft_dl_", ".nbt");
			return client.download(id, tempFile, "nbt", "Create", null, null);
		}).thenAccept(result -> {
			Minecraft.getInstance().execute(() -> {
				try {
					byte[] data = Files.readAllBytes(result.file);
					// Cache for next time
					cache.store(id, "nbt", data);
					Files.deleteIfExists(result.file);

					String filename = title != null ? title : "schematic";
					Path saved = SchematicFileHelper.saveToSchematicsFolder(filename, data);

					if (saved != null) {
						statusText = "\u00a7aSaved: " + saved.getFileName();
						statusClearAt = System.currentTimeMillis() + 4000;
						CreateClient.SCHEMATIC_SENDER.refresh();
						SchematiCraftAPIWrapper.get().submitSuccessFeedback(result.downloadId);
						rebuildLeftList();
						if (palettePanel != null) palettePanel.rebuildList();
					} else {
						statusText = "\u00a7cFailed to save file";
						statusClearAt = System.currentTimeMillis() + 4000;
					}
				} catch (Exception e) {
					statusText = "\u00a7c" + e.getMessage();
					statusClearAt = System.currentTimeMillis() + 4000;
				}
			});
		}).exceptionally(ex -> {
			Minecraft.getInstance().execute(() -> {
				Throwable c = ex;
				while (c.getCause() != null) c = c.getCause();
				statusText = "\u00a7c" + (c.getMessage() != null ? c.getMessage() : "Download failed");
				statusClearAt = System.currentTimeMillis() + 4000;
			});
			return null;
		});
	}
}
