package dev.sfmgraph.graph;

/**
 * A single SFML side qualifier, mirroring {@code ca.teamdman.sfml.ast.Side}.
 *
 * <p>{@link #TOP}/{@link #BOTTOM}/{@link #NORTH}/{@link #SOUTH}/{@link #EAST}/{@link #WEST} are
 * absolute world directions on the labelled block. {@link #FRONT}/{@link #BACK}/{@link #LEFT}/{@link #RIGHT}
 * are relative to the labelled block's own facing, and resolve to nothing when the block has no
 * facing property.
 */
public enum GraphSide {
    TOP,
    BOTTOM,
    NORTH,
    SOUTH,
    EAST,
    WEST,
    FRONT,
    BACK,
    LEFT,
    RIGHT;

    /** Relative sides depend on the target block's own facing, so they can silently do nothing. */
    public boolean isRelative() {
        return switch (this) {
            case FRONT, BACK, LEFT, RIGHT -> true;
            default -> false;
        };
    }

    public boolean isAbsolute() {
        return !isRelative();
    }

    public String sfml() {
        return name();
    }

    public String translationKey() {
        return "sfmgraph.side." + name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Short glyph used on the canvas badges. */
    public String glyph() {
        return switch (this) {
            case TOP -> "上";
            case BOTTOM -> "下";
            case NORTH -> "北";
            case SOUTH -> "南";
            case EAST -> "东";
            case WEST -> "西";
            case FRONT -> "前";
            case BACK -> "后";
            case LEFT -> "左";
            case RIGHT -> "右";
        };
    }

    public static GraphSide byName(String name) {
        if (name == null) return null;
        for (GraphSide side : values()) {
            if (side.name().equalsIgnoreCase(name.trim())) return side;
        }
        return null;
    }
}
