package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.Base58;
import com.sparrowwallet.drongo.protocol.ScriptType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class OrdWalletDumpTest {
    private Coin previousCoin;

    @BeforeEach
    public void setUp() {
        previousCoin = Coin.beginScope(Coin.LITECOIN);
    }

    @AfterEach
    public void tearDown() {
        Coin.endScope(previousCoin);
        Network.set(null);
    }

    private static List<OrdWalletDump.DumpedKey> fixture() throws Exception {
        try(InputStream in = OrdWalletDumpTest.class.getResourceAsStream("ord-dump-regtest.txt")) {
            Assertions.assertNotNull(in);
            return OrdWalletDump.parse(new InputStreamReader(in, StandardCharsets.UTF_8), Network.REGTEST);
        }
    }

    private static String wif(int version, long secret) {
        byte[] payload = new byte[33];
        byte[] key = Utils.bigIntegerToBytes(java.math.BigInteger.valueOf(secret), 32);
        System.arraycopy(key, 0, payload, 0, 32);
        payload[32] = 1;
        return Base58.encodeChecked(version, payload);
    }

    @Test
    public void parsesUsedKeysOnly() throws Exception {
        List<OrdWalletDump.DumpedKey> keys = fixture();
        //One label= key and three change=1 keys; reserve=1 keypool keys and the hdseed key are skipped
        Assertions.assertEquals(4, keys.size());
        Network.set(Network.REGTEST);
        Address first = OrdWalletDump.addresses(keys.getFirst()).get(ScriptType.P2WPKH);
        Assertions.assertEquals("rltc1qluy08d3peapsd8rujc6s7g7lfswhaz950p3ead", first.toString(), "matches the dump's own addr= for that key");
    }

    @Test
    public void everyCompressedKeyGivesFourAddressTypes() throws Exception {
        Map<ScriptType, Address> addresses = OrdWalletDump.addresses(fixture().getFirst());
        Assertions.assertEquals(List.of(ScriptType.P2WPKH, ScriptType.P2TR, ScriptType.P2SH_P2WPKH, ScriptType.P2PKH), List.copyOf(addresses.keySet()));
        for(Map.Entry<ScriptType, Address> entry : addresses.entrySet()) {
            Assertions.assertEquals(entry.getKey().getOutputScript(PolicyType.SINGLE_HD, fixture().getFirst().publicKey()), entry.getValue().getOutputScript());
        }
    }

    @Test
    public void rejectsMainnetKeyOnRegtest() {
        String dump = "# header\n" + wif(0xB0, 5) + " 2026-10-27T01:44:34Z label= # addr=x\n";
        Assertions.assertThrows(IllegalArgumentException.class, () -> OrdWalletDump.parse(new StringReader(dump), Network.REGTEST));
    }

    @Test
    public void acceptsLitecoinCoreMainnetWif() throws Exception {
        String dump = wif(0xB0, 5) + " 2026-10-27T01:44:34Z label= # addr=x\n";
        List<OrdWalletDump.DumpedKey> keys = OrdWalletDump.parse(new StringReader(dump), Network.MAINNET);
        Assertions.assertEquals(1, keys.size());
    }

    @Test
    public void errorNamesLineNotKey() {
        String bad = wif(0x80, 6).substring(0, 40) + "zz";
        String dump = "# one\n# two\n" + bad + " 2026-10-27T01:44:34Z label= # addr=x\n";
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class, () -> OrdWalletDump.parse(new StringReader(dump), Network.REGTEST));
        Assertions.assertTrue(e.getMessage().contains("line 3"), e.getMessage());
        Assertions.assertFalse(e.getMessage().contains(bad.substring(0, 10)), "never repeats key material");
    }

    @Test
    public void clearZeroesKeys() throws Exception {
        List<OrdWalletDump.DumpedKey> keys = fixture();
        Assertions.assertNotNull(keys.getFirst().signingKey());
        OrdWalletDump.clear(keys);
        for(OrdWalletDump.DumpedKey key : keys) {
            Assertions.assertTrue(key.isCleared());
            Assertions.assertThrows(IllegalStateException.class, key::signingKey);
        }
    }
}
