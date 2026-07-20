package com.sparrowwallet.drongo;

import com.sparrowwallet.drongo.protocol.ScriptType;

import java.util.Arrays;
import java.util.Locale;

/**
 * The chain axis, orthogonal to {@link Network}. A Coin identifies which chain the application is
 * built for; Network identifies which network of that chain (mainnet, testnet, ...). Together they
 * select a {@link ChainParams}.
 *
 * Like Network, this is a per-process singleton - each Sparrow-Scrypto binary ships one coin. This
 * keeps every existing {@code Network.get().getXxx()} call site working unchanged, with Network
 * delegating its chain constants here at call time.
 *
 * Params are resolved in a method rather than an EnumMap field to avoid a static initialisation
 * cycle between Coin and Network.
 */
public enum Coin {
    LITECOIN("litecoin", "Litecoin", "LTC", 2, false, true, true),
    DOGECOIN("dogecoin", "Dogecoin", "DOGE", 3, true, false, false),
    PEPECOIN("pepecoin", "Pepecoin", "PEP", 3434, true, false, false),
    LUCKYCOIN("luckycoin", "Luckycoin", "LKY", 0, true, false, false);

    private final String name;
    private final String displayName;
    private final String ticker;
    private final int bip44CoinType;
    private final boolean auxPow;
    private final boolean segwit;
    private final boolean mweb;

    private static Coin currentCoin;

    Coin(String name, String displayName, String ticker, int bip44CoinType, boolean auxPow, boolean segwit, boolean mweb) {
        this.name = name;
        this.displayName = displayName;
        this.ticker = ticker;
        this.bip44CoinType = bip44CoinType;
        this.auxPow = auxPow;
        this.segwit = segwit;
        this.mweb = mweb;
    }

    /**
     * Whether verified parameters exist for this coin on the given network.
     *
     * Callers that probe across networks - such as Address.fromString working out whether an address
     * belongs to a different network than the configured one - must filter on this rather than calling
     * params() and catching. Support is statically known, so it should not be discovered by exception.
     */
    public boolean supports(Network network) {
        return switch(this) {
            case LITECOIN -> true;
            //Only mainnet parameters have been verified for the merge-mined chains so far.
            case DOGECOIN, PEPECOIN, LUCKYCOIN -> network == Network.MAINNET;
        };
    }

    /** The canonical networks this coin has verified parameters for. */
    public Network[] supportedCanonicalValues() {
        return Arrays.stream(Network.canonicalValues()).filter(this::supports).toArray(Network[]::new);
    }

    /**
     * Whether this chain can use the given script type.
     *
     * The witness types need segwit and the MWEB types need MWEB, neither of which the merge-mined
     * chains have - their addresses would otherwise be encoded against a null HRP. P2PK, P2PKH and
     * P2SH work everywhere.
     */
    public boolean supports(ScriptType scriptType) {
        if(scriptType == ScriptType.MWEB || scriptType == ScriptType.MWEB_HOGEX || scriptType == ScriptType.MWEB_PEGIN) {
            return mweb;
        }

        if(Arrays.asList(ScriptType.WITNESS_TYPES).contains(scriptType)) {
            return segwit;
        }

        return true;
    }

    /**
     * The chain constants for this coin on the given network.
     *
     * Combinations whose parameters have not been verified against the chain's own chainparams.cpp
     * throw rather than returning plausible-looking bytes. Guessing an address version byte silently
     * produces addresses on the wrong chain, so unverified combinations must fail loudly.
     */
    public ChainParams params(Network network) {
        return switch(this) {
            case LITECOIN -> switch(network) {
                //WIF version bytes match Litecoin Core chainparams: SECRET_KEY 176 (0xB0) mainnet,
                //239 (0xEF) testnet/regtest. The pre-Coin enum carried 128/191, which are Electrum-LTC's
                //internal WIF_PREFIX base constants (0x80/0xbf) copied without the +48 script-type offset
                //Electrum-LTC adds before emitting - so exported keys were 0x80-prefixed (valid Bitcoin
                //WIFs, rejected by every Litecoin wallet), and standard 0xB0 Litecoin keys would not
                //import. REGTEST already used the correct 239.
                case MAINNET -> new ChainParams(48, "L", 50, "M", "ltc", "ltcsp", "ltcmweb",
                        ExtendedKey.Header.xprv, ExtendedKey.Header.xpub, 176, 9332);
                case TESTNET -> new ChainParams(111, "mn", 58, "Q", "tltc", "tltcsp", "tmweb",
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 239, 19332);
                case REGTEST -> new ChainParams(111, "mn", 196, "2", "rltc", "rltcsp", "tmweb",
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 239, 19443);
                case SIGNET -> new ChainParams(111, "mn", 196, "2", "tb", "tsp", "tmweb",
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 239, 38332);
                case TESTNET4 -> new ChainParams(111, "mn", 196, "2", "tb", "tsp", "tmweb",
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 239, 48332);
            };
            //Dogecoin: no segwit (DEPLOYMENT_SEGWIT nTimeout=0, never activated), so no bech32 HRP.
            //Core defines dgub/dgpv but much of the ecosystem uses standard xpub/xprv - dgub is the
            //serialisation default here, with xpub accepted on import.
            case DOGECOIN -> switch(network) {
                case MAINNET -> new ChainParams(30, "D", 22, "9A", null, null, null,
                        ExtendedKey.Header.dgpv, ExtendedKey.Header.dgub, 158, 22556);
                default -> throw unverified(network);
            };
            //Pepecoin: Dogecoin Core derivative, inherits dgub/dgpv unchanged. No segwit, no bech32.
            case PEPECOIN -> switch(network) {
                case MAINNET -> new ChainParams(56, "P", 22, "9A", null, null, null,
                        ExtendedKey.Header.dgpv, ExtendedKey.Header.dgub, 158, 33874);
                default -> throw unverified(network);
            };
            //Luckycoin: uses Bitcoin's standard xpub/xprv prefixes, not Dogecoin's. No segwit.
            //Version byte 47 straddles the K/L base58 boundary, so P2PKH addresses lead with either -
            //published examples only ever show "L...", but "K..." is equally valid and must be accepted.
            case LUCKYCOIN -> switch(network) {
                case MAINNET -> new ChainParams(47, "KL", 5, "3", null, null, null,
                        ExtendedKey.Header.xprv, ExtendedKey.Header.xpub, 175, 9917);
                default -> throw unverified(network);
            };
        };
    }

    private IllegalStateException unverified(Network network) {
        return new IllegalStateException(displayName + " parameters for " + network.getName()
                + " have not been verified against chainparams and are not available. "
                + "Add them only from the chain's own source - do not infer them.");
    }

    public String getName() {
        return name;
    }

    public String getCapitalizedName() {
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    public String toDisplayString() {
        return displayName;
    }

    /** Ticker symbol, e.g. "LTC". Used for unit labels and fiat pairing. */
    public String getTicker() {
        return ticker;
    }

    /**
     * The BIP21 URI scheme, e.g. "litecoin" for litecoin:LX1n... Each of these chains uses its own
     * lowercase name as the scheme, so this tracks getName(), but the two are separate concepts and
     * callers building or parsing URIs should use this.
     */
    public String getUriScheme() {
        return name;
    }

    /**
     * SLIP-44 coin type for the BIP44 derivation path.
     *
     * Luckycoin is deliberately 0, not the registered 743/790: both shipping Luckycoin wallets derive
     * at m/44'/0'/0', so following SLIP-44 here would strand existing users' funds.
     */
    public int getBip44CoinType() {
        return bip44CoinType;
    }

    /**
     * Whether this chain is merge-mined via AuxPoW, making serialised block headers variable-length.
     * Clients must not assume an 80-byte header on these chains.
     */
    public boolean isAuxPow() {
        return auxPow;
    }

    public boolean isSegwitSupported() {
        return segwit;
    }

    public boolean isMwebSupported() {
        return mweb;
    }

    public static Coin get() {
        if(currentCoin == null) {
            currentCoin = LITECOIN;
        }

        return currentCoin;
    }

    public static void set(Coin coin) {
        if(currentCoin != null && coin != currentCoin && !isTest()) {
            throw new IllegalStateException("Coin already set to " + currentCoin.getName());
        }

        currentCoin = coin;
    }

    private static boolean isTest() {
        return System.getProperty("org.gradle.test.worker") != null;
    }

    @Override
    public String toString() {
        return getName();
    }

    /**
     * Chain constants for one Coin/Network combination. Mirrors the fields the Network enum carried
     * before the coin axis was introduced, so Network's getters are one-line delegations.
     *
     * bech32Hrp, spHrp and mwebHrp are null on chains without segwit, silent payments or MWEB.
     */
    public record ChainParams(
            int p2pkhAddressHeader,
            String p2pkhAddressPrefix,
            int p2shAddressHeader,
            String p2shAddressPrefix,
            String bech32AddressHrp,
            String spAddressHrp,
            String mwebAddressHrp,
            ExtendedKey.Header xprvHeader,
            ExtendedKey.Header xpubHeader,
            int dumpedPrivateKeyHeader,
            int defaultPort) {
    }
}
