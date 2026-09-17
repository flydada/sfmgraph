package dev.sfmgraph.client.ui;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Maps between a slider position and a transfer rate on a log scale. */
public final class RateScale {

    public static final double MIN = 0.01;
    public static final double MAX = 1_000_000;

    private RateScale() {
    }

    public static double toRate(double position) {
        double t = Math.max(0, Math.min(1, position));
        return Math.exp(Math.log(MIN) + t * (Math.log(MAX) - Math.log(MIN)));
    }

    public static double toPosition(double rate) {
        if (rate <= MIN) return 0;
        if (rate >= MAX) return 1;
        return (Math.log(rate) - Math.log(MIN)) / (Math.log(MAX) - Math.log(MIN));
    }

    /** Rounds to the nearest 1/2/5 x 10^n so slider output reads like a person wrote it. */
    public static String nice(double value) {
        if (value <= 0) return "1";
        double exponent = Math.floor(Math.log10(value));
        double magnitude = Math.pow(10, exponent);
        double normalized = value / magnitude;
        double step;
        if (normalized < 1.5) step = 1;
        else if (normalized < 3.5) step = 2;
        else if (normalized < 7.5) step = 5;
        else {
            step = 10;
        }
        double rounded = step * magnitude;
        if (rounded >= 1) {
            return String.valueOf((long) Math.round(rounded));
        }
        return BigDecimal.valueOf(rounded).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
