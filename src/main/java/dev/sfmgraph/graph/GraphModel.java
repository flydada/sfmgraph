package dev.sfmgraph.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The whole visual graph: labelled blocks as nodes, transfers as links.
 *
 * <p>This is deliberately free of any Minecraft dependency so it can be unit tested and compiled to
 * SFML without a running game.
 */
public final class GraphModel {

    private static final double COLUMN_WIDTH = 190.0;
    private static final double ROW_HEIGHT = 84.0;

    public String name = "";
    public final List<GraphNode> nodes = new ArrayList<>();
    public final List<GraphLink> links = new ArrayList<>();

    public GraphNode node(String label) {
        if (label == null) return null;
        for (GraphNode node : nodes) {
            if (node.label.equals(label)) return node;
        }
        return null;
    }

    public boolean hasLabel(String label) {
        return node(label) != null;
    }

    /** Finds or creates the node for a label, placing new nodes in a free grid cell. */
    public GraphNode ensureNode(String label) {
        GraphNode existing = node(label);
        if (existing != null) return existing;
        double[] spot = findFreeSpot();
        GraphNode created = new GraphNode(label, spot[0], spot[1]);
        nodes.add(created);
        return created;
    }

    /**
     * Adds a node for every label that does not have one yet, keeping the layout of existing nodes.
     *
     * @return the labels that were newly added
     */
    public List<String> syncLabels(Collection<String> labels) {
        List<String> added = new ArrayList<>();
        for (String label : labels) {
            if (label == null || label.isBlank() || node(label) != null) continue;
            ensureNode(label);
            added.add(label);
        }
        return added;
    }

    /** Removes a node and every link attached to it. */
    public void removeNode(String label) {
        nodes.removeIf(node -> node.label.equals(label));
        links.removeIf(link -> link.fromLabel.equals(label) || link.toLabel.equals(label));
    }

    public GraphLink link(String id) {
        if (id == null) return null;
        for (GraphLink link : links) {
            if (link.id.equals(id)) return link;
        }
        return null;
    }

    /** @return {@code true} if such a link did not exist yet. */
    public boolean addLink(GraphLink link) {
        for (GraphLink existing : links) {
            if (existing.fromLabel.equals(link.fromLabel)
                && existing.toLabel.equals(link.toLabel)
                && existing.resource.kind() == link.resource.kind()
                && existing.fromSides.equals(link.fromSides)
                && existing.toSides.equals(link.toSides)) {
                return false;
            }
        }
        links.add(link);
        return true;
    }

    public void removeLink(String id) {
        links.removeIf(link -> link.id.equals(id));
    }

    public List<GraphLink> linksFrom(String label) {
        List<GraphLink> result = new ArrayList<>();
        for (GraphLink link : links) {
            if (link.fromLabel.equals(label)) result.add(link);
        }
        return result;
    }

    public List<GraphLink> linksTo(String label) {
        List<GraphLink> result = new ArrayList<>();
        for (GraphLink link : links) {
            if (link.toLabel.equals(label)) result.add(link);
        }
        return result;
    }

    /** Every label referenced by a link, whether or not a node exists for it. */
    public Set<String> referencedLabels() {
        Set<String> result = new LinkedHashSet<>();
        for (GraphLink link : links) {
            if (!link.fromLabel.isBlank()) result.add(link.fromLabel);
            if (!link.toLabel.isBlank()) result.add(link.toLabel);
        }
        return result;
    }

    public void clear() {
        nodes.clear();
        links.clear();
        name = "";
    }

    public GraphModel copy() {
        GraphModel copy = new GraphModel();
        copy.name = name;
        for (GraphNode node : nodes) copy.nodes.add(node.copy());
        for (GraphLink link : links) copy.links.add(link.copy());
        return copy;
    }

    /** Removes blank labels, duplicate nodes and links pointing at unknown labels. */
    public void prune() {
        nodes.removeIf(node -> node.label == null || node.label.isBlank());
        Set<String> seen = new LinkedHashSet<>();
        nodes.removeIf(node -> !seen.add(node.label));
        Set<String> known = new LinkedHashSet<>(seen);
        links.removeIf(link -> link.fromLabel.isBlank()
                              || link.toLabel.isBlank()
                              || !known.contains(link.fromLabel)
                              || !known.contains(link.toLabel));
    }

    private double[] findFreeSpot() {
        for (int column = 0; column < 12; column++) {
            for (int row = 0; row < 40; row++) {
                double x = 40 + column * COLUMN_WIDTH;
                double y = 40 + row * ROW_HEIGHT;
                boolean taken = false;
                for (GraphNode node : nodes) {
                    if (Math.abs(node.x - x) < COLUMN_WIDTH * 0.8 && Math.abs(node.y - y) < ROW_HEIGHT * 0.8) {
                        taken = true;
                        break;
                    }
                }
                if (!taken) return new double[]{x, y};
            }
        }
        return new double[]{40, 40 + nodes.size() * ROW_HEIGHT};
    }
}
