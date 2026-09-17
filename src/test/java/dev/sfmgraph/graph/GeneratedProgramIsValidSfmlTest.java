package dev.sfmgraph.graph;

import ca.teamdman.langs.SFMLLexer;
import ca.teamdman.langs.SFMLParser;
import ca.teamdman.sfml.ast.ASTBuilder;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles generated programs with Super Factory Manager's own parser and AST builder.
 *
 * <p>This is the check that matters: the compiler produces SFML by text, so the only real proof that
 * it produces <em>valid</em> SFML is to hand it to SFM. The AST pass also enforces SFM's minimum
 * trigger interval rule, which is the subtlest part of the rate translation.
 */
class GeneratedProgramIsValidSfmlTest {

    /**
     * Runs the program through SFM's lexer, parser and AST builder.
     *
     * @return a list of problems; empty means SFM accepted the program
     */
    private static List<String> validate(String program) {
        List<String> problems = new ArrayList<>();

        SFMLLexer lexer = new SFMLLexer(CharStreams.fromString(program));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        SFMLParser parser = new SFMLParser(tokens);
        parser.removeErrorListeners();

        BaseErrorListener listener = new BaseErrorListener() {
            @Override
            public void syntaxError(
                    Recognizer<?, ?> recognizer,
                    Object offendingSymbol,
                    int line,
                    int charPositionInLine,
                    String message,
                    RecognitionException exception
            ) {
                problems.add("syntax error at line " + line + ":" + charPositionInLine + " " + message);
            }
        };
        lexer.addErrorListener(listener);
        parser.addErrorListener(listener);

        SFMLParser.ProgramContext context = parser.program();
        if (!problems.isEmpty()) {
            return problems;
        }
        try {
            // Includes the resource id checks and the minimum interval rule.
            new ASTBuilder().visitProgram(context);
        } catch (Throwable t) {
            problems.add(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return problems;
    }

    private static String compile(GraphModel model) {
        return SfmlCompiler.compile(
                model,
                new SfmlCompiler.Options(SfmlCompiler.Limits.SFM_DEFAULTS, GraphCodec.encode(model), "test", Map.of())
        ).text();
    }

    private static GraphModel model(String... labels) {
        GraphModel model = new GraphModel();
        model.name = "generated";
        for (String label : labels) model.ensureNode(label);
        return model;
    }

    // ------------------------------------------------------------------

    @Test
    void simpleItemMoveIsValid() {
        GraphModel model = model("a", "b");
        model.links.add(new GraphLink("a", "b"));
        assertValid(model);
    }

    @Test
    void everyFaceCombinationIsValid() {
        GraphModel model = model("a", "b");
        GraphLink link = new GraphLink("a", "b");
        link.fromSides = SideSet.of(GraphSide.TOP, GraphSide.BOTTOM, GraphSide.NORTH,
                GraphSide.SOUTH, GraphSide.EAST, GraphSide.WEST);
        link.toSides = SideSet.of(GraphSide.FRONT, GraphSide.BACK, GraphSide.LEFT, GraphSide.RIGHT);
        model.links.add(link);
        assertValid(model);
    }

    @Test
    void eachSideIsValid() {
        GraphModel model = model("a", "b");
        GraphLink link = new GraphLink("a", "b");
        link.fromSides = SideSet.EACH;
        link.toSides = SideSet.EACH;
        model.links.add(link);
        assertValid(model);
    }

    @Test
    void slotsRetentionAndFiltersAreValid() {
        GraphModel model = model("a", "b");
        GraphLink link = new GraphLink("a", "b");
        link.fromSides = SideSet.of(GraphSide.NORTH);
        link.fromSlots = "0-3,7,9-12";
        link.toSlots = "0";
        link.retain = 12;
        link.resource = new ResourceSpec(ResourceKind.ITEM, "minecraft:iron_ingot");
        link.rate = "3";
        model.links.add(link);
        assertValid(model);
    }

    @Test
    void wildcardItemFilterIsValid() {
        GraphModel model = model("a", "b");
        GraphLink link = new GraphLink("a", "b");
        link.resource = new ResourceSpec(ResourceKind.ITEM, "*ingot*");
        link.quantityEach = true;
        model.links.add(link);
        assertValid(model);
    }

    @Test
    void fluidTransferIsValid() {
        GraphModel model = model("tankA", "tankB");
        GraphLink plain = new GraphLink("tankA", "tankB");
        plain.resource = ResourceSpec.FLUIDS;
        plain.rate = "500";
        model.links.add(plain);

        GraphLink specific = new GraphLink("tankB", "tankA");
        specific.resource = new ResourceSpec(ResourceKind.FLUID, "minecraft:water");
        specific.rate = "100";
        model.links.add(specific);
        assertValid(model);
    }

    @Test
    void energyAtOneTickPerTickIsValid() {
        GraphModel model = model("generator", "machine");
        GraphLink link = new GraphLink("generator", "machine");
        link.resource = ResourceSpec.ENERGY;
        link.rate = "20000";
        link.fromSides = SideSet.of(GraphSide.TOP);
        link.toSides = SideSet.of(GraphSide.BOTTOM);
        model.links.add(link);
        assertValid(model);
    }

    /**
     * The energy carve out only applies when a trigger touches nothing but Forge Energy, so this
     * checks the mixed case stays on the regular interval and is still legal.
     */
    @Test
    void mixedItemAndEnergyProgramIsValid() {
        GraphModel model = model("chest", "furnace", "generator");
        GraphLink items = new GraphLink("chest", "furnace");
        items.rate = "8";
        model.links.add(items);

        GraphLink energy = new GraphLink("generator", "furnace");
        energy.resource = ResourceSpec.ENERGY;
        energy.rate = "5000";
        model.links.add(energy);
        assertValid(model);
    }

    @Test
    void eachAndRoundRobinVariantsAreValid() {
        GraphModel model = model("ins", "outs");
        for (RoundRobinMode mode : RoundRobinMode.values()) {
            GraphLink link = new GraphLink("ins", "outs");
            link.roundRobin = mode;
            link.fromEach = true;
            link.toEach = true;
            link.rate = "2";
            model.links.add(link);
        }
        assertValid(model);
    }

    @Test
    void emptySlotsVariantIsValid() {
        GraphModel model = model("in", "out");
        GraphLink link = new GraphLink("in", "out");
        link.emptySlotsOnly = true;
        link.toEach = true;
        model.links.add(link);
        assertValid(model);
    }

    @Test
    void conditionsAreValid() {
        GraphModel model = model("source", "target");
        GraphLink redstone = new GraphLink("source", "target");
        redstone.condition = new LinkCondition(LinkCondition.Kind.REDSTONE, 0);
        model.links.add(redstone);

        GraphLink has = new GraphLink("source", "target");
        has.resource = new ResourceSpec(ResourceKind.ITEM, "minecraft:coal");
        has.condition = new LinkCondition(LinkCondition.Kind.SOURCE_HAS_GT, 256);
        model.links.add(has);

        GraphLink hasAny = new GraphLink("source", "target");
        hasAny.condition = new LinkCondition(LinkCondition.Kind.SOURCE_HAS_GT, 10);
        model.links.add(hasAny);
        assertValid(model);
    }

    @Test
    void redstonePulseTriggerIsValid() {
        GraphModel model = model("a", "b");
        GraphLink link = new GraphLink("a", "b");
        link.trigger = TriggerMode.REDSTONE_PULSE;
        link.rate = "16";
        model.links.add(link);
        assertValid(model);
    }

    @Test
    void quotedLabelsAndUnicodeLabelsAreValid() {
        GraphModel model = model("iron ore", "熔炉");
        GraphLink link = new GraphLink("iron ore", "熔炉");
        link.rate = "1";
        model.links.add(link);
        assertValid(model);
    }

    /**
     * Labels and filters that collide with SFML keywords used to produce programs SFM cannot parse;
     * they now get quoted, and this checks SFM agrees.
     */
    @Test
    void keywordLabelsAndFiltersAreValid() {
        GraphModel model = model("in", "output", "each", "to");
        GraphLink first = new GraphLink("in", "output");
        first.resource = new ResourceSpec(ResourceKind.ITEM, "in");
        model.links.add(first);

        GraphLink second = new GraphLink("each", "to");
        second.rate = "4";
        model.links.add(second);
        assertValid(model);
    }

    @Test
    void fluidWithoutNamespaceIsValid() {
        GraphModel model = model("tankA", "tankB");
        GraphLink link = new GraphLink("tankA", "tankB");
        link.resource = new ResourceSpec(ResourceKind.FLUID, "water");
        link.rate = "250";
        model.links.add(link);
        assertValid(model);
    }

    @Test
    void automaticPowerFeedsAreValid() {
        GraphModel model = model("generator", "machineOne", "machineTwo");
        model.node("generator").energySource = true;
        for (String label : List.of("machineOne", "machineTwo")) {
            GraphNode node = model.node(label);
            node.poweredBy = "generator";
            node.powerRate = "2000";
        }
        assertValid(model);
    }

    /**
     * One label covering several machines is the case that silently broke: without {@code EACH} SFM
     * shares a single budget and only the first machine is served.
     */
    @Test
    void eachAcrossASharedLabelIsValid() {
        GraphModel model = model("sink");
        GraphLink link = new GraphLink("machines", "sink");
        link.toEach = true;
        link.rate = "8";
        model.ensureNode("machines");
        model.links.add(link);
        assertValid(model);
    }

    /**
     * The id has to parse with SFM's own grammar. Whether the {@code chemical} resource type is
     * actually registered is a runtime question (it needs Mekanism), which the editor answers with
     * SFM's full compiler before saving.
     */
    @Test
    void chemicalResourceIdsParse() {
        GraphModel model = model("chemTank", "machine");
        GraphLink any = new GraphLink("chemTank", "machine");
        any.resource = ResourceSpec.of(ResourceKind.CHEMICAL);
        any.rate = "500";
        model.links.add(any);

        GraphLink named = new GraphLink("machine", "chemTank");
        named.resource = new ResourceSpec(ResourceKind.CHEMICAL, "mekanism:hydrogen");
        named.rate = "250";
        model.links.add(named);
        assertValid(model);
    }

    @Test
    void oneGeneratorPoweringSeveralMachinesIsValid() {
        GraphModel model = model("generator", "furnace", "crusher", "charger");
        for (String machine : List.of("furnace", "crusher", "charger")) {
            GraphLink power = new GraphLink("generator", machine);
            power.resource = ResourceSpec.ENERGY;
            power.rate = "2000";
            power.toSides = SideSet.of(GraphSide.NORTH);
            model.links.add(power);
        }
        assertValid(model);
    }

    @Test
    void aNodeFeedingItemsAndPowerDownDifferentLinksIsValid() {
        GraphModel model = model("hub", "machineA", "machineB");
        GraphLink items = new GraphLink("hub", "machineA");
        items.rate = "4";
        model.links.add(items);

        GraphLink power = new GraphLink("hub", "machineB");
        power.resource = ResourceSpec.ENERGY;
        power.rate = "1000";
        model.links.add(power);

        // Same endpoints twice, different resource kinds: items on one link, power on the other.
        GraphLink alsoPower = new GraphLink("hub", "machineA");
        alsoPower.resource = ResourceSpec.ENERGY;
        alsoPower.rate = "500";
        model.links.add(alsoPower);
        assertValid(model);
    }

    @Test
    void awkwardRatesAreValid() {
        GraphModel model = model("a", "b");
        for (String rate : List.of("0.001", "0.5", "1.5", "7", "333", "100000")) {
            GraphLink link = new GraphLink("a", "b");
            link.rate = rate;
            model.links.add(link);
        }
        assertValid(model);
    }

    @Test
    void aLargeRealisticProgramIsValid() {
        GraphModel model = model("oreIn", "furnace", "fuelIn", "output", "energy", "trash");
        addLink(model, "oreIn", "furnace", spec -> {
            spec.resource = new ResourceSpec(ResourceKind.ITEM, "minecraft:raw_iron");
            spec.fromSides = SideSet.of(GraphSide.TOP);
            spec.toSides = SideSet.of(GraphSide.TOP);
            spec.rate = "4";
            spec.fromSlots = "0-8";
        });
        addLink(model, "fuelIn", "furnace", spec -> {
            spec.resource = new ResourceSpec(ResourceKind.ITEM, "minecraft:coal");
            spec.toSides = SideSet.of(GraphSide.EAST);
            spec.rate = "1";
        });
        addLink(model, "furnace", "output", spec -> {
            spec.resource = new ResourceSpec(ResourceKind.ITEM, "minecraft:iron_ingot");
            spec.fromSides = SideSet.of(GraphSide.BOTTOM);
            spec.rate = "8";
            spec.condition = new LinkCondition(LinkCondition.Kind.SOURCE_HAS_GT, 32);
        });
        addLink(model, "energy", "furnace", spec -> {
            spec.resource = ResourceSpec.ENERGY;
            spec.rate = "2000";
            spec.toSides = SideSet.of(GraphSide.NORTH);
        });
        addLink(model, "output", "trash", spec -> {
            spec.resource = new ResourceSpec(ResourceKind.ITEM, "*nugget*");
            spec.rate = "0.5";
            spec.quantityEach = true;
            spec.retain = 64;
        });
        assertValid(model);
    }

    private interface Customizer {
        void apply(GraphLink link);
    }

    private static void addLink(GraphModel model, String from, String to, Customizer customizer) {
        GraphLink link = new GraphLink(from, to);
        customizer.apply(link);
        model.links.add(link);
    }

    private static void assertValid(GraphModel model) {
        String program = compile(model);
        List<String> problems = validate(program);
        assertTrue(problems.isEmpty(), () -> "SFM rejected the generated program:\n"
                                             + String.join("\n", problems) + "\n--- program ---\n" + program);
    }

    // ------------------------------------------------------------------
    // The validator itself must be able to fail
    // ------------------------------------------------------------------

    @Test
    void validatorRejectsProgramsSfmWouldReject() {
        // Wrong argument order: SFM's grammar puts the quantity before the resource id.
        assertTrue(!validate("name \"x\"\nEVERY 20 TICKS DO\nOUTPUT energy 1000 TO b\nEND\n").isEmpty());

        // Malformed statement.
        assertTrue(!validate("name \"x\"\nEVERY 20 TICKS DO\nINPUT FROM\nEND\n").isEmpty());

        // Interval below the server minimum for items.
        assertTrue(!validate("name \"x\"\nEVERY 1 TICKS DO\nINPUT 1 FROM a\nOUTPUT 1 TO b\nEND\n").isEmpty());

        // A valid program must still pass, so the assertions above are not vacuous.
        assertEquals(List.of(), validate("name \"x\"\nEVERY 20 TICKS DO\nINPUT 1 FROM a\nOUTPUT 1 TO b\nEND\n"));
    }
}
