package com.schematicraft.bg2addon.client.screen;
import com.schematicraft.lib.client.screen.ApiKeyScreen;
import com.schematicraft.lib.core.SchematicEntry;

import com.google.gson.*;
import com.schematicraft.SchematiCraftMod;
import com.schematicraft.lib.config.ModConfig;
import com.schematicraft.bg2addon.integration.BG2Integration;
import com.schematicraft.bg2addon.network.SchematiCraftAPIWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Main SchematiCraft GUI screen with Search and Library tabs.
 * Consumes raw JSON from the official API client.
 */
public class SchematiCraftScreen extends Screen {

    private enum Tab { LIBRARY, SEARCH }

    private Tab activeTab = Tab.LIBRARY;
    private Button libraryTabBtn;
    private Button searchTabBtn;
    private Button settingsBtn;

    // Parsed schematic entries (id + title + subtitle)
    private record SchematicItem(String id, String title, String subtitle) {}

    // Library state
    private final List<Object> libraryEntries = new ArrayList<>(); // SchematicItem or String (header)
    private boolean libraryLoading = true;
    private String libraryError = null;

    // Search state
    private net.minecraft.client.gui.components.EditBox searchField;
    private final List<SchematicItem> searchResults = new ArrayList<>();
    private boolean searchLoading = false;
    private String searchError = null;

    // Shared
    private SchematicListWidget listWidget;
    private String statusText = "";

    private static final Gson GSON = new Gson();

    public SchematiCraftScreen() {
        super(Component.translatable("screen." + SchematiCraftMod.MODID + ".main.title"));
    }

    @Override
    protected void init() {
        int panelLeft = this.width / 2 - 150;
        int panelTop = 30;
        int panelWidth = 300;

        libraryTabBtn = Button.builder(
                Component.translatable("screen." + SchematiCraftMod.MODID + ".tab.library"),
                btn -> switchTab(Tab.LIBRARY)
        ).bounds(panelLeft, panelTop, panelWidth / 2 - 1, 20).build();
        this.addRenderableWidget(libraryTabBtn);

        searchTabBtn = Button.builder(
                Component.translatable("screen." + SchematiCraftMod.MODID + ".tab.search"),
                btn -> switchTab(Tab.SEARCH)
        ).bounds(panelLeft + panelWidth / 2 + 1, panelTop, panelWidth / 2 - 1, 20).build();
        this.addRenderableWidget(searchTabBtn);

        settingsBtn = Button.builder(
                Component.literal("\u2699"),
                btn -> this.minecraft.setScreen(new ApiKeyScreen(this))
        ).bounds(panelLeft + panelWidth - 20, panelTop - 20, 20, 18).build();
        this.addRenderableWidget(settingsBtn);

        searchField = new net.minecraft.client.gui.components.EditBox(
                this.font, panelLeft, panelTop + 24, panelWidth, 18,
                Component.literal("Search...")
        );
        searchField.setHint(Component.literal("Search schematics..."));
        searchField.setMaxLength(100);
        searchField.setResponder(this::onSearchTextChanged);
        searchField.visible = (activeTab == Tab.SEARCH);
        this.addRenderableWidget(searchField);

        int listTop = panelTop + (activeTab == Tab.SEARCH ? 46 : 24);
        int listBottom = this.height - 30;
        listWidget = new SchematicListWidget(
                this.minecraft, panelWidth, listBottom - listTop,
                listTop, panelLeft, this::onSchematicSelected
        );
        this.addRenderableWidget(listWidget);

        updateTabVisuals();

        if (activeTab == Tab.LIBRARY && libraryLoading) {
            loadLibrary();
        }

        rebuildList();
    }

    private void switchTab(Tab tab) {
        if (activeTab == tab) return;
        activeTab = tab;
        searchField.visible = (tab == Tab.SEARCH);

        this.rebuildWidgets();

        if (tab == Tab.LIBRARY && libraryEntries.isEmpty() && libraryError == null) {
            loadLibrary();
        }

        rebuildList();
    }

    private void updateTabVisuals() {
        libraryTabBtn.active = (activeTab != Tab.LIBRARY);
        searchTabBtn.active = (activeTab != Tab.SEARCH);
    }

    private void loadLibrary() {
        libraryLoading = true;
        libraryError = null;
        statusText = "Loading library...";

        SchematiCraftAPIWrapper.get().loadLibrary().thenRun(() -> {
            Minecraft.getInstance().execute(() -> {
                libraryLoading = false;
                var state = com.schematicraft.bg2addon.core.SchematiCraftState.get();
                libraryEntries.clear();
                for (var bundle : state.getBundles()) {
                    libraryEntries.add("\u00a7e\u00a7l" + bundle.name());
                    for (var s : bundle.schematics()) {
                        libraryEntries.add(new SchematicItem(s.id(), s.title() != null ? s.title() : "Untitled", ""));
                    }
                }
                for (var s : state.getUnbundled()) {
                    libraryEntries.add(new SchematicItem(s.id(), s.title() != null ? s.title() : "Untitled", ""));
                }
                int total = (int) libraryEntries.stream().filter(e -> e instanceof SchematicItem).count();
                statusText = total + " schematics in library";
                rebuildList();
            });
        }).exceptionally(ex -> {
            Minecraft.getInstance().execute(() -> {
                libraryLoading = false;
                libraryError = "Failed to load: " + getRootMessage(ex);
                statusText = libraryError;
                rebuildList();
            });
            return null;
        });
    }

    private void parseLibraryJson(String json) {
        libraryEntries.clear();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int total = 0;

            JsonArray bundles = root.getAsJsonArray("bundles");
            if (bundles != null) {
                for (JsonElement be : bundles) {
                    JsonObject bundle = be.getAsJsonObject();
                    String bundleName = getStr(bundle, "name", "Unnamed Bundle");
                    libraryEntries.add("\u00a7e\u00a7l" + bundleName); // header

                    JsonArray schematics = bundle.getAsJsonArray("schematics");
                    if (schematics != null) {
                        for (JsonElement se : schematics) {
                            libraryEntries.add(parseSchematicItem(se.getAsJsonObject()));
                            total++;
                        }
                    }
                }
            }

            JsonArray unbundled = root.getAsJsonArray("unbundled");
            if (unbundled != null && !unbundled.isEmpty()) {
                if (!libraryEntries.isEmpty()) {
                    libraryEntries.add("\u00a77Unbundled"); // header
                }
                for (JsonElement se : unbundled) {
                    libraryEntries.add(parseSchematicItem(se.getAsJsonObject()));
                    total++;
                }
            }

            statusText = total + " schematics in library";
        } catch (Exception e) {
            libraryError = "Failed to parse library";
            statusText = libraryError;
        }
    }

    private SchematicItem parseSchematicItem(JsonObject obj) {
        String id = getStr(obj, "id", "");
        String title = getStr(obj, "title", "Untitled");
        String desc = getStr(obj, "description", "");
        String subtitle = desc.length() > 60 ? desc.substring(0, 60) + "..." : desc;
        return new SchematicItem(id, title, subtitle);
    }

    private long lastSearchTime = 0;
    private String pendingSearch = null;

    private void onSearchTextChanged(String text) {
        if (text.length() < 2) {
            searchResults.clear();
            rebuildList();
            return;
        }
        pendingSearch = text;
        lastSearchTime = System.currentTimeMillis();
    }

    @Override
    public void tick() {
        super.tick();
        if (pendingSearch != null && System.currentTimeMillis() - lastSearchTime > 500) {
            String query = pendingSearch;
            pendingSearch = null;
            executeSearch(query);
        }
    }

    private void executeSearch(String query) {
        searchLoading = true;
        searchError = null;
        statusText = "Searching...";

        SchematiCraftAPIWrapper.get().search(query).thenAccept(json -> {
            Minecraft.getInstance().execute(() -> {
                searchLoading = false;
                parseSearchJson(json);
                rebuildList();
            });
        }).exceptionally(ex -> {
            Minecraft.getInstance().execute(() -> {
                searchLoading = false;
                searchError = "Search failed: " + getRootMessage(ex);
                statusText = searchError;
                rebuildList();
            });
            return null;
        });
    }

    private void parseSearchJson(String json) {
        searchResults.clear();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray results = root.getAsJsonArray("results");
            boolean hasMore = root.has("hasMore") && root.get("hasMore").getAsBoolean();

            if (results != null) {
                for (JsonElement re : results) {
                    JsonObject r = re.getAsJsonObject();
                    String id = getStr(r, "id", "");
                    String title = getStr(r, "title", "Untitled");
                    String owner = getStr(r, "ownerName", "");
                    int downloads = r.has("downloadCount") ? r.get("downloadCount").getAsInt() : 0;
                    String subtitle = owner;
                    if (downloads > 0) {
                        subtitle += (subtitle.isEmpty() ? "" : " | ") + downloads + " downloads";
                    }
                    searchResults.add(new SchematicItem(id, title, subtitle));
                }
            }

            statusText = searchResults.size() + " results" + (hasMore ? " (showing first 20)" : "");
        } catch (Exception e) {
            searchError = "Failed to parse results";
            statusText = searchError;
        }
    }

    private void rebuildList() {
        if (listWidget == null) return;
        listWidget.clearEntries();

        if (activeTab == Tab.LIBRARY) {
            if (libraryLoading) {
                listWidget.addEntry(new SchematicListWidget.MessageEntry(listWidget, "Loading..."));
                return;
            }
            if (libraryError != null) {
                listWidget.addEntry(new SchematicListWidget.MessageEntry(listWidget, libraryError));
                return;
            }
            if (libraryEntries.isEmpty()) {
                listWidget.addEntry(new SchematicListWidget.MessageEntry(listWidget, "Your library is empty"));
                return;
            }
            for (Object entry : libraryEntries) {
                if (entry instanceof String header) {
                    listWidget.addEntry(new SchematicListWidget.HeaderEntry(listWidget, header));
                } else if (entry instanceof SchematicItem item) {
                    listWidget.addEntry(new SchematicListWidget.SchematicEntry(
                            listWidget, item.id, item.title, item.subtitle));
                }
            }
        } else {
            if (searchLoading) {
                listWidget.addEntry(new SchematicListWidget.MessageEntry(listWidget, "Searching..."));
                return;
            }
            if (searchError != null) {
                listWidget.addEntry(new SchematicListWidget.MessageEntry(listWidget, searchError));
                return;
            }
            if (searchResults.isEmpty()) {
                String hint = searchField.getValue().length() < 2 ? "Type to search..." : "No results found";
                listWidget.addEntry(new SchematicListWidget.MessageEntry(listWidget, hint));
                return;
            }
            for (SchematicItem item : searchResults) {
                listWidget.addEntry(new SchematicListWidget.SchematicEntry(
                        listWidget, item.id, item.title, item.subtitle));
            }
        }
    }

    private void onSchematicSelected(String schematicId, String title) {
        statusText = "Downloading " + title + "...";

        SchematiCraftAPIWrapper.get().downloadSchematic(schematicId).thenAccept(result -> {
            Minecraft.getInstance().execute(() -> {
                try {
                    byte[] data = Files.readAllBytes(result.file);
                    Files.deleteIfExists(result.file);

                    boolean bg2Available = BG2Integration.isBG2Loaded()
                            && this.minecraft.player != null
                            && BG2Integration.isHoldingCopyPasteGadget(this.minecraft.player);

                    boolean success = com.schematicraft.bg2addon.integration.BG2GadgetHelper
                            .loadTemplateIntoGadget(this.minecraft.player, data);

                    if (bg2Available && success) {
                        statusText = "\u00a7aLoaded: " + title + ". Ready to paste!";
                        this.onClose();
                    } else if (success) {
                        statusText = "\u00a7aSent: " + title;
                    } else {
                        statusText = "\u00a7cFailed to load template";
                    }

                    if (result.downloadId != null) {
                        SchematiCraftAPIWrapper.get().submitSuccessFeedback(result.downloadId);
                    }
                } catch (Exception e) {
                    statusText = "\u00a7cFailed: " + e.getMessage();
                }
            });
        }).exceptionally(ex -> {
            Minecraft.getInstance().execute(() -> {
                statusText = "\u00a7c" + getRootMessage(ex);
            });
            return null;
        });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        if (!statusText.isEmpty()) {
            graphics.drawCenteredString(this.font, statusText, this.width / 2, this.height - 18, 0xCCCCCC);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String getStr(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return fallback;
    }

    private static String getRootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) cause = cause.getCause();
        String msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
    }
}
