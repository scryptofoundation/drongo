package com.sparrowwallet.drongo;

import com.sparrowwallet.drongo.protocol.ScriptType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The chain axis, orthogonal to {@link Network}. A Coin identifies which chain the application is
 * built for; Network identifies which network of that chain (mainnet, testnet, ...). Together they
 * select a {@link ChainParams}.
 *
 * The active coin is resolved per-thread, not per-process. This was a process-wide singleton while
 * every binary baked exactly one coin; the unified Sparrow-Scrypt build holds wallets of several
 * coins at once, so the foreground follows the focused wallet's tab while background work brackets
 * itself with {@link #beginScope}/{@link #endScope} to pin its own wallet's coin. Under
 * {@link #setStrictScoping} an unscoped background resolution throws rather than falling back to the
 * focused coin, because that fallback silently derives on the wrong chain. Either way every existing
 * {@code Network.get().getXxx()} call site keeps working, with Network delegating its chain
 * constants here at call time.
 *
 * Params are resolved in a method rather than an EnumMap field to avoid a static initialisation
 * cycle between Coin and Network.
 */
public enum Coin {
    LITECOIN("litecoin", "Litecoin", "LTC", "litecoin", 2, false, true, true),
    DOGECOIN("dogecoin", "Dogecoin", "DOGE", "dogecoin", 3, true, false, false),
    PEPECOIN("pepecoin", "Pepecoin", "PEP", "pepecoin-network", 3434, true, false, false),
    LUCKYCOIN("luckycoin", "Luckycoin", "LKY", "luckycoin", 0, true, false, false),
    //Bellscoin: Dogecoin Core derivative, Scrypt, but AuxPoW is NOT activated on mainnet (legacy blocks),
    //so its headers are standard 80-byte. Segwit since mainnet height 144000 (Bells Core v3).
    BELLSCOIN("bellscoin", "Bellscoin", "BELLS", "bellscoin", 762, false, true, false),
    //Dingocoin reuses Dogecoin's entire address/key identity (P2PKH 30, WIF 158, dgub/dgpv, coin type 3,
    //and even the same genesis hash). It is a distinct chain only at the network layer (magic c1c1c1c1,
    //ports). The flavor builds made that harmless - one coin per binary, pointed at that coin's own
    //backend - but the unified build does not, and that justification no longer holds. A Dingocoin
    //address is byte-identical to a Dogecoin one, so neither an address, an xpub nor a derivation path
    //says which chain it belongs to. Anything that can send funds must take the chain from the wallet's
    //own coin and never infer it by parsing - see coinsSharingAddressEncoding.
    DINGOCOIN("dingocoin", "Dingocoin", "DINGO", "dingocoin", 3, true, false, false),
    //Junkcoin: the May 2013 chain Luckycoin forked from (Junkcoin -> Luckycoin -> Dogecoin), revived by
    //the Junkcoin Foundation. Scrypt, AuxPoW merge-mined (chain ID 0x2020, active since height 173000).
    //bip44CoinType is 0 because the Foundation's shipping wallets (web wallet/JunkieWally) derive at
    //m/44'/0'/0' - same reasoning as Luckycoin.
    JUNKCOIN("junkcoin", "Junkcoin", "JKC", "junkcoin", 0, true, false, false);

    private final String name;
    private final String displayName;
    private final String ticker;
    private final String coingeckoId;
    private final int bip44CoinType;
    private final boolean auxPow;
    private final boolean segwit;
    private final boolean mweb;

    private static Coin currentCoin;
    //Per-thread override of the active coin. Background wallet operations scope themselves to their wallet's
    //coin via beginScope/endScope, so concurrent syncs of different-coin wallets never poison each other's
    //address/key derivation. When unset, get() falls back to currentCoin - except under strict scoping off
    //the foreground thread, where it throws instead of guessing.
    private static final Logger log = LoggerFactory.getLogger(Coin.class);

    private static final ThreadLocal<Coin> threadCoin = new ThreadLocal<>();

    private static volatile boolean strictScoping;
    private static volatile Thread foregroundThread;

    //Bitcoin's SLIP-44 coin type. Luckycoin and Junkcoin derive here too - see sharesBitcoinDerivation().
    private static final int BITCOIN_BIP44_COIN_TYPE = 0;

    Coin(String name, String displayName, String ticker, String coingeckoId, int bip44CoinType, boolean auxPow, boolean segwit, boolean mweb) {
        this.name = name;
        this.displayName = displayName;
        this.ticker = ticker;
        this.coingeckoId = coingeckoId;
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
    /**
     * The coins offered on the given network, in declaration order. Test mode switches every coin at once, so
     * pickers use this rather than values(): a coin with no verified (or no live) testnet is not offered there.
     */
    public static List<Coin> availableOn(Network network) {
        return Arrays.stream(values()).filter(coin -> coin.supports(network)).toList();
    }

    public boolean supports(Network network) {
        return switch(this) {
            case LITECOIN -> true;
            //A coin is offered on testnet only when its testnet parameters are verified against its own chainparams.
            //Litecoin, Pepecoin and Junkcoin have live public testnets with public servers. The others have none
            //usable (2026-10-26): Dogecoin's testnet is alive but no public Electrum or REST server serves it;
            //Luckycoin's and Dingocoin's are dead; Bellscoin's (Bells Core v3 params) has stalled. They are
            //offered for testing against your own node (a private chain mined from the testnet genesis) with an
            //Electrum server in front of it, set as the coin's server in test mode.
            case JUNKCOIN, PEPECOIN, DOGECOIN, LUCKYCOIN, DINGOCOIN -> network == Network.MAINNET || network == Network.TESTNET;
            //Bellscoin also on regtest, where Taproot is always active (its testnet window closed on 2024-12-25)
            case BELLSCOIN -> network == Network.MAINNET || network == Network.TESTNET || network == Network.REGTEST;
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
     * chains have - their addresses would otherwise be encoded against a null HRP. P2TR and P2A also
     * need Taproot, a separate deployment. P2PK, P2PKH and P2SH work everywhere.
     */
    public boolean supports(ScriptType scriptType) {
        if(scriptType == ScriptType.MWEB || scriptType == ScriptType.MWEB_HOGEX || scriptType == ScriptType.MWEB_PEGIN) {
            return mweb;
        }

        //Witness version 1 outputs (P2TR, and P2A anchors) only mean something on a chain with Taproot rules
        if(scriptType == ScriptType.P2TR || scriptType == ScriptType.P2A) {
            return segwit && isTaprootSupported();
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
                case MAINNET -> new ChainParams(48, "L", 50, "M", "ltc", "ltcsp", "ltcspscan", "ltcspspend", "ltcmweb",
                        ExtendedKey.Header.xprv, ExtendedKey.Header.xpub, 176, 9332);
                case TESTNET -> new ChainParams(111, "mn", 58, "Q", "tltc", "tltcsp", "tltcspscan", "tltcspspend", "tmweb",
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 239, 19332);
                case REGTEST -> new ChainParams(111, "mn", 196, "2", "rltc", "rltcsp", "tltcspscan", "tltcspspend", "tmweb",
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 239, 19443);
                case SIGNET -> new ChainParams(111, "mn", 196, "2", "tb", "tsp", "tspscan", "tspspend", "tmweb",
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 239, 38332);
                case TESTNET4 -> new ChainParams(111, "mn", 196, "2", "tb", "tsp", "tspscan", "tspspend", "tmweb",
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 239, 48332);
            };
            //Dogecoin: no segwit (DEPLOYMENT_SEGWIT nTimeout=0, never activated), so no bech32 HRP.
            //Core defines dgub/dgpv but much of the ecosystem uses standard xpub/xprv - dgub is the
            //serialisation default here, with xpub accepted on import.
            case DOGECOIN -> switch(network) {
                case MAINNET -> new ChainParams(30, "D", 22, "9A", null, null, null, null, null,
                        ExtendedKey.Header.dgpv, ExtendedKey.Header.dgub, 158, 22555);
                //Testnet (dogecoin/dogecoin v1.14.9 chainparams.cpp CTestNetParams, chainparamsbase.cpp): P2PKH 113
                //"n", P2SH 196 "2", WIF 241, standard tpub/tprv (0x043587cf/0x04358394), RPC 44555.
                case TESTNET -> new ChainParams(113, "n", 196, "2", null, null, null, null, null,
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 241, 44555);
                default -> throw unverified(network);
            };
            //Pepecoin: Dogecoin Core derivative, inherits dgub/dgpv unchanged. No segwit, no bech32.
            case PEPECOIN -> switch(network) {
                case MAINNET -> new ChainParams(56, "P", 22, "9A", null, null, null, null, null,
                        ExtendedKey.Header.dgpv, ExtendedKey.Header.dgub, 158, 33873);
                //Testnet (pepecoinppc/pepecoin @4fb5a0cd CTestNetParams, chainparamsbase RPC 44873): P2PKH 113 "n",
                //P2SH 196 "2", WIF 241, tpub/tprv. No segwit. Alive 2026-10-23 (tip ~1.55M).
                case TESTNET -> new ChainParams(113, "n", 196, "2", null, null, null, null, null,
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 241, 44873);
                default -> throw unverified(network);
            };
            //Luckycoin: uses Bitcoin's standard xpub/xprv prefixes, not Dogecoin's. No segwit.
            //Version byte 47 straddles the K/L base58 boundary, so P2PKH addresses lead with either -
            //published examples only ever show "L...", but "K..." is equally valid and must be accepted.
            case LUCKYCOIN -> switch(network) {
                case MAINNET -> new ChainParams(47, "KL", 5, "3", null, null, null, null, null,
                        ExtendedKey.Header.xprv, ExtendedKey.Header.xpub, 175, 9918);
                //Testnet (LuckycoinFoundation/Luckycoin v5.0.1 @8b1fa2b chainparams.cpp L320-324, chainparamsbase.cpp
                //L48): P2PKH 113 "n", P2SH 196 "2", WIF 241, standard tpub/tprv, RPC 19918.
                case TESTNET -> new ChainParams(113, "n", 196, "2", null, null, null, null, null,
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 241, 19918);
                default -> throw unverified(network);
            };
            //Bellscoin mainnet as Bells Core v3 (Nintondo/bellscoinV3 kernel/chainparams.cpp CMainParams, genesis
            //e5be24df... matches api.nintondo.io): P2PKH 25 "B", P2SH 30 "D", WIF 153, dgub/dgpv, bech32 "bel" with
            //segwit from height 144000. bel1 P2WPKH outputs are in live use (checked 2026-10-28).
            case BELLSCOIN -> switch(network) {
                case MAINNET -> new ChainParams(25, "B", 30, "D", "bel", null, null, null, null,
                        ExtendedKey.Header.dgpv, ExtendedKey.Header.dgub, 153, 8332);
                //Testnet as Bells Core v3, which the live network runs (BellsChain/bellscoin v3.0.0-rc2 @59addbbe
                //kernel/chainparams.cpp CTestNetParams, chainparamsbase.cpp): P2PKH 33 "E", P2SH 22 "9A", WIF 158,
                //bech32 "tbel", RPC 18332. Core keeps mainnet's dgub/dgpv on testnet, but those headers are mainnet-only
                //here (as for Junkcoin), so testnet keys use tpub/tprv; addresses do not depend on the key prefix.
                case TESTNET -> new ChainParams(33, "E", 22, "9A", "tbel", null, null, null, null,
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 158, 18332);
                //Regtest as Bells Core v3 (kernel/chainparams.cpp CRegTestParams, genesis f97be01b..., chainparamsbase.cpp):
                //P2PKH 111 "mn", P2SH 196 "2", WIF 239, tpub/tprv, bech32 "bcrt", RPC 18443. Taproot is always active.
                case REGTEST -> new ChainParams(111, "mn", 196, "2", "bcrt", null, null, null, null,
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 239, 18443);
                default -> throw unverified(network);
            };
            //Dingocoin: address/key identity identical to Dogecoin (P2PKH 30 "D", P2SH 22 "9A", WIF 158,
            //dgub/dgpv). Distinct only by network magic and ports, which the backend handles.
            case DINGOCOIN -> switch(network) {
                case MAINNET -> new ChainParams(30, "D", 22, "9A", null, null, null, null, null,
                        ExtendedKey.Header.dgpv, ExtendedKey.Header.dgub, 158, 34646);
                //Testnet (dingocoin/dingocoin v1.18.1.1 @ce1acdab chainparams.cpp CTestNetParams, chainparamsbase.cpp
                //L48): P2PKH 113 "n", P2SH 196 "2", WIF 241, standard tpub/tprv, RPC 44556.
                case TESTNET -> new ChainParams(113, "n", 196, "2", null, null, null, null, null,
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 241, 44556);
                default -> throw unverified(network);
            };
            //Junkcoin: verified against Junkcoin-Foundation/junkcoin-core chainparams.cpp. Standard
            //xpub/xprv like Luckycoin, not dgub. P2SH 5 is Bitcoin's script version, so JKC P2SH
            //addresses are byte-identical to Bitcoin's - same situation as Luckycoin.
            case JUNKCOIN -> switch(network) {
                case MAINNET -> new ChainParams(16, "7", 5, "3", null, null, null, null, null,
                        ExtendedKey.Header.xprv, ExtendedKey.Header.xpub, 144, 9771);
                //Testnet (CTestNetParams + CBaseChainParams TESTNET, junkcoin-core v4 @0e5279a): P2PKH 111 "m/n",
                //P2SH 5 (unchanged from mainnet), WIF 239, port 19771 for P2P and RPC. Its genesis equals
                //mainnet's; block 1 (ccedee1f...) tells them apart. Core serialises testnet keys as dgub/dgpv,
                //but those headers are mainnet-only here, so a testnet wallet saved as dgub could not be read
                //back - keys use the standard tpub/tprv instead. Addresses do not depend on the key prefix.
                //Bech32 "tjc": segwit is active from testnet block 140000 and taproot from 160000 (v4
                //chainparams), and the Junkcoin bridge custody is a tjc1p address. Mainnet stays without one
                //until its scheduled segwit height (1145000) has passed.
                case TESTNET -> new ChainParams(111, "mn", 5, "3", "tjc", null, null, null, null,
                        ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 239, 19771);
                default -> throw unverified(network);
            };
        };
    }

    /**
     * Every other supported coin on which a base58 address carrying this version byte is also a
     * well-formed address, in declaration order.
     *
     * A non-empty result means the address string itself does not identify its chain, so only the
     * sending wallet's own coin does. Callers that can move funds must warn on that rather than treat
     * a successful parse as proof the address belongs to the chain being spent.
     *
     * The comparison is over the version byte across both address types, not per script type: it is
     * the whole of what a base58 address encodes about its chain, and the collisions cross script
     * types. Dogecoin P2PKH and Bellscoin P2SH are both 30.
     */
    public static List<Coin> coinsSharingAddressEncoding(Coin coin, int versionByte, Network network) {
        List<Coin> sharing = new ArrayList<>();

        for(Coin other : values()) {
            //Unsupported combinations have no verified parameters, and params() throws rather than guess.
            if(other == coin || !other.supports(network)) {
                continue;
            }

            ChainParams params = other.params(network);
            if(params.p2pkhAddressHeader() == versionByte || params.p2shAddressHeader() == versionByte) {
                sharing.add(other);
            }
        }

        return sharing;
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

    /**
     * The reference full-node software for this chain, e.g. "Litecoin Core", "Dogecoin Core". Each of
     * these coins ships a Bitcoin Core derivative under this name.
     */
    public String getCoreName() {
        return displayName + " Core";
    }

    /** Ticker symbol, e.g. "LTC". Used for unit labels and fiat pairing. */
    public String getTicker() {
        return ticker;
    }

    /**
     * Label for the smallest (integer) unit - the analogue of Bitcoin's satoshi. Every one of these chains
     * inherits Bitcoin's 8-decimal base unit, only the name differs. Litecoin's community uses "lits"
     * (litoshi); the merge-mined chains have no established sub-unit name, so the generic "sats" is used.
     */
    public String getSubunit() {
        return this == LITECOIN ? "lits" : "sats";
    }

    /**
     * CoinGecko API id for fiat price lookups, e.g. "litecoin", "dogecoin". Best-effort - a coin without
     * a CoinGecko listing simply yields no exchange rates rather than an error.
     */
    public String getCoingeckoId() {
        return coingeckoId;
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
     * Whether this chain derives at Bitcoin's SLIP-44 coin type, so a seed used here produces exactly the
     * keys a Bitcoin wallet would produce from it - the same xpub serialisation and the same addresses.
     *
     * Luckycoin and Junkcoin both do. That is the right call for compatibility, since their shipping
     * wallets derive at m/44'/0'/0' and following the SLIP-44 registry instead would strand existing
     * funds - but it means a seed reused from a Bitcoin wallet puts the very same keys on both chains.
     * Anything creating or importing a keystore on these chains should say so rather than leave the user
     * to assume the chains are separate.
     */
    public boolean sharesBitcoinDerivation() {
        return bip44CoinType == BITCOIN_BIP44_COIN_TYPE;
    }

    /**
     * Whether this chain is merge-mined via AuxPoW, making serialised block headers variable-length.
     * Clients must not assume an 80-byte header on these chains.
     */
    public boolean isAuxPow() {
        return auxPow;
    }

    /**
     * Minimum relay fee rate in sat/vB, from this chain's own DEFAULT_MIN_RELAY_TX_FEE (a per-kB constant
     * in Core, divided by 1000 here).
     *
     * These chains do not share Bitcoin's fee scale: the Dogecoin derivatives set RECOMMENDED_MIN_TX_FEE
     * = COIN/100 per kB and DEFAULT_MIN_RELAY_TX_FEE = a tenth of it, and Dingocoin relays nothing under
     * a whole COIN per kB. Each value below is read from that chain's own source - dogecoin/dogecoin,
     * pepecoinppc/pepecoin, LuckycoinFoundation/Luckycoin, Junkcoin-Foundation/junkcoin-core,
     * dingocoin/dingocoin, and nintondo/bellscoin at idquantum-dev (the branch this coin's address
     * parameters also come from; master is unmodified Dogecoin).
     *
     * This applies when the backend cannot report a relay fee itself, which an Electrum server always can
     * but an Esplora REST instance never does.
     */
    public double getMinRelayFeeRate() {
        return switch(this) {
            case LITECOIN, BELLSCOIN -> 1d;
            case DOGECOIN, PEPECOIN, LUCKYCOIN, JUNKCOIN -> 100d;
            case DINGOCOIN -> 100000d;
        };
    }

    /**
     * The chain's recommended fee rate in sat/vB (RECOMMENDED_MIN_TX_FEE), which on these chains is also
     * the mining floor DEFAULT_BLOCK_MIN_TX_FEE. A transaction below it relays but is not mined by
     * default-configured miners, so this is a floor on the fee to offer, not merely a fallback for when
     * no estimator exists. Never below {@link #getMinRelayFeeRate()}.
     */
    public double getRecommendedFeeRate() {
        return switch(this) {
            case LITECOIN -> 1d;
            case BELLSCOIN -> 10d;
            case JUNKCOIN -> 100d;
            case DOGECOIN, PEPECOIN, LUCKYCOIN -> 1000d;
            case DINGOCOIN -> 100000d;
        };
    }

    /**
     * The fee rate in sat/vB to start from before a server has reported estimates: Sparrow's generic fallback
     * (20 on mainnet, 1 on test networks), raised to {@link #getRecommendedFeeRate()} so the first transaction on a
     * chain with a higher floor is not refused or left unmined.
     */
    public double getFallbackFeeRate(Network network) {
        double generic = network == Network.MAINNET ? 20d : 1d;
        return Math.max(generic, getRecommendedFeeRate());
    }

    /**
     * The size a fee rate is charged on. Dingocoin Core's CFeeRate::GetFee rounds the size up to whole kilobytes
     * (amount.cpp, "Round up to the nearest 1000 bytes"), so its relay floor is per started kilobyte.
     */
    public double getFeeSize(double virtualSize) {
        if(this == DINGOCOIN) {
            return Math.ceil(virtualSize / 1000d) * 1000d;
        }

        return virtualSize;
    }

    /**
     * The value in satoshis below which an output should not be created, or 0 where the chain uses
     * Bitcoin's fee-rate-derived dust rule instead of a flat limit.
     *
     * This is the chain's DEFAULT_DUST_LIMIT - the soft limit, not DEFAULT_HARD_DUST_LIMIT. Outputs below
     * the hard limit are non-standard and will not relay at all; outputs between the two relay only if the
     * transaction pays an extra RECOMMENDED_MIN_TX_FEE for each of them. Wallet fee calculation does not
     * model that penalty, so treating the soft limit as the floor keeps every transaction relayable
     * without it, at the cost of sweeping a sub-limit remainder into the fee. On these chains that
     * remainder is worth a fraction of a cent.
     */
    public long getDustLimit() {
        return switch(this) {
            case LITECOIN -> 0L;
            case DINGOCOIN -> 1000L;
            case BELLSCOIN -> 10000L;
            case JUNKCOIN -> 100000L;
            case DOGECOIN, PEPECOIN, LUCKYCOIN -> 1000000L;
        };
    }

    /**
     * Confirmations a coinbase output needs before it can be spent, judged by the height of the block that mined it.
     * From each chain's chainparams (nCoinbaseMaturity, with Dogecoin's switch to 240 at its Digishield height 145000);
     * coins not verified yet keep Bitcoin's and Litecoin's 100.
     */
    public int getCoinbaseMaturity(Network network, int coinbaseHeight) {
        return switch(this) {
            //dogecoin/dogecoin v1.14.9 chainparams: consensus 30, digishieldConsensus 240 from 145000 (main and test)
            case DOGECOIN -> coinbaseHeight < 145000 ? 30 : 240;
            //junkcoin-core v4 chainparams: mainnet 70, testnet 30
            case JUNKCOIN -> network == Network.MAINNET ? 70 : 30;
            //Luckycoin v5.0.1 chainparams: 70 on every network and consensus era
            case LUCKYCOIN -> 70;
            //Bells Core v3 consensus.h: COINBASE_MATURITY = 30 for every network
            case BELLSCOIN -> 30;
            //dingocoin v1.18.1.1 chainparams testnet: 30, digishieldConsensus 240 from 5000 (mainnet not verified yet)
            case DINGOCOIN -> network == Network.MAINNET ? 100 : (coinbaseHeight < 5000 ? 30 : 240);
            default -> 100;
        };
    }

    public boolean isSegwitSupported() {
        return segwit;
    }

    /**
     * Taproot is its own deployment, not part of segwit. Litecoin activated it in 2022. Bellscoin's (BIP9 bit 2,
     * min_activation_height 188000 in Bells Core v3) is active: mainnet blocks signalled it in every period from
     * the window's start and stopped at height 191520, the first period past the minimum height. On any other
     * coin an output to a Taproot program would be anyone-can-spend, so it stays off there.
     */
    public boolean isTaprootSupported() {
        return this == LITECOIN || this == BELLSCOIN;
    }

    public boolean isMwebSupported() {
        return mweb;
    }

    /**
     * Silent payment wallets need the chain's silent payment HRPs, which only Litecoin defines here (upstream's
     * scheme, with ltcsp prefixes). The other coins have neither the HRPs nor Taproot.
     */
    public boolean supportsSilentPayments() {
        return isTaprootSupported() && params(Network.get()).spAddressHrp() != null;
    }

    public static Coin get() {
        Coin scoped = threadCoin.get();
        if(scoped != null) {
            return scoped;
        }

        if(strictScoping && Thread.currentThread() != foregroundThread) {
            IllegalStateException e = new IllegalStateException("No coin scope on thread \"" + Thread.currentThread().getName()
                    + "\". Background work must bracket itself with Coin.beginScope(wallet.getCoin()) and"
                    + " Coin.endScope(previous) so it resolves its own wallet's chain. Falling back to the"
                    + " focused wallet's coin here would derive a well-formed address on the wrong chain,"
                    + " which between Dogecoin and Dingocoin is indistinguishable from a correct one.");
            //Background task failures are often consumed by the task's failed state, so log loudly here too
            log.error(e.getMessage(), e);
            throw e;
        }

        if(currentCoin == null) {
            currentCoin = LITECOIN;
        }

        return currentCoin;
    }

    /**
     * The process-wide active coin (the focused wallet tab's coin), ignoring any thread scope.
     * Callers deciding whether focus has moved to a different coin must use this rather than
     * {@link #get()}, which inside a beginScope bracket returns the scoped coin instead.
     */
    public static Coin getGlobal() {
        if(currentCoin == null) {
            currentCoin = LITECOIN;
        }

        return currentCoin;
    }

    public static void set(Coin coin) {
        //The active coin can change at runtime as the app switches between wallets of different coins (one
        //app holding both Litecoin and Dogecoin), so unlike the old single-flavor model this no longer
        //throws when re-set to a different coin. Callers set it from the wallet being operated on.
        currentCoin = coin;
    }

    /**
     * Makes an unscoped {@link #get()} off the foreground thread throw rather than fall back to the
     * focused wallet's coin. The unified build enables this; the single-coin flavor builds must not,
     * since there the fallback is the only coin there is and is always right.
     */
    public static void setStrictScoping(boolean strict) {
        strictScoping = strict;
    }

    /**
     * Registers the thread on which resolving to the focused wallet's coin is the intended behaviour -
     * the UI thread, whose whole model is focus-follow. Every other thread must declare a scope once
     * strict scoping is on. Pass null to clear.
     */
    public static void setForegroundThread(Thread thread) {
        foregroundThread = thread;
    }

    /**
     * Scopes the active coin to the current thread only, returning the previous scope (or null) to pass back
     * to {@link #endScope}. Background wallet operations (sync, broadcast, address derivation) must bracket
     * their work with beginScope(wallet.getCoin()) / endScope(previous) in a try/finally so they always
     * resolve their wallet's coin regardless of which wallet tab the foreground has focused - this is what
     * lets a Litecoin tab and a Dogecoin tab sync concurrently without one corrupting the other's addresses.
     */
    public static Coin beginScope(Coin coin) {
        Coin previous = threadCoin.get();
        threadCoin.set(coin);
        return previous;
    }

    public static void endScope(Coin previous) {
        if(previous == null) {
            threadCoin.remove();
        } else {
            threadCoin.set(previous);
        }
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
     * bech32Hrp, the silent payment HRPs and mwebHrp are null on chains without segwit, silent payments or MWEB.
     *
     * defaultPort is the node's JSON-RPC port (chainparamsbase nRPCPort), NOT its P2P port - every caller
     * uses it to reach Core over RPC. The two are adjacent on most of these chains, which made it easy to
     * take the wrong one: Dogecoin listens for peers on 22556 and for RPC on 22555, and Luckycoin reverses
     * that ordering. Bellscoin V3 rebased on modern Core without changing the base params, so it answers
     * RPC on Bitcoin's 8332 despite its own P2P port. Junkcoin uses 9771 for both.
     */
    public record ChainParams(
            int p2pkhAddressHeader,
            String p2pkhAddressPrefix,
            int p2shAddressHeader,
            String p2shAddressPrefix,
            String bech32AddressHrp,
            String spAddressHrp,
            String spScanKeyHrp,
            String spSpendKeyHrp,
            String mwebAddressHrp,
            ExtendedKey.Header xprvHeader,
            ExtendedKey.Header xpubHeader,
            int dumpedPrivateKeyHeader,
            int defaultPort) {
    }
}
