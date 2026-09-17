package dev.sfmgraph.client.ui;

import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Small drawing primitives Minecraft does not provide: rasterised lines, bezier curves, outlines and
 * tinted tooltip plates.
 *
 * <p>Everything here works in the current pose, so callers can draw inside a pan/zoom transform.
 */
public final class Draw {

    /** Number of straight segments used to approximate a bezier; enough to look smooth when zoomed. */
    private static final int CURVE_SEGMENTS = 28;

    private Draw() {
    }

    public static void outline(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y + 1, x + 1, y + h - 1, color);
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static void panel(GuiGraphics graphics, int x, int y, int w, int h, int background, int border) {
        graphics.fill(x, y, x + w, y + h, background);
        if (border != 0) outline(graphics, x, y, w, h, border);
    }

    public static void panelWithShadow(
            GuiGraphics graphics,
            int x,
            int y,
            int w,
            int h,
            int background,
            int border
    ) {
        graphics.fill(x + 1, y + 2, x + w + 1, y + h + 2, Theme.PANEL_SHADOW);
        panel(graphics, x, y, w, h, background, border);
    }

    /** A single straight line of the given thickness, rasterised with square steps. */
    public static void line(GuiGraphics graphics, double x1, double y1, double x2, double y2, int color, int thickness) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps <= 0) {
            int x = (int) Math.round(x1);
            int y = (int) Math.round(y1);
            graphics.fill(x, y, x + thickness, y + thickness, color);
            return;
        }
        // Cap the step count: zoomed out, a one pixel stride is plenty.
        int stride = Math.max(1, steps / 160);
        for (int i = 0; i <= steps; i += stride) {
            double t = i / (double) steps;
            int x = (int) Math.round(x1 + dx * t);
            int y = (int) Math.round(y1 + dy * t);
            graphics.fill(x, y, x + thickness, y + thickness, color);
        }
    }

    /** Cubic bezier control points for a link, bowing horizontally like a wiring diagram. */
    public static double[] controlPoints(double x1, double y1, double x2, double y2) {
        return controlPoints(x1, y1, x2, y2, defaultBow(x1, x2));
    }

    /** The bow a link gets when nothing overrides it. */
    public static double defaultBow(double x1, double x2) {
        double dx = Math.abs(x2 - x1);
        return Math.max(36.0, Math.min(160.0, dx * 0.55));
    }

    /**
     * Control points with an explicit bow.
     *
     * <p>Parallel links between the same nodes get different bows, so they separate along their whole
     * length instead of only at the endpoints. Drawing and hit testing share this, so a click lands on
     * the curve that is actually drawn.
     */
    public static double[] controlPoints(double x1, double y1, double x2, double y2, double bow) {
        return new double[]{x1 + bow, y1, x2 - bow, y2};
    }

    /** Samples a link curve into a flat {@code [x0,y0,x1,y1,...]} array. */
    public static double[] curvePoints(double x1, double y1, double x2, double y2, int segments) {
        return curvePoints(x1, y1, x2, y2, segments, defaultBow(x1, x2));
    }

    public static double[] curvePoints(double x1, double y1, double x2, double y2, int segments, double bow) {
        double[] control = controlPoints(x1, y1, x2, y2, bow);
        double[] points = new double[(segments + 1) * 2];
        for (int i = 0; i <= segments; i++) {
            double t = i / (double) segments;
            double mt = 1 - t;
            double a = mt * mt * mt;
            double b = 3 * mt * mt * t;
            double c = 3 * mt * t * t;
            double d = t * t * t;
            points[i * 2] = a * x1 + b * control[0] + c * control[2] + d * x2;
            points[i * 2 + 1] = a * y1 + b * control[1] + c * control[3] + d * y2;
        }
        return points;
    }

    public static void curve(GuiGraphics graphics, double x1, double y1, double x2, double y2, int color, int thickness) {
        curve(graphics, x1, y1, x2, y2, color, thickness, defaultBow(x1, x2));
    }

    public static void curve(
            GuiGraphics graphics,
            double x1,
            double y1,
            double x2,
            double y2,
            int color,
            int thickness,
            double bow
    ) {
        double[] points = curvePoints(x1, y1, x2, y2, CURVE_SEGMENTS, bow);
        for (int i = 0; i + 3 < points.length; i += 2) {
            line(graphics, points[i], points[i + 1], points[i + 2], points[i + 3], color, thickness);
        }
    }

    /** Midpoint of a curve, for placing the rate plate. */
    public static double[] curveMidpoint(double x1, double y1, double x2, double y2, double bow) {
        double[] control = controlPoints(x1, y1, x2, y2, bow);
        return new double[]{
                0.125 * x1 + 0.375 * control[0] + 0.375 * control[2] + 0.125 * x2,
                0.125 * y1 + 0.375 * control[1] + 0.375 * control[3] + 0.125 * y2
        };
    }

    /** Shortest distance from a point to a sampled curve, for click testing. */
    public static double distanceToCurve(double px, double py, double[] points) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 3 < points.length; i += 2) {
            best = Math.min(best, distanceToSegment(px, py, points[i], points[i + 1], points[i + 2], points[i + 3]));
        }
        return best;
    }

    private static double distanceToSegment(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 1e-6) return Math.hypot(px - x1, py - y1);
        double t = ((px - x1) * dx + (py - y1) * dy) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    public static void circle(GuiGraphics graphics, double cx, double cy, double radius, int color) {
        int r = (int) Math.ceil(radius);
        for (int dy = -r; dy <= r; dy++) {
            int span = (int) Math.floor(Math.sqrt(Math.max(0, radius * radius - dy * dy)));
            if (span <= 0) continue;
            graphics.fill((int) Math.round(cx) - span, (int) Math.round(cy) + dy,
                          (int) Math.round(cx) + span, (int) Math.round(cy) + dy + 1, color);
        }
    }

    public static void ring(GuiGraphics graphics, double cx, double cy, double radius, int color, int thickness) {
        for (int i = 0; i < thickness; i++) {
            circleOutline(graphics, cx, cy, radius - i, color);
        }
    }

    private static void circleOutline(GuiGraphics graphics, double cx, double cy, double radius, int color) {
        int r = (int) Math.ceil(radius);
        for (int dy = -r; dy <= r; dy++) {
            int span = (int) Math.floor(Math.sqrt(Math.max(0, radius * radius - dy * dy)));
            int x = (int) Math.round(cx);
            int y = (int) Math.round(cy) + dy;
            graphics.fill(x - span, y, x - span + 1, y + 1, color);
            graphics.fill(x + span, y, x + span + 1, y + 1, color);
        }
    }

    /** Draws text with a subtle drop shadow, matching vanilla tooltips. */
    public static void text(GuiGraphics graphics, net.minecraft.client.gui.Font font, String text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color, false);
    }

    public static void centeredText(
            GuiGraphics graphics,
            net.minecraft.client.gui.Font font,
            String text,
            int centerX,
            int y,
            int color
    ) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    /** Truncates text with an ellipsis so it fits in {@code maxWidth}, useful in the narrow panel. */
    public static String ellipsize(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "…";
        StringBuilder builder = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (font.width(builder.toString() + c + ellipsis) > maxWidth) break;
            builder.append(c);
        }
        return builder + ellipsis;
    }

    /** Draws a tooltip plate at the cursor, wrapping long lines. */
    public static void tooltip(GuiGraphics graphics, net.minecraft.client.gui.Font font, List<String> lines, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (lines.isEmpty()) return;
        int width = 0;
        for (String line : lines) width = Math.max(width, font.width(line));
        int height = lines.size() * (font.lineHeight + 1) - 1;

        int x = mouseX + 10;
        int y = mouseY - 2;
        if (x + width + 6 > screenWidth) x = mouseX - width - 10;
        if (y + height + 4 > screenHeight) y = screenHeight - height - 6;
        if (y < 2) y = 2;

        // Pushed forward in z, exactly like vanilla tooltips. Item icons are drawn with depth
        // writes, so text and fills emitted at the same z afterwards can end up hidden behind them.
        graphics.pose().pushPose();
        graphics.pose().translate(0.0f, 0.0f, 400.0f);

        graphics.fill(x - 3, y - 3, x + width + 3, y + height + 3, 0xF0101014);
        outline(graphics, x - 4, y - 4, width + 8, height + 8, 0xFF40454F);
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i), x, y + i * (font.lineHeight + 1), Theme.TEXT, false);
        }

        graphics.pose().popPose();
    }
}
