package dev.sfmgraph.client.ui;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the canvas coordinate system.
 *
 * <p>What the drawing transform does to a graph point has to be exactly what
 * {@link CanvasView#toScreenX} says it does. The two drifting apart is not theoretical: the pan used
 * to be applied <em>after</em> the scale, which multiplied it by the zoom, so as soon as the view was
 * panned or zoomed above 100% every click landed somewhere other than the cursor.
 */
class CanvasViewTest {

    private static final int VIEWPORT_X = 0;
    private static final int VIEWPORT_Y = 16;

    /** The view starts panned by (40, 30), so {@code panX}/{@code panY} here are added on top. */
    private static CanvasView view(double panX, double panY, double zoom) {
        CanvasView view = new CanvasView();
        view.setViewport(VIEWPORT_X, VIEWPORT_Y, 800, 400);
        view.panBy(panX, panY);
        view.setZoom(zoom);
        return view;
    }

    @Test
    void drawingTransformMatchesTheCoordinateConversions() {
        double[] pans = {0, 40, -123.5, 500};
        double[] zooms = {0.35, 0.5, 0.7, 1.0, 1.37, 1.6, 2.5};
        double[] points = {0, 1, 137.5, -420, 1000};

        for (double pan : pans) {
            for (double zoom : zooms) {
                for (double graph : points) {
                    CanvasView view = view(pan, pan, zoom);
                    Vector3f transformed = new Vector3f();
                    Matrix4f matrix = new Matrix4f();
                    view.applyTo(matrix);
                    matrix.transformPosition((float) graph, (float) graph, 0.0f, transformed);

                    assertEquals(
                            view.toScreenX(graph),
                            Math.round(transformed.x),
                            0.01f,
                            "pose and toScreenX disagree at pan=" + pan + " zoom=" + zoom + " graph=" + graph
                    );
                    assertEquals(
                            view.toScreenY(graph),
                            Math.round(transformed.y),
                            0.01f,
                            "pose and toScreenY disagree at pan=" + pan + " zoom=" + zoom + " graph=" + graph
                    );
                }
            }
        }
    }

    @Test
    void screenAndGraphCoordinatesAreInverses() {
        for (double pan : new double[]{0, 40, -123.5}) {
            for (double zoom : new double[]{0.35, 1.0, 2.5}) {
                CanvasView view = view(pan, -pan, zoom);
                for (double screen : new double[]{0, 16, 137, 799, 420}) {
                    double graphX = view.toGraphX(screen);
                    assertEquals(screen, view.toScreenX(graphX), 0.5, "x round trip at zoom " + zoom);
                    double graphY = view.toGraphY(screen);
                    assertEquals(screen, view.toScreenY(graphY), 0.5, "y round trip at zoom " + zoom);
                }
            }
        }
    }

    @Test
    void panningMovesTheViewByTheMouseDeltaInPixels() {
        CanvasView view = view(0, 0, 2.0);
        int beforeX = view.toScreenX(100);
        int beforeY = view.toScreenY(0);
        view.panBy(30, -12);
        // A drag of 30 screen pixels has to move the content exactly 30 screen pixels, at any zoom.
        assertEquals(beforeX + 30, view.toScreenX(100));
        assertEquals(beforeY - 12, view.toScreenY(0));
    }

    @Test
    void zoomingKeepsThePointUnderTheCursor() {
        CanvasView view = view(0, 0, 1.0);
        double cursorX = 400;
        double cursorY = 200;
        double graphXBefore = view.toGraphX(cursorX);
        double graphYBefore = view.toGraphY(cursorY);

        view.zoomAt(cursorX, cursorY, 1.5);
        view.zoomAt(cursorX, cursorY, 1.2);
        view.zoomAt(cursorX, cursorY, 0.5);

        assertEquals(graphXBefore, view.toGraphX(cursorX), 0.5, "the cursor should stay on the same graph point");
        assertEquals(graphYBefore, view.toGraphY(cursorY), 0.5, "the cursor should stay on the same graph point");
    }

    @Test
    void zoomIsClampedToTheAllowedRange() {
        CanvasView view = view(0, 0, 1.0);
        view.setZoom(100);
        assertEquals(CanvasView.MAX_ZOOM, view.zoom());
        view.setZoom(0.0001);
        assertEquals(CanvasView.MIN_ZOOM, view.zoom());
    }

    @Test
    void centeringPutsThePointInTheMiddleOfTheViewport() {
        CanvasView view = view(0, 0, 1.0);
        view.centerOn(500, 300);
        assertEquals(VIEWPORT_X + 400, view.toScreenX(500), 0.5);
        assertEquals(VIEWPORT_Y + 200, view.toScreenY(300), 0.5);
    }
}
