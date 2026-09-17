package dev.sfmgraph.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The set of faces a link endpoint talks to, which compiles to SFML's side qualifier.
 *
 * <p>SFML accepts nothing (unsided capability lookup), {@code EACH SIDE} (all six world directions
 * plus the unsided lookup), or an explicit list such as {@code TOP, NORTH SIDE}.
 */
public final class SideSet {

    public enum Mode {
        /** No qualifier at all. */
        UNSET,
        /** {@code EACH SIDE} */
        EACH,
        /** An explicit list of sides. */
        EXPLICIT
    }

    public static final SideSet UNSET = new SideSet(Mode.UNSET, List.of());
    public static final SideSet EACH = new SideSet(Mode.EACH, List.of());

    private static final List<GraphSide> CANONICAL = List.of(
            GraphSide.TOP, GraphSide.BOTTOM, GraphSide.NORTH, GraphSide.SOUTH,
            GraphSide.EAST, GraphSide.WEST, GraphSide.FRONT, GraphSide.BACK,
            GraphSide.LEFT, GraphSide.RIGHT
    );

    private final Mode mode;
    private final List<GraphSide> sides;

    private SideSet(Mode mode, List<GraphSide> sides) {
        this.mode = mode;
        this.sides = List.copyOf(sides);
    }

    /** Builds an explicit set. Empty input collapses back to {@link #UNSET}. */
    public static SideSet of(Collection<GraphSide> selected) {
        if (selected == null || selected.isEmpty()) return UNSET;
        // Keep a stable, canonical order so generated programs diff cleanly.
        TreeSet<GraphSide> ordered = new TreeSet<>((a, b) -> Integer.compare(CANONICAL.indexOf(a), CANONICAL.indexOf(b)));
        ordered.addAll(selected);
        // A relative side plus the absolute sides is legal SFML, so no dedup beyond this.
        return new SideSet(Mode.EXPLICIT, new ArrayList<>(ordered));
    }

    public static SideSet of(GraphSide... selected) {
        return of(java.util.Arrays.asList(selected));
    }

    public Mode mode() {
        return mode;
    }

    public List<GraphSide> sides() {
        return sides;
    }

    public boolean isUnset() {
        return mode == Mode.UNSET;
    }

    public boolean isEach() {
        return mode == Mode.EACH;
    }

    public boolean contains(GraphSide side) {
        return mode == Mode.EXPLICIT && sides.contains(side);
    }

    /** True when any selected side is relative, i.e. depends on the block's own facing. */
    public boolean hasRelative() {
        return mode == Mode.EXPLICIT && sides.stream().anyMatch(GraphSide::isRelative);
    }

    /**
     * The SFML text appended after a label, including the leading space.
     *
     * @return {@code ""}, {@code " EACH SIDE"} or {@code " TOP, NORTH SIDE"}
     */
    public String sfmlQualifier() {
        return switch (mode) {
            case UNSET -> "";
            case EACH -> " EACH SIDE";
            case EXPLICIT -> " " + String.join(", ", sides.stream().map(GraphSide::sfml).toList()) + " SIDE";
        };
    }

    /** Human readable summary for tooltips, e.g. {@code 上、北} or {@code 所有面}. */
    public String display() {
        return switch (mode) {
            case UNSET -> "不限";
            case EACH -> "所有面";
            case EXPLICIT -> String.join("、", sides.stream().map(GraphSide::glyph).toList());
        };
    }

    /** Compact badge text for the canvas, e.g. {@code 上北} or {@code 全} / {@code —}. */
    public String badge() {
        return switch (mode) {
            case UNSET -> "—";
            case EACH -> "全";
            case EXPLICIT -> String.join("", sides.stream().map(GraphSide::glyph).toList());
        };
    }

    public String translationKey() {
        return switch (mode) {
            case UNSET -> "sfmgraph.sides.unset";
            case EACH -> "sfmgraph.sides.each";
            case EXPLICIT -> "sfmgraph.sides.explicit";
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SideSet other)) return false;
        return mode == other.mode && sides.equals(other.sides);
    }

    @Override
    public int hashCode() {
        return mode.hashCode() * 31 + sides.hashCode();
    }

    @Override
    public String toString() {
        return "SideSet[" + mode + (sides.isEmpty() ? "" : " " + sides) + "]";
    }

    public static SideSet fromNames(List<String> names) {
        if (names == null || names.isEmpty()) return UNSET;
        List<GraphSide> parsed = new ArrayList<>();
        for (String name : names) {
            if (name == null) continue;
            if (name.equalsIgnoreCase("EACH")) return EACH;
            GraphSide side = GraphSide.byName(name);
            if (side != null) parsed.add(side);
        }
        return of(parsed);
    }

    public List<String> toNames() {
        return switch (mode) {
            case UNSET -> List.of();
            case EACH -> List.of("EACH");
            case EXPLICIT -> sides.stream().map(s -> s.name().toUpperCase(Locale.ROOT)).toList();
        };
    }
}
