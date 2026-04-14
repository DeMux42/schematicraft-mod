package com.schematicraft.lib.client.screen;

/**
 * Shared layout constants for all Schematicraft panels.
 * Tweak these numbers to adjust spacing across all three surfaces
 * (Create Schematic Table, BG2 Template Manager, BG2 Copy/Paste Radial).
 */
public final class PanelLayout {
    private PanelLayout() {}

    // ---- Panel sizing ----

    /** Width of the content area (list widgets, buttons, filter field). */
    public static final int PANEL_W = 180;

    /** Fixed height of the 3D preview area at the bottom of the left panel. */
    public static final int PREVIEW_H = 160;

    // ---- Screen edge margins ----

    /** Gap between the screen edge and the panel background on all four sides. */
    public static final int SCREEN_MARGIN = 6;

    // ---- Panel background padding (gap between background edge and content) ----

    /** Padding on the outer side (toward screen edge). */
    public static final int BG_PAD_OUTER = 3;

    /** Padding on the inner side (toward screen center). */
    public static final int BG_PAD_INNER = 6;

    // ---- Vertical positions ----

    /** Top Y where panel content starts (header buttons). */
    public static final int CONTENT_TOP = 10;

    /** Gap between header row and the next element (filter field). */
    public static final int HEADER_GAP = 20;

    /** Y position of the header underline. */
    public static final int HEADER_LINE_Y = 26;

    /** Y where content below the header+underline starts (list, upload form). */
    public static final int BELOW_HEADER_Y = 28;

    /** Space reserved at the bottom for the status bar text. */
    public static final int STATUS_BAR_H = 24;

    /** Y offset from screen bottom for status bar text. */
    public static final int STATUS_TEXT_Y_OFFSET = 17;

    // ---- Header button sizing ----

    /** Right margin reserved for the X (close/logout) button. */
    public static final int CLOSE_BTN_MARGIN = 18;

    /** Width of the X button. */
    public static final int CLOSE_BTN_W = 12;

    /** Inset of the X button from the right edge of the content area. */
    public static final int CLOSE_BTN_INSET = 14;

    // ---- Left header text inset ----

    /** Extra pixels to shift left panel header text from the left edge. */
    public static final int LEFT_HEADER_INSET = 2;

    // ---- Derived positions (computed from the above) ----

    /** X position of the left panel content area. */
    public static final int LEFT_X = SCREEN_MARGIN;

    /** Right X position of the left panel content area. */
    public static final int LEFT_CONTENT_RIGHT = LEFT_X + PANEL_W;

    /** Left edge of the left panel background. */
    public static final int LEFT_BG_LEFT = LEFT_X - BG_PAD_OUTER;

    /** Right edge of the left panel background. */
    public static final int LEFT_BG_RIGHT = LEFT_X + PANEL_W + BG_PAD_INNER;

    /** X position of the left panel background separator line. */
    public static final int LEFT_SEPARATOR_X = LEFT_BG_RIGHT;

    /** X position of the right panel content area (needs screen width). */
    public static int rightX(int screenWidth) {
        return screenWidth - PANEL_W - SCREEN_MARGIN;
    }

    /** Left edge of the right panel background. */
    public static int rightBgLeft(int screenWidth) {
        return rightX(screenWidth) - BG_PAD_INNER;
    }

    /** Right edge of the right panel background. */
    public static int rightBgRight(int screenWidth) {
        return screenWidth - LEFT_X + BG_PAD_OUTER;
    }

    /** X position of the right panel background separator line. */
    public static int rightSeparatorX(int screenWidth) {
        return rightBgLeft(screenWidth) - 1;
    }
}
