package dev.sfmgraph.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round trip tests for the graph that travels inside the program as a comment block. */
class GraphCodecTest {

    private static GraphModel sampleGraph() {
        GraphModel model = new GraphModel();
        model.name = "冶炼线";
        GraphNode furnace = model.ensureNode("furnace");
        furnace.x = 120.5;
        furnace.y = 44;
        furnace.note = "输入矿石";

        GraphLink link = new GraphLink("ore", "furnace");
        link.fromSides = SideSet.of(GraphSide.TOP, GraphSide.NORTH);
        link.toSides = SideSet.EACH;
        link.fromSlots = "0-3,7";
        link.toSlots = "1";
        link.resource = new ResourceSpec(ResourceKind.ITEM, "minecraft:raw_iron");
        link.rate = "1.5";
        link.retain = 8;
        link.trigger = TriggerMode.TIMER;
        link.roundRobin = RoundRobinMode.BY_BLOCK;
        link.condition = new LinkCondition(LinkCondition.Kind.SOURCE_HAS_GT, 64);
        link.fromEach = true;
        link.toEach = true;
        link.quantityEach = true;
        link.emptySlotsOnly = true;
        link.enabled = false;
        model.links.add(link);
        model.ensureNode("ore");
        return model;
    }

    @Test
    void roundTripPreservesEveryField() {
        GraphModel original = sampleGraph();
        GraphModel decoded = GraphCodec.decode(GraphCodec.encode(original));

        assertNotNull(decoded);
        assertEquals(original.name, decoded.name);
        assertEquals(original.nodes.size(), decoded.nodes.size());
        assertEquals(original.links.size(), decoded.links.size());

        GraphNode originalFurnace = original.node("furnace");
        GraphNode decodedFurnace = decoded.node("furnace");
        assertNotNull(decodedFurnace);
        assertEquals(originalFurnace.x, decodedFurnace.x);
        assertEquals(originalFurnace.y, decodedFurnace.y);
        assertEquals(originalFurnace.note, decodedFurnace.note);

        GraphLink before = original.links.get(0);
        GraphLink after = decoded.links.get(0);
        assertEquals(before.id, after.id);
        assertEquals(before.fromLabel, after.fromLabel);
        assertEquals(before.toLabel, after.toLabel);
        assertEquals(before.fromSides, after.fromSides);
        assertEquals(before.toSides, after.toSides);
        assertEquals(before.fromSlots, after.fromSlots);
        assertEquals(before.toSlots, after.toSlots);
        assertEquals(before.resource, after.resource);
        assertEquals(before.rate, after.rate);
        assertEquals(before.retain, after.retain);
        assertEquals(before.trigger, after.trigger);
        assertEquals(before.roundRobin, after.roundRobin);
        assertEquals(before.condition, after.condition);
        assertEquals(before.fromEach, after.fromEach);
        assertEquals(before.toEach, after.toEach);
        assertEquals(before.quantityEach, after.quantityEach);
        assertEquals(before.emptySlotsOnly, after.emptySlotsOnly);
        assertEquals(before.enabled, after.enabled);
    }

    @Test
    void energySourceFlagRoundTrips() {
        GraphModel model = new GraphModel();
        GraphNode generator = model.ensureNode("generator");
        generator.energySource = true;
        model.ensureNode("machine");

        GraphModel decoded = GraphCodec.decode(GraphCodec.encode(model));

        assertNotNull(decoded);
        assertNotNull(decoded.node("generator"));
        assertTrue(decoded.node("generator").energySource, "the energy source mark has to survive a save");
        assertFalse(decoded.node("machine").energySource);
    }

    @Test
    void powerAssignmentRoundTrips() {
        GraphModel model = new GraphModel();
        GraphNode generator = model.ensureNode("generator");
        generator.energySource = true;
        GraphNode machine = model.ensureNode("machine");
        machine.poweredBy = "generator";
        machine.powerRate = "2500";

        GraphModel decoded = GraphCodec.decode(GraphCodec.encode(model));

        assertNotNull(decoded);
        GraphNode decodedMachine = decoded.node("machine");
        assertNotNull(decodedMachine);
        assertEquals("generator", decodedMachine.poweredBy);
        assertEquals("2500", decodedMachine.powerRate);
        assertTrue(decodedMachine.isPowered());
        // The BACK default is what makes the capability resolve, so it has to survive a save.
        assertEquals(SideSet.of(GraphSide.BACK), decodedMachine.powerSides);
        assertTrue(decoded.node("generator").energySource);

        // An unpowered node must stay unpowered, i.e. the default has to be blank rather than "null".
        GraphNode plain = decoded.ensureNode("fresh");
        assertFalse(plain.isPowered());
    }

    @Test
    void customPowerSidesRoundTrip() {
        GraphModel model = new GraphModel();
        GraphNode machine = model.ensureNode("machine");
        machine.poweredBy = "generator";
        machine.powerSides = SideSet.of(GraphSide.TOP, GraphSide.BOTTOM);
        model.ensureNode("generator");

        GraphModel decoded = GraphCodec.decode(GraphCodec.encode(model));

        assertNotNull(decoded);
        assertEquals(SideSet.of(GraphSide.TOP, GraphSide.BOTTOM), decoded.node("machine").powerSides);
    }

    @Test
    void survivesBeingEmbeddedInAProgram() {
        GraphModel original = sampleGraph();
        String program = "-- a program\nname \"x\"\n\n"
                         + GraphCodec.wrapAsComments(GraphCodec.encode(original))
                         + "\nEVERY 20 TICKS DO\nEND\n";

        assertTrue(GraphCodec.hasGraph(program));
        GraphModel decoded = GraphCodec.decodeProgram(program);
        assertNotNull(decoded);
        assertEquals(original.links.size(), decoded.links.size());
        assertEquals(original.name, decoded.name);
    }

    @Test
    void programWithoutABlobHasNoGraph() {
        assertFalse(GraphCodec.hasGraph("name \"hand written\"\nEVERY 20 TICKS DO\nEND\n"));
        assertNull(GraphCodec.decodeProgram("name \"hand written\"\n"));
    }

    @Test
    void garbageBlobDecodesToNullInsteadOfThrowing() {
        String program = "-- @sfmgraph:v1 BEGIN\n-- not-base64-at-all!!!\n-- @sfmgraph:v1 END\n";
        assertNull(GraphCodec.decodeProgram(program));
    }

    @Test
    void truncatedBlobDecodesToNull() {
        String program = "-- @sfmgraph:v1 BEGIN\n-- eJyrVkrLz1eyUlAqSy0qzszP\n";
        // No end marker at all.
        assertNull(GraphCodec.extractBlob(program));
    }

    @Test
    void emptyGraphRoundTrips() {
        GraphModel empty = new GraphModel();
        empty.name = "";
        GraphModel decoded = GraphCodec.decode(GraphCodec.encode(empty));
        assertNotNull(decoded);
        assertTrue(decoded.nodes.isEmpty());
        assertTrue(decoded.links.isEmpty());
    }

    @Test
    void blobIsWrappedIntoCommentLines() {
        String blob = "A".repeat(500);
        String wrapped = GraphCodec.wrapAsComments(blob);
        for (String line : wrapped.split("\n")) {
            assertTrue(line.startsWith("-- "), line);
            assertTrue(line.length() < 200, "lines should stay short: " + line.length());
        }
        assertEquals(blob, GraphCodec.extractBlob(wrapped));
    }

    @Test
    void guessLabelsReadsFromAndToStatements() {
        String program = """
                name "hand written"
                -- input from a comment should be ignored
                EVERY 20 TICKS DO
                    INPUT FROM ironChest
                    OUTPUT TO furnace TOP SIDE
                    INPUT FROM EACH ore
                    OUTPUT TO EMPTY SLOTS IN output
                END
                """;
        List<String> labels = GraphCodec.guessLabels(program);
        assertTrue(labels.contains("ironChest"), labels.toString());
        assertTrue(labels.contains("furnace"), labels.toString());
        assertTrue(labels.contains("ore"), labels.toString());
        assertTrue(labels.contains("output"), labels.toString());
        // Keywords must never be mistaken for labels.
        assertFalse(labels.contains("EMPTY"), labels.toString());
        assertFalse(labels.contains("SLOTS"), labels.toString());
        assertFalse(labels.contains("IN"), labels.toString());
        // Comments are stripped before scanning.
        assertFalse(labels.contains("a"), labels.toString());
    }

    @Test
    void guessLabelsHandlesQuotedLabels() {
        List<String> labels = GraphCodec.guessLabels("EVERY 20 TICKS DO\nINPUT FROM \"my chest\"\nEND\n");
        assertTrue(labels.contains("my chest"), labels.toString());
    }
}
