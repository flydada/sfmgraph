package dev.sfmgraph.graph;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One directed flow between two labelled blocks: what moves, from which face to which face, and how
 * fast. A link compiles to exactly one SFML trigger block.
 */
public final class GraphLink {

    public String id = UUID.randomUUID().toString();
    public String fromLabel = "";
    public String toLabel = "";

    public SideSet fromSides = SideSet.UNSET;
    public SideSet toSides = SideSet.UNSET;

    /** SFML slot ranges, e.g. {@code 0-8,10}. Empty means "all slots". */
    public String fromSlots = "";
    public String toSlots = "";

    public ResourceSpec resource = ResourceSpec.ITEMS;

    /**
     * Requested amount, as a decimal string so values like {@code 1.5} stay exact.
     * Interpreted per tick for {@link TriggerMode#TIMER} and per pulse for
     * {@link TriggerMode#REDSTONE_PULSE}.
     */
    public String rate = "1";

    /** Timer interval override, {@code 0} means "derive one that represents the rate exactly". */
    public int intervalTicks = 0;

    /**
     * SFML {@code RETAIN}. On the input side it means "leave this much behind"; on the output side
     * it means "only fill the destination up to this much". {@code 0} omits it.
     */
    public long retain = 0;

    public TriggerMode trigger = TriggerMode.TIMER;
    public RoundRobinMode roundRobin = RoundRobinMode.NONE;
    public LinkCondition condition = LinkCondition.NONE;

    /** {@code FROM EACH a} / {@code TO EACH b}: give every matched block its own budget. */
    public boolean fromEach = false;
    public boolean toEach = false;

    /** Give every matched resource id its own budget ({@code INPUT 5 EACH *ingot*}). */
    public boolean quantityEach = false;

    /** {@code OUTPUT ... TO EMPTY SLOTS IN b}: only fill empty slots. */
    public boolean emptySlotsOnly = false;

    public boolean enabled = true;

    public GraphLink() {
    }

    public GraphLink(String fromLabel, String toLabel) {
        this.fromLabel = fromLabel;
        this.toLabel = toLabel;
    }

    /** @return the parsed rate, or {@code null} when the text is not a number. */
    public @Nullable BigDecimal rateValue() {
        String text = rate == null ? "" : rate.trim();
        if (text.isEmpty()) return null;
        try {
            BigDecimal value = new BigDecimal(text);
            return value.signum() > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String label() {
        return fromLabel + " → " + toLabel;
    }

    public GraphLink copy() {
        GraphLink copy = new GraphLink(fromLabel, toLabel);
        copy.id = id;
        copy.fromSides = fromSides;
        copy.toSides = toSides;
        copy.fromSlots = fromSlots;
        copy.toSlots = toSlots;
        copy.resource = resource;
        copy.rate = rate;
        copy.intervalTicks = intervalTicks;
        copy.retain = retain;
        copy.trigger = trigger;
        copy.roundRobin = roundRobin;
        copy.condition = condition;
        copy.fromEach = fromEach;
        copy.toEach = toEach;
        copy.quantityEach = quantityEach;
        copy.emptySlotsOnly = emptySlotsOnly;
        copy.enabled = enabled;
        return copy;
    }

    @Override
    public String toString() {
        return "GraphLink[" + label() + " " + resource + " " + rate + "]";
    }
}
