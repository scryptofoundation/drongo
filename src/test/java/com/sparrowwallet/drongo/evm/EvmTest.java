package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public class EvmTest {
    private static String hex(byte[] bytes) {
        return Utils.bytesToHex(bytes).toLowerCase(Locale.ROOT);
    }

    @Test
    public void keccakOfEmptyInput() {
        Assertions.assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", hex(Keccak.keccak256(new byte[0])));
    }

    @Test
    public void rlpSpecExamples() {
        Assertions.assertEquals("83646f67", hex(Rlp.encode("dog".getBytes(StandardCharsets.US_ASCII))));
        Assertions.assertEquals("c88363617483646f67", hex(Rlp.encode(List.of("cat".getBytes(StandardCharsets.US_ASCII), "dog".getBytes(StandardCharsets.US_ASCII)))));
        Assertions.assertEquals("80", hex(Rlp.encode(new byte[0])));
        Assertions.assertEquals("c0", hex(Rlp.encode(List.of())));
        Assertions.assertEquals("80", hex(Rlp.encode(BigInteger.ZERO)));
        Assertions.assertEquals("0f", hex(Rlp.encode(BigInteger.valueOf(15))));
        Assertions.assertEquals("820400", hex(Rlp.encode(BigInteger.valueOf(1024))));
        //A 56-byte string switches to the long-form length prefix.
        byte[] longString = "Lorem ipsum dolor sit amet, consectetur adipisicing elit".getBytes(StandardCharsets.US_ASCII);
        Assertions.assertTrue(hex(Rlp.encode(longString)).startsWith("b838"));
    }

    @Test
    public void eip55Checksums() {
        for(String checksummed : List.of("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
                "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb")) {
            Assertions.assertEquals(checksummed, EvmAddress.fromString(checksummed.toLowerCase(Locale.ROOT)).toString());
            Assertions.assertEquals(checksummed, EvmAddress.fromString(checksummed).toString());
        }
        //One flipped letter case breaks the checksum and must be rejected.
        Assertions.assertThrows(IllegalArgumentException.class, () -> EvmAddress.fromString("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"));
        Assertions.assertFalse(EvmAddress.isValid("ltc1qxyz"));
        Assertions.assertFalse(EvmAddress.isValid("0x1234"));
    }

    @Test
    public void privateKeyToAddress() {
        ECKey key = ECKey.fromPrivate(Utils.hexToBytes("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"));
        Assertions.assertEquals("0x2c7536E3605D9C16a7a3D7b1898e529396a65c23", EvmAddress.fromPublicKey(key).toString());
    }

    @Test
    public void seedDerivationMatchesStandardWallets() throws Exception {
        //The widely used development mnemonic; every EVM wallet derives these first two accounts from it.
        DeterministicSeed seed = new DeterministicSeed("test test test test test test test test test test test junk", "", 0, DeterministicSeed.Type.BIP39);
        Assertions.assertEquals("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", EvmAccount.fromSeed(seed, 0).getAddress().toString());
        Assertions.assertEquals("0x70997970C51812dc3A010C7d01b50e0d17dc79C8", EvmAccount.fromSeed(seed, 1).getAddress().toString());
        Assertions.assertEquals("m/44'/60'/0'/0/0", EvmAccount.fromSeed(seed, 0).getDerivationPath());
    }

    @Test
    public void eip155SpecSigningExample() {
        //The worked example in EIP-155: nonce 9, 20 gwei, 21000 gas, 1 ether to 0x3535..., chain id 1.
        List<Object> signingFields = List.of(BigInteger.valueOf(9), Wei.gwei(20), BigInteger.valueOf(21000),
                Utils.hexToBytes("3535353535353535353535353535353535353535"), Wei.parse("1"), new byte[0],
                BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO);
        byte[] signingData = Rlp.encode(signingFields);
        Assertions.assertEquals("ec098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a764000080018080", hex(signingData));
        byte[] hash = Keccak.keccak256(signingData);
        Assertions.assertEquals("daf5a779ae972f972197303d7b574746c7ef83eadac0f2791ad23db92e4c8e53", hex(hash));

        ECKey key = ECKey.fromPrivate(Utils.hexToBytes("4646464646464646464646464646464646464646464646464646464646464646"));
        EvmSigner.Signature signature = EvmSigner.sign(hash, key);
        Assertions.assertEquals(new BigInteger("18515461264373351373200002665853028612451056578545711640558177340181847433846"), signature.r());
        Assertions.assertEquals(new BigInteger("46948507304638947509940763649030358759909902576025900602547168820602576006531"), signature.s());
        //v = chainId * 2 + 35 + recoveryId = 37
        Assertions.assertEquals(37, 1 * 2 + 35 + signature.recoveryId());
        Assertions.assertEquals(EvmAddress.fromPublicKey(key), EvmSigner.recoverSigner(hash, signature));
    }

    @Test
    public void eip1559TransactionSignsForLiteForge() throws Exception {
        DeterministicSeed seed = new DeterministicSeed("test test test test test test test test test test test junk", "", 0, DeterministicSeed.Type.BIP39);
        EvmAccount account = EvmAccount.fromSeed(seed, 0);
        Eip1559Transaction tx = new Eip1559Transaction(LitVmNetwork.LITEFORGE_TESTNET.getChainId(), BigInteger.ZERO, BigInteger.ZERO,
                Wei.gwei(2), BigInteger.valueOf(21000), EvmAddress.fromString("0x70997970C51812dc3A010C7d01b50e0d17dc79C8"), Wei.parse("0.25"), null);
        Eip1559Transaction.Signed signed = account.sign(tx);

        Assertions.assertTrue(signed.rawHex().startsWith("0x02"));
        Assertions.assertEquals(account.getAddress(), signed.from());
        Assertions.assertEquals(66, signed.hashHex().length());
        //Deterministic: signing the same transaction twice gives identical bytes.
        Assertions.assertEquals(signed.rawHex(), account.sign(tx).rawHex());
        //The same transaction on another chain id signs differently, so it cannot be replayed across chains.
        Eip1559Transaction otherChain = new Eip1559Transaction(1L, BigInteger.ZERO, BigInteger.ZERO, Wei.gwei(2), BigInteger.valueOf(21000),
                tx.getTo(), tx.getValue(), null);
        Assertions.assertNotEquals(signed.rawHex(), account.sign(otherChain).rawHex());
        Assertions.assertEquals(Wei.parse("0.25").add(Wei.gwei(2).multiply(BigInteger.valueOf(21000))), tx.maxCost());
    }

    @Test
    public void weiFormatting() {
        Assertions.assertEquals("1.5", Wei.format(Wei.parse("1.5")));
        Assertions.assertEquals("0", Wei.format(BigInteger.ZERO));
        Assertions.assertEquals("0.000000000000000001", Wei.format(BigInteger.ONE));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Wei.parse("-1"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Wei.parse("0.0000000000000000001"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Wei.parse("abc"));
    }

    @Test
    public void jsonRpcParsing() {
        Object parsed = MiniJson.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":\"0x1\",\"logs\":[],\"n\":12,\"ok\":true,\"x\":null,\"s\":\"a\\\"b\"}}");
        Assertions.assertTrue(parsed instanceof java.util.Map);
        Assertions.assertEquals(BigInteger.valueOf(4441), LitVmRpcClient.quantity("0x1159"));
        Assertions.assertEquals("0x1159", LitVmRpcClient.toQuantity(BigInteger.valueOf(4441)));
        Assertions.assertEquals(BigInteger.ZERO, LitVmRpcClient.quantity("0x0"));
    }

    /** RPC responses are untrusted: bounded nesting, no large exponent or oversized numbers, hex-only 256-bit quantities. */
    @Test
    public void untrustedRpcInputIsBounded() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("[".repeat(100) + "]".repeat(100)));
        Assertions.assertDoesNotThrow(() -> MiniJson.parse("[".repeat(10) + "]".repeat(10)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"result\":1e999999999}"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"result\":1e-999999999}"));
        Assertions.assertEquals(new java.math.BigDecimal("1.5E-7"), MiniJson.parse("1.5e-7"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("1.2.3"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"result\":" + "9".repeat(200) + "}"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> LitVmRpcClient.quantity(new java.math.BigDecimal("12")));
        Assertions.assertThrows(IllegalArgumentException.class, () -> LitVmRpcClient.quantity("0x" + "f".repeat(65)));
        Assertions.assertEquals(BigInteger.TWO.pow(256).subtract(BigInteger.ONE), LitVmRpcClient.quantity("0x" + "f".repeat(64)));
    }

    @Test
    public void onlyTestnetsAreDefined() {
        Assertions.assertEquals(3, LitVmNetwork.values().length);
        Assertions.assertEquals(4441L, LitVmNetwork.LITEFORGE_TESTNET.getChainId());
        Assertions.assertEquals(11155111L, LitVmNetwork.SEPOLIA.getChainId());
        for(LitVmNetwork network : LitVmNetwork.values()) {
            Assertions.assertTrue(network.isTestnet());
        }
    }
}
