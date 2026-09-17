package dev.sfmgraph.client.ui;

import dev.sfmgraph.graph.GraphModel;
import dev.sfmgraph.graph.GraphNode;

/**
 * Pan and zoom state for the node canvas, plus the coordinate conversions between screen space and
 * graph space.
 *
 * <p>The viewport (the rectangle the canvas occupies on screen) is stored here so callers only ever
 * deal with one pair of coordinates.
 */
public final class CanvasView {

    public static final double MIN_ZOOM = 0.35;
    public static final double MAX_ZOOM = 2.5;

    private double panX = 40;
    private double panY = 30;
    private double zoom = 1.0;

    private int viewportX;
    private int viewportY;
    private int viewportWidth;
    private int viewportHeight;

    public void setViewport(int x, int y, int width, int height) {
        this.viewportX = x;
        this.viewportY = y;
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    public int viewportX() {
        return viewportX;
    }

    public int viewportY() {
        return viewportY;
    }

    public int viewportWidth() {
        return viewportWidth;
    }

    public int viewportHeight() {
        return viewportHeight;
    }

    public double zoom() {
        return zoom;
    }

    public double panX() {
        return panX;
    }

    public double panY() {
        return panY;
    }

    public boolean contains(double screenX, double screenY) {
        return screenX >= viewportX && screenX < viewportX + viewportWidth
               && screenY >= viewportY && screenY < viewportY + viewportHeight;
    }

    public double toGraphX(double screenX) {
        return (screenX - viewportX - panX) / zoom;
    }

    public double toGraphY(double screenY) {
        return (screenY - viewportY - panY) / zoom;
    }

    public int toScreenX(double graphX) {
        return (int) Math.round(viewportX + panX + graphX * zoom);
    }

    public int toScreenY(double graphY) {
        return (int) Math.round(viewportY + panY + graphY * zoom);
    }

    /**
     * The one and only place the pan/zoom transform is applied to a matrix.
     *
     * <p>The invariant, which {@link #toGraphX} and {@link #toScreenX} also encode, is
     * {@code screen = viewport + pan + graph * zoom}. Pan is therefore measured in screen pixels and
     * has to be translated <em>before</em> the scale: translating after the scale multiplies the pan
     * by the zoom, and the canvas then renders in one place while clicks land in another.
     * {@code CanvasViewTest} checks this against the coordinate conversions.
     */
    public void applyTo(org.joml.Matrix4f matrix) {
        matrix.translate((float) (viewportX + panX), (float) (viewportY + panY), 0.0f);
        matrix.scale((float) zoom, (float) zoom, 1.0f);
    }

    /** Applies the transform to the graphics pose, so drawing happens in graph coordinates. */
    public void applyPose(net.minecraft.client.gui.GuiGraphics graphics) {
        applyTo(graphics.pose().last().pose());
    }

    public void panBy(double dx, double dy) {
        panX += dx;
        panY += dy;
    }

    /** Zooms while keeping the graph point under the cursor pinned to the cursor. */
    public void zoomAt(double screenX, double screenY, double factor) {
        double graphX = toGraphX(screenX);
        double graphY = toGraphY(screenY);
        double target = clampZoom(zoom * factor);
        if (target == zoom) return;
        zoom = target;
        panX = screenX - viewportX - graphX * zoom;
        panY = screenY - viewportY - graphY * zoom;
    }

    public void setZoom(double value) {
        zoom = clampZoom(value);
    }

    /** Centres a graph point in the viewport. */
    public void centerOn(double graphX, double graphY) {
        panX = viewportWidth / 2.0 - graphX * zoom;
        panY = viewportHeight / 2.0 - graphY * zoom;
    }

    public void reset() {
        zoom = 1.0;
        panX = 40;
        panY = 30;
    }

    /** Fits every node into the viewport with a margin. */
    public void fitTo(GraphModel model) {
        if (model.nodes.isEmpty()) {
            reset();
            return;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (GraphNode node : model.nodes) {
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
            maxX = Math.max(maxX, node.x + Theme.NODE_WIDTH);
            maxY = Math.max(maxY, node.y + Theme.NODE_HEIGHT);
        }
        double contentWidth = Math.max(1, maxX - minX);
        double contentHeight = Math.max(1, maxY - minY);
        double margin = 40;
        double scaleX = (viewportWidth - margin * 2) / contentWidth;
        double scaleY = (viewportHeight - margin * 2) / contentHeight;
        zoom = clampZoom(Math.min(scaleX, scaleY));
        panX = (viewportWidth - contentWidth * zoom) / 2 - minX * zoom;
        panY = (viewportHeight - contentHeight * zoom) / 2 - minY * zoom;
    }

    private static double clampZoom(double value) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
    }
}
