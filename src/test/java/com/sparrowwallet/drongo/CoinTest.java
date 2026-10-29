package com.sparrowwallet.drongo;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.InvalidAddressException;
import com.sparrowwallet.drongo.address.P2PKHAddress;
import com.sparrowwallet.drongo.address.P2SHAddress;
import com.sparrowwallet.drongo.crypto.DumpedPrivateKey;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.Base58;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.uri.BitcoinURI;
import com.sparrowwallet.drongo.uri.BitcoinURIParseException;
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

    /**
     * Bellscoin mainnet as Bells Core v3 (Nintondo/bellscoinV3 kernel/chainparams.cpp CMainParams): bech32_hrp "bel",
     * SegwitHeight 144000. bel1 P2WPKH outputs are in live use. Taproot is a separate BIP9 deployment (bit 2,
     * min_activation_height 188000) whose activation is not verified, so P2TR stays off.
     */
    /**
     * Before a server reports fee estimates the send form starts from the fallback rate. It must never start below the
     * chain's recommended rate, or the first transaction on DINGO, LKY and the like is refused or never mined.
     */
    @Test
    public void fallbackFeeRateNeverStartsBelowTheChainsRecommendedRate() {
        Assertions.assertEquals(20d, Coin.LITECOIN.getFallbackFeeRate(Network.MAINNET));
        Assertions.assertEquals(1d, Coin.LITECOIN.getFallbackFeeRate(Network.TESTNET));
        Assertions.assertEquals(1000d, Coin.DOGECOIN.getFallbackFeeRate(Network.MAINNET));
        Assertions.assertEquals(1000d, Coin.LUCKYCOIN.getFallbackFeeRate(Network.TESTNET));
        Assertions.assertEquals(100000d, Coin.DINGOCOIN.getFallbackFeeRate(Network.TESTNET));
        Assertions.assertEquals(20d, Coin.BELLSCOIN.getFallbackFeeRate(Network.MAINNET));
        Assertions.assertEquals(10d, Coin.BELLSCOIN.getFallbackFeeRate(Network.TESTNET));
        for(Coin coin : Coin.values()) {
            for(Network network : new Network[]{Network.MAINNET, Network.TESTNET}) {
                Assertions.assertTrue(coin.getFallbackFeeRate(network) >= coin.getRecommendedFeeRate(), coin + " on " + network);
            }
        }
    }

    @Test
    public void bellscoinMainnetHasSegwitButNotTaproot() throws InvalidAddressException {
        Assertions.assertEquals("bel", Coin.BELLSCOIN.params(Network.MAINNET).bech32AddressHrp());
        Assertions.assertTrue(Coin.BELLSCOIN.supports(ScriptType.P2WPKH));
        Assertions.assertTrue(Coin.BELLSCOIN.supports(ScriptType.P2SH_P2WPKH));
        Assertions.assertTrue(Coin.BELLSCOIN.supports(ScriptType.P2WSH));
        Assertions.assertFalse(Coin.BELLSCOIN.supports(ScriptType.P2TR));
        Assertions.assertFalse(Coin.BELLSCOIN.supports(ScriptType.P2A));
        Assertions.assertTrue(Coin.LITECOIN.supports(ScriptType.P2TR));

        //First P2WPKH address of "abandon x11 about" with Nintondo's passphrase "bells" at m/44'/0'/0'/0/0, computed
        //outside drongo (plain Python BIP39, BIP32 and BIP173; the same script gives BIP84's bc1qcr8te...).
        Coin.set(Coin.BELLSCOIN);
        ECKey key = ECKey.fromPublicOnly(Utils.hexToBytes("02688337c538eb7aaed6dfdd1dc572f30ec288eb833f19c141f9ec82795842da02"));
        Address address = ScriptType.P2WPKH.getAddress(key);
        Assertions.assertEquals("bel1qh07eu8daq4xvgefnq567dfrv8dssxm0ruw2q2a", address.toString());
        Assertions.assertArrayEquals(address.getData(), Address.fromString(address.toString()).getData());
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
        assertPrefixMatchesEncoding(Coin.BELLSCOIN, 25, false);
        assertPrefixMatchesEncoding(Coin.DINGOCOIN, 30, false);
        assertPrefixMatchesEncoding(Coin.JUNKCOIN, 16, false);
    }

    @Test
    public void testP2SHPrefixesMatchEncoding() {
        assertPrefixMatchesEncoding(Coin.LITECOIN, 50, true);
        assertPrefixMatchesEncoding(Coin.DOGECOIN, 22, true);
        assertPrefixMatchesEncoding(Coin.PEPECOIN, 22, true);
        assertPrefixMatchesEncoding(Coin.LUCKYCOIN, 5, true);
        assertPrefixMatchesEncoding(Coin.BELLSCOIN, 30, true);
        assertPrefixMatchesEncoding(Coin.DINGOCOIN, 22, true);
        assertPrefixMatchesEncoding(Coin.JUNKCOIN, 5, true);
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

    /**
     * Chains without segwit or MWEB have null HRPs. Address parsing must reject these inputs as
     * invalid rather than dereferencing the null HRP - the prefix checks sit outside the try block in
     * Address.fromString, so a NullPointerException there escapes instead of being reported.
     */
    @Test
    public void testNullHrpChainsRejectRatherThanCrash() {
        String[] candidates = {
                "ltc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
                "ltcmweb1qqf9c2vqx6z0lxs2m2p3n6uqv0kcqz9h",
                "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080",
                "not an address",
                ""
        };

        for(Coin coin : new Coin[]{Coin.DOGECOIN, Coin.PEPECOIN, Coin.LUCKYCOIN, Coin.DINGOCOIN, Coin.JUNKCOIN}) {
            Coin.set(coin);
            Assertions.assertNull(Network.get().getBech32AddressHRP(), coin + " should have no bech32 HRP");

            for(String candidate : candidates) {
                Assertions.assertFalse(Network.get().hasBech32AddressPrefix(candidate));
                Assertions.assertFalse(Network.get().hasMwebAddressPrefix(candidate));
                Assertions.assertFalse(Network.get().hasSilentPaymentsAddressPrefix(candidate));
                Assertions.assertThrows(InvalidAddressException.class, () -> Address.fromString(candidate),
                        coin + " must reject \"" + candidate + "\" as invalid, not crash on a null HRP");
            }
        }
    }

    /**
     * The null-safe prefix helpers must not have broken segwit or MWEB recognition on Litecoin.
     */
    @Test
    public void testLitecoinStillRecognisesBech32AndMweb() {
        Coin.set(Coin.LITECOIN);
        Assertions.assertTrue(Network.get().hasBech32AddressPrefix("ltc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"));
        Assertions.assertTrue(Network.get().hasMwebAddressPrefix("ltcmweb1qqf9c2vqx6z0lxs2m2p3n6uqv0kcqz9h"));
        //Bech32 permits uppercase, which the previous case-sensitive startsWith would have missed.
        Assertions.assertTrue(Network.get().hasBech32AddressPrefix("LTC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4"));
        Assertions.assertFalse(Network.get().hasBech32AddressPrefix("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"));
    }

    @Test
    public void testUriSchemesFollowTheActiveCoin() {
        Assertions.assertEquals("litecoin", Coin.LITECOIN.getUriScheme());
        Assertions.assertEquals("dogecoin", Coin.DOGECOIN.getUriScheme());
        Assertions.assertEquals("pepecoin", Coin.PEPECOIN.getUriScheme());
        Assertions.assertEquals("luckycoin", Coin.LUCKYCOIN.getUriScheme());
        Assertions.assertEquals("bellscoin", Coin.BELLSCOIN.getUriScheme());
        Assertions.assertEquals("dingocoin", Coin.DINGOCOIN.getUriScheme());
        Assertions.assertEquals("junkcoin", Coin.JUNKCOIN.getUriScheme());

        //Resolved per call, not captured in a static field at class-load time.
        for(Coin coin : Coin.values()) {
            Coin.set(coin);
            Assertions.assertEquals(coin.getUriScheme(), BitcoinURI.getUriScheme(),
                    "BitcoinURI scheme must track the active coin");
        }
    }

    /**
     * A BIP21 URI for one chain must not parse on another - the scheme is the only thing distinguishing
     * them before the address is even read.
     */
    @Test
    public void testUriSchemeFromAnotherCoinIsRejected() {
        Coin.set(Coin.DOGECOIN);
        Assertions.assertThrows(BitcoinURIParseException.class,
                () -> new BitcoinURI("litecoin:LX1nHQxHRs5UbjXHTvyDLPnPPfNwYVaCLM"));

        Coin.set(Coin.LITECOIN);
        Assertions.assertThrows(BitcoinURIParseException.class,
                () -> new BitcoinURI("dogecoin:DH5yaieqoZN36fDVciNyRueRGvGLR3mr7L"));
    }

    /**
     * The silent payments URI field is the chain's HRP, so it is absent on chains without the feature.
     */
    @Test
    public void testSilentPaymentsUriFieldFollowsTheActiveCoin() {
        Coin.set(Coin.LITECOIN);
        Assertions.assertEquals("ltcsp", BitcoinURI.getSilentPaymentsAddressField());

        for(Coin coin : new Coin[]{Coin.DOGECOIN, Coin.PEPECOIN, Coin.LUCKYCOIN, Coin.BELLSCOIN, Coin.DINGOCOIN, Coin.JUNKCOIN}) {
            Coin.set(coin);
            Assertions.assertNull(BitcoinURI.getSilentPaymentsAddressField(),
                    coin + " has no silent payments HRP and so no URI field");
        }
    }

    @Test
    public void testScriptTypeSupport() {
        //Litecoin keeps everything it had before the coin axis.
        for(ScriptType scriptType : ScriptType.ADDRESSABLE_TYPES) {
            Assertions.assertTrue(Coin.LITECOIN.supports(scriptType), "Litecoin should still support " + scriptType);
        }

        //Bellscoin has segwit v0 but not (verified) Taproot, so no witness v1 outputs, and no MWEB.
        for(ScriptType scriptType : ScriptType.ADDRESSABLE_TYPES) {
            boolean expected = scriptType != ScriptType.P2TR && scriptType != ScriptType.P2A && scriptType != ScriptType.MWEB;
            Assertions.assertEquals(expected, Coin.BELLSCOIN.supports(scriptType), "Bellscoin support for " + scriptType);
        }

        for(Coin coin : new Coin[]{Coin.DOGECOIN, Coin.PEPECOIN, Coin.LUCKYCOIN, Coin.DINGOCOIN, Coin.JUNKCOIN}) {
            //Legacy types work on every chain.
            Assertions.assertTrue(coin.supports(ScriptType.P2PKH));
            Assertions.assertTrue(coin.supports(ScriptType.P2SH));
            Assertions.assertTrue(coin.supports(ScriptType.P2PK));

            for(ScriptType witnessType : ScriptType.WITNESS_TYPES) {
                Assertions.assertFalse(coin.supports(witnessType), coin + " must not support " + witnessType);
            }

            for(ScriptType mwebType : new ScriptType[]{ScriptType.MWEB, ScriptType.MWEB_HOGEX, ScriptType.MWEB_PEGIN}) {
                Assertions.assertFalse(coin.supports(mwebType), coin + " must not support " + mwebType);
            }
        }
    }

    /**
     * Wallet creation offers whatever getAddressableScriptTypes returns, so it must not list a type
     * whose address encoding would hit a null HRP.
     */
    @Test
    public void testAddressableScriptTypesAreFilteredByCoin() {
        Coin.set(Coin.LITECOIN);
        List<ScriptType> litecoinTypes = ScriptType.getAddressableScriptTypes(PolicyType.SINGLE);
        Assertions.assertTrue(litecoinTypes.contains(ScriptType.P2WPKH), "Litecoin should still offer segwit");

        for(Coin coin : new Coin[]{Coin.DOGECOIN, Coin.PEPECOIN, Coin.LUCKYCOIN, Coin.BELLSCOIN, Coin.DINGOCOIN, Coin.JUNKCOIN}) {
            Coin.set(coin);
            List<ScriptType> types = ScriptType.getAddressableScriptTypes(PolicyType.SINGLE);
            Assertions.assertFalse(types.isEmpty(), coin + " must still offer legacy script types");
            Assertions.assertTrue(types.contains(ScriptType.P2PKH), coin + " should offer P2PKH");

            for(ScriptType type : types) {
                Assertions.assertTrue(coin.supports(type),
                        coin + " offered unsupported script type " + type);
            }
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
        //Bellscoin and Dingocoin both inherit Dogecoin's dgub/dgpv.
        Assertions.assertEquals(ExtendedKey.Header.dgub, Coin.BELLSCOIN.params(Network.MAINNET).xpubHeader());
        Assertions.assertEquals(ExtendedKey.Header.dgub, Coin.DINGOCOIN.params(Network.MAINNET).xpubHeader());
        //Junkcoin uses Bitcoin's standard xpub/xprv (0x0488B21E/0x0488ADE4 in its chainparams), like Luckycoin.
        Assertions.assertEquals(ExtendedKey.Header.xpub, Coin.JUNKCOIN.params(Network.MAINNET).xpubHeader());
        Assertions.assertEquals(ExtendedKey.Header.xprv, Coin.JUNKCOIN.params(Network.MAINNET).xprvHeader());

        Assertions.assertEquals(0x02facafd, ExtendedKey.Header.dgub.getHeader());
        Assertions.assertEquals(0x02fac398, ExtendedKey.Header.dgpv.getHeader());
    }

    /**
     * The default derivation must follow the active coin's SLIP-44 type - the path literals carry
     * Litecoin's 2, which the flavor era never substituted (all legacy flavor wallets derive at 2').
     */
    @Test
    public void testDefaultDerivationFollowsTheActiveCoin() {
        Coin.set(Coin.LITECOIN);
        Assertions.assertEquals("m/44'/2'/0'", ScriptType.P2PKH.getDefaultDerivationPath());
        Assertions.assertEquals("m/84'/2'/0'", ScriptType.P2WPKH.getDefaultDerivationPath());
        Coin.set(Coin.DOGECOIN);
        Assertions.assertEquals("m/44'/3'/0'", ScriptType.P2PKH.getDefaultDerivationPath());
        Coin.set(Coin.PEPECOIN);
        Assertions.assertEquals("m/44'/3434'/0'", ScriptType.P2PKH.getDefaultDerivationPath());
        //Luckycoin and Junkcoin shipping wallets derive at m/44'/0'/0'
        Coin.set(Coin.LUCKYCOIN);
        Assertions.assertEquals("m/44'/0'/0'", ScriptType.P2PKH.getDefaultDerivationPath());
        Coin.set(Coin.JUNKCOIN);
        Assertions.assertEquals("m/44'/0'/0'", ScriptType.P2PKH.getDefaultDerivationPath());
        Coin.set(Coin.BELLSCOIN);
        Assertions.assertEquals("m/44'/762'/0'", ScriptType.P2PKH.getDefaultDerivationPath());
        Coin.set(Coin.DINGOCOIN);
        Assertions.assertEquals("m/44'/3'/0'", ScriptType.P2PKH.getDefaultDerivationPath());
    }

    @Test
    public void testBip44CoinTypes() {
        Assertions.assertEquals(2, Coin.LITECOIN.getBip44CoinType());
        Assertions.assertEquals(3, Coin.DOGECOIN.getBip44CoinType());
        Assertions.assertEquals(3434, Coin.PEPECOIN.getBip44CoinType());
        //Deliberately 0, not SLIP-44's registered 743/790 - both shipping Luckycoin wallets derive at
        //m/44'/0'/0', so following the registry here would strand existing users' funds.
        Assertions.assertEquals(0, Coin.LUCKYCOIN.getBip44CoinType());
        //Bellscoin has a registered SLIP-44 (762); Dingocoin has none and derives at Dogecoin's slot (3).
        Assertions.assertEquals(762, Coin.BELLSCOIN.getBip44CoinType());
        Assertions.assertEquals(3, Coin.DINGOCOIN.getBip44CoinType());
        //Junkcoin's shipping wallets (Foundation web wallet/JunkieWally) derive at m/44'/0'/0'.
        Assertions.assertEquals(0, Coin.JUNKCOIN.getBip44CoinType());
    }

    /**
     * Deriving at coin type 0 means a seed used on this chain produces the very same keys as a Bitcoin
     * wallet from that seed - the xpub is a Bitcoin xpub and the addresses are Bitcoin addresses. That is
     * the right call for compatibility with these chains' shipping wallets, but it is not something a user
     * reusing a seed would expect, so the chains it applies to must be identifiable rather than implicit.
     */
    @Test
    public void testCoinsDerivingAtBitcoinsCoinType() {
        Assertions.assertTrue(Coin.LUCKYCOIN.sharesBitcoinDerivation());
        Assertions.assertTrue(Coin.JUNKCOIN.sharesBitcoinDerivation());

        Assertions.assertFalse(Coin.LITECOIN.sharesBitcoinDerivation());
        Assertions.assertFalse(Coin.DOGECOIN.sharesBitcoinDerivation());
        Assertions.assertFalse(Coin.PEPECOIN.sharesBitcoinDerivation());
        Assertions.assertFalse(Coin.BELLSCOIN.sharesBitcoinDerivation());
        Assertions.assertFalse(Coin.DINGOCOIN.sharesBitcoinDerivation());
    }

    /** The predicate must track the coin type, not a hardcoded list that can drift away from it. */
    @Test
    public void testBitcoinDerivationTracksCoinType() {
        for(Coin coin : Coin.values()) {
            Assertions.assertEquals(coin.getBip44CoinType() == 0, coin.sharesBitcoinDerivation(),
                    coin + " disagrees with its own BIP44 coin type");
        }
    }

    @Test
    public void testChainCapabilities() {
        Assertions.assertFalse(Coin.LITECOIN.isAuxPow());
        Assertions.assertTrue(Coin.DOGECOIN.isAuxPow());
        Assertions.assertTrue(Coin.PEPECOIN.isAuxPow());
        Assertions.assertTrue(Coin.LUCKYCOIN.isAuxPow());
        Assertions.assertTrue(Coin.DINGOCOIN.isAuxPow());
        //Junkcoin activated AuxPoW at height 173000 (chain ID 0x2020), merge-mined with Litecoin.
        Assertions.assertTrue(Coin.JUNKCOIN.isAuxPow());
        //Bellscoin is Scrypt but has NOT activated AuxPoW on mainnet, so its headers are plain 80-byte.
        Assertions.assertFalse(Coin.BELLSCOIN.isAuxPow());

        //Only Litecoin has MWEB; Litecoin and Bellscoin have segwit, the merge-mined chains never activated it.
        Assertions.assertTrue(Coin.LITECOIN.isSegwitSupported());
        Assertions.assertTrue(Coin.LITECOIN.isMwebSupported());
        Assertions.assertTrue(Coin.BELLSCOIN.isSegwitSupported());
        Assertions.assertFalse(Coin.BELLSCOIN.isMwebSupported());
        for(Coin coin : new Coin[]{Coin.DOGECOIN, Coin.PEPECOIN, Coin.LUCKYCOIN, Coin.DINGOCOIN, Coin.JUNKCOIN}) {
            Assertions.assertFalse(coin.isSegwitSupported(), coin + " must not report segwit support");
            Assertions.assertFalse(coin.isMwebSupported(), coin + " must not report MWEB support");
            Assertions.assertNull(coin.params(Network.MAINNET).bech32AddressHrp(), coin + " must have no bech32 HRP");
            Assertions.assertNull(coin.params(Network.MAINNET).mwebAddressHrp(), coin + " must have no MWEB HRP");
        }
    }

    /**
     * The JSON-RPC port each node listens on, taken from that chain's own chainparamsbase nRPCPort - not
     * the P2P port from chainparams nDefaultPort, which is what this originally held for five of the seven
     * and would have pointed every Core connection at the peer-to-peer socket.
     */
    @Test
    public void testDefaultPorts() {
        Assertions.assertEquals(9332, Coin.LITECOIN.params(Network.MAINNET).defaultPort());
        Assertions.assertEquals(22555, Coin.DOGECOIN.params(Network.MAINNET).defaultPort());
        Assertions.assertEquals(33873, Coin.PEPECOIN.params(Network.MAINNET).defaultPort());
        Assertions.assertEquals(9918, Coin.LUCKYCOIN.params(Network.MAINNET).defaultPort());
        //Bellscoin V3 rebased on modern Core and left the base params alone, so it answers on Bitcoin's port
        Assertions.assertEquals(8332, Coin.BELLSCOIN.params(Network.MAINNET).defaultPort());
        Assertions.assertEquals(34646, Coin.DINGOCOIN.params(Network.MAINNET).defaultPort());
        //Junkcoin is the one chain using the same number for RPC and P2P
        Assertions.assertEquals(9771, Coin.JUNKCOIN.params(Network.MAINNET).defaultPort());
    }

    /**
     * Unverified Coin/Network combinations must fail rather than return plausible-looking constants.
     */
    @Test
    public void testUnverifiedNetworksThrow() {
        for(Coin coin : new Coin[]{Coin.DOGECOIN, Coin.PEPECOIN, Coin.LUCKYCOIN, Coin.BELLSCOIN, Coin.DINGOCOIN, Coin.JUNKCOIN}) {
            for(Network network : new Network[]{Network.TESTNET, Network.REGTEST, Network.SIGNET}) {
                if(coin.supports(network)) {
                    continue; //verified, pinned by its own test below
                }
                Assertions.assertThrows(IllegalStateException.class, () -> coin.params(network),
                        coin + " on " + network + " must throw rather than guess parameters");
            }
        }
    }

    /**
     * Test mode: the coins offered on testnet, and Luckycoin excluded because its testnet is dead.
     */
    @Test
    public void testCoinsAvailableOnTestnet() {
        Assertions.assertTrue(Coin.availableOn(Network.TESTNET).contains(Coin.LITECOIN));
        Assertions.assertTrue(Coin.availableOn(Network.TESTNET).contains(Coin.JUNKCOIN));
        Assertions.assertTrue(Coin.availableOn(Network.TESTNET).contains(Coin.PEPECOIN));
        Assertions.assertTrue(Coin.availableOn(Network.TESTNET).contains(Coin.DOGECOIN));
        Assertions.assertTrue(Coin.availableOn(Network.TESTNET).contains(Coin.LUCKYCOIN));
        Assertions.assertTrue(Coin.availableOn(Network.TESTNET).contains(Coin.BELLSCOIN));
        Assertions.assertTrue(Coin.availableOn(Network.TESTNET).contains(Coin.DINGOCOIN));
        Coin.ChainParams pep = Coin.PEPECOIN.params(Network.TESTNET);
        Assertions.assertEquals(113, pep.p2pkhAddressHeader());
        Assertions.assertEquals(196, pep.p2shAddressHeader());
        Assertions.assertEquals(241, pep.dumpedPrivateKeyHeader());
        Assertions.assertEquals(44873, pep.defaultPort());
        Assertions.assertEquals(List.of(Coin.values()), Coin.availableOn(Network.MAINNET));
    }

    /**
     * Junkcoin testnet, from junkcoin-core CTestNetParams. Keys serialise as tpub/tprv (not Core's dgub)
     * because dgub is a mainnet-only header here and a wallet saved with it could not be read back.
     */
    @Test
    public void testJunkcoinTestnetParams() {
        Coin.ChainParams testnet = Coin.JUNKCOIN.params(Network.TESTNET);
        Assertions.assertEquals(111, testnet.p2pkhAddressHeader());
        Assertions.assertEquals(5, testnet.p2shAddressHeader());
        Assertions.assertEquals(239, testnet.dumpedPrivateKeyHeader());
        Assertions.assertEquals(19771, testnet.defaultPort());
        Assertions.assertEquals(ExtendedKey.Header.tpub, testnet.xpubHeader());
        Assertions.assertEquals(ExtendedKey.Header.tprv, testnet.xprvHeader());
        Assertions.assertEquals("tjc", testnet.bech32AddressHrp());
        Assertions.assertNull(Coin.JUNKCOIN.params(Network.MAINNET).bech32AddressHrp());
    }

    /**
     * supports() is what cross-network callers filter on, so it must agree exactly with whether
     * params() succeeds. If the two drift, a caller either probes a network that throws or skips one
     * that would have worked.
     */
    @Test
    public void testSupportsAgreesWithParams() {
        for(Coin coin : Coin.values()) {
            for(Network network : Network.values()) {
                boolean paramsResolved;
                try {
                    coin.params(network);
                    paramsResolved = true;
                } catch(IllegalStateException e) {
                    paramsResolved = false;
                }

                Assertions.assertEquals(paramsResolved, coin.supports(network),
                        coin + " supports(" + network + ") disagrees with whether params() resolves");
            }
        }
    }

    @Test
    public void testCoinbaseMaturity() {
        Assertions.assertEquals(100, Coin.LITECOIN.getCoinbaseMaturity(Network.MAINNET, 1));
        Assertions.assertEquals(30, Coin.DOGECOIN.getCoinbaseMaturity(Network.TESTNET, 144999));
        Assertions.assertEquals(240, Coin.DOGECOIN.getCoinbaseMaturity(Network.MAINNET, 145000));
        Assertions.assertEquals(70, Coin.JUNKCOIN.getCoinbaseMaturity(Network.MAINNET, 1));
        Assertions.assertEquals(30, Coin.JUNKCOIN.getCoinbaseMaturity(Network.TESTNET, 254068));
        Assertions.assertEquals(70, Coin.LUCKYCOIN.getCoinbaseMaturity(Network.TESTNET, 25));
        Assertions.assertEquals(30, Coin.BELLSCOIN.getCoinbaseMaturity(Network.MAINNET, 1));
        Assertions.assertEquals(30, Coin.DINGOCOIN.getCoinbaseMaturity(Network.TESTNET, 4999));
        Assertions.assertEquals(240, Coin.DINGOCOIN.getCoinbaseMaturity(Network.TESTNET, 5000));
        Assertions.assertEquals(100, Coin.PEPECOIN.getCoinbaseMaturity(Network.MAINNET, 1), "unverified coins keep 100");
    }

    @Test
    public void testSupportedCanonicalValues() {
        Assertions.assertArrayEquals(Network.canonicalValues(), Coin.LITECOIN.supportedCanonicalValues());
        Assertions.assertArrayEquals(new Network[]{Network.MAINNET, Network.TESTNET}, Coin.JUNKCOIN.supportedCanonicalValues());
        Assertions.assertArrayEquals(new Network[]{Network.MAINNET, Network.TESTNET}, Coin.PEPECOIN.supportedCanonicalValues());
        Assertions.assertArrayEquals(new Network[]{Network.MAINNET, Network.TESTNET}, Coin.DOGECOIN.supportedCanonicalValues());
        Coin.ChainParams dogeTestnet = Coin.DOGECOIN.params(Network.TESTNET);
        Assertions.assertEquals(113, dogeTestnet.p2pkhAddressHeader());
        Assertions.assertEquals(196, dogeTestnet.p2shAddressHeader());
        Assertions.assertEquals(241, dogeTestnet.dumpedPrivateKeyHeader());
        Assertions.assertEquals(44555, dogeTestnet.defaultPort());
        Assertions.assertArrayEquals(new Network[]{Network.MAINNET, Network.TESTNET}, Coin.LUCKYCOIN.supportedCanonicalValues());
        Coin.ChainParams lkyTestnet = Coin.LUCKYCOIN.params(Network.TESTNET);
        Assertions.assertEquals(113, lkyTestnet.p2pkhAddressHeader());
        Assertions.assertEquals(196, lkyTestnet.p2shAddressHeader());
        Assertions.assertEquals(241, lkyTestnet.dumpedPrivateKeyHeader());
        Assertions.assertEquals(19918, lkyTestnet.defaultPort());
        Coin.ChainParams dingoTestnet = Coin.DINGOCOIN.params(Network.TESTNET);
        Assertions.assertEquals(113, dingoTestnet.p2pkhAddressHeader());
        Assertions.assertEquals(196, dingoTestnet.p2shAddressHeader());
        Assertions.assertEquals(241, dingoTestnet.dumpedPrivateKeyHeader());
        Assertions.assertEquals(44556, dingoTestnet.defaultPort());
        Coin.ChainParams bellsTestnet = Coin.BELLSCOIN.params(Network.TESTNET);
        Assertions.assertEquals(33, bellsTestnet.p2pkhAddressHeader());
        Assertions.assertEquals(22, bellsTestnet.p2shAddressHeader());
        Assertions.assertEquals(158, bellsTestnet.dumpedPrivateKeyHeader());
        Assertions.assertEquals("tbel", bellsTestnet.bech32AddressHrp());
        Assertions.assertEquals(18332, bellsTestnet.defaultPort());
        for(Coin coin : Coin.values()) {
            Assertions.assertTrue(coin.supports(Network.TESTNET), coin + " has verified testnet params");
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
        //Litecoin Core SECRET_KEY: 176 mainnet, 239 testnet/regtest. Not Bitcoin's 128/191.
        Assertions.assertEquals(176, mainnet.dumpedPrivateKeyHeader());

        Coin.ChainParams testnet = Coin.LITECOIN.params(Network.TESTNET);
        Assertions.assertEquals(111, testnet.p2pkhAddressHeader());
        Assertions.assertEquals(58, testnet.p2shAddressHeader());
        Assertions.assertEquals("tltc", testnet.bech32AddressHrp());
        Assertions.assertEquals(239, testnet.dumpedPrivateKeyHeader());
    }

    /**
     * A WIF key exported from Litecoin must import into the Litecoin ecosystem, and a standard Litecoin
     * WIF must import here. Litecoin Core uses 176, which produces "T" (compressed) or "6" (uncompressed)
     * leading characters - not Bitcoin's "L"/"K" from 128. This is the regression that stranded exported
     * keys and rejected imported ones.
     */
    @Test
    public void testLitecoinWifPrefix() {
        Coin.set(Coin.LITECOIN);
        byte[] privateKey = Utils.hexToBytes("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");
        ECKey key = ECKey.fromPrivate(privateKey);

        String wif = key.getPrivateKeyEncoded().toBase58();
        Assertions.assertTrue(wif.startsWith("T"),
                "Litecoin compressed WIF should lead with T, was " + wif.charAt(0));

        //Round-trips through the decoder, which enforces the same version byte.
        Assertions.assertEquals(key.getPrivKey(),
                DumpedPrivateKey.fromBase58(wif).getKey().getPrivKey());
    }
}
