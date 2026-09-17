package dev.sfmgraph.client.ui;

import dev.sfmgraph.graph.ResourceKind;

/** Colours and metrics for the editor, kept in one place so the look stays consistent. */
public final class Theme {

    public static final int BACKDROP = 0xB0101014;
    public static final int CANVAS_BG = 0xFF16181D;
    public static final int GRID_MINOR = 0xFF1F232A;
    public static final int GRID_MAJOR = 0xFF272C35;

    public static final int PANEL = 0xFF1C1F26;
    public static final int PANEL_ALT = 0xFF22262E;
    public static final int PANEL_BORDER = 0xFF31363F;
    public static final int PANEL_SHADOW = 0x50000000;

    public static final int TEXT = 0xFFE4E7EB;
    public static final int TEXT_DIM = 0xFF949BA6;
    public static final int TEXT_DISABLED = 0xFF5E646E;
    public static final int TEXT_TITLE = 0xFFFFFFFF;

    public static final int ACCENT = 0xFF4C9AFF;
    public static final int ACCENT_SOFT = 0x334C9AFF;
    public static final int ACCENT_BORDER = 0xFF6FB0FF;
    public static final int WARN = 0xFFFFC24B;
    public static final int ERROR = 0xFFFF6B6B;
    public static final int OK = 0xFF66D07A;

    public static final int NODE_BG = 0xFF23272F;
    public static final int NODE_BG_SELECTED = 0xFF2B3646;
    public static final int NODE_BORDER = 0xFF3A414E;
    public static final int NODE_BORDER_SEL = 0xFF6FB0FF;
    public static final int NODE_HEADER = 0xFF2C323C;
    public static final int NODE_MISSING = 0xFF3A2A2A;
    public static final int PORT = 0xFF8A93A0;

    public static final int BUTTON_BG = 0xFF272C36;
    public static final int BUTTON_BG_HOVER = 0xFF333A47;
    public static final int BUTTON_BG_ACTIVE = 0xFF35507A;
    public static final int BUTTON_BORDER = 0xFF3C434F;
    public static final int BUTTON_TEXT = 0xFFD8DCE3;

    public static final int EDIT_BG = 0xFF14161A;

    // Layout metrics, in GUI pixels.
    public static final int MENU_HEIGHT = 16;
    public static final int STATUS_HEIGHT = 13;
    public static final int INSPECTOR_WIDTH = 206;
    public static final int PREVIEW_HEIGHT = 92;

    public static final double NODE_WIDTH = 138;
    public static final double NODE_HEIGHT = 56;
    public static final double NODE_HEADER_HEIGHT = 15;
    public static final double PORT_RADIUS = 4.0;
    public static final double PORT_HIT = 9.0;

    public static final int ROW_HEIGHT = 17;
    public static final int CHIP_HEIGHT = 15;

    private Theme() {
    }

    /** Links are colour coded by what they carry. */
    public static int resourceColor(ResourceKind kind) {
        return switch (kind) {
            case ITEM -> 0xFFE0A24C;
            case FLUID -> 0xFF4FA3FF;
            case CHEMICAL -> 0xFFB472E8;
            case ENERGY -> 0xFFFFD24B;
        };
    }

    public static int resourceColorDim(ResourceKind kind) {
        return (resourceColor(kind) & 0x00FFFFFF) | 0x55000000;
    }

    /** Picks black or white text for the given background so labels stay readable. */
    public static int contrastText(int background) {
        int r = (background >> 16) & 0xFF;
        int g = (background >> 8) & 0xFF;
        int b = background & 0xFF;
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.6 ? 0xFF10131A : 0xFFFFFFFF;
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
