package com.sparrowwallet.drongo.evm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** MiniJson reads strict JSON, with no use of the platform's Unicode data, so the phone's reader can match it exactly. */
public class MiniJsonTest {
    private static void refused(String json) {
        Assertions.assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(json), json);
    }

    @Test
    public void whitespaceIsOnlySpaceTabLineFeedAndCarriageReturn() {
        Assertions.assertEquals(Map.of("a", BigDecimal.ONE), MiniJson.parse(" \t\r\n{ \t\r\n\"a\" \t\r\n: \t\r\n1 \t\r\n} \t\r\n"));
        for(String space : List.of("\u2003", "\u00a0", "\u000b", "\u000c", "\u001c", "\u0085", "\u3000", "\ufeff")) {
            refused("{" + space + "\"a\":1}");
            refused(space + "{\"a\":1}");
            refused("{\"a\":1}" + space);
        }
    }

    @Test
    public void unicodeEscapesAreFourAsciiHexDigits() {
        Assertions.assertEquals("AJ\u00e9\u00e9", MiniJson.parse("\"\\u0041\\u004a\\u00e9\\u00E9\""));
        Assertions.assertEquals("\ud83d\ude00", MiniJson.parse("\"\\ud83d\\ude00\""));
        for(String escape : List.of("\\u+041", "\\u-041", "\\u\u0660\u0660\u0664\u0661", "\\u\uff10\uff10\uff14\uff21", "\\u041", "\\u004g", "\\uZZZZ")) {
            refused("\"" + escape + "\"");
        }
        refused("\"\\x41\"");
        refused("\"\\u00");
    }

    @Test
    public void numbersFollowTheJsonGrammar() {
        for(String number : List.of("0", "-0", "5", "-5", "0.5", "-0.5", "1e5", "1E+5", "1e-5", "1.25e2", "10", "1234567890")) {
            Assertions.assertEquals(new BigDecimal(number), MiniJson.parse(number), number);
            Assertions.assertEquals(List.of(new BigDecimal(number)), MiniJson.parse("[" + number + "]"), number);
        }
        for(String number : List.of("+5", ".5", "5.", "007", "-01", "01.5", "-.5", "1e", "1e+", "1.e5", "-", "--1", "1.2.3", "0x10", "1ee5", "Infinity", "NaN")) {
            refused(number);
            refused("[" + number + "]");
            refused("{\"a\":" + number + "}");
        }
    }

    @Test
    public void rawControlCharactersInStringsAreRefused() {
        for(char c = 0; c < 0x20; c++) {
            refused("\"a" + c + "b\"");
        }
        //DEL and C1 are not controls in JSON's grammar
        Assertions.assertEquals("a\u007fb\u0085c", MiniJson.parse("\"a\u007fb\u0085c\""));
        Assertions.assertEquals("a\tb\nc", MiniJson.parse("\"a\\tb\\nc\""));
    }

    @Test
    public void duplicateKeysKeepTheLastValueInTheFirstPlace() {
        Map<?, ?> parsed = (Map<?, ?>)MiniJson.parse("{\"a\":1,\"b\":2,\"a\":3}");
        Assertions.assertEquals(List.of("a", "b"), List.copyOf(parsed.keySet()));
        Assertions.assertEquals(new BigDecimal(3), parsed.get("a"));
    }

    @Test
    public void nestingIsCappedAtSixtyFour() {
        Assertions.assertDoesNotThrow(() -> MiniJson.parse("[".repeat(64) + "]".repeat(64)));
        refused("[".repeat(65) + "]".repeat(65));
        Assertions.assertDoesNotThrow(() -> MiniJson.parse("{\"a\":" + "[".repeat(63) + "]".repeat(63) + "}"));
        refused("{\"a\":" + "[".repeat(64) + "]".repeat(64) + "}");
    }
}
