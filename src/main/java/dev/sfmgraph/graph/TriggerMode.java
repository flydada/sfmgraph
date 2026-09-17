package dev.sfmgraph.graph;

/**
 * How a link is scheduled.
 *
 * <p>SFML only has two triggers: a timer ({@code EVERY n TICKS DO}) and a redstone pulse
 * ({@code EVERY REDSTONE PULSE DO}). The manager block ticks once per server tick, so an interval of
 * 1 really does mean "every game tick".
 */
public enum TriggerMode {
    /** Fires on a timer; the rate field is interpreted as "per tick". */
    TIMER("EVERY %d TICKS DO"),
    /** Fires once per redstone pulse; the rate field is interpreted as "per pulse". */
    REDSTONE_PULSE("EVERY REDSTONE PULSE DO");

    private final String format;

    TriggerMode(String format) {
        this.format = format;
    }

    public String triggerLine(int intervalTicks) {
        return switch (this) {
            case TIMER -> String.format(format, intervalTicks);
            case REDSTONE_PULSE -> format;
        };
    }

    public boolean isTimer() {
        return this == TIMER;
    }

    public String translationKey() {
        return "sfmgraph.trigger." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
