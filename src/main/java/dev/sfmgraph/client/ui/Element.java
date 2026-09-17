package dev.sfmgraph.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * One laid out widget in a side panel.
 *
 * <p>The inspector rebuilds its element list when the selection changes and then replays it every
 * frame. Anything that must update live (previews, values being typed) reads through a supplier
 * instead of a snapshot, so the layout stays stable while the graph changes underneath it.
 */
public abstract class Element {

    protected final Font font;

    public int x;
    public int y;
    public int w;
    public int h;

    protected Element(Font font) {
        this.font = font;
    }

    public abstract void draw(GuiGraphics graphics, int mouseX, int mouseY);

    public void onClick(double mouseX, double mouseY, int button) {
    }

    /** Sliders opt in so the screen knows to route drags to them. */
    public boolean draggable() {
        return false;
    }

    public void onDrag(double mouseX, double mouseY) {
    }

    public List<String> tooltip() {
        return List.of();
    }

    /** Tooltip for a specific point, for elements made of several hit targets. */
    public List<String> tooltipAt(int mouseX, int mouseY) {
        return tooltip();
    }

    /** Called when the element list is rebuilt, so widgets can detach themselves. */
    public void onRemoved() {
    }

    /**
     * Repositions any vanilla widget this element hosts.
     *
     * <p>Vanilla widgets are drawn and clicked in their own pass, outside the panel's scissor, so an
     * element that scrolled out of view has to hide its widget explicitly or it would draw over the
     * panel footer and still take clicks.
     *
     * @param visible whether the element is currently inside the panel's visible region
     */
    public void syncWidget(boolean visible) {
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
