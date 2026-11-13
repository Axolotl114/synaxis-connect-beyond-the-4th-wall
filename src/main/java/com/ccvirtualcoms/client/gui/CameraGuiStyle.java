package com.ccvirtualcoms.client.gui;

import com.ccvirtualcoms.CCVirtualComs;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Geometry, palette and drawing helpers for the Synaxis-styled camera GUI.
 *
 * <p>The numbers mirror Synaxis' own GUI defaults (extracted from
 * {@code com.verr1.synaxis.foundation.ui.ldlib.LdLibLayoutDefaults}): a 242 px wide window,
 * a 30 px tab bar, a 210 px background and a content area of 196x178 starting at (22, 4) inside
 * the background. Rows are 13 px high with a 4 px gap; the left 96 px of a row is the label and the
 * right 96 px is the control. The five PNGs reused here are Synaxis assets reused with the
 * author's permission; see LICENSE and README credits.
 *
 * <p>Kept deliberately free of any state: everything is static so screens and widgets share one
 * definition instead of scattering pixel literals.
 */
public final class CameraGuiStyle {

    // ------------------------------------------------------------------ layout

    /** Window width in GUI pixels. */
    public static final int PANEL_WIDTH = 242;

    /** Height of the tab bar at the top of the window. */
    public static final int TAB_BAR_HEIGHT = 30;

    /** Height of the background panel below the tab bar. */
    public static final int BACKGROUND_HEIGHT = 210;

    /** Total window height (tab bar + background). */
    public static final int PANEL_HEIGHT = TAB_BAR_HEIGHT + BACKGROUND_HEIGHT;

    /** Left edge of the content area, relative to the window. */
    public static final int CONTENT_X = 22;

    /** Top edge of the content area, relative to the window. */
    public static final int CONTENT_Y = TAB_BAR_HEIGHT + 4;

    /** Content area width. */
    public static final int CONTENT_WIDTH = 196;

    /** Content area height. */
    public static final int CONTENT_HEIGHT = 178;

    /** Height of one row. */
    public static final int ROW_HEIGHT = 13;

    /** Vertical gap between rows. */
    public static final int ROW_GAP = 4;

    /** Vertical distance from the top of one row to the next. */
    public static final int ROW_PITCH = ROW_HEIGHT + ROW_GAP;

    /** Rows that fit into the content area without scrolling. */
    public static final int ROWS_PER_PAGE = CONTENT_HEIGHT / ROW_PITCH;

    /** Width of the label column. */
    public static final int LABEL_WIDTH = 96;

    /** Left edge of the control column, relative to the window. */
    public static final int CONTROL_X = CONTENT_X + LABEL_WIDTH + 4;

    /** Width of the control column. */
    public static final int CONTROL_WIDTH = 96;

    /** Standard input height (Synaxis keeps rows 13 high and inputs 10 high). */
    public static final int INPUT_HEIGHT = 10;

    /** Tab button size. */
    public static final int TAB_SIZE = 18;

    /** X of the first tab button. */
    public static final int TAB_X = 38;

    /** Y of the tab buttons inside the tab bar. */
    public static final int TAB_Y = 4;

    /** Horizontal step between tab buttons. */
    public static final int TAB_STEP = 22;

    /** Y of the status / done strip at the bottom of the content area. */
    public static final int BOTTOM_STRIP_Y = TAB_BAR_HEIGHT + BACKGROUND_HEIGHT - 28;

    /** X of the status / done strip, relative to the window. */
    public static final int BOTTOM_STRIP_X = 16;

    /** Width of the status / done strip. */
    public static final int BOTTOM_STRIP_WIDTH = PANEL_WIDTH - 2 * BOTTOM_STRIP_X;

    /** Height of the status / done strip (kept above the decorative bottom border). */
    public static final int BOTTOM_STRIP_HEIGHT = 14;

    /** X of the summary hint bubble above the bottom border. */
    public static final int SUMMARY_X = 16;

    /** Y of the summary hint bubble above the bottom border. */
    public static final int SUMMARY_Y = TAB_BAR_HEIGHT + BACKGROUND_HEIGHT - 32;

    /** Width of the summary hint bubble. */
    public static final int SUMMARY_WIDTH = 148;

    /** Height of the summary hint bubble. */
    public static final int SUMMARY_HEIGHT = 18;

    /** Height of small action buttons in the bottom strip. */
    public static final int ACTION_BUTTON_HEIGHT = 14;

    /** Width of the Done button in the bottom strip. */
    public static final int ACTION_BUTTON_WIDTH = 58;

    // ------------------------------------------------------------------ palette

    /** Row labels (white, bold by convention). */
    public static final int COLOR_LABEL = 0xFFFFFFFF;

    /** Text drawn on light input / selector backgrounds (Synaxis VALUE_COLOR). */
    public static final int COLOR_VALUE = 0xFF3D4966;

    /** Read-only status values (Synaxis STATUS_VALUE_COLOR). */
    public static final int COLOR_STATUS_VALUE = 0xFFFFE8A3;

    /** Secondary text (Synaxis MUTED_COLOR). */
    public static final int COLOR_MUTED = 0xFF7D859A;

    /** Input background fill (Synaxis inputBackground SDF colour). */
    public static final int COLOR_INPUT_FILL = 0xFFE9EDF3;

    /** Input border (Synaxis inputBackground SDF border). */
    public static final int COLOR_INPUT_BORDER = 0xFF6D7691;

    /** Selector background fill (Synaxis selectorBackground SDF colour). */
    public static final int COLOR_SELECTOR_FILL = 0xFFDDE4EE;

    /** Selector border (Synaxis selectorBackground SDF border). */
    public static final int COLOR_SELECTOR_BORDER = 0xFF66718D;

    /** Focus highlight border (Synaxis focus overlay border). */
    public static final int COLOR_FOCUS_BORDER = 0xFFB49B55;

    /** Bottom display strip fill. */
    public static final int COLOR_DISPLAY_FILL = 0x44FFFFFF;

    /** Bottom display strip border. */
    public static final int COLOR_DISPLAY_BORDER = 0x886D7691;

    /** Zebra striping for rows. */
    public static final int COLOR_ROW_EVEN = 0x10FFFFFF;

    /** Zebra striping for rows. */
    public static final int COLOR_ROW_ODD = 0x06FFFFFF;

    /** Empty item slot fill. */
    public static final int COLOR_SLOT_FILL = 0xFF1D1D1D;

    /** Empty item slot border. */
    public static final int COLOR_SLOT_BORDER = 0xFF5A5A5A;

    /** Item slot border while hovered. */
    public static final int COLOR_SLOT_HOVER = 0xFFB49B55;

    /** Row hover highlight. */
    public static final int COLOR_ROW_HOVER = 0x18FFFFFF;

    /** Plain panel fill, used behind the 9-sliced background centre. */
    public static final int COLOR_PANEL = 0xFF333333;

    // ------------------------------------------------------------------ textures

    private static final ResourceLocation TEXTURE_BACKGROUND =
            resource("textures/gui/simple_background_large.png");
    private static final ResourceLocation TEXTURE_TAB_BAR =
            resource("textures/gui/tab_bar.png");
    private static final ResourceLocation TEXTURE_ONE_LINE =
            resource("textures/gui/simple_background_one_line.png");
    private static final ResourceLocation TEXTURE_ICONS =
            resource("textures/gui/icons10x10.png");

    /** A rectangular region of a 256x256 atlas. */
    public record Sprite(int u, int v, int w, int h) {
    }

    /** Background sprite of {@code simple_background_large.png} used by Synaxis. */
    public static final Sprite BACKGROUND_LARGE = new Sprite(0, 0, 256, 133);

    /**
     * Tab bar sprite of {@code tab_bar.png}. Starts at row 2: the source rows 0-1 only contain the
     * two end caps (the middle is transparent), so slicing from row 2 keeps the bar solid.
     */
    public static final Sprite TAB_BAR = new Sprite(0, 2, 166, 28);

    /** Single-line hint bubble sprite of {@code simple_background_one_line.png}. */
    public static final Sprite ONE_LINE = new Sprite(0, 0, 160, 29);

    /** Tool icon (idle). */
    public static final Sprite ICON_TOOL = new Sprite(42, 2, 18, 18);

    /** Tool icon (selected / pressed). */
    public static final Sprite ICON_TOOL_DOWN = new Sprite(42, 24, 18, 18);

    /** Refresh icon. */
    public static final Sprite ICON_REFRESH = new Sprite(22, 2, 18, 18);

    /** Check / accept icon. */
    public static final Sprite ICON_YES = new Sprite(2, 2, 18, 18);

    /** 9x13 "on" toggle sprite (Synaxis SMALL_BUTTON_GREEN). */
    public static final Sprite TOGGLE_ON = new Sprite(2, 50, 9, 13);

    /** 9x13 "off" toggle sprite (Synaxis SMALL_BUTTON_RED). */
    public static final Sprite TOGGLE_OFF = new Sprite(2, 64, 9, 13);

    /** 9x13 selection sprite. */
    public static final Sprite SMALL_SELECTION = new Sprite(12, 50, 9, 13);

    /** 9x13 cross sprite. */
    public static final Sprite SMALL_NO = new Sprite(22, 50, 9, 13);

    /** 9x13 box sprite. */
    public static final Sprite SMALL_YES = new Sprite(32, 50, 9, 13);

    /** Nine-slice borders of the large background (see the camera_gui research note). */
    private static final int BG_LEFT = 24;
    private static final int BG_RIGHT = 24;
    private static final int BG_TOP = 3;
    private static final int BG_BOTTOM = 14;

    private CameraGuiStyle() {
    }

    /** Builds a texture location inside this mod's namespace. */
    public static ResourceLocation resource(String path) {
        return ResourceLocation.fromNamespaceAndPath(CCVirtualComs.MOD_ID, path);
    }

    // ------------------------------------------------------------------ window chrome

    /** Draws the tab bar and the 9-sliced background panel. */
    public static void drawWindow(GuiGraphics graphics, int left, int top) {
        nineSlice(graphics, TEXTURE_TAB_BAR, left, top, PANEL_WIDTH, TAB_BAR_HEIGHT,
                TAB_BAR, 23, 23, 1, 6);
        nineSlice(graphics, TEXTURE_BACKGROUND, left, top + TAB_BAR_HEIGHT,
                PANEL_WIDTH, BACKGROUND_HEIGHT, BACKGROUND_LARGE,
                BG_LEFT, BG_RIGHT, BG_TOP, BG_BOTTOM);
    }

    /**
     * Draws a sprite of a 256x256 atlas stretched into an arbitrary destination rectangle.
     *
     * <p>The stretch keeps each 9-slice piece at its native size and only stretches the middle
     * piece, so the border pixels stay crisp.
     */
    public static void nineSlice(GuiGraphics graphics, ResourceLocation texture,
                                 int x, int y, int width, int height, Sprite sprite,
                                 int borderLeft, int borderRight, int borderTop, int borderBottom) {
        int sourceWidth = sprite.w();
        int sourceHeight = sprite.h();
        int left = Math.min(borderLeft, sourceWidth);
        int right = Math.min(borderRight, sourceWidth - left);
        int topBorder = Math.min(borderTop, sourceHeight);
        int bottom = Math.min(borderBottom, sourceHeight - topBorder);
        int middleSourceWidth = sourceWidth - left - right;
        int middleSourceHeight = sourceHeight - topBorder - bottom;
        int middleWidth = width - left - right;
        int middleHeight = height - topBorder - bottom;

        // Top row.
        stretch(graphics, texture, x, y, left, topBorder,
                sprite.u(), sprite.v(), left, topBorder);
        stretch(graphics, texture, x + left, y, middleWidth, topBorder,
                sprite.u() + left, sprite.v(), middleSourceWidth, topBorder);
        stretch(graphics, texture, x + width - right, y, right, topBorder,
                sprite.u() + sourceWidth - right, sprite.v(), right, topBorder);
        // Middle row.
        stretch(graphics, texture, x, y + topBorder, left, middleHeight,
                sprite.u(), sprite.v() + topBorder, left, middleSourceHeight);
        stretch(graphics, texture, x + left, y + topBorder, middleWidth, middleHeight,
                sprite.u() + left, sprite.v() + topBorder, middleSourceWidth, middleSourceHeight);
        stretch(graphics, texture, x + width - right, y + topBorder, right, middleHeight,
                sprite.u() + sourceWidth - right, sprite.v() + topBorder,
                right, middleSourceHeight);
        // Bottom row.
        stretch(graphics, texture, x, y + height - bottom, left, bottom,
                sprite.u(), sprite.v() + sourceHeight - bottom, left, bottom);
        stretch(graphics, texture, x + left, y + height - bottom, middleWidth, bottom,
                sprite.u() + left, sprite.v() + sourceHeight - bottom, middleSourceWidth, bottom);
        stretch(graphics, texture, x + width - right, y + height - bottom, right, bottom,
                sprite.u() + sourceWidth - right, sprite.v() + sourceHeight - bottom, right, bottom);
    }

    private static void stretch(GuiGraphics graphics, ResourceLocation texture,
                                int x, int y, int width, int height,
                                int u, int v, int sourceWidth, int sourceHeight) {
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }
        graphics.blit(texture, x, y, width, height, (float) u, (float) v,
                sourceWidth, sourceHeight, 256, 256);
    }

    // ------------------------------------------------------------------ controls

    /** Light rounded input background, matching Synaxis' SDF input texture. */
    public static void inputBackground(GuiGraphics graphics, int x, int y, int width, int height) {
        roundedRect(graphics, x, y, width, height, COLOR_INPUT_FILL, COLOR_INPUT_BORDER);
    }

    /** Light speech-bubble hint strip built from {@code simple_background_one_line.png}. */
    public static void hintBubble(GuiGraphics graphics, int x, int y, int width, int height) {
        nineSlice(graphics, TEXTURE_ONE_LINE, x, y, width, height, ONE_LINE, 3, 3, 3, 10);
    }

    /** Light rounded selector background, matching Synaxis' SDF selector texture. */
    public static void selectorBackground(GuiGraphics graphics, int x, int y, int width, int height) {
        roundedRect(graphics, x, y, width, height, COLOR_SELECTOR_FILL, COLOR_SELECTOR_BORDER);
    }

    /** Slightly translucent display background, matching Synaxis' display texture. */
    public static void displayBackground(GuiGraphics graphics, int x, int y, int width, int height) {
        roundedRect(graphics, x, y, width, height, COLOR_DISPLAY_FILL, COLOR_DISPLAY_BORDER);
    }

    /** Draws a rounded 2 px corner rectangle with a 1 px border. */
    public static void roundedRect(GuiGraphics graphics, int x, int y, int width, int height,
                                   int fill, int border) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (width >= 4 && height >= 4) {
            // Fill: three nested bands carve out the 2 px corners.
            graphics.fill(x + 2, y, x + width - 2, y + height, fill);
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
            graphics.fill(x, y + 2, x + width, y + height - 2, fill);
            // Border.
            graphics.fill(x + 2, y, x + width - 2, y + 1, border);
            graphics.fill(x + 2, y + height - 1, x + width - 2, y + height, border);
            graphics.fill(x, y + 2, x + 1, y + height - 2, border);
            graphics.fill(x + width - 1, y + 2, x + width, y + height - 2, border);
            graphics.fill(x + 1, y + 1, x + 2, y + 2, border);
            graphics.fill(x + width - 2, y + 1, x + width - 1, y + 2, border);
            graphics.fill(x + 1, y + height - 2, x + 2, y + height - 1, border);
            graphics.fill(x + width - 2, y + height - 2, x + width - 1, y + height - 1, border);
        } else {
            graphics.fill(x, y, x + width, y + height, fill);
            graphics.fill(x, y, x + width, y + 1, border);
            graphics.fill(x, y + height - 1, x + width, y + height, border);
            graphics.fill(x, y, x + 1, y + height, border);
            graphics.fill(x + width - 1, y, x + width, y + height, border);
        }
    }

    /** Draws a 1 px focus border around a control. */
    public static void focusBorder(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + 1, COLOR_FOCUS_BORDER);
        graphics.fill(x, y + height - 1, x + width, y + height, COLOR_FOCUS_BORDER);
        graphics.fill(x, y, x + 1, y + height, COLOR_FOCUS_BORDER);
        graphics.fill(x + width - 1, y, x + width, y + height, COLOR_FOCUS_BORDER);
    }

    /** Draws an empty item slot with an optional highlight. */
    public static void itemSlot(GuiGraphics graphics, int x, int y, int size, boolean highlighted) {
        graphics.fill(x, y, x + size, y + size, COLOR_SLOT_FILL);
        int border = highlighted ? COLOR_SLOT_HOVER : COLOR_SLOT_BORDER;
        graphics.fill(x, y, x + size, y + 1, border);
        graphics.fill(x, y + size - 1, x + size, y + size, border);
        graphics.fill(x, y, x + 1, y + size, border);
        graphics.fill(x + size - 1, y, x + size, y + size, border);
    }

    /** Draws an icon from the shared 10x10 atlas at native size. */
    public static void icon(GuiGraphics graphics, Sprite sprite, int x, int y) {
        graphics.blit(TEXTURE_ICONS, x, y, sprite.u(), sprite.v(), sprite.w(), sprite.h());
    }

    // ------------------------------------------------------------------ text

    /** Draws a bold white row label. */
    public static void label(GuiGraphics graphics, Font font, Component text, int x, int y) {
        graphics.drawString(font, text, x, y, COLOR_LABEL, false);
    }

    /** Draws dark text on a light control background. */
    public static void controlText(GuiGraphics graphics, Font font, Component text, int x, int y) {
        graphics.drawString(font, text, x, y, COLOR_VALUE, false);
    }

    /** Draws a muted hint. */
    public static void muted(GuiGraphics graphics, Font font, Component text, int x, int y) {
        graphics.drawString(font, text, x, y, COLOR_MUTED, false);
    }

    /** Draws a read-only status value. */
    public static void statusText(GuiGraphics graphics, Font font, Component text, int x, int y) {
        graphics.drawString(font, text, x, y, COLOR_STATUS_VALUE, false);
    }

    /** Truncates {@code text} until it fits into {@code maxWidth} pixels, adding an ellipsis. */
    public static String trim(Font font, String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int budget = Math.max(0, maxWidth - font.width(ellipsis));
        return font.plainSubstrByWidth(text, budget) + ellipsis;
    }

    /** The vertical offset that centres a 9 px high font line in a 13 px row. */
    public static int rowTextOffset() {
        return 2;
    }
}
