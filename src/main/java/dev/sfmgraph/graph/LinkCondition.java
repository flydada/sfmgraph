package dev.sfmgraph.graph;

/**
 * An optional guard wrapped around a link's statements as an SFML {@code IF ... THEN ... END}.
 */
public record LinkCondition(Kind kind, long amount) {

    public enum Kind {
        NONE,
        /** {@code IF REDSTONE THEN} — only move while the manager has a redstone signal. */
        REDSTONE,
        /** {@code IF <source> HAS GT <n> <resource> THEN} — only move while the source holds enough. */
        SOURCE_HAS_GT
    }

    public static final LinkCondition NONE = new LinkCondition(Kind.NONE, 0);

    public boolean isNone() {
        return kind == Kind.NONE;
    }

    /**
     * Builds the condition text, e.g. {@code a HAS GT 100 minecraft:iron_ingot}.
     *
     * @param sourceLabelRef the already-quoted source label
     * @param resourceToken  the resource id token, may be empty for "any item"
     */
    public String sfml(String sourceLabelRef, String resourceToken) {
        String resource = resourceToken.isBlank() ? "" : " " + resourceToken;
        return switch (kind) {
            case NONE -> "";
            case REDSTONE -> amount > 0 ? "REDSTONE > " + amount : "REDSTONE";
            case SOURCE_HAS_GT -> sourceLabelRef + " HAS GT " + amount + resource;
        };
    }

    public String translationKey() {
        return "sfmgraph.condition." + kind.name().toLowerCase(java.util.Locale.ROOT);
    }
}
