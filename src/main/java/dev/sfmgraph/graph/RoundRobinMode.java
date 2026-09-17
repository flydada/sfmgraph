package dev.sfmgraph.graph;

/**
 * SFML's {@code ROUND ROBIN BY LABEL | BY BLOCK} modifier, which spreads a statement across the
 * blocks that share a label instead of touching all of them at once.
 */
public enum RoundRobinMode {
    NONE(""),
    BY_LABEL(" ROUND ROBIN BY LABEL"),
    BY_BLOCK(" ROUND ROBIN BY BLOCK");

    private final String sfml;

    RoundRobinMode(String sfml) {
        this.sfml = sfml;
    }

    public String sfml() {
        return sfml;
    }

    public String translationKey() {
        return "sfmgraph.roundrobin." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
