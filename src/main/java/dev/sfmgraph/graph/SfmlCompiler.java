package dev.sfmgraph.graph;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Compiles a {@link GraphModel} into an SFML program that Super Factory Manager can run.
 *
 * <h2>How "per tick" rates become SFML</h2>
 * SFML has no notion of a rate: a statement moves up to {@code n} resources each time its trigger
 * fires, and the trigger fires every {@code interval} ticks. So a requested rate of {@code r} per
 * tick is expressed as {@code n = r * interval} per firing.
 *
 * <p>The interval cannot be smaller than the server's configured minimum
 * ({@code timerTriggerMinimumIntervalInTicks}, which defaults to 20 ticks — except for triggers
 * that only touch Forge Energy, where the default is 1). Rather than rounding the user's rate, the
 * compiler searches for the smallest legal interval for which {@code r * interval} is a whole
 * number, so {@code 1.5/tick} becomes {@code 30 per 20 ticks} and {@code 0.001/tick} becomes
 * {@code 24 per 24000 ticks} — exact, just delivered in bursts.
 *
 * <h2>Why one trigger block per link</h2>
 * SFM shares an {@code INPUT} statement's budget with every {@code OUTPUT} statement that follows it
 * inside the same trigger execution. Keeping one input/output pair per trigger block means each link
 * gets its own independent budget, its own interval, and cannot steal from another link.
 */
public final class SfmlCompiler {

    public static final int DEFAULT_TIMER_MIN_INTERVAL_TICKS = 20;
    public static final int DEFAULT_ENERGY_MIN_INTERVAL_TICKS = 1;

    /** Longest interval we are willing to generate, so a tiny rate cannot produce a silly program. */
    private static final int MAX_INTERVAL_TICKS = 24_000;
    private static final int MAX_INTERVAL_MULTIPLIER = 4_000;

    private static final Pattern SLOT_RANGE = Pattern.compile("\\d+(\\s*-\\s*\\d+)?");

    /** Server side limits that constrain what we are allowed to emit. */
    public record Limits(int timerMinIntervalTicks, int energyMinIntervalTicks) {

        public static final Limits SFM_DEFAULTS = new Limits(
                DEFAULT_TIMER_MIN_INTERVAL_TICKS,
                DEFAULT_ENERGY_MIN_INTERVAL_TICKS
        );

        public Limits {
            if (timerMinIntervalTicks < 1) timerMinIntervalTicks = 1;
            if (energyMinIntervalTicks < 1) energyMinIntervalTicks = 1;
        }

        public int minIntervalFor(ResourceKind kind) {
            return kind.isEnergy() ? energyMinIntervalTicks : timerMinIntervalTicks;
        }
    }

    /**
     * @param limits   server timing limits
     * @param graphBlob encoded graph to embed as comments, or {@code null} to omit it
     * @param header   extra header comment line, or {@code null}
     * @param labels   what the editor knows about each label (block count, cable reach). May be empty
     *                 when that information is unavailable.
     */
    public record Options(
            Limits limits,
            @Nullable String graphBlob,
            @Nullable String header,
            Map<String, LabelSummary> labels
    ) {

        public static Options defaults() {
            return new Options(Limits.SFM_DEFAULTS, null, null, Map.of());
        }

        public Options withLimits(Limits newLimits) {
            return new Options(newLimits, graphBlob, header, labels);
        }
    }

    /** What one link compiled to, for display in the editor. */
    public record Plan(
            GraphLink link,
            boolean compiled,
            @Nullable String problem,
            int intervalTicks,
            long amountPerFiring,
            boolean exact,
            double effectiveRatePerTick,
            List<String> lines
    ) {
        public boolean isExact() {
            return exact;
        }
    }

    public record Result(String text, List<String> warnings, List<Plan> plans) {

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        public int characterCount() {
            return text.length();
        }

        public List<String> compiledLines() {
            List<String> lines = new ArrayList<>();
            for (Plan plan : plans) lines.addAll(plan.lines());
            return lines;
        }
    }

    private SfmlCompiler() {
    }

    public static Result compile(GraphModel model, Options options) {
        Limits limits = options.limits();
        List<String> warnings = new ArrayList<>();
        List<Plan> plans = new ArrayList<>();
        StringBuilder body = new StringBuilder();

        for (GraphLink link : model.links) {
            Plan plan = compileLink(model, link, limits, warnings, options.labels());
            plans.add(plan);
            if (!plan.compiled()) continue;
            if (body.length() > 0) body.append('\n');
            for (String line : plan.lines()) body.append(line).append('\n');
        }

        // Automatic power feeds: one FE pair per powered node, no drawn link involved.
        int powerFeeds = 0;
        for (GraphNode node : model.nodes) {
            if (!node.isPowered()) continue;
            for (GraphLink link : model.links) {
                if (link.toLabel.equals(node.label) && link.resource.kind() == ResourceKind.ENERGY) {
                    warnings.add(node.label + "：既设置了自动供电，又有一条画上去的电力连线，会重复供电");
                    break;
                }
            }
            Plan plan = compileLink(model, powerLinkFor(node), limits, warnings, options.labels());
            plans.add(plan);
            if (!plan.compiled()) continue;
            powerFeeds++;
            body.append('\n');
            body.append("-- 供电（自动生成，无需连线）\n");
            for (String line : plan.lines()) body.append(line).append('\n');
        }

        if (model.links.isEmpty() && powerFeeds == 0) {
            warnings.add("图谱中还没有任何连线");
        }

        StringBuilder out = new StringBuilder();
        out.append(header(model, plans, warnings, options, powerFeeds));
        String name = model.name == null ? "" : model.name.trim();
        out.append("name \"").append(name.isEmpty() ? "graph" : escape(name)).append("\"\n");
        if (options.graphBlob() != null && !options.graphBlob().isBlank()) {
            out.append('\n').append(GraphCodec.wrapAsComments(options.graphBlob()));
        }
        out.append('\n').append(body);
        return new Result(out.toString(), warnings, plans);
    }

    /**
     * The link an automatic power feed compiles as.
     *
     * <p>Modelling it as a link rather than special casing the text means power feeds get the same
     * rate arithmetic, the same validation and the same comments as a hand drawn link — the energy
     * carve out included, so a 1 tick interval is allowed.
     *
     * <p>The faces come from the node because leaving them out would make SFM do a direction-less
     * capability lookup, which machines commonly do not answer at all.
     *
     * <p>{@code EACH} is on by default here: a label usually covers several machines, and without
     * {@code EACH} SFM shares one budget between them, so only the first machine would ever be
     * powered. With {@code EACH} every listed machine gets the full rate.
     */
    public static GraphLink powerLinkFor(GraphNode node) {
        GraphLink link = new GraphLink(node.poweredBy, node.label);
        link.id = "power:" + node.label;
        link.resource = ResourceSpec.ENERGY;
        link.rate = node.powerRate == null || node.powerRate.isBlank() ? "1000" : node.powerRate;
        SideSet sides = node.powerSides == null ? SideSet.of(GraphSide.BACK) : node.powerSides;
        link.fromSides = sides;
        link.toSides = sides;
        link.fromEach = true;
        link.toEach = true;
        return link;
    }

    /** Compiles a single link, used by the inspector for its live preview. */
    public static Plan compileLink(
            GraphModel model,
            GraphLink link,
            Limits limits,
            @Nullable List<String> warningsOut,
            Map<String, LabelSummary> labels
    ) {
        if (!link.enabled) {
            return new Plan(link, false, "已禁用", 0, 0, true, 0, List.of());
        }
        if (link.fromLabel.isBlank() || link.toLabel.isBlank()) {
            return problem(link, "源或目标标签为空");
        }
        if (model != null) {
            if (!model.hasLabel(link.fromLabel)) {
                return problem(link, "源标签 “" + link.fromLabel + "” 没有对应的节点");
            }
            if (!model.hasLabel(link.toLabel)) {
                return problem(link, "目标标签 “" + link.toLabel + "” 没有对应的节点");
            }
        }

        String resourceProblem = link.resource.problem();
        if (resourceProblem != null) {
            return problem(link, resourceProblem);
        }

        BigDecimal rate = link.rateValue();
        if (rate == null) {
            return problem(link, "速率必须是大于 0 的数字（当前：“" + link.rate + "”）");
        }

        String fromSlots = normalizeSlots(link.fromSlots);
        if (link.fromSlots != null && !link.fromSlots.isBlank() && fromSlots == null) {
            return problem(link, "源槽位范围无效：“" + link.fromSlots + "”（示例：0-8,10）");
        }
        String toSlots = normalizeSlots(link.toSlots);
        if (link.toSlots != null && !link.toSlots.isBlank() && toSlots == null) {
            return problem(link, "目标槽位范围无效：“" + link.toSlots + "”（示例：0-8,10）");
        }
        if (link.resource.kind().isEnergy() && (fromSlots != null || toSlots != null)) {
            if (warningsOut != null) {
                warningsOut.add(link.label() + "：能量没有槽位概念，已忽略槽位设置");
            }
            fromSlots = null;
            toSlots = null;
        }

        long maxAmount = link.resource.kind().maxAmountPerTransfer();
        int minInterval = limits.minIntervalFor(link.resource.kind());

        int interval;
        long amount;
        boolean exact;
        if (link.trigger.isTimer()) {
            Amounts amounts = planTimer(rate, minInterval, maxAmount, link.intervalTicks, link, warningsOut);
            interval = amounts.intervalTicks();
            amount = amounts.amount();
            exact = amounts.exact();
        } else {
            interval = 0;
            BigDecimal rounded = rate.setScale(0, RoundingMode.HALF_UP);
            long value = clamp(rounded, maxAmount);
            exact = rate.stripTrailingZeros().scale() <= 0;
            amount = value;
        }

        double effective = link.trigger.isTimer() ? (double) amount / interval : amount;

        if (link.resource.kind().isEnergy() && amount >= maxAmount && !rate.equals(BigDecimal.valueOf(amount))) {
            if (warningsOut != null) {
                warningsOut.add(link.label() + "：单次传输已达 FE 上限 " + maxAmount + "，请缩短间隔");
            }
        }
        if (link.fromLabel.equals(link.toLabel) && link.fromSides.equals(link.toSides)) {
            if (warningsOut != null) {
                warningsOut.add(link.label() + "：源和目标相同且未区分面，SFM 可能不会搬运任何东西");
            }
        }

        // A label can cover several blocks, and two separate traps come with that:
        //  1. blocks that touch no cable are silently skipped by SFM, so the machines just sit there
        //  2. without EACH, one budget is shared and only the first reachable block is served
        if (warningsOut != null && labels != null && !labels.isEmpty()) {
            warnAboutLabel(link.fromLabel, labels, link.fromEach, true, warningsOut);
            warnAboutLabel(link.toLabel, labels, link.toEach, false, warningsOut);
        }

        List<String> lines = renderLink(model, link, interval, amount, fromSlots, toSlots);
        return new Plan(link, true, null, interval, amount, exact, effective, lines);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private static List<String> renderLink(
            GraphModel model,
            GraphLink link,
            int interval,
            long amount,
            @Nullable String fromSlots,
            @Nullable String toSlots
    ) {
        String fromRef = labelRef(link.fromLabel);
        String toRef = labelRef(link.toLabel);
        String idToken = link.resource.idToken();
        String resources = idToken.isEmpty() ? "" : " " + idToken;

        StringBuilder limit = new StringBuilder().append(amount);
        if (link.quantityEach) limit.append(" EACH");
        if (link.retain > 0) {
            limit.append(" RETAIN ").append(link.retain);
            if (link.quantityEach) limit.append(" EACH");
        }

        String input = "INPUT " + limit + resources
                       + " FROM " + (link.fromEach ? "EACH " : "")
                       + fromRef
                       + link.roundRobin.sfml()
                       + link.fromSides.sfmlQualifier()
                       + slotSuffix(fromSlots);

        String output = "OUTPUT " + limit + resources
                        + " TO " + (link.emptySlotsOnly ? "EMPTY SLOTS IN " : "")
                        + (link.toEach ? "EACH " : "")
                        + toRef
                        + link.roundRobin.sfml()
                        + link.toSides.sfmlQualifier()
                        + slotSuffix(toSlots);

        List<String> lines = new ArrayList<>();
        lines.add(summaryComment(link, interval, amount));
        lines.add(link.trigger.triggerLine(interval));
        String condition = link.condition.sfml(fromRef, idToken);
        if (condition.isEmpty()) {
            lines.add("    " + input);
            lines.add("    " + output);
        } else {
            lines.add("    IF " + condition + " THEN");
            lines.add("        " + input);
            lines.add("        " + output);
            lines.add("    END");
        }
        lines.add("END");
        return lines;
    }

    private static String summaryComment(GraphLink link, int interval, long amount) {
        StringBuilder sb = new StringBuilder("-- ");
        sb.append(link.fromLabel).append('(').append(link.fromSides.display()).append(')');
        sb.append(" → ").append(link.toLabel).append('(').append(link.toSides.display()).append(')');
        sb.append(" · ").append(resourceName(link.resource));
        if (link.trigger.isTimer()) {
            sb.append(" · 每 ").append(interval).append(" tick 搬 ").append(amount)
              .append(" (").append(trim(amount / (double) interval)).append("/tick)");
        } else {
            sb.append(" · 每次红石脉冲搬 ").append(amount);
        }
        if (link.retain > 0) sb.append(" · RETAIN ").append(link.retain);
        return sb.toString();
    }

    private static String resourceName(ResourceSpec spec) {
        String filter = spec.hasFilter() ? " " + spec.filter() : "";
        return switch (spec.kind()) {
            case ITEM -> "物品" + filter;
            case FLUID -> "流体" + filter;
            case CHEMICAL -> "化学品" + filter;
            case ENERGY -> "电力";
        };
    }

    private static String header(
            GraphModel model,
            List<Plan> plans,
            List<String> warnings,
            Options options,
            int powerFeeds
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- ============================================================\n");
        sb.append("-- 由 SFM 图谱编辑器 (sfmgraph) 生成\n");
        sb.append("-- 图形化编辑请使用管理器界面中的“图谱”编辑器；\n");
        sb.append("-- 手工修改下方的语句是安全的，但图谱数据块不会被重新解析。\n");
        if (options.header() != null && !options.header().isBlank()) {
            sb.append("-- ").append(options.header().replace("\n", " ")).append('\n');
        }

        // Totals per tick, which is the number the player actually cares about.
        double itemsPerTick = 0;
        double fluidPerTick = 0;
        double chemicalPerTick = 0;
        double energyPerTick = 0;
        int compiled = 0;
        for (Plan plan : plans) {
            if (!plan.compiled()) continue;
            compiled++;
            switch (plan.link().resource.kind()) {
                case ITEM -> itemsPerTick += plan.effectiveRatePerTick();
                case FLUID -> fluidPerTick += plan.effectiveRatePerTick();
                case CHEMICAL -> chemicalPerTick += plan.effectiveRatePerTick();
                case ENERGY -> energyPerTick += plan.effectiveRatePerTick();
            }
        }
        sb.append("-- 连线: ").append(compiled).append('/').append(plans.size()).append(" 条生效");
        if (itemsPerTick > 0) sb.append(" · 物品 ").append(trim(itemsPerTick)).append("/tick");
        if (fluidPerTick > 0) sb.append(" · 流体 ").append(trim(fluidPerTick)).append(" mB/tick");
        if (chemicalPerTick > 0) sb.append(" · 化学品 ").append(trim(chemicalPerTick)).append(" mB/tick");
        if (energyPerTick > 0) sb.append(" · 电力 ").append(trim(energyPerTick)).append(" FE/tick");
        sb.append('\n');
        if (powerFeeds > 0) {
            sb.append("-- 其中 ").append(powerFeeds).append(" 条是节点间自动供电（没有画连线）\n");
        }
        for (String warning : warnings) {
            sb.append("-- [警告] ").append(warning).append('\n');
        }
        sb.append("-- ============================================================\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private record Amounts(int intervalTicks, long amount, boolean exact) {
    }

    private static Amounts planTimer(
            BigDecimal rate,
            int minInterval,
            long maxAmount,
            int intervalOverride,
            GraphLink link,
            @Nullable List<String> warningsOut
    ) {
        BigDecimal max = BigDecimal.valueOf(maxAmount);

        if (intervalOverride > 0) {
            int interval = Math.max(intervalOverride, minInterval);
            BigDecimal wanted = rate.multiply(BigDecimal.valueOf(interval));
            boolean exact = wanted.stripTrailingZeros().scale() <= 0 && wanted.compareTo(BigDecimal.ONE) >= 0;
            long amount = clamp(wanted.setScale(0, RoundingMode.HALF_UP), maxAmount);
            if (!exact && warningsOut != null) {
                if (wanted.compareTo(BigDecimal.ONE) < 0) {
                    warningsOut.add(link.label() + "：间隔 " + interval + " tick 太短，无法搬走小于 1 的数量，已按 1 处理");
                } else {
                    warningsOut.add(link.label() + "：指定间隔下速率不是整数，已四舍五入为 " + amount);
                }
            }
            if (intervalOverride < minInterval && warningsOut != null) {
                warningsOut.add(link.label() + "：服务器要求最小间隔 " + minInterval + " tick，已调整");
            }
            return new Amounts(interval, amount, exact);
        }

        int maxMultiplier = Math.min(MAX_INTERVAL_MULTIPLIER, Math.max(1, MAX_INTERVAL_TICKS / minInterval));
        for (int multiplier = 1; multiplier <= maxMultiplier; multiplier++) {
            long interval = (long) minInterval * multiplier;
            BigDecimal amount = rate.multiply(BigDecimal.valueOf(interval));
            if (amount.stripTrailingZeros().scale() > 0) continue;
            if (amount.compareTo(max) > 0) break;
            long value = amount.longValueExact();
            if (value >= 1) return new Amounts((int) interval, value, true);
        }

        // No interval in range represents this rate exactly: fall back to the smallest legal one.
        long interval = minInterval;
        BigDecimal amount = rate.multiply(BigDecimal.valueOf(interval));
        long value = clamp(amount.setScale(0, RoundingMode.HALF_UP), maxAmount);
        if (warningsOut != null) {
            double effective = (double) value / interval;
            warningsOut.add(link.label() + "：无法精确表示 " + rate.stripTrailingZeros().toPlainString()
                            + "/tick，已按每 " + interval + " tick 搬 " + value
                            + " 近似（实际 " + trim(effective) + "/tick）");
        }
        return new Amounts((int) interval, value, false);
    }

    private static long clamp(BigDecimal value, long max) {
        BigDecimal maxBd = BigDecimal.valueOf(max);
        if (value.compareTo(maxBd) > 0) return max;
        if (value.compareTo(BigDecimal.ONE) < 0) return 1;
        return value.longValueExact();
    }

    private static Plan problem(GraphLink link, String problem) {
        return new Plan(link, false, problem, 0, 0, true, 0, List.of());
    }

    /** Reports the cable and shared budget traps for one end of a link. */
    private static void warnAboutLabel(
            String label,
            Map<String, LabelSummary> labels,
            boolean each,
            boolean source,
            List<String> warningsOut
    ) {
        LabelSummary summary = labels.get(label);
        if (summary == null) return;

        boolean partial = summary.partiallyDisconnected();
        if (partial && !each) {
            // Both traps at once collapse into one message; the cables are the reason nothing moves.
            warningsOut.add(label + "：" + summary.blocks() + " 个方块里只有 " + summary.nextToCable()
                            + " 个紧贴线缆，其余会被 SFM 直接忽略（这台机器不会工作）");
            return;
        }
        if (partial) {
            warningsOut.add(label + "：" + summary.blocks() + " 个方块里有 " + summary.disconnected()
                            + " 个没有紧贴线缆（或线缆不属于本管理器），SFM 会忽略它们");
        }
        if (summary.blocks() > 1 && !each) {
            warningsOut.add(label + "：" + summary.blocks() + " 个方块共用一份额度，只有其中一个会"
                            + (source ? "被抽取" : "收到") + "，其余机器不工作（可开启「每方块独立」）");
        }
    }

    private static String labelRef(String label) {
        return SfmlKeywords.quoteLabel(label);
    }

    @Nullable
    private static String normalizeSlots(@Nullable String slots) {
        if (slots == null || slots.isBlank()) return null;
        StringBuilder out = new StringBuilder();
        for (String piece : slots.split(",")) {
            String trimmed = piece.trim();
            if (trimmed.isEmpty()) continue;
            if (!SLOT_RANGE.matcher(trimmed).matches()) return null;
            String canonical = trimmed.replaceAll("\\s+", "");
            int dash = canonical.indexOf('-');
            if (dash >= 0) {
                int start = Integer.parseInt(canonical.substring(0, dash).trim());
                int end = Integer.parseInt(canonical.substring(dash + 1).trim());
                if (end < start) return null;
            }
            if (out.length() > 0) out.append(',');
            out.append(canonical);
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static String slotSuffix(@Nullable String normalizedSlots) {
        return normalizedSlots == null ? "" : " SLOTS " + normalizedSlots;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Formats a rate for display without trailing zeros. */
    public static String trim(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
