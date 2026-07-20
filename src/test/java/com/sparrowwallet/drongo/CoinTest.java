package com.sparrowwallet.drongo;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.InvalidAddressException;
import com.sparrowwallet.drongo.address.P2PKHAddress;
import com.sparrowwallet.drongo.address.P2SHAddress;
import com.sparrowwallet.drongo.protocol.Base58;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Guards the chain constants introduced with the coin axis.
 *
 * A wrong address version byte does not fail loudly - it silently produces a well-formed address on
 * the wrong chain, so every value here is asserted against the figure taken from that chain's own
 * chainparams.cpp rather than against whatever the code currently happens to emit.
 */
public class CoinTest {
    //Fixed so encodings are deterministic across runs.
    private static final byte[] HASH160 = Utils.hexToBytes("0102030405060708090a0b0c0d0e0f1011121314");

    @AfterEach
    public void resetCoin() {
        Coin.set(Coin.LITECOIN);
    }

    private static int versionByteOf(Address address) {
        return Byte.toUnsignedInt(Base58.decodeChecked(address.toString())[0]);
    }

    /**
     * Hashes spanning the encodable range. The leading base58 character of an address is determined by
     * its high-order bytes, so sweeping the first byte plus both extremes surfaces every leading
     * character a version byte can produce - a single sample does not.
     */
    private static List<byte[]> sampleHashes() {
        List<byte[]> hashes = new ArrayList<>();
        hashes.add(new byte[20]);
        byte[] max = new byte[20];
        Arrays.fill(max, (byte)0xff);
        hashes.add(max);

        for(int i = 0; i < 256; i++) {
            byte[] hash = HASH160.clone();
            hash[0] = (byte)i;
            hashes.add(hash);
        }

        return hashes;
    }

    /**
     * Asserts the version byte is constant across the range and returns every leading character the
     * encoding actually produces.
     */
    private Set<Character> observedLeadingCharacters(Coin coin, int expectedVersion, Function<byte[], Address> factory) {
        Coin.set(coin);
        Set<Character> observed = new TreeSet<>();

        for(byte[] hash : sampleHashes()) {
            Address address = factory.apply(hash);
            Assertions.assertEquals(expectedVersion, versionByteOf(address), coin + " version byte");
            observed.add(address.toString().charAt(0));
        }

        return observed;
    }

    private static Set<Character> declaredPrefixes(String prefix) {
        return prefix.chars().mapToObj(c -> (char)c).collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * The declared prefix must match the set of leading characters the encoding produces exactly.
     *
     * Too narrow and valid addresses are rejected - Luckycoin's version byte 47 straddles the K/L
     * boundary, and published examples only ever show "L...". Too broad and addresses from an adjacent
     * chain pass the prefix screen.
     */
    private void assertPrefixMatchesEncoding(Coin coin, int expectedVersion, boolean p2sh) {
        Function<byte[], Address> factory = p2sh ? P2SHAddress::new : P2PKHAddress::new;
        Set<Character> observed = observedLeadingCharacters(coin, expectedVersion, factory);
        String declared = p2sh
                ? coin.params(Network.MAINNET).p2shAddressPrefix()
                : coin.params(Network.MAINNET).p2pkhAddressPrefix();

        Assertions.assertEquals(declaredPrefixes(declared), observed,
                coin + (p2sh ? " P2SH" : " P2PKH") + " declared prefix \"" + declared
                        + "\" does not match the characters version byte " + expectedVersion + " produces");

        for(byte[] hash : sampleHashes()) {
            String address = factory.apply(hash).toString();
            boolean matched = p2sh
                    ? Network.get().hasP2SHAddressPrefix(address)
                    : Network.get().hasP2PKHAddressPrefix(address);
            Assertions.assertTrue(matched, coin + " address " + address + " not recognised by prefix matching");
        }
    }

    @Test
    public void testP2PKHPrefixesMatchEncoding() {
        assertPrefixMatchesEncoding(Coin.LITECOIN, 48, false);
        assertPrefixMatchesEncoding(Coin.DOGECOIN, 30, false);
        assertPrefixMatchesEncoding(Coin.PEPECOIN, 56, false);
        assertPrefixMatchesEncoding(Coin.LUCKYCOIN, 47, false);
    }

    @Test
    public void testP2SHPrefixesMatchEncoding() {
        assertPrefixMatchesEncoding(Coin.LITECOIN, 50, true);
        assertPrefixMatchesEncoding(Coin.DOGECOIN, 22, true);
        assertPrefixMatchesEncoding(Coin.PEPECOIN, 22, true);
        assertPrefixMatchesEncoding(Coin.LUCKYCOIN, 5, true);
    }

    @Test
    public void testAddressesRoundTrip() throws InvalidAddressException {
        for(Coin coin : Coin.values()) {
            Coin.set(coin);
            Address address = new P2PKHAddress(HASH160);
            Assertions.assertArrayEquals(HASH160, Address.fromString(address.toString()).getData(),
                    coin + " P2PKH address did not round-trip");
        }
    }

    @Test
    public void testExtendedKeyHeaders() {
        //Dogecoin and Pepecoin serialise as dgub/dgpv; Luckycoin uses Bitcoin's standard xpub/xprv.
        Assertions.assertEquals(ExtendedKey.Header.xpub, Coin.LITECOIN.params(Network.MAINNET).xpubHeader());
        Assertions.assertEquals(ExtendedKey.Header.dgub, Coin.DOGECOIN.params(Network.MAINNET).xpubHeader());
        Assertions.assertEquals(ExtendedKey.Header.dgpv, Coin.DOGECOIN.params(Network.MAINNET).xprvHeader());
        Assertions.assertEquals(ExtendedKey.Header.dgub, Coin.PEPECOIN.params(Network.MAINNET).xpubHeader());
        Assertions.assertEquals(ExtendedKey.Header.xpub, Coin.LUCKYCOIN.params(Network.MAINNET).xpubHeader());

        Assertions.assertEquals(0x02facafd, ExtendedKey.Header.dgub.getHeader());
        Assertions.assertEquals(0x02fac398, ExtendedKey.Header.dgpv.getHeader());
    }

    @Test
    public void testBip44CoinTypes() {
        Assertions.assertEquals(2, Coin.LITECOIN.getBip44CoinType());
        Assertions.assertEquals(3, Coin.DOGECOIN.getBip44CoinType());
        Assertions.assertEquals(3434, Coin.PEPECOIN.getBip44CoinType());
        //Deliberately 0, not SLIP-44's registered 743/790 - both shipping Luckycoin wallets derive at
        //m/44'/0'/0', so following the registry here would strand existing users' funds.
        Assertions.assertEquals(0, Coin.LUCKYCOIN.getBip44CoinType());
    }

    @Test
    public void testChainCapabilities() {
        Assertions.assertFalse(Coin.LITECOIN.isAuxPow());
        Assertions.assertTrue(Coin.DOGECOIN.isAuxPow());
        Assertions.assertTrue(Coin.PEPECOIN.isAuxPow());
        Assertions.assertTrue(Coin.LUCKYCOIN.isAuxPow());

        //Only Litecoin has segwit and MWEB; the merge-mined chains never activated segwit.
        Assertions.assertTrue(Coin.LITECOIN.isSegwitSupported());
        Assertions.assertTrue(Coin.LITECOIN.isMwebSupported());
        for(Coin coin : new Coin[]{Coin.DOGECOIN, Coin.PEPECOIN, Coin.LUCKYCOIN}) {
            Assertions.assertFalse(coin.isSegwitSupported(), coin + " must not report segwit support");
            Assertions.assertFalse(coin.isMwebSupported(), coin + " must not report MWEB support");
            Assertions.assertNull(coin.params(Network.MAINNET).bech32AddressHrp(), coin + " must have no bech32 HRP");
            Assertions.assertNull(coin.params(Network.MAINNET).mwebAddressHrp(), coin + " must have no MWEB HRP");
        }
    }

    @Test
    public void testDefaultPorts() {
        Assertions.assertEquals(9332, Coin.LITECOIN.params(Network.MAINNET).defaultPort());
        Assertions.assertEquals(22556, Coin.DOGECOIN.params(Network.MAINNET).defaultPort());
        Assertions.assertEquals(33874, Coin.PEPECOIN.params(Network.MAINNET).defaultPort());
        Assertions.assertEquals(9917, Coin.LUCKYCOIN.params(Network.MAINNET).defaultPort());
    }

    /**
     * Unverified Coin/Network combinations must fail rather than return plausible-looking constants.
     */
    @Test
    public void testUnverifiedNetworksThrow() {
        for(Coin coin : new Coin[]{Coin.DOGECOIN, Coin.PEPECOIN, Coin.LUCKYCOIN}) {
            for(Network network : new Network[]{Network.TESTNET, Network.REGTEST, Network.SIGNET}) {
                Assertions.assertThrows(IllegalStateException.class, () -> coin.params(network),
                        coin + " on " + network + " must throw rather than guess parameters");
            }
        }
    }

    @Test
    public void testLitecoinParametersUnchanged() {
        //Pins the pre-refactor Network values so the coin axis stays behaviour-preserving for Litecoin.
        Coin.ChainParams mainnet = Coin.LITECOIN.params(Network.MAINNET);
        Assertions.assertEquals(48, mainnet.p2pkhAddressHeader());
        Assertions.assertEquals(50, mainnet.p2shAddressHeader());
        Assertions.assertEquals("ltc", mainnet.bech32AddressHrp());
        Assertions.assertEquals("ltcmweb", mainnet.mwebAddressHrp());
        //128 is Bitcoin's WIF prefix; Litecoin Core uses 176. Preserved deliberately - see Coin.java.
        Assertions.assertEquals(128, mainnet.dumpedPrivateKeyHeader());

        Coin.ChainParams testnet = Coin.LITECOIN.params(Network.TESTNET);
        Assertions.assertEquals(111, testnet.p2pkhAddressHeader());
        Assertions.assertEquals(58, testnet.p2shAddressHeader());
        Assertions.assertEquals("tltc", testnet.bech32AddressHrp());
    }
}
