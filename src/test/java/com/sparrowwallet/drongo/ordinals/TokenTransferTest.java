package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Coin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class TokenTransferTest {
    private static byte[] fixture(String name) throws IOException {
        try(InputStream in = TokenTransferTest.class.getResourceAsStream(name)) {
            Assertions.assertNotNull(in, name);
            return in.readAllBytes();
        }
    }

    private static Optional<TokenTransfer.Parsed> parseText(String json) {
        return TokenTransfer.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void protocols() {
        Assertions.assertEquals("ltc-20", TokenProtocol.LTC20.p());
        Assertions.assertEquals("LTC-20", TokenProtocol.LTC20.displayName());
        Assertions.assertEquals("JUNK-20", TokenProtocol.JUNK20.displayName());
        Assertions.assertEquals(Coin.DOGECOIN, TokenProtocol.DRC20.coin());
        Assertions.assertEquals(Optional.of(TokenProtocol.BEL20), TokenProtocol.forCoin(Coin.BELLSCOIN));
        Assertions.assertEquals(Optional.empty(), TokenProtocol.forCoin(Coin.LUCKYCOIN));
    }

    @Test
    public void bodyLtc20() {
        Assertions.assertArrayEquals("{\"p\":\"ltc-20\",\"op\":\"transfer\",\"tick\":\"lite\",\"amt\":\"100\"}".getBytes(StandardCharsets.UTF_8),
                TokenTransfer.body(TokenProtocol.LTC20, "lite", "100"));
        Assertions.assertEquals("text/plain;charset=utf-8", TokenTransfer.CONTENT_TYPE);
    }

    @Test
    public void bodyDecimalAmount() {
        Assertions.assertEquals("{\"p\":\"drc-20\",\"op\":\"transfer\",\"tick\":\"dogi\",\"amt\":\"1.5\"}",
                new String(TokenTransfer.body(TokenProtocol.DRC20, "dogi", "1.5"), StandardCharsets.UTF_8));
    }

    @Test
    public void bodyTick() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> TokenTransfer.body(TokenProtocol.LTC20, "abc", "1"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> TokenTransfer.body(TokenProtocol.LTC20, "abcde", "1"));
        Assertions.assertTrue(new String(TokenTransfer.body(TokenProtocol.DRC20, "dogx", "1"), StandardCharsets.UTF_8).contains("\"dogx\""));
        Assertions.assertTrue(new String(TokenTransfer.body(TokenProtocol.DRC20, "DOGX", "1"), StandardCharsets.UTF_8).contains("\"DOGX\""));
    }

    @Test
    public void bodyMatchesLiveInscriptions() throws IOException {
        Assertions.assertArrayEquals(fixture("drc20-transfer.txt"), TokenTransfer.body(TokenProtocol.DRC20, "dogx", "59"));
        Assertions.assertArrayEquals(fixture("ltc20-transfer.txt"), TokenTransfer.body(TokenProtocol.LTC20, "3333", "100"));
    }

    @Test
    public void parseFixtures() throws IOException {
        Assertions.assertEquals(new TokenTransfer.Parsed("drc-20", "transfer", "dogx", "59"), TokenTransfer.parse(fixture("drc20-transfer.txt")).orElseThrow());
        Assertions.assertEquals(new TokenTransfer.Parsed("ltc-20", "transfer", "3333", "100"), TokenTransfer.parse(fixture("ltc20-transfer.txt")).orElseThrow());
    }

    @Test
    public void parseDeploy() {
        TokenTransfer.Parsed p = parseText("{\"p\":\"drc-20\",\"op\":\"deploy\",\"tick\":\"dogx\",\"max\":\"21000000000\",\"dec\":18}").orElseThrow();
        Assertions.assertEquals("deploy", p.op());
        Assertions.assertNull(p.amt());
    }

    @Test
    public void parseRejects() {
        Assertions.assertTrue(parseText("hello").isEmpty());
        Assertions.assertTrue(parseText("{\"op\":\"transfer\",\"tick\":\"abcd\",\"amt\":\"1\"}").isEmpty());
        Assertions.assertTrue(parseText("[1]").isEmpty());
        Assertions.assertTrue(TokenTransfer.parse(new byte[0]).isEmpty());
        Assertions.assertTrue(TokenTransfer.parse(null).isEmpty());
        String big = "{\"p\":\"ltc-20\",\"op\":\"transfer\",\"tick\":\"lite\",\"amt\":\"1\",\"x\":\"" + "a".repeat(1000) + "\"}";
        Assertions.assertTrue(parseText(big).isEmpty());
    }

    @Test
    public void parseEscapes() {
        Assertions.assertEquals("a\"b", parseText("{\"p\":\"ltc-20\",\"op\":\"transfer\",\"tick\":\"a\\\"b\",\"amt\":\"1\"}").orElseThrow().tick());
        Assertions.assertEquals("\u00e9\ud83d\ude00", parseText("{\"p\":\"x\",\"op\":\"\\u00e9\\ud83d\\ude00\"}").orElseThrow().op());
        Assertions.assertEquals("a/b\n\\", parseText("{\"p\":\"x\",\"op\":\"a\\/b\\n\\\\\"}").orElseThrow().op());
        Assertions.assertTrue(parseText("{\"p\":\"x\",\"op\":\"\\q\"}").isEmpty());
        Assertions.assertTrue(parseText("{\"p\":\"x\",\"op\":\"\\u12\"}").isEmpty());
        Assertions.assertTrue(parseText("{\"p\":\"x\",\"op\":\"\\u12zz\"}").isEmpty());
    }

    @Test
    public void parseRejectsNestedAndDuplicate() {
        Assertions.assertTrue(parseText("{\"p\":\"ltc-20\",\"op\":\"transfer\",\"extra\":{\"a\":1}}").isEmpty());
        Assertions.assertTrue(parseText("{\"p\":\"ltc-20\",\"op\":\"transfer\",\"extra\":[1]}").isEmpty());
        Assertions.assertTrue(parseText("{\"p\":\"ltc-20\",\"p\":\"drc-20\",\"op\":\"transfer\"}").isEmpty());
        Assertions.assertTrue(parseText("{\"p\":\"ltc-20\",\"op\":\"transfer\"} x").isEmpty());
    }

    @Test
    public void parseRejectsSignedHexEscapes() {
        Assertions.assertTrue(parseText("{\"p\":\"x\",\"op\":\"\\u+041\"}").isEmpty());
        Assertions.assertTrue(parseText("{\"p\":\"x\",\"op\":\"\\u-001\"}").isEmpty());
        Assertions.assertEquals("A", parseText("{\"p\":\"x\",\"op\":\"\\u0041\"}").orElseThrow().op());
    }

    @Test
    public void bodyRejectsZeroAndTooManyDecimals() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> TokenTransfer.body(TokenProtocol.LTC20, "lite", "0"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> TokenTransfer.body(TokenProtocol.LTC20, "lite", "0.000"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> TokenTransfer.body(TokenProtocol.LTC20, "lite", "1." + "1".repeat(19)));
        Assertions.assertDoesNotThrow(() -> TokenTransfer.body(TokenProtocol.LTC20, "lite", "1." + "1".repeat(18)));
    }

    @Test
    public void parseRequiresStringForKnownFields() {
        Assertions.assertTrue(parseText("{\"p\":\"ltc-20\",\"op\":\"transfer\",\"tick\":\"lite\",\"amt\":5}").isEmpty());
        Assertions.assertTrue(parseText("{\"p\":\"ltc-20\",\"op\":\"transfer\",\"tick\":null,\"amt\":\"5\"}").isEmpty());
        Assertions.assertTrue(parseText("{\"p\":true,\"op\":\"transfer\"}").isEmpty());
        Assertions.assertTrue(parseText("{\"p\":\"ltc-20\",\"op\":7}").isEmpty());
        Assertions.assertTrue(parseText("{\"p\":\"ltc-20\",\"op\":\"deploy\",\"max\":21000000,\"dec\":8}").isPresent());
    }

    @Test
    public void parseRejectsMalformedUtf8() {
        String json = "{\"p\":\"ltc-20\",\"op\":\"transfer\",\"tick\":\"liXe\",\"amt\":\"1\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Assertions.assertTrue(TokenTransfer.parse(bytes).isPresent());
        bytes[json.indexOf('X')] = (byte)0xff;
        Assertions.assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("\ufffd"));
        Assertions.assertTrue(TokenTransfer.parse(bytes).isEmpty());
    }

    @Test
    public void parseTruncatedInput() {
        Assertions.assertTrue(parseText("{\"p\":\"x\"").isEmpty());
        Assertions.assertTrue(parseText("{\"p\"").isEmpty());
        Assertions.assertTrue(parseText("{\"p\":\"x\\").isEmpty());
        Assertions.assertTrue(parseText("{").isEmpty());
        Assertions.assertTrue(parseText("{\"p\":").isEmpty());
    }

    @Test
    public void amountSyntax() {
        for(String bad : new String[] {"1e3", "+5", "-1", "0", "0.000", " 5", "5.", ".5", "1,000", "", null}) {
            Assertions.assertTrue(TokenTransfer.amountProblem(bad, 18, null).isPresent(), "'" + bad + "'");
        }
        Assertions.assertTrue(TokenTransfer.amountProblem("5", 18, null).isEmpty());
        Assertions.assertTrue(TokenTransfer.amountProblem("0.001", 18, null).isEmpty());
    }

    @Test
    public void amountDecimals() {
        Assertions.assertTrue(TokenTransfer.amountProblem("1.123", 2, "10").isPresent());
        Assertions.assertTrue(TokenTransfer.amountProblem("1.12", 2, "10").isEmpty());
        Assertions.assertTrue(TokenTransfer.amountProblem("1.5", null, "900.5").isEmpty());
        Assertions.assertTrue(TokenTransfer.amountProblem("1.55", null, "900.5").isPresent());
        Assertions.assertTrue(TokenTransfer.amountProblem("1.5", null, "900").isPresent());
        Assertions.assertTrue(TokenTransfer.amountProblem("1.5", null, null).isPresent());
        Assertions.assertTrue(TokenTransfer.amountProblem("1." + "1".repeat(19), 18, null).isPresent());
        Assertions.assertTrue(TokenTransfer.amountProblem("1." + "1".repeat(18), 18, null).isEmpty());
        Assertions.assertTrue(TokenTransfer.amountProblem("1." + "1".repeat(19), null, "1." + "0".repeat(25)).isPresent());
    }
}
