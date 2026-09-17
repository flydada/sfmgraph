package dev.sfmgraph.graph;

/**
 * What the editor knows about one label, straight from the disk's label table.
 *
 * <p>The cable count matters because SFM only ever works with a labelled block that is adjacent to a
 * cable of the manager's network; anything else is silently skipped. Since that failure is invisible
 * in game — the machines simply sit there — the editor reports it instead.
 *
 * @param blocks     how many blocks carry the label
 * @param nextToCable how many of those look adjacent to a cable
 */
public record LabelSummary(int blocks, int nextToCable) {

    public static final LabelSummary UNKNOWN = new LabelSummary(1, 1);

    public boolean partiallyDisconnected() {
        return nextToCable < blocks;
    }

    public int disconnected() {
        return Math.max(0, blocks - nextToCable);
    }
}
