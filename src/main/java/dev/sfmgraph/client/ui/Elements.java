package dev.sfmgraph.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The concrete widgets the inspector and preview panels are built from. */
public final class Elements {

    private Elements() {
    }

    /** Vertical gap. */
    public static final class Spacer extends Element {

        public Spacer(Font font, int height) {
            super(font);
            this.h = height;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY) {
        }
    }

    /** Small dim caption above a control. */
    public static final class Caption extends Element {

        private final Supplier<String> text;

        public Caption(Font font, Supplier<String> text) {
            super(font);
            this.text = text;
            this.h = 10;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            Draw.text(graphics, font, Draw.ellipsize(font, text.get(), w), x, y, Theme.TEXT_DIM);
        }
    }

    /** Section heading with a rule under it. */
    public static final class Section extends Element {

        private final String title;

        public Section(Font font, String title) {
            super(font);
            this.title = title;
            this.h = 14;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            Draw.text(graphics, font, title, x, y + 3, Theme.ACCENT);
            graphics.fill(x, y + h - 2, x + w, y + h - 1, Theme.PANEL_BORDER);
        }
    }

    /** Plain multi line text, read live through a supplier. */
    public static final class Lines extends Element {

        public record Line(String text, int color) {
        }

        private final Supplier<List<Line>> lines;

        public Lines(Font font, Supplier<List<Line>> lines) {
            super(font);
            this.lines = lines;
        }

        public int measure() {
            return Math.max(1, lines.get().size()) * (font.lineHeight + 1);
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            int lineY = y;
            for (Line line : lines.get()) {
                Draw.text(graphics, font, Draw.ellipsize(font, line.text(), w), x, lineY, line.color());
                lineY += font.lineHeight + 1;
            }
        }
    }

    /** Push button, or a toggle when {@code selected} is supplied. */
    public static final class Button extends Element {

        private final Supplier<String> label;
        private final Runnable action;
        private final Supplier<Boolean> enabled;
        private final Supplier<Boolean> selected;
        private final List<String> tooltip;
        private final int accent;

        public Button(
                Font font,
                Supplier<String> label,
                Runnable action,
                Supplier<Boolean> enabled,
                Supplier<Boolean> selected,
                List<String> tooltip,
                int accent
        ) {
            super(font);
            this.label = label;
            this.action = action;
            this.enabled = enabled;
            this.selected = selected;
            this.tooltip = tooltip;
            this.accent = accent;
            this.h = Theme.CHIP_HEIGHT;
        }

        public static Button of(Font font, String label, Runnable action) {
            return new Button(font, () -> label, action, () -> true, null, List.of(), 0);
        }

        /** A toggle chip whose highlight state is read live. */
        public static Button toggle(
                Font font,
                String label,
                Supplier<Boolean> selected,
                Runnable action,
                List<String> tooltip
        ) {
            return new Button(font, () -> label, action, () -> true, selected, tooltip, 0);
        }

        public Button withTooltip(List<String> lines) {
            return new Button(font, label, action, enabled, selected, lines, accent);
        }

        public Button withAccent(int color) {
            return new Button(font, label, action, enabled, selected, tooltip, color);
        }

        public Button width(int width) {
            this.w = width;
            return this;
        }

        /** Sizes the button to its label plus padding. */
        public Button autoWidth(int padding) {
            this.w = font.width(label.get()) + padding * 2;
            return this;
        }

        @Override
        public List<String> tooltip() {
            return enabled.get() ? tooltip : List.of();
        }

        @Override
        public void onClick(double mouseX, double mouseY, int button) {
            if (button != 0 || !enabled.get()) return;
            action.run();
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            boolean isEnabled = enabled.get();
            boolean isSelected = selected != null && selected.get();
            boolean isHovered = contains(mouseX, mouseY);
            int background;
            if (!isEnabled) {
                background = Theme.BUTTON_BG;
            } else if (isSelected) {
                background = accent != 0 ? accent : Theme.BUTTON_BG_ACTIVE;
            } else {
                background = isHovered ? Theme.BUTTON_BG_HOVER : Theme.BUTTON_BG;
            }
            int border = isSelected ? Theme.ACCENT_BORDER : Theme.BUTTON_BORDER;
            Draw.panel(graphics, x, y, w, h, background, border);
            int textColor = !isEnabled ? Theme.TEXT_DISABLED
                    : isSelected && accent != 0 ? Theme.contrastText(background)
                    : isSelected ? Theme.TEXT_TITLE
                    : Theme.BUTTON_TEXT;
            Draw.centeredText(
                    graphics,
                    font,
                    Draw.ellipsize(font, label.get(), w - 6),
                    x + w / 2,
                    y + (h - 8) / 2,
                    textColor
            );
        }
    }

    /** A row of toggle chips that wraps onto as many lines as it needs. */
    public static final class ChipGroup extends Element {

        public record Chip(String label, Supplier<Boolean> selected, Runnable action, List<String> tooltip) {
        }

        private record Placed(Chip chip, int x, int y, int w) {
        }

        private final List<Placed> placed = new ArrayList<>();

        public ChipGroup(Font font, int maxWidth, List<Chip> chips) {
            super(font);
            this.w = maxWidth;
            int cursorX = 0;
            int cursorY = 0;
            int rowHeight = Theme.CHIP_HEIGHT + 3;
            for (Chip chip : chips) {
                int chipWidth = font.width(chip.label()) + 10;
                if (cursorX > 0 && cursorX + chipWidth > maxWidth) {
                    cursorX = 0;
                    cursorY += rowHeight;
                }
                placed.add(new Placed(chip, cursorX, cursorY, chipWidth));
                cursorX += chipWidth + 3;
            }
            this.h = cursorY + Theme.CHIP_HEIGHT;
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            for (Placed item : placed) {
                boolean isSelected = item.chip().selected().get();
                int chipX = x + item.x();
                int chipY = y + item.y();
                boolean isHovered = mouseX >= chipX && mouseX < chipX + item.w()
                                    && mouseY >= chipY && mouseY < chipY + Theme.CHIP_HEIGHT;
                int background = isSelected ? Theme.BUTTON_BG_ACTIVE
                        : isHovered ? Theme.BUTTON_BG_HOVER : Theme.BUTTON_BG;
                Draw.panel(
                        graphics,
                        chipX,
                        chipY,
                        item.w(),
                        Theme.CHIP_HEIGHT,
                        background,
                        isSelected ? Theme.ACCENT_BORDER : Theme.BUTTON_BORDER
                );
                Draw.centeredText(
                        graphics,
                        font,
                        item.chip().label(),
                        chipX + item.w() / 2,
                        chipY + (Theme.CHIP_HEIGHT - 8) / 2,
                        isSelected ? Theme.TEXT_TITLE : Theme.BUTTON_TEXT
                );
            }
        }

        @Override
        public List<String> tooltip() {
            return List.of();
        }

        @Override
        public void onClick(double mouseX, double mouseY, int button) {
            if (button != 0) return;
            for (Placed item : placed) {
                if (mouseX >= x + item.x() && mouseX < x + item.x() + item.w()
                    && mouseY >= y + item.y() && mouseY < y + item.y() + Theme.CHIP_HEIGHT) {
                    item.chip().action().run();
                    return;
                }
            }
        }

        /** Chip tooltip under the cursor, if any. */
        public List<String> tooltipAt(int mouseX, int mouseY) {
            for (Placed item : placed) {
                if (mouseX >= x + item.x() && mouseX < x + item.x() + item.w()
                    && mouseY >= y + item.y() && mouseY < y + item.y() + Theme.CHIP_HEIGHT) {
                    return item.chip().tooltip();
                }
            }
            return List.of();
        }
    }

    /** Log scale slider with a live readout. */
    public static final class Slider extends Element {

        private final Supplier<Double> position;
        private final Consumer<Double> onChange;
        private final Supplier<String> readout;

        public Slider(Font font, Supplier<Double> position, Consumer<Double> onChange, Supplier<String> readout) {
            super(font);
            this.position = position;
            this.onChange = onChange;
            this.readout = readout;
            this.h = 24;
        }

        private void applyAt(double mouseX) {
            double t = (mouseX - x - 3) / Math.max(1, w - 6);
            onChange.accept(Math.max(0, Math.min(1, t)));
        }

        @Override
        public boolean draggable() {
            return true;
        }

        @Override
        public void onDrag(double mouseX, double mouseY) {
            applyAt(mouseX);
        }

        @Override
        public void onClick(double mouseX, double mouseY, int button) {
            if (button != 0) return;
            if (mouseY < y + 9) return;
            applyAt(mouseX);
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            Draw.text(graphics, font, readout.get(), x, y, Theme.TEXT);
            int trackY = y + 16;
            graphics.fill(x, trackY, x + w, trackY + 3, Theme.EDIT_BG);
            Draw.outline(graphics, x, trackY - 1, w, 5, Theme.PANEL_BORDER);
            int knobX = x + 3 + (int) Math.round(position.get() * (w - 6));
            graphics.fill(x, trackY, knobX, trackY + 3, Theme.ACCENT_SOFT);
            Draw.circle(graphics, knobX, trackY + 1.5, 4, Theme.ACCENT_BORDER);
            Draw.circle(graphics, knobX, trackY + 1.5, 2, Theme.ACCENT);
        }
    }

    /**
     * A number field with minus and plus buttons: {@code [-] [ 128 ] [+]}. Typing is the primary way
     * to set it, since values like a RETAIN of 100000 are painful to reach by clicking; the buttons
     * are there for nudging. An empty field means zero, shown as {@code zeroText}.
     */
    public static final class Stepper extends Element {

        private final EditBox box;
        private final Consumer<EditBox> detach;
        private final Supplier<Long> value;
        private final Consumer<Long> onChange;
        private final long step;
        private final long min;
        private final long max;
        /** Guards the responder while the field is being written to from the model. */
        private boolean syncing;

        public Stepper(
                Font font,
                Supplier<Long> value,
                Consumer<Long> onChange,
                long step,
                long min,
                long max,
                String zeroText,
                Consumer<EditBox> attach,
                Consumer<EditBox> detach
        ) {
            super(font);
            this.value = value;
            this.onChange = onChange;
            this.step = step;
            this.min = min;
            this.max = max;
            this.detach = detach;
            this.h = Theme.CHIP_HEIGHT;

            this.box = new EditBox(font, 0, 0, 40, 12, Component.empty());
            this.box.setBordered(false);
            this.box.setMaxLength(18);
            this.box.setTextColor(Theme.TEXT);
            this.box.setHint(Component.literal(zeroText).withColor(0x666C78));
            this.box.setResponder(this::onTextChanged);
            attach.accept(this.box);
            syncFromModel();
        }

        private void onTextChanged(String text) {
            if (syncing) return;
            String digits = text.replaceAll("[^0-9]", "");
            if (!digits.equals(text)) {
                // Strip anything that is not a digit, without moving the caret oddly.
                syncing = true;
                box.setValue(digits);
                syncing = false;
            }
            onChange.accept(parse(digits));
        }

        private long parse(String digits) {
            if (digits.isEmpty()) return 0;
            try {
                return Math.max(min, Math.min(max, Long.parseLong(digits)));
            } catch (NumberFormatException e) {
                // More digits than fit in a long: clamp rather than complain.
                return max;
            }
        }

        private void syncFromModel() {
            long current = value.get();
            String text = current == 0 ? "" : Long.toString(current);
            if (text.equals(box.getValue())) return;
            syncing = true;
            box.setValue(text);
            syncing = false;
        }

        @Override
        public void syncWidget(boolean visible) {
            box.setX(x + 20);
            box.setY(y + (h - 8) / 2);
            box.setWidth(Math.max(20, w - 40));
            box.setVisible(visible);
            syncFromModel();
        }

        @Override
        public void onClick(double mouseX, double mouseY, int button) {
            if (button != 0) return;
            long current = value.get();
            long next;
            if (mouseX < x + 16) {
                next = current - step;
            } else if (mouseX > x + w - 16) {
                next = current + step;
            } else {
                // The middle is the text field; leave the click to the widget.
                return;
            }
            onChange.accept(Math.max(min, Math.min(max, next)));
            syncFromModel();
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            Draw.panel(graphics, x, y, 15, h, Theme.BUTTON_BG, Theme.BUTTON_BORDER);
            Draw.centeredText(graphics, font, "-", x + 7, y + (h - 8) / 2, Theme.BUTTON_TEXT);
            Draw.panel(graphics, x + 17, y, w - 34, h, Theme.EDIT_BG,
                       box.isFocused() ? Theme.ACCENT_BORDER : Theme.BUTTON_BORDER);
            Draw.panel(graphics, x + w - 15, y, 15, h, Theme.BUTTON_BG, Theme.BUTTON_BORDER);
            Draw.centeredText(graphics, font, "+", x + w - 7, y + (h - 8) / 2, Theme.BUTTON_TEXT);
        }

        @Override
        public void onRemoved() {
            detach.accept(box);
        }
    }

    /** Text field backed by a vanilla {@link EditBox}, applied live as the player types. */
    public static final class Edit extends Element {

        private final EditBox box;
        private final Consumer<EditBox> detach;

        public Edit(
                Font font,
                String initial,
                String hint,
                int width,
                int maxLength,
                boolean editable,
                Consumer<String> onChange,
                Consumer<EditBox> attach,
                Consumer<EditBox> detach
        ) {
            super(font);
            this.w = width;
            this.h = 14;
            this.detach = detach;
            this.box = new EditBox(font, 0, 0, width - 8, 12, Component.empty());
            this.box.setBordered(false);
            this.box.setMaxLength(maxLength);
            this.box.setEditable(editable);
            this.box.setTextColor(Theme.TEXT);
            this.box.setTextColorUneditable(Theme.TEXT_DIM);
            if (hint != null && !hint.isEmpty()) {
                this.box.setHint(Component.literal(hint).withColor(0x666C78));
            }
            this.box.setValue(initial == null ? "" : initial);
            this.box.setResponder(onChange);
            attach.accept(this.box);
        }

        public EditBox box() {
            return box;
        }

        /** Pushes the element position into the vanilla widget; call after moving the element. */
        @Override
        public void syncWidget(boolean visible) {
            box.setX(x + 4);
            box.setY(y + (h - 8) / 2);
            box.setWidth(w - 8);
            box.setVisible(visible);
        }

        @Override
        public void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            Draw.panel(
                    graphics,
                    x,
                    y,
                    w,
                    h,
                    Theme.EDIT_BG,
                    box.isFocused() ? Theme.ACCENT_BORDER : Theme.PANEL_BORDER
            );
        }

        @Override
        public void onRemoved() {
            detach.accept(box);
        }
    }
}
