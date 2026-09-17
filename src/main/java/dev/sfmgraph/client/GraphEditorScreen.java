package dev.sfmgraph.client;

import ca.teamdman.sfm.client.screen.SFMScreenChangeHelpers;
import ca.teamdman.sfm.client.screen.text_editor.ISFMTextEditScreen;
import ca.teamdman.sfm.client.text_editor.ISFMTextEditScreenOpenContext;
import ca.teamdman.sfm.common.config.SFMConfig;
import ca.teamdman.sfml.ast.Program;
import ca.teamdman.sfml.program_builder.ProgramBuildResult;
import ca.teamdman.sfml.program_builder.ProgramBuilder;
import dev.sfmgraph.SFMGraph;
import dev.sfmgraph.client.ui.CanvasView;
import dev.sfmgraph.client.ui.Draw;
import dev.sfmgraph.client.ui.Element;
import dev.sfmgraph.client.ui.Elements;
import dev.sfmgraph.client.ui.ItemPickerPopup;
import dev.sfmgraph.client.ui.MenuBar;
import dev.sfmgraph.client.ui.Modal;
import dev.sfmgraph.client.ui.Theme;
import dev.sfmgraph.graph.GraphCodec;
import dev.sfmgraph.graph.GraphLink;
import dev.sfmgraph.graph.GraphModel;
import dev.sfmgraph.graph.GraphNode;
import dev.sfmgraph.graph.LabelSummary;
import dev.sfmgraph.graph.ResourceKind;
import dev.sfmgraph.graph.ResourceSpec;
import dev.sfmgraph.graph.SfmlCompiler;
import dev.sfmgraph.graph.SideSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The visual program editor.
 *
 * <p>Registered as an SFM text editor, so it opens from the manager's own edit button once it is
 * selected in the SFM client config ({@code preferredEditor = "sfmgraph:graph"}), or directly with
 * the mod's keybind while a manager screen is open.
 *
 * <p>The graph is compiled to SFML on every change, so the generated program, the per tick totals and
 * the warnings are always in sync with what is on screen.
 */
public class GraphEditorScreen extends Screen implements ISFMTextEditScreen {

    private static final int INSPECTOR_PADDING = 8;
    private static final int INSPECTOR_FOOTER = 50;

    private final ISFMTextEditScreenOpenContext openContext;
    private final GraphModel graph = new GraphModel();
    private final Map<String, LabelFacts.Fact> facts = new LinkedHashMap<>();
    private final CanvasView view = new CanvasView();
    private final MenuBar menuBar;
    private final Inspector inspector;
    private final SfmlCompiler.Limits limits;

    /** An inspector element plus its unscrolled position. */
    private record Placed(Element element, int baseX, int baseY) {
    }

    private final List<Placed> placed = new ArrayList<>();
    private boolean uiBuilt;

    private final Set<String> selectedNodes = new LinkedHashSet<>();
    private final Set<String> selectedLinks = new LinkedHashSet<>();
    private @Nullable String primaryNode;
    private @Nullable String primaryLink;

    private enum Drag { NONE, NODE, PAN, LINK, MARQUEE, SLIDER }

    private Drag drag = Drag.NONE;
    private @Nullable GraphNode draggingNode;
    private double dragGrabX;
    private double dragGrabY;
    private double lastMouseX;
    private double lastMouseY;
    private @Nullable String linkingFrom;
    private double linkFromX;
    private double linkFromY;
    private double marqueeX0;
    private double marqueeY0;
    private double marqueeX1;
    private double marqueeY1;
    private double mouseGraphX;
    private double mouseGraphY;
    private @Nullable Element activeSlider;

    private boolean advanced;
    private boolean showGrid = true;
    private boolean snapToGrid = true;
    private boolean previewOpen = true;
    private int inspectorScroll;
    private int previewScroll;

    private List<SfmlCompiler.Plan> plans = List.of();
    private final Map<String, SfmlCompiler.Plan> plansByLinkId = new HashMap<>();
    private List<String> warnings = List.of();
    private String generatedText = "";

    private final Deque<GraphModel> undoStack = new ArrayDeque<>();
    private final Deque<GraphModel> redoStack = new ArrayDeque<>();

    private String toast = "";
    private long toastUntil;
    private @Nullable List<String> barTooltip;

    private @Nullable List<MenuBar.Item> popupItems;
    private int popupX;
    private int popupY;
    private int popupWidth;
    private @Nullable Modal modal;
    private @Nullable List<int[]> modalButtonRects;
    private @Nullable ItemPickerPopup picker;

    private int lastViewportWidth = -1;
    private int lastViewportHeight = -1;
    private boolean lastPreviewOpen;

    private final String originalProgram;
    private boolean originalHadGraph;
    private boolean confirmedOverwrite;

    public GraphEditorScreen(ISFMTextEditScreenOpenContext openContext) {
        super(Component.translatable("sfmgraph.editor.title"));
        this.openContext = openContext;
        this.originalProgram = openContext.initialValue() == null ? "" : openContext.initialValue();
        this.originalHadGraph = GraphCodec.hasGraph(originalProgram);
        this.limits = readLimits();
        this.inspector = new Inspector(this);
        // The inherited font field is only assigned in init(), so build the menu from the live font.
        this.menuBar = new MenuBar(Minecraft.getInstance().font, buildMenus());
        loadGraph();
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private static SfmlCompiler.Limits readLimits() {
        try {
            var server = SFMConfig.SERVER_CONFIG;
            int timer = SFMConfig.getOrFallback(
                    server.timerTriggerMinimumIntervalInTicks,
                    SfmlCompiler.DEFAULT_TIMER_MIN_INTERVAL_TICKS
            );
            int energy = SFMConfig.getOrFallback(
                    server.timerTriggerMinimumIntervalInTicksWhenOnlyForgeEnergyIO,
                    SfmlCompiler.DEFAULT_ENERGY_MIN_INTERVAL_TICKS
            );
            return new SfmlCompiler.Limits(timer, energy);
        } catch (Throwable t) {
            SFMGraph.LOGGER.warn("Could not read SFM's trigger interval limits, using defaults", t);
            return SfmlCompiler.Limits.SFM_DEFAULTS;
        }
    }

    private void loadGraph() {
        facts.clear();
        facts.putAll(LabelFacts.collect(openContext.labelPositionHolder()));

        GraphModel decoded = GraphCodec.decodeProgram(originalProgram);
        if (decoded != null) {
            graph.name = decoded.name;
            graph.nodes.addAll(decoded.nodes);
            graph.links.addAll(decoded.links);
        } else {
            graph.name = "";
            // A hand written program: surface the labels it mentions as loose nodes.
            graph.syncLabels(GraphCodec.guessLabels(originalProgram));
            if (!originalProgram.isBlank() && !graph.nodes.isEmpty()) {
                toast("已从现有程序识别出 " + graph.nodes.size() + " 个标签");
            }
        }
        graph.syncLabels(facts.keySet());
        recompile();
    }

    @Override
    protected void init() {
        closePicker();
        clearWidgets();
        placed.clear();
        rebuildUi();
        uiBuilt = true;
    }

    /** Opens the searchable item/fluid picker for a link's filter field. */
    public void openPicker(GraphLink link) {
        ResourceKind kind = link.resource.kind();
        if (kind == ResourceKind.ENERGY) {
            toast("电力不需要过滤器");
            return;
        }
        closePicker();
        picker = new ItemPickerPopup(
                font,
                kind,
                link.resource.filter(),
                id -> {
                    link.resource = link.resource.withFilter(id);
                    closePicker();
                    markDirty();
                    rebuildUi();
                },
                this::closePicker,
                this::attachEditBox,
                this::detachEditBox
        );
        // It unfolds just left of the inspector, so it never covers the field it fills in.
        picker.layout(inspectorLeft() - 440, width, height);
        picker.searchBox().setFocused(true);
        setFocused(picker.searchBox());
    }

    public void closePicker() {
        if (picker == null) return;
        ItemPickerPopup closing = picker;
        picker = null;
        closing.dispose();
        if (getFocused() == closing.searchBox()) setFocused(null);
    }

    public boolean isPickerOpen() {
        return picker != null;
    }

    // ------------------------------------------------------------------
    // API used by the inspector
    // ------------------------------------------------------------------

    public GraphModel graph() {
        return graph;
    }

    public boolean advanced() {
        return advanced;
    }

    public void setAdvanced(boolean value) {
        advanced = value;
    }

    public @Nullable GraphLink selectedLink() {
        return primaryLink == null ? null : graph.link(primaryLink);
    }

    public @Nullable GraphNode selectedNode() {
        return primaryNode == null ? null : graph.node(primaryNode);
    }

    public @Nullable LabelFacts.Fact fact(String label) {
        return label == null ? null : facts.get(label);
    }

    /** How many blocks carry a label. Unknown labels read as one so nothing is claimed falsely. */
    public int blockCount(String label) {
        LabelFacts.Fact fact = facts.get(label);
        return fact == null ? 1 : fact.count();
    }

    /** Label to what we know about it, for the compiler's shared budget and cable warnings. */
    private Map<String, LabelSummary> labelSummaries() {
        Map<String, LabelSummary> summaries = new HashMap<>();
        facts.forEach((label, fact) -> summaries.put(
                label,
                new LabelSummary(fact.count(), fact.cableReach())
        ));
        return summaries;
    }

    public SfmlCompiler.Limits limits() {
        return limits;
    }

    public void attachEditBox(EditBox box) {
        addRenderableWidget(box);
    }

    public void detachEditBox(EditBox box) {
        removeWidget(box);
        if (getFocused() == box) setFocused(null);
    }

    /** Recompiles the program and refreshes live values; does not touch the layout. */
    public void markDirty() {
        recompile();
    }

    /** Rebuilds the inspector contents, for changes that alter which rows exist. */
    public void rebuildUi() {
        for (Placed item : placed) {
            item.element().onRemoved();
        }
        placed.clear();
        for (Element element : inspector.build(Theme.INSPECTOR_WIDTH - INSPECTOR_PADDING * 2)) {
            placed.add(new Placed(element, element.x, element.y));
        }
        layoutElements();
    }

    public void toast(String message) {
        toast = message;
        toastUntil = System.currentTimeMillis() + 2600;
    }

    /** Live text for the inspector's plan preview. */
    public List<Elements.Lines.Line> describePlan(GraphLink link) {
        SfmlCompiler.Plan plan = plansByLinkId.get(link.id);
        if (plan == null) plan = SfmlCompiler.compileLink(graph, link, limits, null, labelSummaries());
        List<Elements.Lines.Line> lines = new ArrayList<>();
        if (!plan.compiled()) {
            lines.add(new Elements.Lines.Line("未生效：" + plan.problem(), Theme.ERROR));
            return lines;
        }
        String unit = link.resource.kind().unit();
        if (link.trigger.isTimer()) {
            lines.add(new Elements.Lines.Line(
                    "每 " + plan.intervalTicks() + " tick 搬 " + plan.amountPerFiring() + " " + unit
                    + (plan.isExact() ? "（精确）" : "（近似）"),
                    plan.isExact() ? Theme.OK : Theme.WARN
            ));
        } else {
            lines.add(new Elements.Lines.Line(
                    "每次红石脉冲搬 " + plan.amountPerFiring() + " " + unit,
                    Theme.OK
            ));
        }
        for (String line : plan.lines()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--") || trimmed.equals("END")) continue;
            if (trimmed.startsWith("EVERY")) continue;
            lines.add(new Elements.Lines.Line(trimmed, Theme.TEXT_DIM));
        }
        return lines;
    }

    public void deleteSelection() {
        if (selectedLinks.isEmpty() && selectedNodes.isEmpty()) return;
        pushUndo();
        for (String id : new ArrayList<>(selectedLinks)) {
            graph.removeLink(id);
        }
        for (String label : new ArrayList<>(selectedNodes)) {
            graph.removeNode(label);
        }
        selectedLinks.clear();
        selectedNodes.clear();
        primaryLink = null;
        primaryNode = null;
        rebuildUi();
        recompile();
    }

    public void beginLinkFrom(GraphNode node) {
        beginLink(node.label);
    }

    public void beginLinkTo(GraphNode node) {
        selectNode(node.label, false);
        toast("从别的节点右侧圆点拖到本节点即可连线");
    }

    public void centerOnNode(GraphNode node) {
        view.centerOn(node.x + Theme.NODE_WIDTH / 2, node.y + Theme.NODE_HEIGHT / 2);
    }

    // ------------------------------------------------------------------
    // Compilation
    // ------------------------------------------------------------------

    private void recompile() {
        SfmlCompiler.Options options = new SfmlCompiler.Options(
                limits,
                GraphCodec.encode(graph),
                SFMGraph.MOD_NAME + " " + SFMGraph.version,
                labelSummaries()
        );
        SfmlCompiler.Result result = SfmlCompiler.compile(graph, options);
        generatedText = result.text();
        warnings = result.warnings();
        plans = result.plans();
        plansByLinkId.clear();
        for (SfmlCompiler.Plan plan : plans) {
            plansByLinkId.put(plan.link().id, plan);
        }
    }

    /** Runs SFM's own compiler over the generated program and returns its errors. */
    public List<String> validateProgram() {
        List<String> errors = new ArrayList<>();
        try {
            ProgramBuildResult result = new ProgramBuilder(generatedText).useCache(false).build();
            for (TranslatableContents contents : result.metadata().errors()) {
                errors.add(Component.translatable(contents.getKey(), contents.getArgs()).getString());
            }
        } catch (Throwable t) {
            errors.add("校验时发生异常：" + t.getClass().getSimpleName() + " " + t.getMessage());
        }
        return errors;
    }

    // ------------------------------------------------------------------
    // Undo
    // ------------------------------------------------------------------

    private void pushUndo() {
        undoStack.push(graph.copy());
        while (undoStack.size() > 64) undoStack.removeLast();
        redoStack.clear();
    }

    private void applySnapshot(GraphModel snapshot) {
        graph.clear();
        graph.name = snapshot.name;
        for (GraphNode node : snapshot.nodes) graph.nodes.add(node.copy());
        for (GraphLink link : snapshot.links) graph.links.add(link.copy());
        revalidateSelection();
        rebuildUi();
        recompile();
    }

    private void revalidateSelection() {
        selectedNodes.removeIf(label -> !graph.hasLabel(label));
        selectedLinks.removeIf(id -> graph.link(id) == null);
        if (primaryNode != null && !graph.hasLabel(primaryNode)) primaryNode = null;
        if (primaryLink != null && graph.link(primaryLink) == null) primaryLink = null;
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            toast("没有可撤销的操作");
            return;
        }
        redoStack.push(graph.copy());
        applySnapshot(undoStack.pop());
        toast("已撤销");
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            toast("没有可重做的操作");
            return;
        }
        undoStack.push(graph.copy());
        applySnapshot(redoStack.pop());
        toast("已重做");
    }

    // ------------------------------------------------------------------
    // Menus
    // ------------------------------------------------------------------

    private List<MenuBar.Menu> buildMenus() {
        return List.of(
                new MenuBar.Menu("文件", List.of(
                        MenuBar.Item.of("新建空白图谱", this::newGraph),
                        MenuBar.Item.of("从标签重建节点", this::resyncNodes),
                        MenuBar.Item.separator(),
                        MenuBar.Item.of("保存到磁盘            Ctrl+S", () -> requestSave(false)),
                        MenuBar.Item.of("保存并退出", () -> requestSave(true)),
                        MenuBar.Item.of("用 SFM 编译器校验", this::runValidation),
                        MenuBar.Item.of("设为管理器的默认编辑器", this::useAsDefaultEditor),
                        MenuBar.Item.separator(),
                        MenuBar.Item.of("复制生成的程序", () -> {
                            setClipboard(generatedText);
                            toast("程序已复制到剪贴板");
                        }),
                        MenuBar.Item.of("复制原程序", () -> {
                            setClipboard(originalProgram);
                            toast("原程序已复制到剪贴板");
                        }),
                        MenuBar.Item.separator(),
                        MenuBar.Item.of("关闭", this::onClose)
                )),
                new MenuBar.Menu("编辑", List.of(
                        MenuBar.Item.of("撤销                  Ctrl+Z", this::undo),
                        MenuBar.Item.of("重做                  Ctrl+Y", this::redo),
                        MenuBar.Item.separator(),
                        MenuBar.Item.of("全选                  Ctrl+A", this::selectAll),
                        MenuBar.Item.of("删除选中              Del", this::deleteSelection),
                        MenuBar.Item.separator(),
                        MenuBar.Item.of("反转选中连线方向", this::reverseSelected),
                        MenuBar.Item.of("启用/禁用选中连线", this::toggleSelectedEnabled)
                )),
                new MenuBar.Menu("视图", List.of(
                        MenuBar.Item.toggle("显示网格", () -> showGrid, () -> showGrid = !showGrid),
                        MenuBar.Item.toggle("对齐网格", () -> snapToGrid, () -> snapToGrid = !snapToGrid),
                        MenuBar.Item.toggle("显示程序预览", () -> previewOpen, () -> {
                            previewOpen = !previewOpen;
                            previewScroll = 0;
                        }),
                        MenuBar.Item.separator(),
                        MenuBar.Item.of("适应视图              F", () -> view.fitTo(graph)),
                        MenuBar.Item.of("重置缩放", view::reset)
                )),
                new MenuBar.Menu("连线", List.of(
                        MenuBar.Item.of("选中连线速率设为 1", () -> setSelectedRate("1")),
                        MenuBar.Item.of("选中连线速率设为 4", () -> setSelectedRate("4")),
                        MenuBar.Item.of("选中连线速率设为 16", () -> setSelectedRate("16")),
                        MenuBar.Item.of("选中连线速率设为 64", () -> setSelectedRate("64")),
                        MenuBar.Item.separator(),
                        MenuBar.Item.of("选中连线改为物品", () -> setSelectedKind(ResourceKind.ITEM)),
                        MenuBar.Item.of("选中连线改为流体", () -> setSelectedKind(ResourceKind.FLUID)),
                        MenuBar.Item.of("选中连线改为电力", () -> setSelectedKind(ResourceKind.ENERGY))
                )),
                new MenuBar.Menu("帮助", List.of(
                        MenuBar.Item.of("速率与面是怎么变成程序的", this::showHelp),
                        MenuBar.Item.of("用 SFM 编译器校验", this::runValidation),
                        MenuBar.Item.separator(),
                        MenuBar.Item.of("关于 " + SFMGraph.MOD_NAME, this::showAbout)
                ))
        );
    }

    private void newGraph() {
        pushUndo();
        graph.clear();
        graph.syncLabels(facts.keySet());
        selectedNodes.clear();
        selectedLinks.clear();
        primaryLink = null;
        primaryNode = null;
        rebuildUi();
        recompile();
    }

    private void resyncNodes() {
        pushUndo();
        List<String> added = graph.syncLabels(facts.keySet());
        rebuildUi();
        recompile();
        toast(added.isEmpty() ? "没有发现新标签" : "新增 " + added.size() + " 个节点");
    }

    private void reverseSelected() {
        if (selectedLinks.isEmpty()) {
            toast("先选中一条连线");
            return;
        }
        pushUndo();
        for (String id : selectedLinks) {
            GraphLink link = graph.link(id);
            if (link == null) continue;
            String from = link.fromLabel;
            link.fromLabel = link.toLabel;
            link.toLabel = from;
            var sides = link.fromSides;
            link.fromSides = link.toSides;
            link.toSides = sides;
        }
        recompile();
    }

    private void toggleSelectedEnabled() {
        if (selectedLinks.isEmpty()) {
            toast("先选中一条连线");
            return;
        }
        pushUndo();
        boolean enable = selectedLinks.stream().map(graph::link)
                                     .anyMatch(link -> link != null && !link.enabled);
        for (String id : selectedLinks) {
            GraphLink link = graph.link(id);
            if (link != null) link.enabled = enable;
        }
        recompile();
    }

    private void setSelectedRate(String rate) {
        if (selectedLinks.isEmpty()) {
            toast("先选中一条连线");
            return;
        }
        pushUndo();
        for (String id : selectedLinks) {
            GraphLink link = graph.link(id);
            if (link == null) continue;
            link.rate = rate;
            link.intervalTicks = 0;
        }
        recompile();
        toast("已更新 " + selectedLinks.size() + " 条连线");
    }

    private void setSelectedKind(ResourceKind kind) {
        if (selectedLinks.isEmpty()) {
            toast("先选中一条连线");
            return;
        }
        pushUndo();
        for (String id : selectedLinks) {
            GraphLink link = graph.link(id);
            if (link != null) link.resource = link.resource.withKind(kind);
        }
        rebuildUi();
        recompile();
    }

    private void selectAll() {
        selectedNodes.clear();
        selectedLinks.clear();
        for (GraphNode node : graph.nodes) selectedNodes.add(node.label);
        for (GraphLink link : graph.links) selectedLinks.add(link.id);
        primaryNode = graph.nodes.isEmpty() ? null : graph.nodes.get(0).label;
        primaryLink = null;
        rebuildUi();
    }

    private void showHelp() {
        modal = new Modal(
                "速率与面是怎么变成程序的",
                List.of(
                        "SFM 没有「每 tick 传输多少」这种设置：",
                        "一条语句每次执行搬走固定数量，靠触发间隔决定频率。",
                        "",
                        "所以编辑器把「速率」翻译成：每 N tick 搬 (速率 × N) 个。",
                        "服务器默认要求 N ≥ " + limits.timerMinIntervalTicks()
                        + " tick（只搬电力时可以为 " + limits.energyMinIntervalTicks() + "），",
                        "编辑器会在允许范围内挑最小的 N，让速率能被整除，",
                        "得到精确的平均速率，而不是四舍五入。",
                        "",
                        "面则直接写进语句：",
                        "  INPUT 80 FROM a TOP, NORTH SIDE SLOTS 0-3",
                        "  OUTPUT TO b WEST SIDE",
                        "「不限」= 不写面，让方块自己判断（多数机器可用）；",
                        "「所有面」= EACH SIDE，六个方向逐个尝试，开销最大。",
                        "",
                        "每条连线生成一个独立触发器：有自己的间隔，",
                        "也不会和别的连线抢同一次执行的限额。"
                ),
                List.of(MenuBar.Item.of("知道了", () -> modal = null))
        );
    }

    private void showAbout() {
        modal = new Modal(
                SFMGraph.MOD_NAME + " " + SFMGraph.version,
                List.of(
                        "给超级工厂管理器加上可视化图谱编辑。",
                        "图谱数据以注释形式存在程序里，跟着磁盘走。",
                        "",
                        "SFM 版本：" + sfmVersion(),
                        "服务器最小间隔：" + limits.timerMinIntervalTicks() + " tick"
                        + "（仅电力 " + limits.energyMinIntervalTicks() + " tick）",
                        "当前程序 " + generatedText.length() + " / " + Program.MAX_PROGRAM_LENGTH + " 字符"
                ),
                List.of(MenuBar.Item.of("关闭", () -> modal = null))
        );
    }

    private static String sfmVersion() {
        try {
            return net.neoforged.fml.ModList.get()
                    .getModContainerById("sfm")
                    .map(container -> String.valueOf(container.getModInfo().getVersion()))
                    .orElse("未知");
        } catch (Throwable t) {
            return "未知";
        }
    }

    /** Switches SFM's own client config over, so the manager's edit button opens this editor. */
    private void useAsDefaultEditor() {
        try {
            SFMConfig.CLIENT_TEXT_EDITOR_CONFIG.preferredEditor.set(SFMGraph.MOD_ID + ":graph");
            modal = new Modal(
                    "已设为管理器的默认编辑器",
                    List.of(
                            "SFM 客户端配置里的 preferredEditor 现在指向 " + SFMGraph.MOD_ID + ":graph，",
                            "管理器界面上的编辑按钮会直接打开图谱编辑器。",
                            "",
                            "如果重启后没有保留，请手动修改：",
                            "config/sfm-client-program-editor.toml",
                            "preferredEditor = \"" + SFMGraph.MOD_ID + ":graph\"",
                            "",
                            "在那之前，可以在管理器界面里按 Ctrl+G 直接打开图谱。"
                    ),
                    List.of(MenuBar.Item.of("好", () -> modal = null))
            );
        } catch (Throwable t) {
            modal = new Modal(
                    "设置失败",
                    List.of("写入 SFM 配置时出错：", String.valueOf(t.getMessage()), "",
                            "请手动修改 config/sfm-client-program-editor.toml"),
                    List.of(MenuBar.Item.of("好", () -> modal = null))
            );
        }
    }

    private void runValidation() {
        List<String> errors = validateProgram();
        if (errors.isEmpty()) {
            modal = new Modal(
                    "校验通过",
                    List.of(
                            "SFM 的编译器接受了当前生成的程序。",
                            "",
                            "连线 " + graph.links.size() + " 条，节点 " + graph.nodes.size() + " 个",
                            "程序 " + generatedText.length() + " / " + Program.MAX_PROGRAM_LENGTH + " 字符"
                    ),
                    List.of(MenuBar.Item.of("好", () -> modal = null))
            );
            return;
        }
        modal = new Modal("校验未通过", errorLines(errors), List.of(
                MenuBar.Item.of("返回修改", () -> modal = null),
                MenuBar.Item.of("仍然保存", () -> {
                    modal = null;
                    doSave(false);
                })
        ));
    }

    private static List<String> errorLines(List<String> errors) {
        List<String> lines = new ArrayList<>();
        lines.add("SFM 编译器报了 " + errors.size() + " 个问题：");
        lines.add("");
        lines.addAll(errors.subList(0, Math.min(errors.size(), 8)));
        return lines;
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    /** @param closeAfter true for "save and exit"; false keeps the editor open */
    private void requestSave(boolean closeAfter) {
        if (!originalHadGraph && !originalProgram.isBlank() && !confirmedOverwrite) {
            modal = new Modal(
                    "原程序会被覆盖",
                    List.of(
                            "这个磁盘上的程序不是图谱生成的，",
                            "里面可能有手工写的语句。",
                            "",
                            "保存会用当前图谱重新生成整个程序。",
                            "想保留原内容的话，先用",
                            "「文件 → 复制原程序」复制到剪贴板。"
                    ),
                    List.of(
                            MenuBar.Item.of("返回", () -> modal = null),
                            MenuBar.Item.of("覆盖保存", () -> {
                                confirmedOverwrite = true;
                                modal = null;
                                requestSave(closeAfter);
                            })
                    )
            );
            return;
        }
        if (generatedText.length() > Program.MAX_PROGRAM_LENGTH) {
            modal = new Modal(
                    "程序太长",
                    List.of(
                            "生成结果 " + generatedText.length() + " 字符，超过 SFM 上限 "
                            + Program.MAX_PROGRAM_LENGTH + "。",
                            "",
                            "请减少连线，或者把速率位数写小一点。"
                    ),
                    List.of(MenuBar.Item.of("好", () -> modal = null))
            );
            return;
        }
        List<String> errors = validateProgram();
        if (!errors.isEmpty()) {
            modal = new Modal("先确认一下", errorLines(errors), List.of(
                    MenuBar.Item.of("返回修改", () -> modal = null),
                    MenuBar.Item.of("仍然保存", () -> {
                        modal = null;
                        doSave(closeAfter);
                    })
            ));
            return;
        }
        doSave(closeAfter);
    }

    /**
     * @param closeAfter false writes the program and stays open, which is what Ctrl+S and the plain
     *                   save button do. SFM's own context saves and closes in one step, so the two
     *                   are kept apart here on purpose.
     */
    private void doSave(boolean closeAfter) {
        if (closeAfter) {
            openContext.onSaveAndClose(generatedText);
            return;
        }
        openContext.saveWriter().accept(generatedText);
        // The disk now holds a graph, so a later save must not warn about clobbering a hand written
        // program again.
        originalHadGraph = true;
        confirmedOverwrite = true;
        toast("已保存到磁盘");
    }

    private void setClipboard(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private int canvasLeft() {
        return 0;
    }

    private int canvasTop() {
        return Theme.MENU_HEIGHT;
    }

    private int canvasRight() {
        return Math.max(120, width - Theme.INSPECTOR_WIDTH);
    }

    private int canvasBottom() {
        return height - Theme.STATUS_HEIGHT - (previewOpen ? Theme.PREVIEW_HEIGHT : 0);
    }

    private int inspectorLeft() {
        return canvasRight();
    }

    private int inspectorTop() {
        return Theme.MENU_HEIGHT + INSPECTOR_PADDING;
    }

    private int inspectorBottom() {
        return height - Theme.STATUS_HEIGHT;
    }

    private int previewTop() {
        return canvasBottom();
    }

    private void ensureLayout() {
        int vw = canvasRight() - canvasLeft();
        int vh = canvasBottom() - canvasTop();
        if (vw != lastViewportWidth || vh != lastViewportHeight || previewOpen != lastPreviewOpen || !uiBuilt) {
            view.setViewport(canvasLeft(), canvasTop(), Math.max(1, vw), Math.max(1, vh));
            lastViewportWidth = vw;
            lastViewportHeight = vh;
            lastPreviewOpen = previewOpen;
            if (!uiBuilt) {
                rebuildUi();
                uiBuilt = true;
            } else {
                layoutElements();
            }
        }
    }

    private void layoutElements() {
        int panelX = inspectorLeft() + INSPECTOR_PADDING;
        int top = inspectorTop() - inspectorScroll;
        int clipTop = inspectorTop() - 4;
        int clipBottom = inspectorBottom() - INSPECTOR_FOOTER;
        for (Placed item : placed) {
            Element element = item.element();
            element.x = item.baseX() + panelX;
            element.y = item.baseY() + top;
            boolean visible = element.y + element.h > clipTop && element.y < clipBottom;
            element.syncWidget(visible);
        }
    }

    private int inspectorContentHeight() {
        int bottom = 0;
        for (Placed item : placed) {
            bottom = Math.max(bottom, item.baseY() + item.element().h);
        }
        return bottom;
    }

    private int maxInspectorScroll() {
        int viewport = inspectorBottom() - INSPECTOR_FOOTER - inspectorTop();
        return Math.max(0, inspectorContentHeight() - viewport);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ensureLayout();
        renderBackground(graphics, mouseX, mouseY, partialTick);

        renderCanvas(graphics, mouseX, mouseY);
        renderInspector(graphics, mouseX, mouseY);
        if (previewOpen) renderPreview(graphics);
        renderStatusBar(graphics);

        // Drawn before the widget pass so the picker's search field lands on top of its panel.
        if (picker != null) {
            picker.layout(inspectorLeft() - 348, width, height);
            picker.render(graphics, mouseX, mouseY);
        }

        // Vanilla widgets (the text fields) draw above the panel they belong to.
        for (var renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }

        menuBar.updateHover(mouseX, mouseY);
        menuBar.render(graphics, width);
        renderBarButtons(graphics, mouseX, mouseY);

        renderTooltips(graphics, mouseX, mouseY);
        menuBar.renderDropdown(graphics, mouseX, mouseY, width);
        renderPopup(graphics);
        renderModal(graphics, mouseX, mouseY);
    }

    private void renderCanvas(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = canvasLeft();
        int top = canvasTop();
        int right = canvasRight();
        int bottom = canvasBottom();
        graphics.fill(left, top, right, bottom, Theme.CANVAS_BG);
        graphics.enableScissor(left, top, right, bottom);

        graphics.pose().pushPose();
        // Pan/zoom comes from the view so the drawing transform and the mouse mapping cannot drift
        // apart; see CanvasView.applyTo for the invariant.
        view.applyPose(graphics);

        if (showGrid) renderGrid(graphics);
        // Unselected links first, so the selected one is never hidden under its neighbours.
        for (GraphLink link : graph.links) {
            if (!selectedLinks.contains(link.id)) renderLink(graphics, link);
        }
        for (GraphLink link : graph.links) {
            if (selectedLinks.contains(link.id)) renderLink(graphics, link);
        }
        renderRubberBand(graphics);
        for (GraphNode node : graph.nodes) {
            renderNode(graphics, node);
        }
        renderMarquee(graphics);

        graphics.pose().popPose();
        graphics.disableScissor();
    }

    private void renderGrid(GuiGraphics graphics) {
        double gx0 = view.toGraphX(canvasLeft());
        double gx1 = view.toGraphX(canvasRight());
        double gy0 = view.toGraphY(canvasTop());
        double gy1 = view.toGraphY(canvasBottom());
        double step = 32;
        int thickness = Math.max(1, (int) Math.round(1 / view.zoom()));
        for (double x = Math.floor(gx0 / step) * step; x < gx1; x += step) {
            boolean major = Math.abs(x % (step * 5)) < 0.001;
            Draw.line(graphics, x, gy0, x, gy1, major ? Theme.GRID_MAJOR : Theme.GRID_MINOR, thickness);
        }
        for (double y = Math.floor(gy0 / step) * step; y < gy1; y += step) {
            boolean major = Math.abs(y % (step * 5)) < 0.001;
            Draw.line(graphics, gx0, y, gx1, y, major ? Theme.GRID_MAJOR : Theme.GRID_MINOR, thickness);
        }
    }

    private void renderNode(GuiGraphics graphics, GraphNode node) {
        if (!isVisible(node.x, node.y, Theme.NODE_WIDTH, Theme.NODE_HEIGHT)) return;
        int x = (int) Math.round(node.x);
        int y = (int) Math.round(node.y);
        int w = (int) Theme.NODE_WIDTH;
        int h = (int) Theme.NODE_HEIGHT;
        int headerHeight = (int) Theme.NODE_HEADER_HEIGHT;

        boolean selected = selectedNodes.contains(node.label);
        LabelFacts.Fact fact = facts.get(node.label);
        int headerColor = nodeHeaderColor(node.label, selected);

        graphics.fill(x + 1, y + 2, x + w + 1, y + h + 2, Theme.PANEL_SHADOW);
        Draw.panel(graphics, x, y, w, h,
                   fact == null ? Theme.NODE_MISSING : selected ? Theme.NODE_BG_SELECTED : Theme.NODE_BG,
                   selected ? Theme.NODE_BORDER_SEL : Theme.NODE_BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + headerHeight, headerColor);

        if (fact != null && !fact.icon().isEmpty()) {
            graphics.renderItem(fact.icon(), x + 3, y);
        }
        Draw.text(graphics, font, Draw.ellipsize(font, node.label, w - 34), x + 22, y + 4,
                  Theme.contrastText(headerColor));
        if (node.energySource) {
            String badge = "电";
            int badgeX = x + w - 6 - font.width(badge) - (facts.get(node.label) != null
                                                          && facts.get(node.label).count() > 1 ? 20 : 0);
            graphics.fill(badgeX - 2, y + 3, badgeX + font.width(badge) + 2, y + 12, 0x66000000);
            Draw.text(graphics, font, badge, badgeX, y + 4, Theme.contrastText(headerColor));
        }
        if (fact != null && fact.count() > 1) {
            String count = "×" + fact.count();
            Draw.text(graphics, font, count, x + w - 6 - font.width(count), y + 4,
                      Theme.contrastText(headerColor));
        }

        int bodyY = y + headerHeight + 3;
        String subtitle = fact == null ? "磁盘上没有此标签"
                : fact.blockName() == null ? "区块未加载" : fact.blockName();
        Draw.text(graphics, font, Draw.ellipsize(font, subtitle, w - 8), x + 4, bodyY, Theme.TEXT);
        if (fact != null) {
            Draw.text(graphics, font, Draw.ellipsize(font, fact.coords(), w - 8), x + 4, bodyY + 11,
                      Theme.TEXT_DIM);
        }
        if (fact != null && fact.notNextToCable() > 0) {
            // A label where only some blocks reach a cable is the failure that looks like "only the
            // first machine works", so it is called out on the node itself.
            String text = fact.cableReach() == 0 ? "未紧贴线缆" : fact.notNextToCable() + " 个未接线缆";
            Draw.text(graphics, font, text, x + 4, bodyY + 22, Theme.WARN);
        } else if (node.note != null && !node.note.isBlank()) {
            Draw.text(graphics, font, Draw.ellipsize(font, node.note, w - 8), x + 4, bodyY + 22,
                      Theme.TEXT_DIM);
        }

        // Automatic power feed marker: no link is drawn for these, so the node has to show it.
        if (node.isPowered()) {
            String power = "电 " + node.powerRate;
            int labelWidth = font.width(power);
            int badgeX = x + w - 6 - labelWidth;
            int badgeY = y + h - 12;
            graphics.fill(badgeX - 3, badgeY - 1, badgeX + labelWidth + 3, badgeY + 9, 0x99000000);
            Draw.text(graphics, font, power, badgeX, badgeY, Theme.resourceColor(ResourceKind.ENERGY));
        }

        double portY = node.y + Theme.NODE_HEIGHT / 2;
        Draw.circle(graphics, node.x, portY, Theme.PORT_RADIUS, Theme.PORT);
        Draw.circle(graphics, node.x + Theme.NODE_WIDTH, portY, Theme.PORT_RADIUS, Theme.PORT);
        Draw.circle(graphics, node.x, portY, Theme.PORT_RADIUS - 1.6, Theme.CANVAS_BG);
        Draw.circle(graphics, node.x + Theme.NODE_WIDTH, portY, Theme.PORT_RADIUS - 1.6, Theme.CANVAS_BG);
    }

    private int nodeHeaderColor(String label, boolean selected) {
        GraphNode node = graph.node(label);
        // A declared power supply is always shown in the energy colour, even before it has links.
        if (node != null && node.energySource) return Theme.resourceColorDim(ResourceKind.ENERGY);
        ResourceKind kind = null;
        for (GraphLink link : graph.links) {
            if (!link.fromLabel.equals(label) && !link.toLabel.equals(label)) continue;
            if (kind == null) {
                kind = link.resource.kind();
            } else if (kind != link.resource.kind()) {
                return selected ? 0xFF3B4658 : Theme.NODE_HEADER;
            }
        }
        if (kind == null) return selected ? 0xFF3B4658 : Theme.NODE_HEADER;
        return Theme.resourceColorDim(kind);
    }

    /** Where a link's curve actually runs, shared by drawing and hit testing. */
    private record LinkGeometry(double x1, double y1, double x2, double y2, double bow, int fromIndex, int toIndex) {
    }

    /**
     * The curve geometry for a link, fanned out when several links share a node.
     *
     * <p>Two things get separated, and both matter: the endpoints, so a second link out of a node is
     * visibly not the same line, and the bow, so parallel links do not lie on top of each other along
     * their whole length. Rendering and hit testing both come through here so they cannot disagree.
     */
    private LinkGeometry geometryOf(GraphLink link) {
        GraphNode from = graph.node(link.fromLabel);
        GraphNode to = graph.node(link.toLabel);
        List<GraphLink> outgoing = graph.linksFrom(link.fromLabel);
        List<GraphLink> incoming = graph.linksTo(link.toLabel);
        int fromIndex = Math.max(0, outgoing.indexOf(link));
        int toIndex = Math.max(0, incoming.indexOf(link));
        double spread = 7;
        double x1 = from.x + Theme.NODE_WIDTH;
        double y1 = from.y + Theme.NODE_HEIGHT / 2 + (fromIndex - (outgoing.size() - 1) / 2.0) * spread;
        double x2 = to.x;
        double y2 = to.y + Theme.NODE_HEIGHT / 2 + (toIndex - (incoming.size() - 1) / 2.0) * spread;
        double bow = Draw.defaultBow(x1, x2) + (fromIndex + toIndex) * 15;
        return new LinkGeometry(x1, y1, x2, y2, bow, fromIndex, toIndex);
    }

    /** Links whose curve passes near a point, topmost first, for selection. */
    private List<GraphLink> linksAt(double graphX, double graphY) {
        double tolerance = Math.max(4, 7 / view.zoom());
        List<GraphLink> hits = new ArrayList<>();
        for (int i = graph.links.size() - 1; i >= 0; i--) {
            GraphLink link = graph.links.get(i);
            if (graph.node(link.fromLabel) == null || graph.node(link.toLabel) == null) continue;
            LinkGeometry geometry = geometryOf(link);
            double[] points = Draw.curvePoints(
                    geometry.x1(), geometry.y1(), geometry.x2(), geometry.y2(), 24, geometry.bow());
            if (Draw.distanceToCurve(graphX, graphY, points) <= tolerance) hits.add(link);
        }
        return hits;
    }

    private @Nullable GraphLink linkAt(double graphX, double graphY) {
        List<GraphLink> hits = linksAt(graphX, graphY);
        return hits.isEmpty() ? null : hits.get(0);
    }

    private void renderLink(GuiGraphics graphics, GraphLink link) {
        if (graph.node(link.fromLabel) == null || graph.node(link.toLabel) == null) return;
        LinkGeometry geometry = geometryOf(link);
        double x1 = geometry.x1();
        double y1 = geometry.y1();
        double x2 = geometry.x2();
        double y2 = geometry.y2();
        double bow = geometry.bow();
        int fromIndex = geometry.fromIndex();
        int toIndex = geometry.toIndex();
        boolean selected = selectedLinks.contains(link.id);
        int color = link.enabled ? Theme.resourceColor(link.resource.kind()) : Theme.TEXT_DISABLED;

        if (selected) {
            Draw.curve(graphics, x1, y1, x2, y2, Theme.withAlpha(Theme.ACCENT, 0x88), 6, bow);
        }
        Draw.curve(graphics, x1, y1, x2, y2, color, selected ? 3 : 2, bow);
        Draw.line(graphics, x2 - 7, y2 - 4, x2 - 1, y2, color, 2);
        Draw.line(graphics, x2 - 7, y2 + 4, x2 - 1, y2, color, 2);

        // Face badges stack upwards so every link's ends stay readable when a node has several.
        renderSideBadge(graphics, x1 + 10, y1 - 14 - fromIndex * 7, link.fromSides.badge(),
                        link.fromSides.isUnset());
        renderSideBadge(graphics, x2 - 10 - badgeWidth(link.toSides.badge()), y2 - 14 - toIndex * 7,
                        link.toSides.badge(), link.toSides.isUnset());

        String rate = link.rate + (link.trigger.isTimer() ? "/t" : "/脉冲");
        double[] mid = Draw.curveMidpoint(x1, y1, x2, y2, bow);
        int textWidth = font.width(rate);
        int plateX = (int) Math.round(mid[0] - textWidth / 2.0) - 2;
        int plateY = (int) Math.round(mid[1]) - 5 + fromIndex * 11;
        graphics.fill(plateX, plateY, plateX + textWidth + 4, plateY + 11, 0xD014161A);
        Draw.outline(graphics, plateX - 1, plateY - 1, textWidth + 6, 13, Theme.withAlpha(color, 0xCC));
        Draw.text(graphics, font, rate, plateX + 2, plateY + 2, color);
    }

    private int badgeWidth(String badge) {
        return font.width(badge) + 6;
    }

    private void renderSideBadge(GuiGraphics graphics, double x, double y, String badge, boolean unset) {
        int w = badgeWidth(badge);
        graphics.fill((int) x, (int) y, (int) x + w, (int) y + 11, 0xD014161A);
        Draw.outline(graphics, (int) x, (int) y, w, 11, unset ? Theme.TEXT_DISABLED : Theme.ACCENT_BORDER);
        Draw.centeredText(graphics, font, badge, (int) x + w / 2, (int) y + 2,
                          unset ? Theme.TEXT_DIM : Theme.TEXT_TITLE);
    }

    private void renderRubberBand(GuiGraphics graphics) {
        if (drag != Drag.LINK || linkingFrom == null) return;
        Draw.curve(graphics, linkFromX, linkFromY, mouseGraphX, mouseGraphY, Theme.ACCENT, 2);
        Draw.circle(graphics, mouseGraphX, mouseGraphY, 3, Theme.ACCENT_BORDER);
    }

    private void renderMarquee(GuiGraphics graphics) {
        if (drag != Drag.MARQUEE) return;
        int x0 = (int) Math.round(Math.min(marqueeX0, marqueeX1));
        int y0 = (int) Math.round(Math.min(marqueeY0, marqueeY1));
        int x1 = (int) Math.round(Math.max(marqueeX0, marqueeX1));
        int y1 = (int) Math.round(Math.max(marqueeY0, marqueeY1));
        graphics.fill(x0, y0, Math.max(x0 + 1, x1), Math.max(y0 + 1, y1), Theme.ACCENT_SOFT);
        Draw.outline(graphics, x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), Theme.ACCENT_BORDER);
    }

    private void renderInspector(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = inspectorLeft();
        int top = Theme.MENU_HEIGHT;
        int right = width;
        int bottom = inspectorBottom();
        graphics.fill(left, top, right, bottom, Theme.PANEL);
        graphics.fill(left, top, left + 1, bottom, Theme.PANEL_BORDER);

        int footerTop = bottom - INSPECTOR_FOOTER;
        int clipTop = Math.min(inspectorTop() - 4, footerTop - 1);
        int clipBottom = Math.max(clipTop + 1, footerTop);
        graphics.enableScissor(left + 1, clipTop, Math.max(left + 2, right), clipBottom);
        for (Placed item : placed) {
            item.element().draw(graphics, mouseX, mouseY);
        }
        graphics.disableScissor();

        graphics.fill(left + 1, footerTop, right, bottom, Theme.PANEL_ALT);
        graphics.fill(left + 1, footerTop, right, footerTop + 1, Theme.PANEL_BORDER);
        Draw.text(graphics, font, "生成结果", left + INSPECTOR_PADDING, footerTop + 4, Theme.ACCENT);

        GraphLink link = selectedLink();
        List<Elements.Lines.Line> lines;
        if (link != null) {
            lines = describePlan(link);
        } else {
            GraphNode node = selectedNode();
            lines = node != null
                    ? describePowerPlan(node)
                    : List.of(new Elements.Lines.Line("选中一条连线或一个节点即可查看生成的语句", Theme.TEXT_DIM));
        }
        int lineY = footerTop + 16;
        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            Elements.Lines.Line line = lines.get(i);
            Draw.text(graphics, font, Draw.ellipsize(font, line.text(), right - left - 16),
                      left + INSPECTOR_PADDING, lineY, line.color());
            lineY += font.lineHeight + 1;
        }

        if (maxInspectorScroll() > 0) {
            int trackTop = top + 2;
            int trackHeight = bottom - top - 4;
            graphics.fill(right - 4, trackTop, right - 2, bottom - 2, Theme.EDIT_BG);
            int viewport = footerTop - inspectorTop();
            int thumb = Math.max(16, trackHeight * viewport / Math.max(1, inspectorContentHeight()));
            int thumbY = trackTop + (trackHeight - thumb) * inspectorScroll / Math.max(1, maxInspectorScroll());
            graphics.fill(right - 4, thumbY, right - 2, thumbY + thumb, Theme.BUTTON_BORDER);
        }
    }

    private void renderPreview(GuiGraphics graphics) {
        int left = canvasLeft();
        int top = previewTop();
        int right = canvasRight();
        int bottom = height - Theme.STATUS_HEIGHT;
        graphics.fill(left, top, right, bottom, Theme.PANEL);
        graphics.fill(left, top, right, top + 1, Theme.PANEL_BORDER);

        String title = "程序预览（只读，保存时写入磁盘）";
        Draw.text(graphics, font, title, left + 8, top + 3, Theme.ACCENT);
        if (warnings.isEmpty()) {
            Draw.text(graphics, font, "无警告", left + 8 + font.width(title) + 12, top + 3, Theme.OK);
        } else {
            Draw.text(graphics, font, "警告 " + warnings.size() + " 条", left + 8 + font.width(title) + 12,
                      top + 3, Theme.WARN);
        }

        int textTop = top + 14;
        if (bottom - 1 <= textTop) return;
        graphics.enableScissor(left + 1, textTop, Math.max(left + 2, right - 1), bottom - 1);
        List<String> lines = previewLines();
        int maxLines = Math.max(1, (bottom - textTop) / (font.lineHeight + 1));
        int lineY = textTop;
        for (int i = previewScroll; i < Math.min(lines.size(), previewScroll + maxLines); i++) {
            renderHighlightedLine(graphics, lines.get(i), left + 8, lineY, right);
            lineY += font.lineHeight + 1;
        }
        graphics.disableScissor();

        if (previewScroll > 0) {
            String hint = "上方还有 " + previewScroll + " 行";
            Draw.text(graphics, font, hint, right - font.width(hint) - 6, bottom - 11, Theme.TEXT_DIM);
        }
    }

    private List<String> previewLines() {
        List<String> lines = new ArrayList<>();
        boolean inBlob = false;
        int blobLines = 0;
        for (String line : generatedText.split("\n", -1)) {
            if (line.contains(GraphCodec.MARKER_BEGIN)) {
                inBlob = true;
                continue;
            }
            if (line.contains(GraphCodec.MARKER_END)) {
                inBlob = false;
                lines.add("-- … 图谱数据块 " + blobLines + " 行已折叠 …");
                continue;
            }
            if (inBlob) {
                blobLines++;
                continue;
            }
            lines.add(line);
        }
        return lines;
    }

    private void renderHighlightedLine(GuiGraphics graphics, String line, int x, int y, int right) {
        if (line.trim().startsWith("--")) {
            Draw.text(graphics, font, Draw.ellipsize(font, line, right - x - 8), x, y, Theme.TEXT_DISABLED);
            return;
        }
        int cursor = x;
        for (String token : line.split("(?<=\\s)|(?=\\s)")) {
            if (token.isEmpty()) continue;
            if (cursor + font.width(token) > right - 6) break;
            Draw.text(graphics, font, token, cursor, y, tokenColor(token, line));
            cursor += font.width(token);
        }
    }

    private int tokenColor(String token, String line) {
        String upper = token.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "EVERY", "DO", "END", "IF", "THEN", "ELSE", "INPUT", "OUTPUT", "FROM", "TO",
                 "EACH", "SIDE", "SLOTS", "RETAIN", "EMPTY", "IN", "NAME", "HAS", "GT", "LT",
                 "EXCEPT", "ROUND", "ROBIN", "BY", "LABEL", "BLOCK", "REDSTONE", "PULSE" ->
                    Theme.ACCENT;
            default -> token.trim().matches("\\d+") ? Theme.WARN
                    : line.trim().startsWith("name") ? Theme.OK
                    : Theme.TEXT;
        };
    }

    private void renderStatusBar(GuiGraphics graphics) {
        int top = height - Theme.STATUS_HEIGHT;
        graphics.fill(0, top, width, height, Theme.PANEL_ALT);
        graphics.fill(0, top, width, top + 1, Theme.PANEL_BORDER);

        if (System.currentTimeMillis() < toastUntil) {
            Draw.text(graphics, font, toast, 6, top + 3, Theme.ACCENT_BORDER);
        } else {
            StringBuilder status = new StringBuilder();
            status.append(graph.nodes.size()).append(" 节点 · ").append(graph.links.size()).append(" 连线");
            double items = 0;
            double fluids = 0;
            double chemicals = 0;
            double energy = 0;
            int failed = 0;
            for (SfmlCompiler.Plan plan : plans) {
                if (!plan.compiled()) {
                    failed++;
                    continue;
                }
                switch (plan.link().resource.kind()) {
                    case ITEM -> items += plan.effectiveRatePerTick();
                    case FLUID -> fluids += plan.effectiveRatePerTick();
                    case CHEMICAL -> chemicals += plan.effectiveRatePerTick();
                    case ENERGY -> energy += plan.effectiveRatePerTick();
                }
            }
            if (items > 0) status.append(" · 物品 ").append(SfmlCompiler.trim(items)).append("/tick");
            if (fluids > 0) status.append(" · 流体 ").append(SfmlCompiler.trim(fluids)).append(" mB/tick");
            if (chemicals > 0) {
                status.append(" · 化学品 ").append(SfmlCompiler.trim(chemicals)).append(" mB/tick");
            }
            if (energy > 0) status.append(" · 电力 ").append(SfmlCompiler.trim(energy)).append(" FE/tick");
            if (failed > 0) status.append(" · ").append(failed).append(" 条未生效");
            Draw.text(graphics, font, status.toString(), 6, top + 3, Theme.TEXT);
        }

        String size = generatedText.length() + " / " + Program.MAX_PROGRAM_LENGTH + " 字符";
        boolean over = generatedText.length() > Program.MAX_PROGRAM_LENGTH;
        Draw.text(graphics, font, size, width - font.width(size) - 6, top + 3,
                  over ? Theme.ERROR : Theme.TEXT_DIM);
        String zoom = Math.round(view.zoom() * 100) + "%";
        Draw.text(graphics, font, zoom, width - font.width(size) - font.width(zoom) - 16, top + 3,
                  Theme.TEXT_DIM);
    }

    private void renderTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        // The picker draws its own tooltip, and the manager/popup layers own theirs.
        if (modal != null || popupItems != null || picker != null || menuBar.isOpen()) return;
        if (barTooltip != null) {
            Draw.tooltip(graphics, font, barTooltip, mouseX, mouseY, width, height);
            return;
        }
        if (mouseX >= inspectorLeft()) {
            for (Placed item : placed) {
                Element element = item.element();
                if (!element.contains(mouseX, mouseY)) continue;
                List<String> lines = element.tooltipAt(mouseX, mouseY);
                if (!lines.isEmpty()) {
                    Draw.tooltip(graphics, font, lines, mouseX, mouseY, width, height);
                    return;
                }
            }
            return;
        }
        if (!view.contains(mouseX, mouseY)) return;

        double graphX = view.toGraphX(mouseX);
        double graphY = view.toGraphY(mouseY);
        GraphNode node = nodeAt(graphX, graphY);
        if (node == null) {
            List<GraphLink> hits = linksAt(graphX, graphY);
            if (!hits.isEmpty()) {
                List<String> lines = new ArrayList<>(linkTooltip(hits.get(0)));
                if (hits.size() > 1) {
                    lines.add("");
                    lines.add("这里有 " + hits.size() + " 条连线重叠，连续点击可逐条切换");
                }
                Draw.tooltip(graphics, font, lines, mouseX, mouseY, width, height);
            }
            return;
        }
        LabelFacts.Fact fact = facts.get(node.label);
        List<String> lines = new ArrayList<>();
        lines.add(node.label);
        if (fact == null) {
            lines.add("磁盘上没有这个标签");
        } else {
            lines.add((fact.blockName() == null ? "（区块未加载）" : fact.blockName()) + " ×" + fact.count());
            lines.add(fact.coords());
            if (fact.count() > 1) {
                lines.add("这个标签有 " + fact.count() + " 个方块");
                lines.add("连线要开「每方块独立」才会每台都搬");
            }
            if (fact.notNextToCable() > 0) {
                lines.add("只有 " + fact.cableReach() + "/" + fact.count() + " 个方块紧贴线缆");
                lines.add("没紧贴线缆的会被 SFM 直接忽略（不会报错）");
            }
            if (!fact.hasFacing()) lines.add("无朝向属性：前/后/左/右 无效");
        }
        lines.add("");
        lines.add("左键拖动移动 · 右侧圆点拖出连线");
        lines.add("右键菜单 · 中键平移 · 滚轮缩放");
        Draw.tooltip(graphics, font, lines, mouseX, mouseY, width, height);
    }

    private List<String> linkTooltip(GraphLink link) {
        List<String> lines = new ArrayList<>();
        lines.add(link.fromLabel + " → " + link.toLabel);
        lines.add("资源：" + switch (link.resource.kind()) {
            case ITEM -> "物品" + (link.resource.hasFilter() ? " " + link.resource.filter() : "");
            case FLUID -> "流体" + (link.resource.hasFilter() ? " " + link.resource.filter() : "");
            case CHEMICAL -> "化学品" + (link.resource.hasFilter() ? " " + link.resource.filter() : "");
            case ENERGY -> "电力 FE";
        });
        lines.add("源面 " + link.fromSides.display() + " · 目标面 " + link.toSides.display());
        lines.add("速率 " + link.rate + link.resource.kind().unit()
                  + " / " + (link.trigger.isTimer() ? "tick" : "脉冲"));
        SfmlCompiler.Plan plan = plansByLinkId.get(link.id);
        if (plan != null && plan.compiled()) {
            lines.add("实际：每 " + plan.intervalTicks() + " tick 搬 " + plan.amountPerFiring());
        } else if (plan != null) {
            lines.add("未生效：" + plan.problem());
        }
        if (!link.enabled) lines.add("已禁用");
        lines.add("");
        lines.add("点击编辑 · 右键更多操作");
        return lines;
    }

    private void renderPopup(GuiGraphics graphics) {
        if (popupItems == null) return;
        int height = 0;
        for (MenuBar.Item item : popupItems) height += item.isSeparator() ? 5 : 14;
        Draw.panelWithShadow(graphics, popupX, popupY, popupWidth, height + 2, Theme.PANEL, Theme.PANEL_BORDER);
        int y = popupY + 1;
        for (MenuBar.Item item : popupItems) {
            if (item.isSeparator()) {
                graphics.fill(popupX + 4, y + 2, popupX + popupWidth - 4, y + 3, Theme.PANEL_BORDER);
                y += 5;
                continue;
            }
            Draw.text(graphics, font, item.label(), popupX + 8, y + 3, Theme.TEXT);
            y += 14;
        }
    }

    // ------------------------------------------------------------------
    // Save buttons, in the menu bar strip
    // ------------------------------------------------------------------

    private int saveButtonWidth() {
        return font.width("保存") + 14;
    }

    private int saveExitButtonWidth() {
        return font.width("保存并退出") + 14;
    }

    private int saveExitButtonX() {
        return width - saveExitButtonWidth() - 4;
    }

    private int saveButtonX() {
        return saveExitButtonX() - saveButtonWidth() - 4;
    }

    private void renderBarButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        barTooltip = null;
        renderBarButton(graphics, saveButtonX(), saveButtonWidth(), "保存", mouseX, mouseY, false,
                        requestSaveTooltip(false));
        renderBarButton(graphics, saveExitButtonX(), saveExitButtonWidth(), "保存并退出", mouseX, mouseY, true,
                        requestSaveTooltip(true));
    }

    private List<String> requestSaveTooltip(boolean closeAfter) {
        List<String> lines = new ArrayList<>();
        lines.add(closeAfter ? "写入磁盘并关闭编辑器" : "写入磁盘，编辑器保持打开（Ctrl+S）");
        lines.add("保存前会用 SFM 自己的编译器校验一遍");
        return lines;
    }

    private void renderBarButton(
            GuiGraphics graphics,
            int x,
            int w,
            String label,
            int mouseX,
            int mouseY,
            boolean primary,
            List<String> tooltip
    ) {
        boolean hovered = isOverBarButton(mouseX, mouseY, x, w);
        int background = hovered
                ? (primary ? 0xFF3E6FB0 : Theme.BUTTON_BG_HOVER)
                : (primary ? Theme.BUTTON_BG_ACTIVE : Theme.BUTTON_BG);
        Draw.panel(graphics, x, 1, w, Theme.MENU_HEIGHT - 3, background,
                   primary ? Theme.ACCENT_BORDER : Theme.BUTTON_BORDER);
        Draw.centeredText(graphics, font, label, x + w / 2, 4,
                          primary ? Theme.TEXT_TITLE : Theme.BUTTON_TEXT);
        if (hovered) barTooltip = tooltip;
    }

    private boolean isOverBarButton(double mouseX, double mouseY, int x, int w) {
        return mouseX >= x && mouseX < x + w && mouseY >= 1 && mouseY < Theme.MENU_HEIGHT - 2;
    }

    /** @return true when a save button was hit */
    private boolean clickBarButtons(double mouseX, double mouseY) {
        if (isOverBarButton(mouseX, mouseY, saveButtonX(), saveButtonWidth())) {
            requestSave(false);
            return true;
        }
        if (isOverBarButton(mouseX, mouseY, saveExitButtonX(), saveExitButtonWidth())) {
            requestSave(true);
            return true;
        }
        return false;
    }

    private void renderModal(GuiGraphics graphics, int mouseX, int mouseY) {
        if (modal == null) {
            modalButtonRects = null;
            return;
        }
        graphics.fill(0, 0, width, height, 0xA0000000);
        List<String> lines = modal.lines();
        int maxWidth = font.width(modal.title());
        for (String line : lines) maxWidth = Math.max(maxWidth, font.width(line));
        int buttonsWidth = 0;
        for (MenuBar.Item button : modal.buttons()) buttonsWidth += font.width(button.label()) + 26;
        int panelWidth = Math.min(width - 20, Math.max(220, Math.max(maxWidth + 40, buttonsWidth + 28)));
        int panelHeight = 34 + lines.size() * (font.lineHeight + 2) + 28;
        int x = (width - panelWidth) / 2;
        int y = Math.max(8, (height - panelHeight) / 2);

        Draw.panelWithShadow(graphics, x, y, panelWidth, panelHeight, Theme.PANEL, Theme.ACCENT_BORDER);
        Draw.text(graphics, font, modal.title(), x + 14, y + 10, Theme.TEXT_TITLE);
        graphics.fill(x + 14, y + 22, x + panelWidth - 14, y + 23, Theme.PANEL_BORDER);

        int lineY = y + 30;
        for (String line : lines) {
            Draw.text(graphics, font, Draw.ellipsize(font, line, panelWidth - 28), x + 14, lineY, Theme.TEXT);
            lineY += font.lineHeight + 2;
        }

        int buttonY = y + panelHeight - 26;
        int cursor = x + 14;
        List<int[]> rects = new ArrayList<>();
        for (MenuBar.Item button : modal.buttons()) {
            int buttonWidth = font.width(button.label()) + 22;
            boolean hovered = mouseX >= cursor && mouseX < cursor + buttonWidth
                              && mouseY >= buttonY && mouseY < buttonY + 16;
            Draw.panel(graphics, cursor, buttonY, buttonWidth, 16,
                       hovered ? Theme.BUTTON_BG_HOVER : Theme.BUTTON_BG, Theme.BUTTON_BORDER);
            Draw.centeredText(graphics, font, button.label(), cursor + buttonWidth / 2, buttonY + 4,
                              Theme.TEXT);
            rects.add(new int[]{cursor, buttonY, buttonWidth, 16});
            cursor += buttonWidth + 4;
        }
        modalButtonRects = rects;
    }

    private boolean isVisible(double x, double y, double w, double h) {
        double gx0 = view.toGraphX(canvasLeft());
        double gy0 = view.toGraphY(canvasTop());
        double gx1 = view.toGraphX(canvasRight());
        double gy1 = view.toGraphY(canvasBottom());
        return x + w >= gx0 && x <= gx1 && y + h >= gy0 && y <= gy1;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private void updateMouse(double mouseX, double mouseY) {
        mouseGraphX = view.toGraphX(mouseX);
        mouseGraphY = view.toGraphY(mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        updateMouse(mouseX, mouseY);

        if (modal != null) {
            handleModalClick(mouseX, mouseY);
            return true;
        }
        if (picker != null) {
            boolean inside = picker.contains(mouseX, mouseY);
            // Only clicks inside the popup go to the vanilla widget pass, so its search field works.
            if (inside && super.mouseClicked(mouseX, mouseY, button)) return true;
            if (picker.mouseClicked(mouseX, mouseY, button)) return true;
            if (inside) return true;
            // A click anywhere else closes it and still acts on whatever is underneath.
            closePicker();
        }
        if (popupItems != null) {
            handlePopupClick(mouseX, mouseY);
            return true;
        }
        if (menuBar.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && clickBarButtons(mouseX, mouseY)) return true;
        // Text fields are vanilla widgets, so they get the click first.
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == 0 && handleInspectorClick(mouseX, mouseY)) return true;
        if (view.contains(mouseX, mouseY)) return handleCanvasClick(mouseX, mouseY, button);
        return false;
    }

    private boolean handleInspectorClick(double mouseX, double mouseY) {
        if (mouseX < inspectorLeft() || mouseY < Theme.MENU_HEIGHT || mouseY > inspectorBottom()) return false;
        for (Placed item : placed) {
            Element element = item.element();
            if (!element.contains(mouseX, mouseY)) continue;
            if (element instanceof Elements.Slider) pushUndo();
            element.onClick(mouseX, mouseY, 0);
            if (element.draggable()) {
                activeSlider = element;
                drag = Drag.SLIDER;
            }
            return true;
        }
        // Clicks in the panel should not fall through to the canvas.
        return true;
    }

    private boolean handleCanvasClick(double mouseX, double mouseY, int button) {
        double gx = view.toGraphX(mouseX);
        double gy = view.toGraphY(mouseY);

        // Note the button numbers: in LWJGL right is 1 and middle is 2, not the other way round.
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            openCanvasPopup(mouseX, mouseY, gx, gy);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            drag = Drag.PAN;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;

        // The grab radius is a screen distance, so the port stays catchable when zoomed out.
        double grab = Math.max(Theme.PORT_HIT, 9.0 / view.zoom());
        for (int i = graph.nodes.size() - 1; i >= 0; i--) {
            GraphNode node = graph.nodes.get(i);
            double portX = node.x + Theme.NODE_WIDTH;
            double portY = node.y + Theme.NODE_HEIGHT / 2;
            if (Math.hypot(gx - portX, gy - portY) <= grab) {
                beginLink(node.label);
                return true;
            }
        }

        GraphNode node = nodeAt(gx, gy);
        if (node != null) {
            selectNode(node.label, Screen.hasShiftDown());
            pushUndo();
            draggingNode = node;
            dragGrabX = gx - node.x;
            dragGrabY = gy - node.y;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            drag = Drag.NODE;
            return true;
        }

        List<GraphLink> hits = linksAt(gx, gy);
        if (!hits.isEmpty()) {
            GraphLink choice = hits.get(0);
            // Clicking the same stack again walks down it, so a link buried under its neighbours is
            // still reachable instead of being permanently unselectable.
            if (!Screen.hasShiftDown() && selectedLinks.size() == 1) {
                String current = selectedLinks.iterator().next();
                for (int i = 0; i < hits.size(); i++) {
                    if (hits.get(i).id.equals(current)) {
                        choice = hits.get((i + 1) % hits.size());
                        break;
                    }
                }
            }
            selectLink(choice, Screen.hasShiftDown());
            if (hits.size() > 1) {
                toast(hits.size() + " 条连线在此重叠，再点一次切换（当前：" + choice.label() + "）");
            }
            return true;
        }

        if (!Screen.hasShiftDown()) clearSelection();
        drag = Drag.MARQUEE;
        marqueeX0 = gx;
        marqueeY0 = gy;
        marqueeX1 = gx;
        marqueeY1 = gy;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        updateMouse(mouseX, mouseY);
        switch (drag) {
            case NODE -> {
                if (draggingNode != null) {
                    double gx = view.toGraphX(mouseX) - dragGrabX;
                    double gy = view.toGraphY(mouseY) - dragGrabY;
                    if (snapToGrid) {
                        gx = Math.round(gx / 8.0) * 8.0;
                        gy = Math.round(gy / 8.0) * 8.0;
                    }
                    draggingNode.x = gx;
                    draggingNode.y = gy;
                }
                return true;
            }
            case PAN -> {
                view.panBy(mouseX - lastMouseX, mouseY - lastMouseY);
                lastMouseX = mouseX;
                lastMouseY = mouseY;
                return true;
            }
            case LINK -> {
                return true;
            }
            case MARQUEE -> {
                marqueeX1 = view.toGraphX(mouseX);
                marqueeY1 = view.toGraphY(mouseY);
                return true;
            }
            case SLIDER -> {
                if (activeSlider != null) {
                    activeSlider.onDrag(mouseX, mouseY);
                    return true;
                }
            }
            default -> {
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        updateMouse(mouseX, mouseY);
        switch (drag) {
            case LINK -> {
                GraphNode target = nodeAt(view.toGraphX(mouseX), view.toGraphY(mouseY));
                if (target != null && linkingFrom != null) {
                    createLink(linkingFrom, target.label);
                }
                linkingFrom = null;
            }
            case MARQUEE -> {
                double x0 = Math.min(marqueeX0, marqueeX1);
                double y0 = Math.min(marqueeY0, marqueeY1);
                double x1 = Math.max(marqueeX0, marqueeX1);
                double y1 = Math.max(marqueeY0, marqueeY1);
                if (Math.abs(x1 - x0) > 2 || Math.abs(y1 - y0) > 2) {
                    for (GraphNode node : graph.nodes) {
                        boolean intersects = node.x + Theme.NODE_WIDTH >= x0 && node.x <= x1
                                             && node.y + Theme.NODE_HEIGHT >= y0 && node.y <= y1;
                        if (intersects) {
                            selectedNodes.add(node.label);
                            if (primaryNode == null) primaryNode = node.label;
                        }
                    }
                    rebuildUi();
                }
            }
            default -> {
            }
        }
        drag = Drag.NONE;
        draggingNode = null;
        activeSlider = null;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (picker != null && picker.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        if (mouseX >= inspectorLeft() && mouseY >= Theme.MENU_HEIGHT) {
            int max = maxInspectorScroll();
            if (max > 0) {
                inspectorScroll = (int) Math.max(0, Math.min(max, inspectorScroll - scrollY * 16));
                layoutElements();
            }
            return true;
        }
        if (previewOpen && mouseY >= previewTop()) {
            int max = Math.max(0, previewLines().size() - 5);
            previewScroll = (int) Math.max(0, Math.min(max, previewScroll - scrollY));
            return true;
        }
        if (view.contains(mouseX, mouseY)) {
            view.zoomAt(mouseX, mouseY, scrollY > 0 ? 1.12 : 1 / 1.12);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (modal != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) modal = null;
            return true;
        }
        if (popupItems != null && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            popupItems = null;
            return true;
        }
        if (menuBar.isOpen() && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            menuBar.close();
            return true;
        }
        // Escape closes the picker rather than just blurring its search field.
        if (picker != null && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closePicker();
            return true;
        }
        // Let a focused text field consume typing. Escape blurs it first, so a stray escape does not
        // throw away the whole graph.
        if (getFocused() instanceof EditBox box && box.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                box.setFocused(false);
                setFocused(null);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (super.keyPressed(keyCode, scanCode, modifiers)) return true;

        boolean ctrl = Screen.hasControlDown();
        if (ctrl && keyCode == GLFW.GLFW_KEY_S) {
            requestSave(false);
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_Z) {
            if (Screen.hasShiftDown()) {
                redo();
            } else {
                undo();
            }
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_Y) {
            redo();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_A) {
            selectAll();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_G) {
            showGrid = !showGrid;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            deleteSelection();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F) {
            view.fitTo(graph);
            return true;
        }
        double panStep = 24;
        return switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                view.panBy(panStep, 0);
                yield true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                view.panBy(-panStep, 0);
                yield true;
            }
            case GLFW.GLFW_KEY_UP -> {
                view.panBy(0, panStep);
                yield true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                view.panBy(0, -panStep);
                yield true;
            }
            default -> false;
        };
    }

    @Override
    public void onClose() {
        // Saving pops the layer itself, so an unsaved exit has to do the same.
        openContext.onTryClose(generatedText, SFMScreenChangeHelpers::popScreen);
    }

    @Override
    public void added() {
        super.added();
        SFMGraphClient.editorOpened();
    }

    @Override
    public void removed() {
        super.removed();
        SFMGraphClient.editorClosed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // Canvas helpers
    // ------------------------------------------------------------------

    private @Nullable GraphNode nodeAt(double graphX, double graphY) {
        for (int i = graph.nodes.size() - 1; i >= 0; i--) {
            GraphNode node = graph.nodes.get(i);
            if (graphX >= node.x && graphX <= node.x + Theme.NODE_WIDTH
                && graphY >= node.y && graphY <= node.y + Theme.NODE_HEIGHT) {
                return node;
            }
        }
        return null;
    }

    private void beginLink(String label) {
        GraphNode node = graph.node(label);
        if (node == null) return;
        linkingFrom = label;
        linkFromX = node.x + Theme.NODE_WIDTH;
        linkFromY = node.y + Theme.NODE_HEIGHT / 2;
        drag = Drag.LINK;
        toast("拖到目标节点上松开即可连线");
    }

    private void createLink(String fromLabel, String toLabel) {
        GraphLink link = new GraphLink(fromLabel, toLabel);
        // Inherit from an existing link so a chain stays consistent.
        for (GraphLink candidate : graph.links) {
            if (candidate.fromLabel.equals(fromLabel)) {
                link.resource = candidate.resource;
                link.rate = candidate.rate;
                link.trigger = candidate.trigger;
                link.quantityEach = candidate.quantityEach;
                break;
            }
        }

        // A label usually covers several machines. Without EACH, SFM shares one budget across them,
        // so only the first block would be served and the rest would look broken.
        int fromCount = blockCount(fromLabel);
        int toCount = blockCount(toLabel);
        if (fromCount > 1) link.fromEach = true;
        if (toCount > 1) link.toEach = true;

        pushUndo();
        if (!graph.addLink(link)) {
            toast("这条连线已经存在");
            return;
        }
        selectLink(link, false);
        recompile();

        if (fromCount > 1 || toCount > 1) {
            String which = fromCount > 1 && toCount > 1
                    ? fromLabel + " 和 " + toLabel
                    : fromCount > 1 ? fromLabel : toLabel;
            toast(which + " 有多个方块，已开启「每方块独立」让每台都搬");
        }
    }

    private void selectNode(String label, boolean additive) {
        if (!additive) {
            selectedNodes.clear();
            selectedLinks.clear();
            primaryLink = null;
        }
        selectedNodes.add(label);
        primaryNode = label;
        rebuildUi();
    }

    private void selectLink(GraphLink link, boolean additive) {
        if (!additive) {
            selectedNodes.clear();
            selectedLinks.clear();
            primaryNode = null;
        }
        selectedLinks.add(link.id);
        primaryLink = link.id;
        rebuildUi();
    }

    private void clearSelection() {
        selectedNodes.clear();
        selectedLinks.clear();
        primaryNode = null;
        primaryLink = null;
        rebuildUi();
    }

    // ------------------------------------------------------------------
    // Popup and modal clicks
    // ------------------------------------------------------------------

    private void openCanvasPopup(double mouseX, double mouseY, double graphX, double graphY) {
        GraphNode node = nodeAt(graphX, graphY);
        if (node != null) {
            selectNode(node.label, false);
            popupItems = List.of(
                    MenuBar.Item.of("从这里连出（作为源）", () -> beginLink(node.label)),
                    MenuBar.Item.of("连到别的节点…", () -> openConnectPicker(node, mouseX, mouseY)),
                    MenuBar.Item.of("视图居中到这个节点", () -> centerOnNode(node)),
                    MenuBar.Item.of(node.energySource ? "取消能源节点标记" : "标记为能源节点",
                                    () -> toggleEnergySource(node)),
                    MenuBar.Item.of("复制标签名", () -> {
                        setClipboard(node.label);
                        toast("已复制标签名");
                    }),
                    MenuBar.Item.separator(),
                    MenuBar.Item.of("删除节点及其连线", this::deleteSelection)
            );
        } else {
            GraphLink link = linkAt(graphX, graphY);
            if (link != null) {
                selectLink(link, false);
                popupItems = List.of(
                        MenuBar.Item.of(link.enabled ? "禁用这条连线" : "启用这条连线", () -> {
                            pushUndo();
                            link.enabled = !link.enabled;
                            recompile();
                        }),
                        MenuBar.Item.of("反转方向", () -> {
                            pushUndo();
                            String from = link.fromLabel;
                            link.fromLabel = link.toLabel;
                            link.toLabel = from;
                            var sides = link.fromSides;
                            link.fromSides = link.toSides;
                            link.toSides = sides;
                            recompile();
                        }),
                        MenuBar.Item.separator(),
                        MenuBar.Item.of("删除这条连线", this::deleteSelection)
                );
            } else {
                popupItems = List.of(
                        MenuBar.Item.of("适应视图", () -> view.fitTo(graph)),
                        MenuBar.Item.of("全选", this::selectAll),
                        MenuBar.Item.toggle("对齐网格", () -> snapToGrid, () -> snapToGrid = !snapToGrid),
                        MenuBar.Item.separator(),
                        MenuBar.Item.of("从标签重建节点", this::resyncNodes)
                );
            }
        }
        showPopup(popupItems, mouseX, mouseY);
    }

    private int popupHeight() {
        int total = 2;
        if (popupItems != null) {
            for (MenuBar.Item item : popupItems) total += item.isSeparator() ? 5 : 14;
        }
        return total;
    }

    /** Opens a popup anchored at a point, clamped to the screen. */
    private void showPopup(List<MenuBar.Item> items, double anchorX, double anchorY) {
        popupItems = items;
        popupWidth = 150;
        for (MenuBar.Item item : items) popupWidth = Math.max(popupWidth, font.width(item.label()) + 20);
        popupX = (int) Math.max(2, Math.min(anchorX, width - popupWidth - 2));
        popupY = (int) Math.max(2, Math.min(anchorY, height - popupHeight() - 4));
    }

    /** Opens a popup whose right edge sits at {@code rightEdge}, for buttons in the side panel. */
    private void showPopupLeftOf(List<MenuBar.Item> items, int rightEdge, int top) {
        showPopup(items, rightEdge, top);
        popupX = (int) Math.max(2, Math.min(rightEdge - popupWidth, width - popupWidth - 2));
    }

    // ------------------------------------------------------------------
    // Connecting by menu, for when dragging is awkward
    // ------------------------------------------------------------------

    /** Lists every other node, so a node can be given as many outgoing links as it needs. */
    private void openConnectPicker(GraphNode from, double anchorX, double anchorY) {
        List<MenuBar.Item> items = new ArrayList<>();
        for (GraphNode other : graph.nodes) {
            if (other.label.equals(from.label)) continue;
            boolean already = graph.linksFrom(from.label).stream()
                                   .anyMatch(link -> link.toLabel.equals(other.label));
            items.add(MenuBar.Item.of(
                    "连到 " + other.label + (already ? "（已连，可再加一条）" : ""),
                    () -> createLink(from.label, other.label)
            ));
        }
        if (items.isEmpty()) {
            toast("图上没有别的节点可连");
            return;
        }
        showPopup(items, anchorX, anchorY);
    }

    // ------------------------------------------------------------------
    // Energy sources
    // ------------------------------------------------------------------

    /** A hand drawn energy link into this node, if any. */
    public @Nullable GraphLink energyInput(String label) {
        for (GraphLink link : graph.links) {
            if (link.toLabel.equals(label) && link.resource.kind() == ResourceKind.ENERGY) return link;
        }
        return null;
    }

    public void toggleEnergySource(GraphNode node) {
        pushUndo();
        node.energySource = !node.energySource;
        if (!node.energySource && node.isPowered()) {
            // A power source cannot be powered by something else.
            node.poweredBy = "";
        }
        rebuildUi();
        recompile();
        toast(node.energySource
              ? node.label + " 已标记为能源节点，其他节点可以在「供电来源」里选它"
              : node.label + " 取消了能源节点标记");
    }

    /**
     * Assigns the node's power supply.
     *
     * <p>No link is created on purpose: one generator feeding a dozen machines would otherwise need a
     * dozen drawn links and turn the canvas into spaghetti. The compiler emits the
     * {@code INPUT/OUTPUT forge_energy} pair for every powered node instead, which is exactly what a
     * drawn link would have compiled to.
     */
    private void setPowerSource(GraphNode consumer, String sourceLabel) {
        pushUndo();
        consumer.poweredBy = sourceLabel;
        if (consumer.powerRate == null || consumer.powerRate.isBlank()) consumer.powerRate = "1000";
        rebuildUi();
        recompile();
        toast(consumer.label + " 的供电已设为 " + sourceLabel + "（自动生成，无需连线）");
    }

    public void clearPowerSource(GraphNode consumer) {
        if (!consumer.isPowered()) return;
        pushUndo();
        consumer.poweredBy = "";
        rebuildUi();
        recompile();
        toast("已断开 " + consumer.label + " 的供电");
    }

    /** Which faces the automatic power feed uses; naming a face is what makes the capability resolve. */
    public void setPowerSides(GraphNode node, SideSet sides) {
        pushUndo();
        node.powerSides = sides;
        rebuildUi();
        recompile();
    }

    /** The statements a node's automatic power feed compiles to, for the inspector footer. */
    public List<Elements.Lines.Line> describePowerPlan(GraphNode node) {
        if (!node.isPowered()) {
            List<Elements.Lines.Line> lines = new ArrayList<>();
            lines.add(new Elements.Lines.Line(node.energySource
                                              ? "这是电源节点，不生成供电语句"
                                              : "未接入供电，没有供电语句", Theme.TEXT_DIM));
            return lines;
        }
        SfmlCompiler.Plan plan = plansByLinkId.get("power:" + node.label);
        if (plan == null) {
            plan = SfmlCompiler.compileLink(
                    graph, SfmlCompiler.powerLinkFor(node), limits, null, labelSummaries());
        }
        List<Elements.Lines.Line> lines = new ArrayList<>();
        if (!plan.compiled()) {
            lines.add(new Elements.Lines.Line("未生效：" + plan.problem(), Theme.ERROR));
            return lines;
        }
        lines.add(new Elements.Lines.Line(
                "每 " + plan.intervalTicks() + " tick 搬 " + plan.amountPerFiring() + " FE",
                Theme.OK
        ));
        for (String line : plan.lines()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--") || trimmed.equals("END")) continue;
            if (trimmed.startsWith("EVERY")) continue;
            lines.add(new Elements.Lines.Line(trimmed, Theme.TEXT_DIM));
        }
        return lines;
    }

    /** Why a node's automatic power feed is not compiling, or null when it is fine. */
    public @Nullable String powerProblem(String label) {
        SfmlCompiler.Plan plan = plansByLinkId.get("power:" + label);
        return plan == null || plan.compiled() ? null : plan.problem();
    }

    /** Lets a machine pick which declared energy node powers it. */
    public void openPowerPicker(GraphNode consumer) {
        List<MenuBar.Item> items = new ArrayList<>();
        for (GraphNode source : graph.nodes) {
            if (!source.energySource || source.label.equals(consumer.label)) continue;
            boolean isCurrent = consumer.poweredBy != null && consumer.poweredBy.equals(source.label);
            items.add(MenuBar.Item.of((isCurrent ? "✔ " : "") + "来自 " + source.label,
                                      () -> setPowerSource(consumer, source.label)));
        }
        if (items.isEmpty()) {
            toast("还没有能源节点：先在能源设备的节点上勾选「标记为能源节点」");
            return;
        }
        if (consumer.isPowered()) {
            items.add(MenuBar.Item.separator());
            items.add(MenuBar.Item.of("断开供电", () -> clearPowerSource(consumer)));
        }
        showPopupLeftOf(items, inspectorLeft() - 12, Theme.MENU_HEIGHT + 80);
    }

    private void handlePopupClick(double mouseX, double mouseY) {
        if (popupItems == null) return;
        int y = popupY + 1;
        for (MenuBar.Item item : popupItems) {
            int height = item.isSeparator() ? 5 : 14;
            if (!item.isSeparator() && mouseX >= popupX && mouseX < popupX + popupWidth
                && mouseY >= y && mouseY < y + height) {
                Runnable action = item.action();
                popupItems = null;
                if (action != null) action.run();
                return;
            }
            y += height;
        }
        popupItems = null;
    }

    private void handleModalClick(double mouseX, double mouseY) {
        if (modal == null || modalButtonRects == null) return;
        for (int i = 0; i < modalButtonRects.size() && i < modal.buttons().size(); i++) {
            int[] rect = modalButtonRects.get(i);
            if (mouseX >= rect[0] && mouseX < rect[0] + rect[2]
                && mouseY >= rect[1] && mouseY < rect[1] + rect[3]) {
                Runnable action = modal.buttons().get(i).action();
                if (action != null) action.run();
                return;
            }
        }
    }

    @Override
    public ISFMTextEditScreenOpenContext openContext() {
        return openContext;
    }
}
