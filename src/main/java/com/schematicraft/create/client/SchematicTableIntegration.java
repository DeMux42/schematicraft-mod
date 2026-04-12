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
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME, modid = SchematiCraftMod.MODID)
public class SchematicTableIntegration {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int PANEL_W = 160;

	// Right panel: cloud library
	private enum RightTab { LIBRARY, SEARCH }
	private static RightTab rightTab = RightTab.LIBRARY;
	private static SchematicListWidget rightList;
	private static EditBox searchField;
	private static boolean searchLoading = false;
	private static String pendingSearch = null;
	private static long lastSearchTime = 0;

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
	private static Button headerBtn, logoutBtn, libBtn, srchBtn, bundleBtn;

	@SubscribeEvent
	public static void onScreenInit(ScreenEvent.Init.Post event) {
		if (!(event.getScreen() instanceof SchematicTableScreen screen)) return;

		Minecraft mc = Minecraft.getInstance();
		statusText = "";
		rightList = null;
		leftList = null;
		searchField = null;
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

		initRightPanel(event, screen, mc);
		initLeftPanel(event, screen, mc);

		LibraryState state = LibraryState.get();
		if (!state.isLibraryLoaded() && !state.isLibraryLoading()) {
			SchematiCraftAPIWrapper.get().loadLibrary().thenRun(() ->
				mc.execute(SchematicTableIntegration::rebuildRightList));
		}
	}

	private static void initRightPanel(ScreenEvent.Init.Post event, SchematicTableScreen screen, Minecraft mc) {
		int rightX = screen.width - PANEL_W - 6;
		int y = 10;

		headerBtn = Button.builder(Component.literal("\u00a7b\u2601 Schematicraft"),
			b -> net.minecraft.Util.getPlatform().openUri("https://www.schematicraft.com"))
			.bounds(rightX, y, PANEL_W - 14, 12).build();
		event.addListener(headerBtn);

		logoutBtn = Button.builder(Component.literal("\u00a7c\u2716"),
			b -> { ModConfig.setApiKey(""); mc.setScreen(screen); })
			.bounds(rightX + PANEL_W - 12, y, 12, 12).build();
		event.addListener(logoutBtn);
		y += 14;

		libBtn = Button.builder(Component.literal("Library"),
			b -> { rightTab = RightTab.LIBRARY; mc.setScreen(screen); })
			.bounds(rightX, y, PANEL_W / 2 - 1, 14).build();
		srchBtn = Button.builder(Component.literal("Search"),
			b -> { rightTab = RightTab.SEARCH; mc.setScreen(screen); })
			.bounds(rightX + PANEL_W / 2 + 1, y, PANEL_W / 2 - 1, 14).build();
		libBtn.active = rightTab != RightTab.LIBRARY;
		srchBtn.active = rightTab != RightTab.SEARCH;
		event.addListener(libBtn);
		event.addListener(srchBtn);
		y += 16;

		if (rightTab == RightTab.SEARCH) {
			searchField = new EditBox(mc.font, rightX, y, PANEL_W, 14, Component.literal(""));
			searchField.setHint(Component.literal("Search schematics..."));
			searchField.setMaxLength(100);
			searchField.setResponder(t -> {
				if (t.length() < 2) { rebuildRightList(); return; }
				pendingSearch = t;
				lastSearchTime = System.currentTimeMillis();
			});
			event.addListener(searchField);
			y += 16;
		}

		int listH = screen.height - y - 16;
		rightList = new SchematicListWidget(mc, PANEL_W, listH, y, rightX,
			SchematicTableIntegration::onCloudSchematicClicked);
		event.addListener(rightList);
		rebuildRightList();
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
		selectedBundleIndex = 0;
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
					Minecraft.getInstance().execute(SchematicTableIntegration::rebuildRightList));
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
	public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
		if (!(event.getScreen() instanceof SchematicTableScreen)) return;
		EditBox focused = null;
		if (searchField != null && searchField.isFocused()) focused = searchField;
		if (uploadTitle != null && uploadTitle.isFocused()) focused = uploadTitle;
		if (uploadDesc != null && uploadDesc.isFocused()) focused = uploadDesc;
		if (focused != null) {
			int key = event.getKeyCode();
			if (key != 256 && key != 257) {
				focused.keyPressed(key, event.getScanCode(), event.getModifiers());
				event.setCanceled(true);
			}
		}
	}

	@SubscribeEvent
	public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
		if (!(event.getScreen() instanceof SchematicTableScreen)) return;
		EditBox focused = null;
		if (searchField != null && searchField.isFocused()) focused = searchField;
		if (uploadTitle != null && uploadTitle.isFocused()) focused = uploadTitle;
		if (uploadDesc != null && uploadDesc.isFocused()) focused = uploadDesc;
		if (focused != null) {
			focused.charTyped(event.getCodePoint(), event.getModifiers());
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
		int rightX = screen.width - PANEL_W - 6;
		int mx = event.getMouseX();
		int my = event.getMouseY();
		float pt = event.getPartialTick();

		// Right panel background
		g.fill(rightX - 4, 6, screen.width, screen.height - 6, 0xD0080808);
		g.fill(rightX - 5, 6, rightX - 4, screen.height - 6, 0x40FFFFFF);
		g.fill(rightX, 20, rightX + PANEL_W, 21, 0x30FFFFFF);

		// Left panel background
		g.fill(0, 6, PANEL_W + 8, screen.height - 6, 0xD0080808);
		g.fill(PANEL_W + 8, 6, PANEL_W + 9, screen.height - 6, 0x40FFFFFF);

		if (showUploadForm) {
			g.drawString(mc.font, "\u00a7e\u2B06 Upload Schematic", leftX, 12, 0xFFFFFF);
		} else {
			g.drawString(mc.font, "\u00a7a\u2702 Local Files", leftX, 12, 0xFFFFFF);
		}
		g.fill(leftX, 20, leftX + PANEL_W, 21, 0x30FFFFFF);

		// Re-render all widgets on top
		if (rightList != null) rightList.render(g, mx, my, pt);
		if (leftList != null) leftList.render(g, mx, my, pt);
		if (searchField != null) searchField.render(g, mx, my, pt);
		if (uploadTitle != null) uploadTitle.render(g, mx, my, pt);
		if (uploadDesc != null) uploadDesc.render(g, mx, my, pt);
		if (headerBtn != null) headerBtn.render(g, mx, my, pt);
		if (logoutBtn != null) logoutBtn.render(g, mx, my, pt);
		if (libBtn != null) libBtn.render(g, mx, my, pt);
		if (srchBtn != null) srchBtn.render(g, mx, my, pt);
		if (bundleBtn != null) bundleBtn.render(g, mx, my, pt);

		// Status text
		if (statusClearAt > 0 && System.currentTimeMillis() >= statusClearAt) {
			statusText = "";
			statusClearAt = 0;
		}
		if (!statusText.isEmpty()) {
			g.drawCenteredString(mc.font, statusText, screen.width / 2, screen.height - 14, 0xCCCCCC);
		}

		// Search debounce
		if (pendingSearch != null && System.currentTimeMillis() - lastSearchTime > 500) {
			String q = pendingSearch;
			pendingSearch = null;
			searchLoading = true;
			rebuildRightList();
			SchematiCraftAPIWrapper.get().search(q).thenAccept(json -> {
				mc.execute(() -> {
					searchLoading = false;
					var results = ApiJsonParser.parseSearch(json);
					if (rightList != null) {
						rightList.clearEntries();
						for (var r : results) {
							SchematicEntry s = r.schematic();
							rightList.addEntry(new SchematicListWidget.SchematicEntry(rightList, s.id(),
								s.title() != null ? s.title() : "Untitled",
								s.ownerName() != null ? s.ownerName() : "",
								s.thumbnailUrl()));
						}
						if (results.isEmpty())
							rightList.addEntry(new SchematicListWidget.MessageEntry(rightList, "No results"));
					}
				});
			});
		}
	}

	private static void rebuildRightList() {
		if (rightList == null) return;
		rightList.clearEntries();
		LibraryState state = LibraryState.get();

		if (rightTab == RightTab.LIBRARY) {
			if (state.isLibraryLoading()) {
				rightList.addEntry(new SchematicListWidget.MessageEntry(rightList, "Loading..."));
				return;
			}
			if (state.getLibraryError() != null) {
				rightList.addEntry(new SchematicListWidget.MessageEntry(rightList, state.getLibraryError()));
				return;
			}
			for (BundleEntry b : state.getBundles()) {
				rightList.addEntry(new SchematicListWidget.HeaderEntry(rightList, "\u00a7e" + b.name()));
				for (SchematicEntry s : b.schematics()) {
					rightList.addEntry(new SchematicListWidget.SchematicEntry(rightList, s.id(),
						s.title() != null ? s.title() : "Untitled", "", s.thumbnailUrl()));
				}
			}
			if (!state.getUnbundled().isEmpty()) {
				rightList.addEntry(new SchematicListWidget.HeaderEntry(rightList, "\u00a77Unbundled"));
				for (SchematicEntry s : state.getUnbundled()) {
					rightList.addEntry(new SchematicListWidget.SchematicEntry(rightList, s.id(),
						s.title() != null ? s.title() : "Untitled", "", s.thumbnailUrl()));
				}
			}
			if (state.getBundles().isEmpty() && state.getUnbundled().isEmpty()) {
				rightList.addEntry(new SchematicListWidget.MessageEntry(rightList, "Library empty"));
			}
		} else {
			if (searchLoading) {
				rightList.addEntry(new SchematicListWidget.MessageEntry(rightList, "Searching..."));
			} else if (searchField != null && searchField.getValue().length() < 2) {
				rightList.addEntry(new SchematicListWidget.MessageEntry(rightList, "Type to search..."));
			}
		}
	}

	private static void onCloudSchematicClicked(String id, String title) {
		statusText = "\u00a7eDownloading...";

		SchematiCraftAPIWrapper.get().runAsync(() -> {
			var client = SchematiCraftAPIWrapper.get().createClient();
			Path tempFile = Files.createTempFile("schematicraft_dl_", ".nbt");
			return client.download(id, tempFile, "nbt", "Create", null, null);
		}).thenAccept(result -> {
			Minecraft.getInstance().execute(() -> {
				try {
					byte[] data = Files.readAllBytes(result.file);
					Files.deleteIfExists(result.file);

					String filename = title != null ? title : "schematic";
					Path saved = SchematicFileHelper.saveToSchematicsFolder(filename, data);

					if (saved != null) {
						statusText = "\u00a7aSaved: " + saved.getFileName();
						statusClearAt = System.currentTimeMillis() + 4000;
						CreateClient.SCHEMATIC_SENDER.refresh();
						SchematiCraftAPIWrapper.get().submitSuccessFeedback(result.downloadId);
						rebuildLeftList();
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
