package dev.sfmgraph.graph;

/**
 * A node in the graph, standing for one SFM label.
 *
 * <p>Nodes are keyed by label name, because the label is what SFM resolves at runtime: the disk in
 * the manager holds a map of label name to a set of block positions. Several blocks can share one
 * label, in which case a node represents all of them.
 */
public final class GraphNode {

    /** Matches SFM's label length limit. */
    public static final int MAX_LABEL_LENGTH = 256;

    public String label;
    public double x;
    public double y;
    /** Free-form note shown in the inspector; stored with the graph. */
    public String note = "";

    /**
     * Marks this node as an energy source. Other nodes can then pick it as their power supply from
     * their own inspector, which is sugar for creating an energy link from here to them — the
     * generated program is still plain {@code INPUT/OUTPUT forge_energy}, so SFM needs to know
     * nothing about this.
     */
    public boolean energySource;

    /**
     * The energy source label powering this node, or blank for none.
     *
     * <p>Powering a machine is deliberately <em>not</em> a drawn link: with one generator feeding a
     * dozen machines, a dozen links turn the canvas into spaghetti. The editor instead emits the
     * {@code INPUT/OUTPUT forge_energy} pair for every powered node, which is exactly what a drawn
     * link would have compiled to.
     */
    public String poweredBy = "";

    /** FE per tick for the automatic power feed. */
    public String powerRate = "1000";

    /**
     * Which faces the automatic power feed talks to on both ends.
     *
     * <p>Defaults to {@code BACK} rather than "no side": a missing side qualifier makes SFM do a
     * direction-less capability lookup, and many machines — Mekanism's especially, which is why SFM
     * ships a linter warning about it — simply do not answer that query. Naming a face is what makes
     * the capability resolve.
     */
    public SideSet powerSides = SideSet.of(GraphSide.BACK);

    public GraphNode(String label) {
        this.label = label;
    }

    public GraphNode(String label, double x, double y) {
        this.label = label;
        this.x = x;
        this.y = y;
    }

    /**
     * True when the label cannot be written bare. Note this is stricter than SFM's own
     * {@code Label.needsQuotes}: it also covers keywords, which SFM would emit unquoted and then
     * fail to parse.
     */
    public boolean labelNeedsQuotes() {
        return !SfmlKeywords.isBareIdentifier(label);
    }

    /** The label as it must appear in a program. */
    public String labelRef() {
        return SfmlKeywords.quoteLabel(label);
    }

    public GraphNode copy() {
        GraphNode copy = new GraphNode(label, x, y);
        copy.note = note;
        copy.energySource = energySource;
        copy.poweredBy = poweredBy;
        copy.powerRate = powerRate;
        copy.powerSides = powerSides;
        return copy;
    }

    /** True when this node draws power from another node without a drawn link. */
    public boolean isPowered() {
        return poweredBy != null && !poweredBy.isBlank();
    }

    @Override
    public String toString() {
        return "GraphNode[" + label + "]";
    }
}
