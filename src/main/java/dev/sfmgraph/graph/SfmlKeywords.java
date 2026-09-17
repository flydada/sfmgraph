package dev.sfmgraph.graph;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The words SFML's lexer treats as keywords.
 *
 * <p>This matters because a label is usually written bare ({@code FROM a}), and a bare label is a
 * grammar {@code IDENTIFIER}. Labelling a block {@code in} or {@code output} would therefore produce
 * a program that does not parse — {@code INPUT FROM in} reads as the {@code IN} keyword. SFM's own
 * rule only quotes labels that contain unusual characters, so it misses this case; this editor
 * quotes anything that would collide with a keyword, which keeps the generated program valid while
 * still resolving to the same label at runtime.
 */
public final class SfmlKeywords {

    /** Every keyword terminal in the SFML grammar, upper cased. */
    private static final Set<String> KEYWORDS = Set.of(
            // statements and structure
            "IF", "THEN", "ELSE", "END", "DO", "EVERY", "NAME",
            // io
            "INPUT", "OUTPUT", "FROM", "TO", "EACH", "EXCEPT", "FORGET", "RETAIN", "EMPTY", "IN",
            "WHERE", "SLOTS", "SLOT",
            // boolean and comparison
            "TRUE", "FALSE", "NOT", "AND", "OR", "HAS", "OVERALL", "SOME", "ONE", "LONE",
            "GT", "LT", "EQ", "LE", "GE",
            // faces
            "TOP", "BOTTOM", "NORTH", "EAST", "SOUTH", "WEST", "SIDE",
            "LEFT", "RIGHT", "FRONT", "BACK", "NULL",
            // triggers
            "TICKS", "TICK", "SECONDS", "SECOND", "GLOBAL", "G", "PLUS", "REDSTONE", "PULSE",
            // with and round robin
            "WITH", "WITHOUT", "TAG", "ROUND", "ROBIN", "BY", "LABEL", "BLOCK"
    );

    /** The shape of a bare SFML identifier: {@code [a-zA-Z_*][a-zA-Z0-9_*]*}. */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_*][a-zA-Z0-9_*]*");

    private SfmlKeywords() {
    }

    public static boolean isKeyword(String word) {
        return word != null && KEYWORDS.contains(word.trim().toUpperCase(Locale.ROOT));
    }

    /** True when the text can be written without quotes and still lex as one identifier. */
    public static boolean isBareIdentifier(String text) {
        if (text == null || text.isEmpty()) return false;
        return IDENTIFIER.matcher(text).matches() && !isKeyword(text);
    }

    /**
     * Quotes a label when it would otherwise be read as a keyword or is not a valid identifier.
     *
     * @return the label as it should appear in a program
     */
    public static String quoteLabel(String label) {
        String value = label == null ? "" : label;
        if (isBareIdentifier(value)) return value;
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
