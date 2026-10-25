package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.Utils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

public class AbiTest {
    private static final EvmAddress ADDRESS = EvmAddress.fromString("0x000000000000000000000000000000000000dEaD");

    @Test
    public void wellKnownSelectors() {
        Assertions.assertEquals("a9059cbb", Utils.bytesToHex(Abi.selector("transfer(address,uint256)")));
        Assertions.assertEquals("70a08231", Utils.bytesToHex(Abi.selector("balanceOf(address)")));
        Assertions.assertEquals("313ce567", Utils.bytesToHex(Abi.selector("decimals()")));
        Assertions.assertEquals("95d89b41", Utils.bytesToHex(Abi.selector("symbol()")));
        Assertions.assertEquals("095ea7b3", Utils.bytesToHex(Abi.selector("approve(address,uint256)")));
    }

    @Test
    public void encodesTransfer() {
        byte[] data = Abi.encodeCall("transfer(address,uint256)", ADDRESS, BigInteger.valueOf(1000));
        Assertions.assertEquals("a9059cbb"
                + "000000000000000000000000000000000000000000000000000000000000dead"
                + "00000000000000000000000000000000000000000000000000000000000003e8", Utils.bytesToHex(data));
    }

    @Test
    public void encodesDynamicString() {
        byte[] data = Abi.encodeCall("f(string,uint256)", "JKC", BigInteger.ONE);
        String hex = Utils.bytesToHex(data).substring(8);
        Assertions.assertEquals("0000000000000000000000000000000000000000000000000000000000000040", hex.substring(0, 64)); //offset 64
        Assertions.assertEquals("0000000000000000000000000000000000000000000000000000000000000001", hex.substring(64, 128));
        Assertions.assertEquals("0000000000000000000000000000000000000000000000000000000000000003", hex.substring(128, 192)); //length
        Assertions.assertEquals("4a4b43" + "0".repeat(58), hex.substring(192, 256));
    }

    @Test
    public void decodesStringAndBytes32Results() {
        byte[] dynamic = Utils.hexToBytes("0000000000000000000000000000000000000000000000000000000000000020"
                + "0000000000000000000000000000000000000000000000000000000000000004"
                + Utils.bytesToHex("wJKC".getBytes(StandardCharsets.UTF_8)) + "0".repeat(56));
        Assertions.assertEquals("wJKC", Abi.decodeString(dynamic));

        byte[] bytes32 = new byte[32];
        System.arraycopy("MKR".getBytes(StandardCharsets.UTF_8), 0, bytes32, 0, 3);
        Assertions.assertEquals("MKR", Abi.decodeString(bytes32));

        byte[] badLength = Utils.hexToBytes("0000000000000000000000000000000000000000000000000000000000000020"
                + "00000000000000000000000000000000000000000000000000000000ffffffff");
        Assertions.assertThrows(IllegalArgumentException.class, () -> Abi.decodeString(badLength));
    }

    @Test
    public void decodesWords() {
        byte[] result = Utils.hexToBytes("000000000000000000000000000000000000000000000000000000000000dead"
                + "0000000000000000000000000000000000000000000000000000000000000001");
        Assertions.assertEquals(ADDRESS, Abi.decodeAddress(result, 0));
        Assertions.assertTrue(Abi.decodeBool(result, 1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Abi.decodeUint(result, 2));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Abi.uint(BigInteger.TWO.pow(256)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Abi.uint(BigInteger.valueOf(-1)));
    }

    @Test
    public void tokenAmountsAndSanitising() {
        Erc20Token token = new Erc20Token(ADDRESS, "wJKC‮\n", "Wrapped Junkcoin", 8);
        Assertions.assertEquals("wJKC", token.symbol(), "control and bidi override characters must be stripped");
        Assertions.assertEquals("1.5", token.format(BigInteger.valueOf(150_000_000)));
        Assertions.assertEquals(BigInteger.valueOf(150_000_000), token.parse("1.5"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> token.parse("0.000000001"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> token.parse("-1"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Erc20Token(ADDRESS, "X", "X", 77));
    }
}
