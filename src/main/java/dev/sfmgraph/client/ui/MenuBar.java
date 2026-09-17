package dev.sfmgraph.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A classic top-of-window menu bar with drop down menus, drawn by hand because Minecraft has no such
 * widget.
 */
public final class MenuBar {

    public record Item(String label, @Nullable Runnable action, boolean enabled, @Nullable Supplier<Boolean> checked) {

        public static Item of(String label, Runnable action) {
            return new Item(label, action, true, null);
        }

        public static Item toggle(String label, Supplier<Boolean> checked, Runnable action) {
            return new Item(label, action, true, checked);
        }

        public static Item disabled(String label) {
            return new Item(label, null, false, null);
        }

        public static Item separator() {
            return new Item("", null, false, null);
        }

        public boolean isSeparator() {
            return label.isEmpty() && action == null;
        }

        /** Read live, so a menu can reflect state that changed since it was built. */
        public boolean isChecked() {
            return checked != null && checked.get();
        }
    }

    public record Menu(String label, List<Item> items) {
    }

    private record ItemRect(int x, int y, int w, int h, Item item) {
    }

    private final Font font;
    private final List<Menu> menus;
    private final List<int[]> titleRects = new ArrayList<>();

    private int openIndex = -1;
    private int hoveredTitle = -1;
    private final List<ItemRect> openItemRects = new ArrayList<>();
    private int dropdownX;
    private int dropdownY;
    private int dropdownWidth;
    private int dropdownHeight;

    public MenuBar(Font font, List<Menu> menus) {
        this.font = font;
        this.menus = menus;
    }

    public int height() {
        return Theme.MENU_HEIGHT;
    }

    public boolean isOpen() {
        return openIndex >= 0;
    }

    public void close() {
        openIndex = -1;
    }

    private void layoutTitles() {
        titleRects.clear();
        int x = 4;
        for (Menu menu : menus) {
            int width = font.width(menu.label()) + 12;
            titleRects.add(new int[]{x, width});
            x += width;
        }
    }

    private int titleIndexAt(double mouseX, double mouseY) {
        if (mouseY < 0 || mouseY > Theme.MENU_HEIGHT) return -1;
        for (int i = 0; i < titleRects.size(); i++) {
            int[] rect = titleRects.get(i);
            if (mouseX >= rect[0] && mouseX < rect[0] + rect[1]) return i;
        }
        return -1;
    }

    private void layoutDropdown(int index) {
        openItemRects.clear();
        Menu menu = menus.get(index);
        dropdownWidth = 0;
        for (Item item : menu.items()) {
            dropdownWidth = Math.max(dropdownWidth, item.isSeparator() ? 0 : font.width(item.label()) + 30);
        }
        dropdownWidth = Math.max(dropdownWidth, 110);
        dropdownX = titleRects.get(index)[0];
        dropdownY = Theme.MENU_HEIGHT;

        int y = dropdownY + 2;
        for (Item item : menu.items()) {
            int height = item.isSeparator() ? 5 : 14;
            openItemRects.add(new ItemRect(dropdownX + 1, y, dropdownWidth - 2, height, item));
            y += height;
        }
        dropdownHeight = y - dropdownY + 1;

        // Keep the dropdown on screen.
        if (dropdownX + dropdownWidth > 32000) {
            dropdownX = 0;
        }
    }

    /** @return true when the click was consumed by the bar or an open dropdown. */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            if (isOpen()) {
                close();
                return true;
            }
            return false;
        }
        layoutTitles();
        int title = titleIndexAt(mouseX, mouseY);
        if (title >= 0) {
            openIndex = openIndex == title ? -1 : title;
            if (openIndex >= 0) layoutDropdown(openIndex);
            return true;
        }
        if (isOpen()) {
            for (ItemRect rect : openItemRects) {
                if (rect.item().isSeparator() || !rect.item().enabled()) continue;
                if (mouseX >= rect.x() && mouseX < rect.x() + rect.w()
                    && mouseY >= rect.y() && mouseY < rect.y() + rect.h()) {
                    Runnable action = rect.item().action();
                    close();
                    if (action != null) action.run();
                    return true;
                }
            }
            // Clicking anywhere else closes the menu and swallows the click.
            close();
            return true;
        }
        return false;
    }

    /** Registers hover state; call from the screen's render pass. */
    public void updateHover(double mouseX, double mouseY) {
        layoutTitles();
        hoveredTitle = titleIndexAt(mouseX, mouseY);
        if (isOpen() && hoveredTitle >= 0 && hoveredTitle != openIndex) {
            openIndex = hoveredTitle;
            layoutDropdown(openIndex);
        }
    }

    public void render(GuiGraphics graphics, int screenWidth) {
        layoutTitles();
        graphics.fill(0, 0, screenWidth, Theme.MENU_HEIGHT, Theme.PANEL_ALT);
        graphics.fill(0, Theme.MENU_HEIGHT - 1, screenWidth, Theme.MENU_HEIGHT, Theme.PANEL_BORDER);

        for (int i = 0; i < menus.size(); i++) {
            int[] rect = titleRects.get(i);
            boolean active = i == openIndex;
            boolean hovered = i == hoveredTitle;
            if (active || hovered) {
                graphics.fill(rect[0], 1, rect[0] + rect[1], Theme.MENU_HEIGHT - 1,
                              active ? Theme.BUTTON_BG_ACTIVE : Theme.BUTTON_BG_HOVER);
            }
            Draw.text(
                    graphics,
                    font,
                    menus.get(i).label(),
                    rect[0] + 6,
                    (Theme.MENU_HEIGHT - 8) / 2,
                    active ? Theme.TEXT_TITLE : Theme.TEXT
            );
        }
    }

    /** Draws the open dropdown; call last so it sits above everything else. */
    public void renderDropdown(GuiGraphics graphics, int mouseX, int mouseY, int screenWidth) {
        if (!isOpen()) return;
        if (dropdownX + dropdownWidth > screenWidth) {
            dropdownX = Math.max(2, screenWidth - dropdownWidth - 2);
        }
        Draw.panelWithShadow(graphics, dropdownX, dropdownY, dropdownWidth, dropdownHeight, Theme.PANEL, Theme.PANEL_BORDER);
        for (ItemRect rect : openItemRects) {
            if (rect.item().isSeparator()) {
                graphics.fill(rect.x() + 4, rect.y() + 2, rect.x() + rect.w() - 4, rect.y() + 3, Theme.PANEL_BORDER);
                continue;
            }
            boolean hovered = mouseX >= rect.x() && mouseX < rect.x() + rect.w()
                              && mouseY >= rect.y() && mouseY < rect.y() + rect.h();
            if (hovered && rect.item().enabled()) {
                graphics.fill(rect.x(), rect.y(), rect.x() + rect.w(), rect.y() + rect.h(), Theme.BUTTON_BG_HOVER);
            }
            if (rect.item().isChecked()) {
                graphics.fill(rect.x() + 4, rect.y() + 5, rect.x() + 8, rect.y() + 9, Theme.ACCENT);
            }
            int color = rect.item().enabled() ? Theme.TEXT : Theme.TEXT_DISABLED;
            Draw.text(graphics, font, rect.item().label(), rect.x() + 16, rect.y() + 3, color);
        }
    }

    public boolean isOverDropdown(double mouseX, double mouseY) {
        if (!isOpen()) return false;
        return mouseX >= dropdownX && mouseX < dropdownX + dropdownWidth
               && mouseY >= dropdownY && mouseY < dropdownY + dropdownHeight;
    }

    public boolean isOverBar(double mouseX, double mouseY) {
        return mouseY >= 0 && mouseY < Theme.MENU_HEIGHT;
    }
}
