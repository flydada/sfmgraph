package dev.sfmgraph.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the graph to SFML translation.
 *
 * <p>The interesting cases are the rate conversions: SFML has no notion of "per tick", so the
 * compiler has to pick a trigger interval and a per-execution amount that together reproduce the
 * requested rate exactly.
 */
class SfmlCompilerTest {

    private static GraphModel model(String... labels) {
        GraphModel model = new GraphModel();
        model.name = "test";
        for (String label : labels) model.ensureNode(label);
        return model;
    }

    private static GraphLink link(String from, String to) {
        return new GraphLink(from, to);
    }

    private static SfmlCompiler.Result compile(GraphModel model) {
        return SfmlCompiler.compile(model, SfmlCompiler.Options.defaults());
    }

    private static String text(GraphModel model) {
        return compile(model).text();
    }

    /** Strips the generated header so assertions read against the statements only. */
    private static String body(String program) {
        int index = program.indexOf("name \"");
        return index < 0 ? program : program.substring(program.indexOf('\n', index) + 1).trim();
    }

    // ------------------------------------------------------------------
    // Rate mathematics
    // ------------------------------------------------------------------

    @Test
    void onePerTickBecomesTwentyPerTwentyTicks() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.rate = "1";
        model.links.add(link);

        String program = text(model);
        assertTrue(program.contains("EVERY 20 TICKS DO"), program);
        assertTrue(program.contains("INPUT 20 FROM a"), program);
        assertTrue(program.contains("OUTPUT 20 TO b"), program);
    }

    @Test
    void fractionalRateIsRepresentedExactly() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.rate = "1.5";
        model.links.add(link);

        String program = text(model);
        // 1.5 per tick over 20 ticks is 30, a whole number, so no rounding is needed.
        assertTrue(program.contains("INPUT 30 FROM a"), program);
        assertTrue(program.contains("EVERY 20 TICKS DO"), program);
        assertTrue(compile(model).plans().get(0).isExact());
    }

    @Test
    void rateSmallerThanOneStillFindsAnExactInterval() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.rate = "0.001";
        model.links.add(link);

        SfmlCompiler.Plan plan = compile(model).plans().get(0);
        assertTrue(plan.isExact(), "0.001/tick should be expressible exactly");
        // 0.001 * 1000 == 1, so the smallest exact setting is 1 item per 1000 ticks.
        assertEquals(1000, plan.intervalTicks());
        assertEquals(1, plan.amountPerFiring());
    }

    @Test
    void largeRateKeepsAShortInterval() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.rate = "64";
        model.links.add(link);

        SfmlCompiler.Plan plan = compile(model).plans().get(0);
        assertTrue(plan.isExact());
        // Every legal interval is exact when the rate is an integer, so the shortest wins.
        assertEquals(20, plan.intervalTicks());
        assertEquals(1280, plan.amountPerFiring());
    }

    @Test
    void energyUsesAOneTickIntervalBecauseOfTheEnergyCarveOut() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.resource = ResourceSpec.ENERGY;
        link.rate = "10000";
        model.links.add(link);

        String program = text(model);
        // SFM allows a 1 tick interval when a trigger only touches Forge Energy.
        assertTrue(program.contains("EVERY 1 TICKS DO"), program);
        assertTrue(program.contains("INPUT 10000 sfm:forge_energy:forge:energy FROM a"), program);
        assertTrue(program.contains("OUTPUT 10000 sfm:forge_energy:forge:energy TO b"), program);
    }

    @Test
    void energyAmountIsCappedAtIntMaxWithAWarning() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.resource = ResourceSpec.ENERGY;
        link.rate = "10000000000";
        model.links.add(link);

        SfmlCompiler.Result result = compile(model);
        SfmlCompiler.Plan plan = result.plans().get(0);
        // Energy is an int inside SFM, so a single transfer can never exceed Integer.MAX_VALUE.
        assertEquals(Integer.MAX_VALUE, plan.amountPerFiring());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void explicitIntervalOverrideIsClampedToTheServerMinimum() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.rate = "1";
        link.intervalTicks = 5;
        model.links.add(link);

        SfmlCompiler.Result result = compile(model);
        assertEquals(20, result.plans().get(0).intervalTicks());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("最小间隔")), result.warnings().toString());
    }

    // ------------------------------------------------------------------
    // Faces
    // ------------------------------------------------------------------

    @Test
    void explicitSidesAreWrittenAfterTheLabel() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.fromSides = SideSet.of(GraphSide.NORTH, GraphSide.TOP);
        link.toSides = SideSet.of(GraphSide.BOTTOM);
        model.links.add(link);

        String program = text(model);
        assertTrue(program.contains("INPUT 20 FROM a TOP, NORTH SIDE"), program);
        assertTrue(program.contains("OUTPUT 20 TO b BOTTOM SIDE"), program);
    }

    @Test
    void eachSideAndUnsetAreDistinct() {
        GraphModel model = model("a", "b", "c");
        GraphLink each = link("a", "b");
        each.fromSides = SideSet.EACH;
        model.links.add(each);
        GraphLink unset = link("b", "c");
        model.links.add(unset);

        String program = text(model);
        assertTrue(program.contains("FROM a EACH SIDE"), program);
        assertTrue(program.contains("FROM b\n"), program);
    }

    @Test
    void relativeSidesArePassedThrough() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.toSides = SideSet.of(GraphSide.FRONT, GraphSide.LEFT);
        model.links.add(link);

        assertTrue(text(model).contains("TO b FRONT, LEFT SIDE"));
    }

    @Test
    void roundRobinIsPlacedBeforeTheSideQualifier() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.roundRobin = RoundRobinMode.BY_BLOCK;
        link.fromSides = SideSet.of(GraphSide.TOP);
        model.links.add(link);

        // SFML grammar order is: label, round robin, sides, slots.
        assertTrue(text(model).contains("FROM a ROUND ROBIN BY BLOCK TOP SIDE"), text(model));
    }

    // ------------------------------------------------------------------
    // Slots, retention and filters
    // ------------------------------------------------------------------

    @Test
    void slotsAreEmittedAfterSides() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.fromSides = SideSet.of(GraphSide.TOP);
        link.fromSlots = "0-3, 5";
        link.toSlots = "1";
        model.links.add(link);

        String program = text(model);
        assertTrue(program.contains("FROM a TOP SIDE SLOTS 0-3,5"), program);
        assertTrue(program.contains("TO b SLOTS 1"), program);
    }

    @Test
    void invalidSlotsPreventCompilationOfThatLink() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.fromSlots = "5-1";
        model.links.add(link);

        SfmlCompiler.Plan plan = compile(model).plans().get(0);
        assertFalse(plan.compiled());
        assertTrue(plan.problem().contains("槽位"));
    }

    @Test
    void itemFilterIsWrittenBetweenTheQuantityAndFrom() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.resource = new ResourceSpec(ResourceKind.ITEM, "minecraft:iron_ingot");
        model.links.add(link);

        String program = text(model);
        assertTrue(program.contains("INPUT 20 minecraft:iron_ingot FROM a"), program);
        assertTrue(program.contains("OUTPUT 20 minecraft:iron_ingot TO b"), program);
    }

    @Test
    void fluidWithoutFilterStaysGeneric() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.resource = ResourceSpec.FLUIDS;
        model.links.add(link);

        assertTrue(text(model).contains("INPUT 20 fluid:: FROM a"));
    }

    /**
     * A fluid id needs three parts. Writing {@code fluid:water} would be two parts, which SFM reads
     * as an <em>item</em> id, so the empty namespace has to be kept explicit.
     */
    @Test
    void fluidWithoutNamespaceKeepsTheEmptyNamespace() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.resource = new ResourceSpec(ResourceKind.FLUID, "water");
        model.links.add(link);

        assertTrue(text(model).contains("INPUT 20 fluid::water FROM a"), text(model));

        GraphLink namespaced = link("b", "a");
        namespaced.resource = new ResourceSpec(ResourceKind.FLUID, "minecraft:water");
        model.links.add(namespaced);
        assertTrue(text(model).contains("fluid:minecraft:water"), text(model));
    }

    // ------------------------------------------------------------------
    // SFML keyword collisions
    // ------------------------------------------------------------------

    @Test
    void labelsThatAreKeywordsAreQuoted() {
        GraphModel model = model("in", "output");
        model.links.add(link("in", "output"));

        String program = text(model);
        // Bare `in` / `output` would lex as the IN and OUTPUT keywords and fail to parse.
        assertTrue(program.contains("INPUT 20 FROM \"in\""), program);
        assertTrue(program.contains("OUTPUT 20 TO \"output\""), program);
    }

    @Test
    void ordinaryLabelsAreNotQuoted() {
        GraphModel model = model("chest1", "furnace_2");
        model.links.add(link("chest1", "furnace_2"));

        String program = text(model);
        assertTrue(program.contains("FROM chest1"), program);
        assertTrue(program.contains("TO furnace_2"), program);
    }

    @Test
    void itemFilterThatIsAKeywordIsQuoted() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.resource = new ResourceSpec(ResourceKind.ITEM, "in");
        model.links.add(link);

        assertTrue(text(model).contains("INPUT 20 \"in\" FROM a"), text(model));
    }

    @Test
    void wildcardAndNamespacedFiltersStayBare() {
        GraphModel model = model("a", "b");
        GraphLink wildcard = link("a", "b");
        wildcard.resource = new ResourceSpec(ResourceKind.ITEM, "*ingot*");
        model.links.add(wildcard);
        GraphLink namespaced = link("b", "a");
        namespaced.resource = new ResourceSpec(ResourceKind.ITEM, "minecraft:iron_ingot");
        model.links.add(namespaced);

        String program = text(model);
        assertTrue(program.contains("*ingot*"), program);
        assertTrue(program.contains("minecraft:iron_ingot"), program);
        assertFalse(program.contains("\"*ingot*\""), program);
    }

    @Test
    void fluidFilterThatIsAKeywordIsRejected() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.resource = new ResourceSpec(ResourceKind.FLUID, "in");
        model.links.add(link);

        SfmlCompiler.Plan plan = compile(model).plans().get(0);
        assertFalse(plan.compiled());
        assertTrue(plan.problem().contains("关键字"), plan.problem());
    }

    @Test
    void retentionIsWrittenBeforeTheResourceId() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.retain = 5;
        link.resource = new ResourceSpec(ResourceKind.ITEM, "iron_ingot");
        model.links.add(link);

        String program = text(model);
        assertTrue(program.contains("INPUT 20 RETAIN 5 iron_ingot FROM a"), program);
        assertTrue(program.contains("OUTPUT 20 RETAIN 5 iron_ingot TO b"), program);
    }

    @Test
    void eachFlagsAreWrittenInTheRightPlaces() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.fromEach = true;
        link.toEach = true;
        link.quantityEach = true;
        model.links.add(link);

        String program = text(model);
        assertTrue(program.contains("INPUT 20 EACH FROM EACH a"), program);
        assertTrue(program.contains("OUTPUT 20 EACH TO EACH b"), program);
    }

    @Test
    void emptySlotsOnlyOutputIsWrittenAfterTo() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.emptySlotsOnly = true;
        model.links.add(link);

        assertTrue(text(model).contains("OUTPUT 20 TO EMPTY SLOTS IN b"));
    }

    // ------------------------------------------------------------------
    // Triggers and conditions
    // ------------------------------------------------------------------

    @Test
    void redstonePulseLinksHaveNoInterval() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.trigger = TriggerMode.REDSTONE_PULSE;
        link.rate = "8";
        model.links.add(link);

        String program = text(model);
        assertTrue(program.contains("EVERY REDSTONE PULSE DO"), program);
        // With a pulse trigger the amount is per pulse, not a rate.
        assertTrue(program.contains("INPUT 8 FROM a"), program);
    }

    @Test
    void redstoneConditionWrapsTheStatements() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.condition = new LinkCondition(LinkCondition.Kind.REDSTONE, 0);
        model.links.add(link);

        String program = text(model);
        assertTrue(program.contains("IF REDSTONE THEN"), program);
        assertTrue(program.contains("    END"), program);
    }

    @Test
    void sourceHasConditionUsesTheSourceLabelAndResource() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.resource = new ResourceSpec(ResourceKind.ITEM, "minecraft:coal");
        link.condition = new LinkCondition(LinkCondition.Kind.SOURCE_HAS_GT, 128);
        model.links.add(link);

        assertTrue(text(model).contains("IF a HAS GT 128 minecraft:coal THEN"), text(model));
    }

    // ------------------------------------------------------------------
    // Fan out: one node feeding several others
    // ------------------------------------------------------------------

    @Test
    void oneNodeCanFeedSeveralTargets() {
        GraphModel model = model("ore", "a", "b", "c");
        for (String target : List.of("a", "b", "c")) {
            GraphLink link = link("ore", target);
            link.rate = "1";
            model.links.add(link);
        }

        SfmlCompiler.Result result = compile(model);
        assertEquals(3, result.plans().stream().filter(SfmlCompiler.Plan::compiled).count());
        assertEquals(3, result.text().split("EVERY ", -1).length - 1, result.text());
        for (String target : List.of("a", "b", "c")) {
            assertTrue(result.text().contains("OUTPUT 20 TO " + target), result.text());
        }
    }

    @Test
    void duplicateLinksAreRejectedButDifferentTargetsAreNot() {
        GraphModel model = model("ore", "a", "b");
        assertTrue(model.addLink(link("ore", "a")), "first link to a");
        assertTrue(model.addLink(link("ore", "b")), "a second target is not a duplicate");
        assertFalse(model.addLink(link("ore", "a")), "the exact same link again is a duplicate");

        // Same endpoints, different resource: a legitimate pair of links (items and power side by side).
        GraphLink power = link("ore", "a");
        power.resource = ResourceSpec.ENERGY;
        assertTrue(model.addLink(power), "a different resource kind is not a duplicate");

        // Same endpoints, different faces: also legitimate.
        GraphLink topFace = link("ore", "a");
        topFace.fromSides = SideSet.of(GraphSide.TOP);
        assertTrue(model.addLink(topFace), "a different face is not a duplicate");
    }

    @Test
    void oneEnergySourceCanPowerSeveralMachines() {
        GraphModel model = model("generator", "machineA", "machineB");
        for (String machine : List.of("machineA", "machineB")) {
            GraphLink power = link("generator", machine);
            power.resource = ResourceSpec.ENERGY;
            power.rate = "2000";
            model.links.add(power);
        }

        String program = text(model);
        assertTrue(program.contains("OUTPUT 2000 sfm:forge_energy:forge:energy TO machineA"), program);
        assertTrue(program.contains("OUTPUT 2000 sfm:forge_energy:forge:energy TO machineB"), program);
        assertTrue(program.contains("电力 4000 FE/tick"), program);
    }

    // ------------------------------------------------------------------
    // Automatic power feeds (no drawn link)
    // ------------------------------------------------------------------

    @Test
    void poweredNodesGetAnAutomaticEnergyFeedWithoutLinks() {
        GraphModel model = model("generator", "machine");
        model.node("generator").energySource = true;
        GraphNode machine = model.node("machine");
        machine.poweredBy = "generator";
        machine.powerRate = "2500";

        SfmlCompiler.Result result = compile(model);
        assertTrue(model.links.isEmpty(), "the point of an automatic feed is that no link is needed");

        String program = result.text();
        // Energy only triggers may run every tick, which is what makes a per tick rate exact.
        assertTrue(program.contains("EVERY 1 TICKS DO"), program);
        // The faces have to be named: a direction-less lookup is what machines fail to answer.
        assertTrue(program.contains("INPUT 2500 sfm:forge_energy:forge:energy FROM EACH generator BACK SIDE"),
                   program);
        // EACH keeps a label that covers several machines from sharing one budget.
        assertTrue(program.contains("OUTPUT 2500 sfm:forge_energy:forge:energy TO EACH machine BACK SIDE"),
                   program);
        assertTrue(program.contains("供电（自动生成"), program);
    }

    @Test
    void powerFeedSidesAreConfigurable() {
        GraphModel model = model("generator", "machine");
        GraphNode machine = model.node("machine");
        machine.poweredBy = "generator";

        machine.powerSides = SideSet.EACH;
        assertTrue(text(model).contains("TO EACH machine EACH SIDE"), text(model));

        machine.powerSides = SideSet.UNSET;
        assertTrue(text(model).contains("TO EACH machine\n"), text(model));

        machine.powerSides = SideSet.of(GraphSide.BACK, GraphSide.TOP);
        assertTrue(text(model).contains("TO EACH machine TOP, BACK SIDE"), text(model));
    }

    @Test
    void severalPoweredNodesShareOneSource() {
        GraphModel model = model("generator", "a", "b", "c");
        for (String label : List.of("a", "b", "c")) {
            GraphNode node = model.node(label);
            node.poweredBy = "generator";
            node.powerRate = "1000";
        }

        SfmlCompiler.Result result = compile(model);
        assertEquals(3, result.text().split("EVERY ", -1).length - 1);
        assertTrue(result.text().contains("电力 3000 FE/tick"), result.text());
    }

    @Test
    void poweringANodeFromAMissingSourceIsReported() {
        GraphModel model = model("machine");
        model.node("machine").poweredBy = "ghost";

        SfmlCompiler.Plan plan = compile(model).plans().get(0);
        assertFalse(plan.compiled());
        assertTrue(plan.problem().contains("ghost"), plan.problem());
    }

    @Test
    void duplicatePowerIsWarnedAbout() {
        GraphModel model = model("generator", "machine");
        model.node("machine").poweredBy = "generator";
        GraphLink drawn = link("generator", "machine");
        drawn.resource = ResourceSpec.ENERGY;
        model.links.add(drawn);

        SfmlCompiler.Result result = compile(model);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("重复供电")),
                   result.warnings().toString());
    }

    // ------------------------------------------------------------------
    // Mekanism chemicals
    // ------------------------------------------------------------------

    @Test
    void chemicalIdsCarryTheirType() {
        GraphModel model = model("tankA", "tankB");

        GraphLink any = link("tankA", "tankB");
        any.resource = ResourceSpec.of(ResourceKind.CHEMICAL);
        model.links.add(any);
        assertTrue(text(model).contains("INPUT 20 chemical:: FROM tankA"), text(model));

        GraphLink named = link("tankB", "tankA");
        named.resource = new ResourceSpec(ResourceKind.CHEMICAL, "hydrogen");
        model.links.add(named);
        assertTrue(text(model).contains("chemical::hydrogen"), text(model));

        GraphLink namespaced = link("tankB", "tankA");
        namespaced.resource = new ResourceSpec(ResourceKind.CHEMICAL, "mekanism:hydrogen");
        model.links.add(namespaced);
        assertTrue(text(model).contains("chemical:mekanism:hydrogen"), text(model));
    }

    @Test
    void chemicalIdsUseMillibucketsAsTheirUnit() {
        assertEquals("mB", ResourceKind.CHEMICAL.unit());
        GraphModel model = model("a", "b");
        GraphLink chemical = link("a", "b");
        chemical.resource = ResourceSpec.of(ResourceKind.CHEMICAL);
        model.links.add(chemical);
        assertTrue(text(model).contains("化学品 1 mB/tick"), text(model));
    }

    @Test
    void chemicalIdThatIsAKeywordIsRejected() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.resource = new ResourceSpec(ResourceKind.CHEMICAL, "in");
        model.links.add(link);

        SfmlCompiler.Plan plan = compile(model).plans().get(0);
        assertFalse(plan.compiled());
        assertTrue(plan.problem().contains("化学品"), plan.problem());
    }

    @Test
    void chemicalsDoNotGetTheEnergyIntervalCarveOut() {
        GraphModel model = model("a", "b");
        GraphLink chemical = link("a", "b");
        chemical.resource = ResourceSpec.of(ResourceKind.CHEMICAL);
        chemical.rate = "1000";
        model.links.add(chemical);

        // Only Forge Energy triggers may run every tick, so a chemical link stays on the normal interval.
        SfmlCompiler.Plan plan = compile(model).plans().get(0);
        assertEquals(20, plan.intervalTicks());
        assertEquals(20000, plan.amountPerFiring());
    }

    // ------------------------------------------------------------------
    // Shared budgets across a label with several blocks
    // ------------------------------------------------------------------

    private static SfmlCompiler.Result compileWithCounts(GraphModel model, Map<String, Integer> blockCounts) {
        Map<String, LabelSummary> labels = new java.util.HashMap<>();
        blockCounts.forEach((label, count) -> labels.put(label, new LabelSummary(count, count)));
        return compileWithLabels(model, labels);
    }

    private static SfmlCompiler.Result compileWithLabels(
            GraphModel model,
            Map<String, LabelSummary> labels
    ) {
        return SfmlCompiler.compile(
                model,
                new SfmlCompiler.Options(SfmlCompiler.Limits.SFM_DEFAULTS, null, null, labels)
        );
    }

    /**
     * The trap that is invisible in game: SFM silently skips every labelled block that is not adjacent
     * to a cable of the manager's network, so a label covering four machines where only one touches a
     * cable works for exactly one machine — no matter what the statement says.
     */
    @Test
    void blocksThatDoNotTouchACableAreWarnedAbout() {
        GraphModel model = model("crushers", "chest");
        GraphLink link = link("chest", "crushers");
        link.toEach = true;
        model.links.add(link);

        SfmlCompiler.Result result = compileWithLabels(model, Map.of(
                "crushers", new LabelSummary(4, 1),
                "chest", new LabelSummary(1, 1)
        ));

        assertTrue(
                result.warnings().stream().anyMatch(w -> w.contains("紧贴线缆")),
                result.warnings().toString()
        );
    }

    @Test
    void fullyConnectedLabelsProduceNoCableWarning() {
        GraphModel model = model("crushers", "chest");
        GraphLink link = link("chest", "crushers");
        link.toEach = true;
        model.links.add(link);

        SfmlCompiler.Result result = compileWithLabels(model, Map.of(
                "crushers", new LabelSummary(4, 4),
                "chest", new LabelSummary(1, 1)
        ));

        assertTrue(
                result.warnings().stream().noneMatch(w -> w.contains("紧贴线缆")),
                result.warnings().toString()
        );
    }

    @Test
    void aLabelWithSeveralBlocksWithoutEachIsWarnedAbout() {
        GraphModel model = model("machines", "chest");
        GraphLink link = link("chest", "machines");
        model.links.add(link);

        SfmlCompiler.Result result = compileWithCounts(model, Map.of("machines", 3, "chest", 1));
        assertTrue(
                result.warnings().stream().anyMatch(w -> w.contains("只有其中一个会收到")),
                result.warnings().toString()
        );
    }

    @Test
    void theWarningIsSilencedByTurningEachOn() {
        GraphModel model = model("machines", "chest");
        GraphLink link = link("chest", "machines");
        model.links.add(link);

        Map<String, Integer> counts = Map.of("machines", 3, "chest", 1);
        link.toEach = true;
        assertTrue(
                compileWithCounts(model, counts).warnings().stream()
                        .noneMatch(w -> w.contains("只有其中一个会收到")),
                "EACH gives every block its own budget, so there is nothing to warn about"
        );

        link.fromEach = false;
        link.toEach = false;
        link.fromLabel = "machines";
        link.toLabel = "chest";
        assertTrue(
                compileWithCounts(model, counts).warnings().stream()
                        .anyMatch(w -> w.contains("只有其中一个会被抽取")),
                "the source side has the same trap"
        );
    }

    @Test
    void eachOnAMultiBlockLabelCompilesPerBlockBudget() {
        GraphModel model = model("machines", "chest");
        GraphLink link = link("chest", "machines");
        link.toEach = true;
        model.links.add(link);

        assertTrue(text(model).contains("OUTPUT 20 TO EACH machines"), text(model));
    }

    @Test
    void eachLinkGetsItsOwnTriggerBlock() {
        GraphModel model = model("a", "b", "c");
        GraphLink first = link("a", "b");
        first.rate = "1";
        GraphLink second = link("b", "c");
        second.rate = "1000";
        second.resource = ResourceSpec.ENERGY;
        model.links.add(first);
        model.links.add(second);

        String program = text(model);
        assertEquals(2, program.split("EVERY ", -1).length - 1);
        assertTrue(program.contains("EVERY 20 TICKS DO"), program);
        assertTrue(program.contains("EVERY 1 TICKS DO"), program);
    }

    // ------------------------------------------------------------------
    // Validation and warnings
    // ------------------------------------------------------------------

    @Test
    void invalidResourceIdIsRejectedRatherThanEmitted() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.resource = new ResourceSpec(ResourceKind.ITEM, "minecraft:iron ingot");
        model.links.add(link);

        SfmlCompiler.Plan plan = compile(model).plans().get(0);
        assertFalse(plan.compiled());
        assertTrue(plan.problem().contains("资源 ID"), plan.problem());
    }

    @Test
    void nonNumericRateIsRejected() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.rate = "fast";
        model.links.add(link);

        assertFalse(compile(model).plans().get(0).compiled());
    }

    @Test
    void zeroRateIsRejected() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.rate = "0";
        model.links.add(link);

        assertFalse(compile(model).plans().get(0).compiled());
    }

    @Test
    void disabledLinksProduceNoStatements() {
        GraphModel model = model("a", "b");
        GraphLink link = link("a", "b");
        link.enabled = false;
        model.links.add(link);

        SfmlCompiler.Result result = compile(model);
        assertFalse(result.plans().get(0).compiled());
        assertFalse(result.text().contains("INPUT"));
    }

    @Test
    void linkToAnUnknownLabelIsReported() {
        GraphModel model = model("a");
        model.links.add(link("a", "ghost"));

        SfmlCompiler.Plan plan = compile(model).plans().get(0);
        assertFalse(plan.compiled());
        assertTrue(plan.problem().contains("ghost"), plan.problem());
    }

    @Test
    void labelsNeedingQuotesAreQuoted() {
        GraphModel model = model("iron ore");
        model.links.add(link("iron ore", "iron ore"));

        String program = text(model);
        assertTrue(program.contains("FROM \"iron ore\""), program);
        assertTrue(program.contains("TO \"iron ore\""), program);
    }

    @Test
    void emptyGraphStillProducesAValidProgram() {
        GraphModel model = model();
        SfmlCompiler.Result result = compile(model);
        assertTrue(result.text().contains("name \"test\""));
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void headerReportsPerTickTotals() {
        GraphModel model = model("a", "b", "c");
        GraphLink items = link("a", "b");
        items.rate = "4";
        GraphLink energy = link("b", "c");
        energy.rate = "2000";
        energy.resource = ResourceSpec.ENERGY;
        model.links.add(items);
        model.links.add(energy);

        String program = text(model);
        assertTrue(program.contains("物品 4/tick"), program);
        assertTrue(program.contains("电力 2000 FE/tick"), program);
    }

    @Test
    void blobIsEmbeddedWhenProvided() {
        GraphModel model = model("a", "b");
        model.links.add(link("a", "b"));
        String blob = GraphCodec.encode(model);

        SfmlCompiler.Result result = SfmlCompiler.compile(
                model,
                new SfmlCompiler.Options(SfmlCompiler.Limits.SFM_DEFAULTS, blob, "test", Map.of())
        );
        assertTrue(result.text().contains(GraphCodec.MARKER_BEGIN));
        assertEquals(blob, GraphCodec.extractBlob(result.text()));
        // The embedded graph must survive a round trip through the generated program.
        GraphModel decoded = GraphCodec.decodeProgram(result.text());
        assertEquals(1, decoded.links.size());
    }

    @Test
    void bodyContainsExactlyOneTriggerPerEnabledLink() {
        GraphModel model = model("a", "b", "c");
        model.links.add(link("a", "b"));
        model.links.add(link("b", "c"));
        String program = body(text(model));
        assertEquals(2, program.split("END", -1).length - 1);
        List<String> lines = List.of(program.split("\n"));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("EVERY")));
    }
}
