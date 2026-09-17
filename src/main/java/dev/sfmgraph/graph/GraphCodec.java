package dev.sfmgraph.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

/**
 * Stores the visual graph inside the SFM program itself, as a deflated, base64 encoded comment
 * block:
 *
 * <pre>
 * -- &#64;sfmgraph:v1 BEGIN
 * -- eJyrVkrLz1eyUlAqSy0qzszPU9JRSs7PLShKLS5OTVGyUipJLS5RqgUAcRQMHA==
 * -- &#64;sfmgraph:v1 END
 * </pre>
 *
 * <p>Embedding it in the program means the graph rides along with the disk item: copying the disk,
 * putting it in another manager, or sharing the program text all carry the graph with them, with no
 * extra block entity data, packets, or saved-data files. SFML treats these lines as comments, so the
 * program stays valid on its own.
 */
public final class GraphCodec {

    public static final String MARKER_BEGIN = "@sfmgraph:v1 BEGIN";
    public static final String MARKER_END = "@sfmgraph:v1 END";
    public static final int FORMAT_VERSION = 1;

    private static final String COMMENT_PREFIX = "-- ";
    private static final int CHUNK_SIZE = 180;

    private GraphCodec() {
    }

    // ------------------------------------------------------------------
    // Program text <-> blob
    // ------------------------------------------------------------------

    /** Wraps a blob in comment lines so it can live inside an SFML program. */
    public static String wrapAsComments(String blob) {
        StringBuilder sb = new StringBuilder();
        sb.append(COMMENT_PREFIX).append(MARKER_BEGIN).append('\n');
        for (int i = 0; i < blob.length(); i += CHUNK_SIZE) {
            sb.append(COMMENT_PREFIX)
              .append(blob, i, Math.min(blob.length(), i + CHUNK_SIZE))
              .append('\n');
        }
        sb.append(COMMENT_PREFIX).append(MARKER_END).append('\n');
        return sb.toString();
    }

    /** @return the embedded blob, or {@code null} when the program has none. */
    public static @Nullable String extractBlob(String programText) {
        if (programText == null) return null;
        int begin = programText.indexOf(MARKER_BEGIN);
        if (begin < 0) return null;
        int afterBegin = programText.indexOf('\n', begin);
        if (afterBegin < 0) return null;
        int end = programText.indexOf(MARKER_END, afterBegin);
        if (end < 0) return null;

        StringBuilder blob = new StringBuilder();
        for (String rawLine : programText.substring(afterBegin, end).split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("--")) line = line.substring(2).strip();
            blob.append(line);
        }
        return blob.length() == 0 ? null : blob.toString();
    }

    public static boolean hasGraph(String programText) {
        return extractBlob(programText) != null;
    }

    // ------------------------------------------------------------------
    // Model <-> blob
    // ------------------------------------------------------------------

    /** Encodes a graph into a base64 blob. */
    public static String encode(GraphModel model) {
        JsonObject root = new JsonObject();
        root.addProperty("v", FORMAT_VERSION);
        root.addProperty("name", model.name == null ? "" : model.name);

        JsonArray nodes = new JsonArray();
        for (GraphNode node : model.nodes) {
            JsonObject json = new JsonObject();
            json.addProperty("label", node.label);
            json.addProperty("x", round(node.x));
            json.addProperty("y", round(node.y));
            if (node.note != null && !node.note.isBlank()) json.addProperty("note", node.note);
            if (node.energySource) json.addProperty("energySource", true);
            if (node.isPowered()) {
                json.addProperty("poweredBy", node.poweredBy);
                if (!"1000".equals(node.powerRate)) json.addProperty("powerRate", node.powerRate);
                // Save the sides whenever they differ from the BACK default, so an old saved graph
                // picks up the new default on load instead of staying broken.
                List<String> powerSides = node.powerSides.toNames();
                if (!SideSet.of(GraphSide.BACK).toNames().equals(powerSides)) {
                    JsonArray sides = new JsonArray();
                    powerSides.forEach(sides::add);
                    json.add("powerSides", sides);
                }
            }
            nodes.add(json);
        }
        root.add("nodes", nodes);

        JsonArray links = new JsonArray();
        for (GraphLink link : model.links) {
            JsonObject json = new JsonObject();
            json.addProperty("id", link.id);
            json.addProperty("from", link.fromLabel);
            json.addProperty("to", link.toLabel);

            JsonArray fromSides = new JsonArray();
            link.fromSides.toNames().forEach(fromSides::add);
            json.add("fromSides", fromSides);
            JsonArray toSides = new JsonArray();
            link.toSides.toNames().forEach(toSides::add);
            json.add("toSides", toSides);

            if (notBlank(link.fromSlots)) json.addProperty("fromSlots", link.fromSlots.trim());
            if (notBlank(link.toSlots)) json.addProperty("toSlots", link.toSlots.trim());

            json.addProperty("kind", link.resource.kind().name());
            if (notBlank(link.resource.filter())) json.addProperty("filter", link.resource.filter());

            json.addProperty("rate", link.rate);
            if (link.intervalTicks > 0) json.addProperty("interval", link.intervalTicks);
            if (link.retain > 0) json.addProperty("retain", link.retain);
            json.addProperty("trigger", link.trigger.name());
            if (link.roundRobin != RoundRobinMode.NONE) json.addProperty("roundRobin", link.roundRobin.name());
            if (!link.condition.isNone()) {
                json.addProperty("condition", link.condition.kind().name());
                if (link.condition.amount() > 0) json.addProperty("conditionAmount", link.condition.amount());
            }
            if (link.fromEach) json.addProperty("fromEach", true);
            if (link.toEach) json.addProperty("toEach", true);
            if (link.quantityEach) json.addProperty("quantityEach", true);
            if (link.emptySlotsOnly) json.addProperty("emptySlotsOnly", true);
            if (!link.enabled) json.addProperty("enabled", false);
            links.add(json);
        }
        root.add("links", links);

        return compress(root.toString());
    }

    /**
     * Decodes a blob back into a graph.
     *
     * @return the graph, or {@code null} when the blob is absent or unreadable. Callers should fall
     * back to an empty graph rather than showing an error, so a corrupt blob never blocks editing.
     */
    public static @Nullable GraphModel decode(String blob) {
        if (blob == null || blob.isBlank()) return null;
        try {
            JsonElement parsed = JsonParser.parseString(decompress(blob));
            if (!parsed.isJsonObject()) return null;
            return fromJson(parsed.getAsJsonObject());
        } catch (Exception e) {
            return null;
        }
    }

    /** Convenience: pulls the graph straight out of a program string. */
    public static @Nullable GraphModel decodeProgram(String programText) {
        return decode(extractBlob(programText));
    }

    private static GraphModel fromJson(JsonObject root) {
        GraphModel model = new GraphModel();
        model.name = optString(root, "name", "");

        if (root.has("nodes") && root.get("nodes").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("nodes")) {
                if (!element.isJsonObject()) continue;
                JsonObject json = element.getAsJsonObject();
                String label = optString(json, "label", "");
                if (label.isBlank()) continue;
                GraphNode node = new GraphNode(label, optDouble(json, "x", 40), optDouble(json, "y", 40));
                node.note = optString(json, "note", "");
                node.energySource = optBool(json, "energySource", false);
                node.poweredBy = optString(json, "poweredBy", "");
                node.powerRate = optString(json, "powerRate", "1000");
                if (json.has("powerSides")) {
                    node.powerSides = SideSet.fromNames(stringList(json, "powerSides"));
                }
                model.nodes.add(node);
            }
        }

        if (root.has("links") && root.get("links").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("links")) {
                if (!element.isJsonObject()) continue;
                JsonObject json = element.getAsJsonObject();
                GraphLink link = new GraphLink();
                String id = optString(json, "id", "");
                if (!id.isBlank()) link.id = id;
                link.fromLabel = optString(json, "from", "");
                link.toLabel = optString(json, "to", "");

                link.fromSides = SideSet.fromNames(stringList(json, "fromSides"));
                link.toSides = SideSet.fromNames(stringList(json, "toSides"));
                link.fromSlots = optString(json, "fromSlots", "");
                link.toSlots = optString(json, "toSlots", "");

                ResourceKind kind = ResourceKind.ITEM;
                String kindName = optString(json, "kind", "ITEM");
                for (ResourceKind candidate : ResourceKind.values()) {
                    if (candidate.name().equalsIgnoreCase(kindName)) {
                        kind = candidate;
                        break;
                    }
                }
                link.resource = new ResourceSpec(kind, optString(json, "filter", ""));

                link.rate = optString(json, "rate", "1");
                link.intervalTicks = Math.max(0, (int) optDouble(json, "interval", 0));
                link.retain = (long) optDouble(json, "retain", 0);
                link.trigger = enumOrDefault(TriggerMode.class, optString(json, "trigger", ""), TriggerMode.TIMER);
                link.roundRobin = enumOrDefault(
                        RoundRobinMode.class,
                        optString(json, "roundRobin", ""),
                        RoundRobinMode.NONE
                );
                link.condition = new LinkCondition(
                        enumOrDefault(LinkCondition.Kind.class, optString(json, "condition", ""), LinkCondition.Kind.NONE),
                        (long) optDouble(json, "conditionAmount", 0)
                );
                link.fromEach = optBool(json, "fromEach", false);
                link.toEach = optBool(json, "toEach", false);
                link.quantityEach = optBool(json, "quantityEach", false);
                link.emptySlotsOnly = optBool(json, "emptySlotsOnly", false);
                link.enabled = optBool(json, "enabled", true);
                model.links.add(link);
            }
        }
        return model;
    }

    // ------------------------------------------------------------------
    // Importing a hand written program
    // ------------------------------------------------------------------

    private static final Set<String> NOT_LABELS = Set.of(
            "each", "empty", "slots", "slot", "in", "side", "top", "bottom", "north", "south",
            "east", "west", "left", "right", "front", "back", "null", "round", "robin", "by",
            "label", "block"
    );

    private static final Pattern LABEL_AFTER_FROM_TO = Pattern.compile(
            "(?i)\\b(?:from|to)\\s+(?:(?:each)\\s+)?(?:(?:empty)\\s+(?:slots|slot)\\s+in\\s+)?"
            + "(\"[^\"]*\"|[a-zA-Z_][a-zA-Z0-9_]*)"
    );

    /**
     * Best effort extraction of labels from a hand written program, used so that opening an existing
     * program still shows the blocks the player already labelled as nodes.
     */
    public static List<String> guessLabels(String programText) {
        Set<String> found = new LinkedHashSet<>();
        if (programText == null) return List.of();
        // Drop comments first so examples in comments do not create phantom nodes.
        String stripped = programText.replaceAll("(?m)--.*$", "");
        Matcher matcher = LABEL_AFTER_FROM_TO.matcher(stripped);
        while (matcher.find()) {
            String label = matcher.group(1);
            if (label.startsWith("\"")) {
                label = label.substring(1, label.length() - 1);
            }
            if (label.isBlank()) continue;
            if (NOT_LABELS.contains(label.toLowerCase(Locale.ROOT))) continue;
            found.add(label);
        }
        return new ArrayList<>(found);
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private static String compress(String text) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
            try (DeflaterOutputStream deflated = new DeflaterOutputStream(buffer, deflater)) {
                deflated.write(text.getBytes(StandardCharsets.UTF_8));
            } finally {
                deflater.end();
            }
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch (Exception e) {
            // Fall back to raw base64 rather than losing the graph entirely.
            return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String decompress(String blob) throws Exception {
        byte[] raw = Base64.getDecoder().decode(blob);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InflaterOutputStream inflated = new InflaterOutputStream(buffer, new Inflater())) {
                inflated.write(raw);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            // The blob may have been stored uncompressed (see compress()).
            return new String(raw, StandardCharsets.UTF_8);
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static boolean notBlank(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static String optString(JsonObject json, String key, String fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double optDouble(JsonObject json, String key, double fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsDouble();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean optBool(JsonObject json, String key, boolean fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static List<String> stringList(JsonObject json, String key) {
        List<String> result = new ArrayList<>();
        if (!json.has(key) || !json.get(key).isJsonArray()) return result;
        for (JsonElement element : json.getAsJsonArray(key)) {
            try {
                result.add(element.getAsString());
            } catch (Exception ignored) {
                // skip malformed entries
            }
        }
        return result;
    }

    private static <E extends Enum<E>> E enumOrDefault(Class<E> type, String name, E fallback) {
        if (name == null || name.isBlank()) return fallback;
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equalsIgnoreCase(name.trim())) return candidate;
        }
        return fallback;
    }
}
