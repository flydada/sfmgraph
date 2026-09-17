package dev.sfmgraph.client;

import dev.sfmgraph.client.ui.Element;
import dev.sfmgraph.client.ui.Elements;
import dev.sfmgraph.client.ui.RateScale;
import dev.sfmgraph.client.ui.Theme;
import dev.sfmgraph.graph.GraphLink;
import dev.sfmgraph.graph.GraphNode;
import dev.sfmgraph.graph.GraphSide;
import dev.sfmgraph.graph.LinkCondition;
import dev.sfmgraph.graph.ResourceKind;
import dev.sfmgraph.graph.RoundRobinMode;
import dev.sfmgraph.graph.SideSet;
import dev.sfmgraph.graph.TriggerMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Builds the element list for the right hand panel: either the properties of the selected link, or
 * the details of the selected node.
 *
 * <p>Elements are laid out starting at {@code (0, 0)}; the screen shifts them into the panel and
 * applies the scroll offset, then calls {@link Elements.Edit#syncWidget()} for text fields.
 */
public final class Inspector {

    private final GraphEditorScreen screen;
    private final Font font = Minecraft.getInstance().font;

    public Inspector(GraphEditorScreen screen) {
        this.screen = screen;
    }

    public List<Element> build(int width) {
        List<Element> out = new ArrayList<>();
        Row row = new Row(width);
        GraphLink link = screen.selectedLink();
        GraphNode node = screen.selectedNode();
        if (link != null) {
            buildLink(out, row, link);
        } else if (node != null) {
            buildNode(out, row, node);
        } else {
            buildHint(out, row);
        }
        return out;
    }

    /** Vertical cursor plus the row helper, so two controls can share one row. */
    private static final class Row {
        final int width;
        int y;

        Row(int width) {
            this.width = width;
        }

        /** Reserves a row and returns its top edge. */
        int next(int height, int gap) {
            int at = y;
            y += height + gap;
            return at;
        }

        int peek() {
            return y;
        }

        void advance(int amount) {
            y += amount;
        }
    }

    private static <E extends Element> E at(E element, int x, int rowTop, int width) {
        element.x = x;
        element.y = rowTop;
        element.w = width;
        return element;
    }

    /** Full width single element row. */
    private static void addRow(List<Element> out, Row row, Element element, int gap) {
        at(element, 0, row.next(element.h, gap), row.width);
        out.add(element);
    }

    // ------------------------------------------------------------------
    // Link
    // ------------------------------------------------------------------

    private void buildLink(List<Element> out, Row row, GraphLink link) {
        addRow(out, row, new Elements.Section(font, "连线"), 4);

        Elements.Lines summary = new Elements.Lines(font, () -> List.of(
                new Elements.Lines.Line(link.fromLabel + "  →  " + link.toLabel, Theme.TEXT_TITLE)
        ));
        summary.h = 10;
        addRow(out, row, summary, 5);

        addRow(out, row, new Elements.Caption(font, () -> "资源类型"), 1);
        addRow(out, row, new Elements.ChipGroup(font, row.width, kindChips(link)), 6);

        boolean energy = link.resource.kind() == ResourceKind.ENERGY;
        boolean fluid = link.resource.kind() == ResourceKind.FLUID;
        addRow(out, row, new Elements.Caption(font, () -> energy
                ? "过滤器：电力不需要"
                : fluid ? "过滤器：流体 ID，可留空" : "过滤器：物品 ID 或 *模式*，可留空"), 1);
        int filterWidth = row.width - 38;
        int filterRow = row.next(14, 6);
        Elements.Edit filterEdit = new Elements.Edit(
                font,
                link.resource.filter(),
                fluid ? "water" : "minecraft:iron_ingot",
                filterWidth,
                64,
                !energy,
                value -> {
                    link.resource = link.resource.withFilter(value);
                    screen.markDirty();
                },
                screen::attachEditBox,
                screen::detachEditBox
        );
        out.add(at(filterEdit, 0, filterRow, filterWidth));
        // The button sits after the field: its x has to be derived from the field width, not fixed.
        out.add(at(Elements.Button.of(font, "手持", () -> pickFilterFromHand(link))
                           .width(36)
                           .withTooltip(List.of("读取主手物品作为过滤器", energy ? "电力不需要过滤器" : "需要手持对应物品")),
                   filterWidth + 2, filterRow, 36));

        if (!energy) {
            addRow(out, row, Elements.Button.of(
                    font,
                    fluid ? "浏览所有流体…" : "浏览所有物品…",
                    () -> screen.openPicker(link)
            ).width(row.width).withTooltip(List.of(
                    "打开选择器，按名称或 ID 搜索全部已注册的" + (fluid ? "流体" : "物品"),
                    "点一个条目即填入过滤器"
            )), 6);
        }

        addRow(out, row, new Elements.Caption(font, () -> "速率（每 " + unitPeriod(link) + "）"), 1);
        addRow(out, row, new Elements.Slider(
                font,
                () -> ratePosition(link),
                position -> {
                    link.rate = RateScale.nice(RateScale.toRate(position));
                    link.intervalTicks = 0;
                    screen.markDirty();
                },
                () -> link.rate + " " + link.resource.kind().unit() + " / " + unitPeriod(link)
        ), 2);
        addRow(out, row, new Elements.ChipGroup(font, row.width, ratePresets(link)), 4);

        int rateWidth = row.width - 34;
        int rateRow = row.next(14, 6);
        Elements.Edit rateEdit = new Elements.Edit(
                font, link.rate, "1", rateWidth, 24, true,
                value -> {
                    link.rate = value;
                    screen.markDirty();
                },
                screen::attachEditBox,
                screen::detachEditBox
        );
        out.add(at(rateEdit, 0, rateRow, rateWidth));
        Elements.Caption unit = new Elements.Caption(
                font,
                () -> link.resource.kind().unit() + "/" + unitPeriod(link)
        );
        unit.h = 14;
        out.add(at(unit, rateWidth + 3, rateRow + 3, row.width - rateWidth - 3));

        addRow(out, row, new Elements.Caption(font, () -> "源面 · 从 " + link.fromLabel + " 的哪个面取出"), 1);
        addRow(out, row, sideChips(link, link.fromLabel, true, row.width), 6);
        addRow(out, row, new Elements.Caption(font, () -> "目标面 · 送入 " + link.toLabel + " 的哪个面"), 1);
        addRow(out, row, sideChips(link, link.toLabel, false, row.width), 6);

        // The same trap the power feed had: with no face named, SFM asks for the capability without a
        // direction, and many machines never answer that.
        Elements.Lines sideHint = new Elements.Lines(font, () -> {
            if (!link.fromSides.isUnset() || !link.toSides.isUnset()) return List.of();
            return List.of(
                    new Elements.Lines.Line("两面都是「不限」：SFM 会做无方向能力查询，", Theme.WARN),
                    new Elements.Lines.Line("通用机械等方块可能不响应，结果是搬不动东西", Theme.WARN)
            );
        });
        sideHint.h = 21;
        addRow(out, row, sideHint, 8);

        buildSharedBudgetWarning(out, row, link);

        addRow(out, row, new Elements.Section(font, "触发"), 4);
        addRow(out, row, new Elements.ChipGroup(font, row.width, List.of(
                new Elements.ChipGroup.Chip("每 N tick", () -> link.trigger == TriggerMode.TIMER,
                        () -> {
                            link.trigger = TriggerMode.TIMER;
                            screen.markDirty();
                        }, List.of("按时间间隔执行，速率单位是「每 tick」")),
                new Elements.ChipGroup.Chip("红石脉冲", () -> link.trigger == TriggerMode.REDSTONE_PULSE,
                        () -> {
                            link.trigger = TriggerMode.REDSTONE_PULSE;
                            screen.markDirty();
                        }, List.of("每次红石脉冲执行一次，速率单位是「每次脉冲」"))
        )), 6);

        Elements.Lines plan = new Elements.Lines(font, () -> screen.describePlan(link));
        plan.h = 22;
        addRow(out, row, plan, 8);

        addRow(out, row, Elements.Button.of(
                font,
                screen.advanced() ? "▾ 高级设置" : "▸ 高级设置",
                () -> {
                    screen.setAdvanced(!screen.advanced());
                    screen.rebuildUi();
                }
        ).width(row.width), 4);

        if (screen.advanced()) {
            buildAdvanced(out, row, link);
        }
    }

    private void buildAdvanced(List<Element> out, Row row, GraphLink link) {
        addRow(out, row, new Elements.Caption(font, () -> "槽位（如 0-8,10，留空表示全部）"), 1);
        int half = (row.width - 3) / 2;
        int slotsRow = row.next(14, 6);
        out.add(at(new Elements.Edit(font, link.fromSlots, "全部", half, 32, true,
                value -> {
                    link.fromSlots = value;
                    screen.markDirty();
                }, screen::attachEditBox, screen::detachEditBox), 0, slotsRow, half));
        out.add(at(new Elements.Edit(font, link.toSlots, "全部", half, 32, true,
                value -> {
                    link.toSlots = value;
                    screen.markDirty();
                }, screen::attachEditBox, screen::detachEditBox), half + 3, slotsRow, half));

        addRow(out, row, new Elements.Caption(font, () -> "RETAIN：源保留数量 / 目标上限"), 1);
        addRow(out, row, new Elements.Stepper(font, () -> link.retain, value -> {
            link.retain = value;
            screen.markDirty();
        }, 1, 0, Integer.MAX_VALUE, "不使用", screen::attachEditBox, screen::detachEditBox), 6);

        addRow(out, row, new Elements.Caption(font, () -> "限额方式"), 1);
        addRow(out, row, new Elements.ChipGroup(font, row.width, List.of(
                new Elements.ChipGroup.Chip("每方块独立", () -> link.fromEach, () -> {
                    link.fromEach = !link.fromEach;
                    link.toEach = link.fromEach;
                    screen.markDirty();
                }, List.of("FROM EACH / TO EACH：每个方块各自拥有一份限额")),
                new Elements.ChipGroup.Chip("每种资源独立", () -> link.quantityEach, () -> {
                    link.quantityEach = !link.quantityEach;
                    screen.markDirty();
                }, List.of("数量后加 EACH：每种物品各自一份限额，通常和 *模式* 一起用")),
                new Elements.ChipGroup.Chip("只填空槽位", () -> link.emptySlotsOnly, () -> {
                    link.emptySlotsOnly = !link.emptySlotsOnly;
                    screen.markDirty();
                }, List.of("TO EMPTY SLOTS IN：只往空槽位放"))
        )), 6);

        addRow(out, row, new Elements.Caption(font, () -> "轮流选择（同标签多个方块时）"), 1);
        addRow(out, row, new Elements.ChipGroup(font, row.width, List.of(
                roundRobinChip(link, RoundRobinMode.NONE, "关闭"),
                roundRobinChip(link, RoundRobinMode.BY_LABEL, "按标签"),
                roundRobinChip(link, RoundRobinMode.BY_BLOCK, "按方块")
        )), 6);

        addRow(out, row, new Elements.Caption(font, () -> "条件"), 1);
        addRow(out, row, new Elements.ChipGroup(font, row.width, List.of(
                conditionChip(link, LinkCondition.Kind.NONE, "无条件"),
                conditionChip(link, LinkCondition.Kind.REDSTONE, "有红石"),
                conditionChip(link, LinkCondition.Kind.SOURCE_HAS_GT, "库存 >")
        )), 4);
        if (link.condition.kind() != LinkCondition.Kind.NONE) {
            addRow(out, row, new Elements.Stepper(font, () -> link.condition.amount(), value -> {
                link.condition = new LinkCondition(link.condition.kind(), value);
                screen.markDirty();
            }, 1, 0, Integer.MAX_VALUE, "未设置", screen::attachEditBox, screen::detachEditBox), 6);
        }
    }

    // ------------------------------------------------------------------
    // Node
    // ------------------------------------------------------------------

    private void buildNode(List<Element> out, Row row, GraphNode node) {
        addRow(out, row, new Elements.Section(font, "节点"), 4);

        Elements.Lines info = new Elements.Lines(font, () -> {
            List<Elements.Lines.Line> lines = new ArrayList<>();
            LabelFacts.Fact fact = screen.fact(node.label);
            if (fact == null) {
                lines.add(new Elements.Lines.Line("标签 " + node.labelRef(), Theme.TEXT_TITLE));
                lines.add(new Elements.Lines.Line("磁盘上没有这个标签", Theme.WARN));
                return lines;
            }
            lines.add(new Elements.Lines.Line(
                    fact.blockName() == null ? "（区块未加载）" : fact.blockName(),
                    Theme.TEXT_TITLE
            ));
            lines.add(new Elements.Lines.Line("标签 " + node.labelRef(), Theme.TEXT_DIM));
            lines.add(new Elements.Lines.Line("位置 " + fact.coords() + "  ×" + fact.count(), Theme.TEXT_DIM));
            if (fact.notNextToCable() > 0) {
                // SFM skips each unconnected block on its own, so a label where only one of four
                // machines reaches a cable works for exactly one machine. Say so with numbers.
                lines.add(new Elements.Lines.Line(
                        "只有 " + fact.cableReach() + "/" + fact.count() + " 个方块紧贴线缆", Theme.WARN));
                lines.add(new Elements.Lines.Line(
                        "没紧贴线缆的会被 SFM 直接忽略（不会报错）", Theme.WARN));
            } else if (!fact.hasFacing()) {
                lines.add(new Elements.Lines.Line("无朝向属性：前/后/左/右 无效", Theme.WARN));
            }
            return lines;
        });
        info.h = 44;
        addRow(out, row, info, 8);

        addRow(out, row, new Elements.Caption(font, () -> "备注（随图谱保存）"), 1);
        addRow(out, row, new Elements.Edit(font, node.note, "写点什么…", row.width, 128, true,
                value -> {
                    node.note = value;
                    screen.markDirty();
                }, screen::attachEditBox, screen::detachEditBox), 8);

        buildEnergySection(out, row, node);

        addRow(out, row, Elements.Button.of(font, "从这里连出（作为源）", () -> screen.beginLinkFrom(node))
                                     .width(row.width), 3);
        addRow(out, row, Elements.Button.of(font, "连到这里（作为目标）", () -> screen.beginLinkTo(node))
                                     .width(row.width), 3);
        addRow(out, row, Elements.Button.of(font, "视图居中到这个节点", () -> screen.centerOnNode(node))
                                     .width(row.width), 3);
        addRow(out, row, Elements.Button.of(font, "删除节点及其连线", () -> screen.deleteSelection())
                                     .width(row.width), 3);
    }

    /**
     * Power supply. Declaring a node as an energy source is one click, and powering a machine is one
     * more — deliberately not a drawn link, because a dozen links from one generator would bury the
     * canvas. The compiler still emits the usual {@code INPUT/OUTPUT forge_energy} pair per machine.
     */
    private void buildEnergySection(List<Element> out, Row row, GraphNode node) {
        addRow(out, row, new Elements.Section(font, "能源"), 4);

        addRow(out, row, Elements.Button.toggle(
                font,
                node.energySource ? "✔ 已标记为能源节点" : "标记为能源节点",
                () -> node.energySource,
                () -> screen.toggleEnergySource(node),
                List.of("把这台设备声明为电源",
                        "其他节点就能在「供电来源」里选它，不需要画任何连线")
        ).width(row.width), 6);

        if (node.energySource) {
            Elements.Lines note = new Elements.Lines(font, () -> List.of(
                    new Elements.Lines.Line("这是电源：其他节点可以直接选它供电", Theme.WARN)
            ));
            note.h = 10;
            addRow(out, row, note, 6);
            return;
        }

        Elements.Lines status = new Elements.Lines(font, () -> {
            List<Elements.Lines.Line> lines = new ArrayList<>();
            if (!node.isPowered()) {
                lines.add(new Elements.Lines.Line("未接入供电", Theme.TEXT_DIM));
            } else {
                String problem = screen.powerProblem(node.label);
                lines.add(new Elements.Lines.Line(
                        "供电来自 " + node.poweredBy + " · " + node.powerRate + " FE/tick",
                        problem == null ? Theme.OK : Theme.ERROR
                ));
                if (problem != null) {
                    lines.add(new Elements.Lines.Line(problem, Theme.ERROR));
                } else {
                    lines.add(new Elements.Lines.Line("保存时自动生成，无需连线", Theme.TEXT_DIM));
                }
            }
            GraphLink drawn = screen.energyInput(node.label);
            if (drawn != null) {
                lines.add(new Elements.Lines.Line("另外还有一条手绘的电力连线（可能重复供电）", Theme.WARN));
            }
            return lines;
        });
        status.h = 32;
        addRow(out, row, status, 4);

        addRow(out, row, Elements.Button.of(font, "供电来源…", () -> screen.openPowerPicker(node))
                                     .width(row.width)
                                     .withTooltip(List.of("从已标记的能源节点里选一个给这台机器供电",
                                                          "不需要连线，保存时自动生成供电语句")), 4);

        if (node.isPowered()) {
            addRow(out, row, new Elements.Caption(font, () -> "供电方向（相对方块自身朝向）"), 1);
            addRow(out, row, powerSideChips(node, row.width), 6);

            addRow(out, row, new Elements.Caption(font, () -> "供电速率（FE/tick）"), 1);
            addRow(out, row, new Elements.Edit(
                    font,
                    node.powerRate,
                    "1000",
                    row.width,
                    24,
                    true,
                    value -> {
                        node.powerRate = value;
                        screen.markDirty();
                    },
                    screen::attachEditBox,
                    screen::detachEditBox
            ), 4);
            addRow(out, row, Elements.Button.of(font, "断开供电", () -> screen.clearPowerSource(node))
                                         .width(row.width), 4);
        }
    }

    /**
     * Warns when a link touches a label that covers several blocks without {@code EACH}.
     *
     * <p>This is the single most confusing thing SFM does: one budget is shared across every block
     * with that label, so the first machine eats it all and the others look broken. The fix is one
     * click, so it is offered right here rather than buried in the advanced section.
     */
    private void buildSharedBudgetWarning(List<Element> out, Row row, GraphLink link) {
        int fromCount = screen.blockCount(link.fromLabel);
        int toCount = screen.blockCount(link.toLabel);
        boolean fromNeeds = fromCount > 1 && !link.fromEach;
        boolean toNeeds = toCount > 1 && !link.toEach;
        if (!fromNeeds && !toNeeds) return;

        Elements.Lines warning = new Elements.Lines(font, () -> {
            List<Elements.Lines.Line> lines = new ArrayList<>();
            if (screen.blockCount(link.fromLabel) > 1 && !link.fromEach) {
                lines.add(new Elements.Lines.Line(
                        link.fromLabel + " 下有 " + screen.blockCount(link.fromLabel) + " 个方块：",
                        Theme.WARN));
                lines.add(new Elements.Lines.Line("只有其中一个会被抽取", Theme.WARN));
            }
            if (screen.blockCount(link.toLabel) > 1 && !link.toEach) {
                lines.add(new Elements.Lines.Line(
                        link.toLabel + " 下有 " + screen.blockCount(link.toLabel) + " 个方块：",
                        Theme.WARN));
                lines.add(new Elements.Lines.Line("只有其中一个会收到，其余机器不工作", Theme.WARN));
            }
            return lines;
        });
        warning.h = 22;
        addRow(out, row, warning, 4);

        addRow(out, row, Elements.Button.of(
                font,
                "让每台都搬一份（开启 EACH）",
                () -> {
                    if (fromNeeds) link.fromEach = true;
                    if (toNeeds) link.toEach = true;
                    screen.markDirty();
                    screen.rebuildUi();
                }
        ).width(row.width).withTooltip(List.of(
                "写成 FROM EACH / TO EACH",
                "这样每个方块各自拥有一份额度，而不是共抢一份"
        )), 4);
    }

    /**
     * Faces for the automatic power feed.
     *
     * <p>Naming a face matters more than it looks: with no side qualifier SFM queries the capability
     * without a direction, and plenty of machines — Mekanism's in particular, which is why SFM ships a
     * linter warning about exactly this — do not answer that query, so no power moves at all.
     */
    private Elements.ChipGroup powerSideChips(GraphNode node, int width) {
        List<Elements.ChipGroup.Chip> chips = new ArrayList<>();
        for (GraphSide side : new GraphSide[]{GraphSide.BACK, GraphSide.FRONT, GraphSide.LEFT,
                GraphSide.RIGHT, GraphSide.TOP, GraphSide.BOTTOM}) {
            chips.add(new Elements.ChipGroup.Chip(
                    side.glyph(),
                    () -> node.powerSides.contains(side),
                    () -> {
                        List<GraphSide> current = new ArrayList<>(
                                node.powerSides.mode() == SideSet.Mode.EXPLICIT
                                        ? node.powerSides.sides() : List.of());
                        if (!current.remove(side)) current.add(side);
                        screen.setPowerSides(node, SideSet.of(current));
                    },
                    List.of("相对方块自身朝向：" + side.sfml(),
                            "机器朝向不同时结果也不同")
            ));
        }
        chips.add(new Elements.ChipGroup.Chip(
                "不限",
                () -> node.powerSides.isUnset(),
                () -> screen.setPowerSides(node, SideSet.UNSET),
                List.of("不写面：SFM 做无方向查询",
                        "很多机器（尤其通用机械）不响应这种查询，可能完全供不上电")
        ));
        chips.add(new Elements.ChipGroup.Chip(
                "所有面",
                () -> node.powerSides.isEach(),
                () -> screen.setPowerSides(node, SideSet.EACH),
                List.of("EACH SIDE：六个方向加无方向逐个尝试",
                        "最不容易供不上电，但每 tick 的查询量最大")
        ));
        return new Elements.ChipGroup(font, width, chips);
    }

    private void buildHint(List<Element> out, Row row) {
        addRow(out, row, new Elements.Section(font, "检查器"), 4);
        Elements.Lines hint = new Elements.Lines(font, () -> List.of(
                new Elements.Lines.Line("点击节点查看方块信息", Theme.TEXT_DIM),
                new Elements.Lines.Line("点击连线编辑面与速率", Theme.TEXT_DIM),
                new Elements.Lines.Line("", Theme.TEXT_DIM),
                new Elements.Lines.Line("左键拖动节点移动", Theme.TEXT_DIM),
                new Elements.Lines.Line("从节点右侧圆点拖出连线", Theme.TEXT_DIM),
                new Elements.Lines.Line("中键拖动画布 · 滚轮缩放", Theme.TEXT_DIM),
                new Elements.Lines.Line("右键打开菜单", Theme.TEXT_DIM)
        ));
        hint.h = 70;
        addRow(out, row, hint, 0);
    }

    // ------------------------------------------------------------------
    // Pieces
    // ------------------------------------------------------------------

    /**
     * The resource kind chips. Chemicals are only offered when Mekanism is installed, since it is
     * Mekanism that registers SFM's chemical resource types; a link already using them keeps its chip
     * visible so it can be switched away from.
     */
    private List<Elements.ChipGroup.Chip> kindChips(GraphLink link) {
        List<Elements.ChipGroup.Chip> chips = new ArrayList<>();
        chips.add(kindChip(link, ResourceKind.ITEM));
        chips.add(kindChip(link, ResourceKind.FLUID));
        if (OptionalMods.hasChemicals() || link.resource.kind() == ResourceKind.CHEMICAL) {
            chips.add(kindChip(link, ResourceKind.CHEMICAL));
        }
        chips.add(kindChip(link, ResourceKind.ENERGY));
        return chips;
    }

    private Elements.ChipGroup.Chip kindChip(GraphLink link, ResourceKind kind) {
        return new Elements.ChipGroup.Chip(
                switch (kind) {
                    case ITEM -> "物品";
                    case FLUID -> "流体";
                    case CHEMICAL -> "化学品";
                    case ENERGY -> "电力";
                },
                () -> link.resource.kind() == kind,
                () -> {
                    link.resource = link.resource.withKind(kind);
                    if (kind == ResourceKind.ENERGY && link.rate.equals("1")) link.rate = "1000";
                    screen.markDirty();
                    screen.rebuildUi();
                },
                List.of(switch (kind) {
                    case ITEM -> "SFM 默认类型就是物品，可以不写资源 ID";
                    case FLUID -> "会生成 fluid:: 或 fluid:<id>";
                    case CHEMICAL -> "通用机械的化学品，会生成 chemical:: 或 chemical:<id>；单位 mB";
                    case ENERGY -> "Forge Energy，单位 FE；单个能量方块每 tick 有上限";
                })
        );
    }

    private List<Elements.ChipGroup.Chip> ratePresets(GraphLink link) {
        List<String> presets = link.resource.kind() == ResourceKind.ENERGY
                ? List.of("100", "1000", "10000", "100000")
                : List.of("1", "4", "16", "64", "256");
        List<Elements.ChipGroup.Chip> chips = new ArrayList<>();
        for (String preset : presets) {
            chips.add(new Elements.ChipGroup.Chip(preset, () -> link.rate.equals(preset), () -> {
                link.rate = preset;
                link.intervalTicks = 0;
                screen.markDirty();
            }, List.of("设为 " + preset + " " + link.resource.kind().unit() + " / " + unitPeriod(link))));
        }
        return chips;
    }

    private Elements.ChipGroup.Chip roundRobinChip(GraphLink link, RoundRobinMode mode, String label) {
        return new Elements.ChipGroup.Chip(label, () -> link.roundRobin == mode, () -> {
            link.roundRobin = mode;
            screen.markDirty();
        }, List.of("ROUND ROBIN：同标签下多个方块时轮流处理，而不是一次全部处理"));
    }

    private Elements.ChipGroup.Chip conditionChip(GraphLink link, LinkCondition.Kind kind, String label) {
        return new Elements.ChipGroup.Chip(label, () -> link.condition.kind() == kind, () -> {
            long amount = kind == LinkCondition.Kind.SOURCE_HAS_GT ? 100 : 0;
            link.condition = new LinkCondition(kind, amount);
            screen.markDirty();
            screen.rebuildUi();
        }, List.of(switch (kind) {
            case NONE -> "不做判断，直接搬运";
            case REDSTONE -> "IF REDSTONE THEN：有红石信号才搬运";
            case SOURCE_HAS_GT -> "IF <源> HAS GT n THEN：源库存超过 n 才搬运";
        }));
    }

    private Elements.ChipGroup sideChips(GraphLink link, String label, boolean source, int width) {
        Supplier<SideSet> get = () -> source ? link.fromSides : link.toSides;
        Consumer<SideSet> set = value -> {
            if (source) {
                link.fromSides = value;
            } else {
                link.toSides = value;
            }
            screen.markDirty();
        };
        LabelFacts.Fact fact = screen.fact(label);
        boolean hasFacing = fact == null || fact.hasFacing();

        List<Elements.ChipGroup.Chip> chips = new ArrayList<>();
        for (GraphSide side : new GraphSide[]{GraphSide.TOP, GraphSide.BOTTOM, GraphSide.NORTH,
                GraphSide.SOUTH, GraphSide.EAST, GraphSide.WEST}) {
            chips.add(sideChip(get, set, side, List.of("世界方向 " + side.sfml())));
        }
        for (GraphSide side : new GraphSide[]{GraphSide.FRONT, GraphSide.BACK, GraphSide.LEFT, GraphSide.RIGHT}) {
            chips.add(sideChip(get, set, side, hasFacing
                    ? List.of("跟随方块自身朝向：" + side.sfml())
                    : List.of("该方块没有朝向属性，这个面不会生效")));
        }
        chips.add(new Elements.ChipGroup.Chip("所有面", () -> get.get().isEach(),
                () -> set.accept(SideSet.EACH),
                List.of("EACH SIDE：六个方向逐个尝试（查询量最大）")));
        chips.add(new Elements.ChipGroup.Chip("不限", () -> get.get().isUnset(),
                () -> set.accept(SideSet.UNSET),
                List.of("不写面：交给方块自己判断，多数机器可以直接用")));
        return new Elements.ChipGroup(font, width, chips);
    }

    private Elements.ChipGroup.Chip sideChip(
            Supplier<SideSet> get,
            Consumer<SideSet> set,
            GraphSide side,
            List<String> tooltip
    ) {
        return new Elements.ChipGroup.Chip(
                side.glyph(),
                () -> get.get().contains(side),
                () -> {
                    List<GraphSide> current = new ArrayList<>(
                            get.get().mode() == SideSet.Mode.EXPLICIT ? get.get().sides() : List.of());
                    if (!current.remove(side)) current.add(side);
                    set.accept(SideSet.of(current));
                },
                tooltip
        );
    }

    private static String unitPeriod(GraphLink link) {
        return link.trigger == TriggerMode.TIMER ? "tick" : "次脉冲";
    }

    private static double ratePosition(GraphLink link) {
        try {
            return RateScale.toPosition(new BigDecimal(link.rate.trim()).doubleValue());
        } catch (RuntimeException e) {
            return RateScale.toPosition(1);
        }
    }

    private void pickFilterFromHand(GraphLink link) {
        if (Minecraft.getInstance().player == null) return;
        ItemStack held = Minecraft.getInstance().player.getMainHandItem();
        if (held.isEmpty()) {
            screen.toast("主手没有物品");
            return;
        }
        switch (link.resource.kind()) {
            case ITEM -> link.resource = link.resource.withFilter(
                    BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
            case FLUID -> {
                FluidStack fluid = FluidUtil.getFluidContained(held).orElse(FluidStack.EMPTY);
                if (fluid.isEmpty()) {
                    screen.toast("主手物品里没有流体");
                    return;
                }
                link.resource = link.resource.withFilter(
                        BuiltInRegistries.FLUID.getKey(fluid.getFluid()).getPath());
            }
            case ENERGY -> {
                screen.toast("电力不需要过滤器");
                return;
            }
        }
        screen.markDirty();
        screen.rebuildUi();
    }
}
