package dev.sfmgraph.graph;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * What a link moves: a {@link ResourceKind} plus an optional id filter.
 *
 * <p>SFM resolves a resource id as {@code <typeNamespace>:<typeName>:<namespace>:<name>}, where the
 * type namespace defaults to {@code sfm} and the name is treated as a regex. Because of that:
 * <ul>
 *   <li>a bare word such as {@code iron_ingot} means "any item whose path matches {@code iron_ingot}"</li>
 *   <li>{@code *ingot*} is a pattern match, since {@code *} becomes {@code .*}</li>
 *   <li>{@code minecraft:iron_ingot} pins the namespace</li>
 *   <li>a fluid needs three parts, so a bare fluid name becomes {@code fluid::water}, not
 *       {@code fluid:water} — the latter would be read as an item id</li>
 * </ul>
 *
 * <p>Ids are quoted when a part would otherwise be read as an SFML keyword ({@code "in"}), and fluid
 * ids that cannot be expressed without quoting are rejected rather than emitted, so the generated
 * program is never syntactically broken by a typo.
 */
public record ResourceSpec(ResourceKind kind, String filter) {

    public static final ResourceSpec ITEMS = new ResourceSpec(ResourceKind.ITEM, "");
    public static final ResourceSpec FLUIDS = new ResourceSpec(ResourceKind.FLUID, "");
    public static final ResourceSpec ENERGY = new ResourceSpec(ResourceKind.ENERGY, "");

    /** The shape a single id segment may have; {@code *} is SFM's wildcard. */
    private static final Pattern ID_PART_SHAPE = Pattern.compile("[a-zA-Z_*][a-zA-Z0-9_*]*");

    public static ResourceSpec of(ResourceKind kind) {
        return new ResourceSpec(kind, "");
    }

    public ResourceSpec withKind(ResourceKind newKind) {
        if (newKind == kind) return this;
        // Filters are kind specific (an item id is not a fluid id), so switching kinds clears it.
        return new ResourceSpec(newKind, "");
    }

    public ResourceSpec withFilter(String newFilter) {
        return new ResourceSpec(kind, newFilter == null ? "" : newFilter.trim());
    }

    public String filter() {
        return filter;
    }

    public boolean hasFilter() {
        return !filter.isBlank() && kind != ResourceKind.ENERGY;
    }

    /** The token written into the program, possibly empty for "any item". */
    public String idToken() {
        // Items are the one kind that can be quoted, which is how a keyword collision is escaped.
        // Fluids and chemicals name their type, so quoting is neither needed nor verifiable.
        if (kind == ResourceKind.ITEM && !filter.isBlank()) {
            return quoteIfNeeded(filter.trim());
        }
        return kind.idToken(filter);
    }

    private static String[] parts(String id) {
        if (id.isEmpty()) return new String[0];
        return id.split(":", -1);
    }

    /** Wraps an id in a string literal when a bare form would not lex as expected. */
    private static String quoteIfNeeded(String id) {
        for (String part : parts(id)) {
            if (part.isEmpty()) continue;
            if (!SfmlKeywords.isBareIdentifier(part)) {
                return "\"" + id.replace("\"", "\\\"") + "\"";
            }
        }
        return id;
    }

    /**
     * @return {@code null} when the spec can be compiled, otherwise a human readable reason.
     */
    public @Nullable String problem() {
        if (kind == ResourceKind.ENERGY) return null;
        String clean = filter.trim();
        if (clean.isEmpty()) return null;

        String[] parts = parts(clean);
        if (parts.length > 2) {
            return "资源 ID 最多两段（如 minecraft:iron_ingot）；" + kindLabel()
                   + "请写成 " + (kind == ResourceKind.FLUID ? "water" : "hydrogen") + " 或 minecraft:water";
        }
        for (String part : parts) {
            if (part.isEmpty()) {
                return "资源 ID 含有空的片段：" + clean;
            }
            // Rejecting typos here is friendlier than emitting a pattern that silently matches nothing.
            if (!ID_PART_SHAPE.matcher(part).matches()) {
                return "资源 ID 片段 “" + part + "” 不合法（只允许字母、数字、下划线和 *）";
            }
            // Fluids and chemicals have no quoting escape hatch, so report instead of smuggling it through.
            if (kind.isTypePrefixed() && SfmlKeywords.isKeyword(part)) {
                return kindLabel() + " ID 片段 “" + part + "” 是 SFML 关键字，无法写进程序";
            }
        }
        return null;
    }

    private String kindLabel() {
        return switch (kind) {
            case ITEM -> "物品";
            case FLUID -> "流体";
            case CHEMICAL -> "化学品";
            case ENERGY -> "电力";
        };
    }

    public boolean isValid() {
        return problem() == null;
    }

    @Override
    public String toString() {
        return kind.name() + (filter.isBlank() ? "" : "[" + filter + "]");
    }
}
