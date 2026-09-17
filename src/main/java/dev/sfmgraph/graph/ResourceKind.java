package dev.sfmgraph.graph;

/**
 * The kind of resource a link moves, which selects the SFML resource type.
 *
 * <p>The registered types on 1.21.1 are {@code sfm:item}, {@code sfm:fluid}, {@code sfm:forge_energy},
 * and — when Mekanism is installed — {@code chemical}, {@code gas}, {@code infusion}, {@code pigment}
 * and {@code slurry}. Only the general {@code chemical} type is exposed here, because a chemical id
 * filter is enough to single out a specific chemical; the subtypes would only be needed to exclude
 * e.g. slurries.
 */
public enum ResourceKind {
    ITEM("item", "个", false),
    FLUID("fluid", "mB", false),
    CHEMICAL("chemical", "mB", false),
    ENERGY("forge_energy", "FE", true);

    private final String sfmTypeName;
    private final String unit;
    private final boolean intCapped;

    ResourceKind(String sfmTypeName, String unit, boolean intCapped) {
        this.sfmTypeName = sfmTypeName;
        this.unit = unit;
        this.intCapped = intCapped;
    }

    public String sfmTypeName() {
        return sfmTypeName;
    }

    /**
     * The canonical, unambiguous resource id emitted into the program.
     *
     * <p>Items stand alone, and everything else has to name its type: {@code fluid::water} is a fluid
     * while {@code water} alone would be read as an item.
     */
    public String idToken(String filter) {
        String clean = filter == null ? "" : filter.trim();
        return switch (this) {
            case ITEM -> clean.isEmpty() ? "" : clean;
            case FLUID, CHEMICAL -> typePrefixed(clean);
            case ENERGY -> "sfm:forge_energy:forge:energy";
        };
    }

    /** {@code fluid::}, {@code fluid::water}, {@code fluid:mekanism:heavy_water}. */
    private String typePrefixed(String clean) {
        if (clean.isEmpty()) return sfmTypeName + "::";
        return clean.indexOf(':') < 0 ? sfmTypeName + "::" + clean : sfmTypeName + ":" + clean;
    }

    public boolean isEnergy() {
        return this == ENERGY;
    }

    /** True when a bare id would be read as an item, so the type has to be written out. */
    public boolean isTypePrefixed() {
        return this == FLUID || this == CHEMICAL;
    }

    public boolean isChemical() {
        return this == CHEMICAL;
    }

    /**
     * Energy is handled as an {@code int} internally by SFM, so a single transfer can never exceed
     * {@link Integer#MAX_VALUE} FE. Everything else is tracked as a {@code long}.
     */
    public long maxAmountPerTransfer() {
        return intCapped ? Integer.MAX_VALUE : 1_000_000_000_000_000L;
    }

    /** Unit shown next to the rate field. */
    public String unit() {
        return unit;
    }

    public String translationKey() {
        return "sfmgraph.resource." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
