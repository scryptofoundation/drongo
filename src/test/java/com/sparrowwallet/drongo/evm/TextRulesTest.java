package com.sparrowwallet.drongo.evm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TextRulesTest {
    private static final String EMOJI = "\ud83d\ude00";

    @Test
    public void theStripRuleIsAFixedTable() {
        //Not this platform's Unicode data: U+2065 is unassigned and the variation selectors are marks, yet all are stripped
        int[] strippedPoints = {0x00, 0x1F, 0x7F, 0x9F, 0xAD, 0x600, 0x605, 0x61C, 0x6DD, 0x70F, 0x890, 0x891, 0x8E2, 0x180E, 0x200B, 0x200F,
                0x2028, 0x202E, 0x2060, 0x2065, 0x206F, 0xFE00, 0xFE0F, 0xFEFF, 0xFFF9, 0xFFFB, 0x110BD, 0x110CD, 0x13430, 0x1343F, 0x1BCA0,
                0x1BCA3, 0x1D173, 0x1D17A, 0xE0001, 0xE0020, 0xE007F, 0xE0100, 0xE01EF};
        for(int c : strippedPoints) {
            Assertions.assertTrue(TextRules.stripped(c), Integer.toHexString(c));
            Assertions.assertEquals("ab", TextRules.singleLine("a" + new String(Character.toChars(c)) + "b", 64), Integer.toHexString(c));
        }
        int[] keptPoints = {0x20, 'a', 0xA0, 0xAC, 0xAE, 0x5FF, 0x606, 0x180D, 0x180F, 0x200A, 0x2010, 0x2027, 0x202F, 0x205F, 0x2070, 0xFDFF,
                0xFE10, 0xFEFE, 0xFF00, 0xFFF8, 0xFFFC, 0x1343E + 2, 0x1D17B, 0xE0000, 0xE0002, 0xE0080, 0xE00FF, 0xE01F0, 0x1F600};
        for(int c : keptPoints) {
            Assertions.assertFalse(TextRules.stripped(c), Integer.toHexString(c));
        }
        Assertions.assertEquals("15.1", TextRules.UNICODE_VERSION);
    }

    @Test
    public void loneSurrogatesAreStripped() {
        Assertions.assertEquals("ab", TextRules.singleLine("a\ud800b", 64));
        Assertions.assertEquals("ab", TextRules.singleLine("a\udc00b", 64));
        Assertions.assertEquals("ab", TextRules.singleLine("a\udbff\udbffb", 64));
        Assertions.assertEquals("a" + EMOJI + "b", TextRules.singleLine("a" + EMOJI + "b", 64));
        Assertions.assertEquals("ab", TextRules.description("a\udfffb", 64));
    }

    @Test
    public void aSingleLineHasOneSpaceBetweenWords() {
        Assertions.assertEquals("two words", TextRules.singleLine("  two \t\r\n words\n", 64));
        Assertions.assertEquals("a b", TextRules.singleLine("a \u200b b", 64));
        Assertions.assertEquals("ab", TextRules.singleLine("a\u200bb", 64));
        Assertions.assertEquals("", TextRules.singleLine(" \t\u200b\n ", 64));
        //Only the four separators: a no-break or em space is text
        Assertions.assertEquals("a\u00a0b \u2003", TextRules.singleLine("a\u00a0b \u2003 ", 64));
        Assertions.assertEquals("", TextRules.singleLine(null, 64));
    }

    @Test
    public void aDescriptionKeepsItsLines() {
        Assertions.assertEquals("line one\nline two", TextRules.description("\r\n line one \r\n\r\n \t line two  \n", 500));
        Assertions.assertEquals("a\nb\nc", TextRules.description("a\rb\n\nc", 500));
        StringBuilder many = new StringBuilder();
        for(int i = 1; i <= 25; i++) {
            many.append("L").append(i).append('\n');
        }
        String described = TextRules.description(many.toString(), 500);
        Assertions.assertEquals(TextRules.MAX_LINES, described.split("\n", -1).length);
        Assertions.assertTrue(described.endsWith("\nL20 L21 L22 L23 L24 L25"), described);
        //A single line never holds a line feed
        Assertions.assertEquals("a b", TextRules.singleLine("a\nb", 64));
    }

    @Test
    public void capsCountCodePointsAfterCleaning() {
        Assertions.assertEquals("x".repeat(63) + EMOJI, TextRules.singleLine("x".repeat(63) + EMOJI, 64));
        Assertions.assertEquals("x".repeat(64), TextRules.singleLine("x".repeat(64) + EMOJI, 64));
        Assertions.assertEquals(EMOJI.repeat(16), TextRules.singleLine(EMOJI.repeat(20), 16));
        //Stripped characters do not count
        Assertions.assertEquals("x".repeat(16), TextRules.singleLine("\u200b".repeat(50) + "x".repeat(16), 16));
        //No space or line feed is left at the cut
        Assertions.assertEquals("x".repeat(63), TextRules.singleLine("x".repeat(63) + " y", 64));
        Assertions.assertEquals("x".repeat(63), TextRules.description("x".repeat(63) + "\ny", 64));
        Assertions.assertEquals("x".repeat(62) + " y", TextRules.singleLine("x".repeat(62) + "   y", 64));
    }
}
