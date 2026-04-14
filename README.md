# Schematicraft

Access your [schematicraft.com](https://schematicraft.com) cloud schematic library from inside Minecraft. Browse, search, download, and upload schematics through the editors you already use.

## Supported Editors

- **Building Gadgets 2** - Full integration in both the Copy/Paste gadget radial menu (requires server-side mod) and the Template Manager (client-only, works on any server). Library, clipboard, upload, and camera mode in both.
- **Create** - Purely client-side. Side panel in the Schematic Table for downloading and uploading. No server-side mod required.

Both are optional. The mod detects which editors are installed and activates the appropriate integration.

## The Palette

Both editors share the same palette interface, designed for rapid schematic access:

- Always-on filter field that narrows your library as you type
- Pin up to 7 bundles as quick-access tabs (tab 8 is Home)
- Ctrl+1 through Ctrl+8 switches tabs, arrow keys navigate, Enter loads
- Local file cache so repeat downloads are instant
- Right-click a bundle header to pin it, Ctrl+click a tab to clear it

## Setup

1. Install the mod
2. Press J in-game or open a Template Manager / Schematic Table
3. Click "Set API Key" and paste your key from [schematicraft.com/account](https://schematicraft.com/account)
4. Your library loads automatically

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.200+
- A free [schematicraft.com](https://schematicraft.com) account and API key
- At least one supported editor mod

## Compatibility

Client-side mod. Works on any server without server-side installation. When the server also has the mod installed, Building Gadgets 2 gets additional features (direct gadget loading).

## Building

```
gradlew build
```

Requires Java 21. The build pulls Building Gadgets 2 and Create from CurseMaven as optional dependencies.

## License

LGPL-3.0
