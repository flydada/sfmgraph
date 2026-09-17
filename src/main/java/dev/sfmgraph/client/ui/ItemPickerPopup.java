package dev.sfmgraph.client.ui;

import dev.sfmgraph.graph.ResourceKind;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A searchable picker for the filter field: every registered item (or fluid) as an icon grid, with a
 * search box that matches both the id and the translated name.
 *
 * <p>Picking an entry writes that entry's full id into the filter, so a picked entry and a hand typed
 * id end up as the same kind of value.
 */
public final class ItemPickerPopup {

    private static final int CELL = 20;
    private static final int HEADER = 22;
    private static final int FOOTER = 12;

    /** Built once per session; the item and fluid registries do not change while playing. */
    private static List<Entry> itemCache;
    private static List<Entry> fluidCache;

    public record Entry(ItemStack stack, String id, String searchText) {
        public boolean matches(String query) {
            return query.isEmpty() || searchText.contains(query);
        }
    }

    private final Font font;
    private final ResourceKind kind;
    private final List<Entry> entries;
    private final List<Entry> visible = new ArrayList<>();
    private final EditBox searchBox;
    private final Consumer<String> onPick;
    private final Consumer<EditBox> detach;
    private final Runnable onClose;
    private final String title;

    /** The filter value to highlight in the grid, if any. */
    private final String currentFilter;

    private int x;
    private int y;
    private int w;
    private int h;
    private int scroll;
    private int screenWidth = 4000;
    private int screenHeight = 4000;

    public ItemPickerPopup(
            Font font,
            ResourceKind kind,
            String currentFilter,
            Consumer<String> onPick,
            Runnable onClose,
            Consumer<EditBox> attach,
            Consumer<EditBox> detach
    ) {
        this.font = font;
        this.kind = kind;
        this.currentFilter = currentFilter == null ? "" : currentFilter.trim();
        this.onPick = onPick;
        this.onClose = onClose;
        this.detach = detach;
        this.title = kind == ResourceKind.FLUID ? "选择流体" : "选择物品";
        this.entries = kind == ResourceKind.FLUID ? collectFluids() : collectItems();

        this.searchBox = new EditBox(font, 0, 0, 100, 12, Component.empty());
        this.searchBox.setBordered(false);
        this.searchBox.setMaxLength(64);
        this.searchBox.setTextColor(Theme.TEXT);
        this.searchBox.setHint(Component.literal("搜索名称或 ID…").withColor(0x666C78));
        this.searchBox.setResponder(this::applyQuery);
        attach.accept(this.searchBox);
        applyQuery("");
    }

    public EditBox searchBox() {
        return searchBox;
    }

    /** Removes the search field from the screen. Must be called when the popup closes. */
    public void dispose() {
        detach.accept(searchBox);
    }

    public void close() {
        onClose.run();
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private static List<Entry> collectItems() {
        if (itemCache != null) return itemCache;
        List<ResourceLocation> ids = new ArrayList<>(BuiltInRegistries.ITEM.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        List<Entry> result = new ArrayList<>(ids.size());
        for (ResourceLocation id : ids) {
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) continue;
            ItemStack stack = new ItemStack(item);
            result.add(new Entry(stack, id.toString(), searchText(id.toString(), stack.getHoverName().getString())));
        }
        itemCache = result;
        return itemCache;
    }

    private static List<Entry> collectFluids() {
        if (fluidCache != null) return fluidCache;
        List<ResourceLocation> ids = new ArrayList<>(BuiltInRegistries.FLUID.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        List<Entry> result = new ArrayList<>(ids.size());
        for (ResourceLocation id : ids) {
            Fluid fluid = BuiltInRegistries.FLUID.get(id);
            if (fluid == null || fluid == Fluids.EMPTY) continue;
            // The flowing variant of a fluid is an implementation detail, not a pickable choice.
            if (id.getPath().startsWith("flowing_")) continue;
            Item bucket = fluid.getBucket();
            ItemStack stack = bucket == Items.AIR ? ItemStack.EMPTY : new ItemStack(bucket);
            String label = stack.isEmpty() ? id.getPath() : stack.getHoverName().getString();
            result.add(new Entry(stack, id.toString(), searchText(id.toString(), label)));
        }
        fluidCache = result;
        return fluidCache;
    }

    /** Lower cased "id + display name", so one search box matches either. */
    private static String searchText(String id, String displayName) {
        return (id + " " + displayName).toLowerCase(Locale.ROOT);
    }

    /** Re-filters the grid; called on every keystroke. */
    public void applyQuery(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (Entry entry : entries) {
            if (entry.matches(needle)) visible.add(entry);
        }
        scroll = 0;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    /** Places the popup beside the inspector panel, clamped to the screen. */
    public void layout(int preferredX, int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.w = Math.min(430, Math.max(180, screenWidth - 24));
        this.h = Math.min(300, Math.max(120, screenHeight - 36));
        this.x = Math.max(4, Math.min(preferredX, screenWidth - w - 4));
        this.y = Math.max(4, (screenHeight - h) / 2);
        searchBox.setX(x + 10);
        searchBox.setY(y + 8);
        searchBox.setWidth(w - 20);
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private int columns() {
        return Math.max(1, (w - 16) / CELL);
    }

    private int visibleRows() {
        return Math.max(1, (h - HEADER - FOOTER) / CELL);
    }

    private int rowsTotal() {
        return (visible.size() + columns() - 1) / columns();
    }

    private int maxScroll() {
        return Math.max(0, rowsTotal() - visibleRows());
    }

    private int gridLeft() {
        return x + 8;
    }

    private int gridTop() {
        return y + HEADER;
    }

    private int gridBottom() {
        return y + h - FOOTER;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * @return true when the click was handled and should not reach the canvas
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!contains(mouseX, mouseY)) return false;
        if (button != 0) return true;
        // The search field is a vanilla widget, which the screen routes the click to first.
        if (mouseY < gridTop()) return true;

        Entry hovered = entryAt(mouseX, mouseY);
        if (hovered != null) {
            onPick.accept(hovered.id());
        }
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!contains(mouseX, mouseY)) return false;
        int max = maxScroll();
        if (max > 0) {
            scroll = (int) Math.max(0, Math.min(max, scroll - delta));
        }
        return true;
    }

    private Entry entryAt(double mouseX, double mouseY) {
        int top = gridTop();
        int bottom = gridBottom();
        if (mouseY < top || mouseY >= bottom) return null;
        int column = (int) ((mouseX - gridLeft()) / CELL);
        if (column < 0 || column >= columns()) return null;
        int row = (int) ((mouseY - top) / CELL) + scroll;
        int index = row * columns() + column;
        return index >= 0 && index < visible.size() ? visible.get(index) : null;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        Draw.panelWithShadow(graphics, x, y, w, h, Theme.PANEL, Theme.ACCENT_BORDER);

        Draw.text(graphics, font, title, x + 10, y + 4, Theme.TEXT_TITLE);
        String counts = visible.size() + " / " + entries.size();
        Draw.text(graphics, font, counts, x + w - 10 - font.width(counts), y + 4, Theme.TEXT_DIM);

        // Background for the search field; the editable text is a widget drawn after this pass.
        int fieldTop = y + 6;
        graphics.fill(x + 8, fieldTop - 2, x + w - 8, fieldTop + 14, Theme.EDIT_BG);
        Draw.outline(graphics, x + 8, fieldTop - 2, w - 16, 16,
                     searchBox.isFocused() ? Theme.ACCENT_BORDER : Theme.PANEL_BORDER);

        int left = gridLeft();
        int top = gridTop();
        int bottom = gridBottom();

        if (visible.isEmpty()) {
            Draw.text(graphics, font, "没有匹配的" + (kind == ResourceKind.FLUID ? "流体" : "物品"),
                      x + 12, top + 6, Theme.TEXT_DIM);
        } else {
            graphics.enableScissor(x + 1, top, x + w - 1, bottom);
            int columns = columns();
            int rows = visibleRows();
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    int index = (row + scroll) * columns + column;
                    if (index >= visible.size()) break;
                    Entry entry = visible.get(index);
                    int cellX = left + column * CELL;
                    int cellY = top + row * CELL;

                    if (!currentFilter.isEmpty() && entry.id().equals(currentFilter)) {
                        Draw.panel(graphics, cellX, cellY, CELL, CELL, Theme.BUTTON_BG_ACTIVE, Theme.ACCENT);
                    }
                    if (!entry.stack().isEmpty()) {
                        graphics.renderItem(entry.stack(), cellX + 1, cellY + 1);
                    } else {
                        Draw.centeredText(graphics, font, entry.id().substring(0, 1).toUpperCase(Locale.ROOT),
                                          cellX + CELL / 2, cellY + 5, Theme.TEXT);
                    }
                }
            }
            graphics.disableScissor();

            if (maxScroll() > 0) {
                int track = bottom - top;
                int thumbHeight = Math.max(12, track * visibleRows() / Math.max(1, rowsTotal()));
                int thumbY = top + (track - thumbHeight) * scroll / maxScroll();
                graphics.fill(x + w - 5, top, x + w - 3, bottom, Theme.EDIT_BG);
                graphics.fill(x + w - 5, thumbY, x + w - 3, thumbY + thumbHeight, Theme.BUTTON_BORDER);
            }
        }

        Draw.text(graphics, font, "滚轮滚动 · 点击选择 · Esc 关闭", x + 10, bottom + 2, Theme.TEXT_DIM);

        Entry hovered = contains(mouseX, mouseY) ? entryAt(mouseX, mouseY) : null;
        if (hovered != null) {
            Draw.panel(graphics, left + ((int) ((mouseX - left) / CELL)) * CELL,
                       top + ((int) ((mouseY - top) / CELL)) * CELL, CELL, CELL,
                       Theme.BUTTON_BG_HOVER, Theme.ACCENT_BORDER);
            List<String> lines = new ArrayList<>();
            if (!hovered.stack().isEmpty()) {
                lines.add(hovered.stack().getHoverName().getString());
            }
            lines.add(hovered.id());
            lines.add("点击填入过滤器");
            Draw.tooltip(graphics, font, lines, mouseX, mouseY, screenWidth, screenHeight);
        }
    }
}
