package com.sparrowwallet.drongo.evm;

/**
 * The one rule for text that comes from a contract or its metadata: an NFT's name, description and attributes, a
 * collection's name and symbol, and the text of an SVG picture ({@link SvgSubset}). It uses no platform Unicode data,
 * so every platform, the phone's port included, gives the same answer for every input.
 *
 * STRIPPED, a fixed table as of Unicode 15.1, is removed: C0 and C1 controls and DEL, U+2028 and U+2029, the variation
 * selectors U+FE00..U+FE0F and U+E0100..U+E01EF, and every format (Cf) character as of Unicode 15.1 (soft hyphen,
 * Arabic number signs, the letter mark, zero-width characters, bidirectional marks, overrides and isolates, invisible
 * operators, BOM, interlinear annotation, Egyptian format controls, shorthand and musical format controls, tags). The
 * table is the rule, not any platform's Unicode data. A lone surrogate (half of a pair) is removed too.
 *
 * Tab, line feed, carriage return and space separate words; they are not in the result as themselves.
 *   Single line (a name, a symbol, an attribute's name and value): a run of separators between two kept characters
 *   becomes one space (a removed character inside the run does not end it), and there are none at the ends.
 *   Description: the same, except that a run holding a line feed or a carriage return becomes one line feed, so there
 *   are no blank lines; from the 20th line on, such a run becomes a space instead, so there are at most 20 lines.
 * A cap counts code points and is applied after cleaning; a separator left at the end of the cut is dropped, so the
 * result never ends in a space or a line feed and never holds half a surrogate pair.
 */
final class TextRules {
    static final String UNICODE_VERSION = "15.1";
    static final int MAX_LINES = 20;

    /** Inclusive ranges, see the class comment. */
    private static final int[][] STRIPPED = {
            {0x0000, 0x001F}, {0x007F, 0x009F}, {0x00AD, 0x00AD}, {0x0600, 0x0605}, {0x061C, 0x061C}, {0x06DD, 0x06DD}, {0x070F, 0x070F},
            {0x0890, 0x0891}, {0x08E2, 0x08E2}, {0x180E, 0x180E}, {0x200B, 0x200F}, {0x2028, 0x202E}, {0x2060, 0x206F}, {0xFE00, 0xFE0F},
            {0xFEFF, 0xFEFF}, {0xFFF9, 0xFFFB}, {0x110BD, 0x110BD}, {0x110CD, 0x110CD}, {0x13430, 0x1343F}, {0x1BCA0, 0x1BCA3},
            {0x1D173, 0x1D17A}, {0xE0001, 0xE0001}, {0xE0020, 0xE007F}, {0xE0100, 0xE01EF}};

    private TextRules() {}

    /** Whether this code point is in the fixed table. */
    static boolean stripped(int c) {
        for(int[] range : STRIPPED) {
            if(c >= range[0] && c <= range[1]) {
                return true;
            }
        }
        return false;
    }

    /** Whether this code point is a lone surrogate, as a string's code points give one. */
    static boolean loneSurrogate(int c) {
        return c >= 0xD800 && c <= 0xDFFF;
    }

    /** The separators: tab, line feed, carriage return and space. */
    static boolean separator(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /** One line of text, at most maxCodePoints code points. */
    static String singleLine(String text, int maxCodePoints) {
        return clean(text, maxCodePoints, 1);
    }

    /** A description: at most 20 lines, at most maxCodePoints code points with the line feeds counted. */
    static String description(String text, int maxCodePoints) {
        return clean(text, maxCodePoints, MAX_LINES);
    }

    private static String clean(String text, int maxCodePoints, int maxLines) {
        if(text == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        boolean space = false;
        boolean lineBreak = false;
        int lines = 1;
        for(int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            if(c == '\n' || c == '\r') {
                lineBreak = true;
                continue;
            }
            if(c == ' ' || c == '\t') {
                space = true;
                continue;
            }
            if(loneSurrogate(c) || stripped(c)) {
                continue;
            }
            if(kept.length() > 0 && (space || lineBreak)) {
                if(lineBreak && lines < maxLines) {
                    kept.append('\n');
                    lines++;
                } else {
                    kept.append(' ');
                }
            }
            space = false;
            lineBreak = false;
            kept.appendCodePoint(c);
        }
        if(kept.codePointCount(0, kept.length()) <= maxCodePoints) {
            return kept.toString();
        }
        String cut = kept.substring(0, kept.offsetByCodePoints(0, maxCodePoints));
        //Runs are already single, so at most one separator can end the cut
        return cut.endsWith(" ") || cut.endsWith("\n") ? cut.substring(0, cut.length() - 1) : cut;
    }
}
