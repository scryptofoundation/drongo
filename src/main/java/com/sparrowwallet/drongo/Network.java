package com.sparrowwallet.drongo;

import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.drongo.protocol.HeaderCheckpoints;
import com.sparrowwallet.drongo.protocol.Sha256Hash;

import java.math.BigInteger;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The network axis: which network of the active {@link Coin} the application is running against.
 *
 * Chain constants (address headers, key prefixes, ports) live on Coin and are resolved here at call
 * time, so this enum's public API is unchanged from before the coin axis was introduced.
 */
public enum Network {
    MAINNET("mainnet", "Mainnet", "mainnet"),
    TESTNET("testnet", "Testnet", "testnet"),
    REGTEST("regtest", "Regtest", "regtest"),
    SIGNET("signet", "Signet", "signet"),
    TESTNET4("testnet4", "Testnet4", "testnet4");

    public static final String BLOCK_HEIGHT_PROPERTY = "com.sparrowwallet.blockHeight";
    private static final Network[] CANONICAL_VALUES = new Network[]{MAINNET, TESTNET, REGTEST, SIGNET};

    Network(String name, String displayName, String home) {
        this.name = name;
        this.displayName = displayName;
        this.home = home;
    }

    private final String name;
    private final String displayName;
    private final String home;

    private static Network currentNetwork;
    private static final Map<Network, BlockHeader> GENESIS_HEADERS = new EnumMap<>(Network.class);

    private Coin.ChainParams params() {
        return Coin.get().params(this);
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

    public String getHome() {
        return home;
    }

    public int getP2PKHAddressHeader() {
        return params().p2pkhAddressHeader();
    }

    public int getP2SHAddressHeader() {
        return params().p2shAddressHeader();
    }

    /** Null on chains without segwit (Dogecoin, Pepecoin, Luckycoin). */
    public String getBech32AddressHRP() {
        return params().bech32AddressHrp();
    }

    /** Null on chains without silent payments support. */
    public String getSilentPaymentsAddressHrp() {
        return params().spAddressHrp();
    }

    /** Null on chains without silent payments support. */
    public String getSilentPaymentsScanKeyHrp() {
        return params().spScanKeyHrp();
    }

    /** Null on chains without silent payments support. */
    public String getSilentPaymentsSpendKeyHrp() {
        return params().spSpendKeyHrp();
    }

    /** Null on chains without MWEB (everything except Litecoin). */
    public String getMwebAddressHrp() {
        return params().mwebAddressHrp();
    }

    public ExtendedKey.Header getXprvHeader() {
        return params().xprvHeader();
    }

    public ExtendedKey.Header getXpubHeader() {
        return params().xpubHeader();
    }

    public int getDumpedPrivateKeyHeader() {
        return params().dumpedPrivateKeyHeader();
    }

    public int getDefaultPort() {
        return params().defaultPort();
    }

    /**
     * The maximum (easiest) allowed difficulty target for this network, decoded from the consensus powLimit compact bits.
     */
    public BigInteger getProofOfWorkLimit() {
        if(this == REGTEST) {
            return Utils.decodeCompactBits(0x207fffffL);
        } else if(this == SIGNET) {
            return Utils.decodeCompactBits(0x1e0377aeL);
        } else if(this == TESTNET4) {
            return Utils.decodeCompactBits(0x1d00ffffL);
        }

        return Utils.decodeCompactBits(0x1e0fffffL);
    }

    /**
     * Whether block headers on this network can be verified against compiled in checkpoints and proof of work.
     * Litecoin proof of work is scrypt, not the double SHA256 BlockHeader.verifyProofOfWork checks, and no Litecoin checkpoints are compiled in,
     * so header verification stays off until both exist. The same holds for every other coin (scrypt, and AuxPoW on the Dogecoin family).
     */
    public boolean supportsHeaderVerification() {
        return false;
    }

    /**
     * The network's genesis block header, parsed from its compiled-in serialization on first use.
     * Only Litecoin genesis headers are compiled in, and nothing reads them while header verification is off.
     */
    public BlockHeader getGenesisHeader() {
        if(Coin.get() != Coin.LITECOIN) {
            throw new UnsupportedOperationException("No genesis header compiled in for " + Coin.get().getName());
        }

        synchronized(GENESIS_HEADERS) {
            return GENESIS_HEADERS.computeIfAbsent(this, network -> new BlockHeader(Utils.hexToBytes(network.getGenesisHeaderHex())));
        }
    }

    public Sha256Hash getGenesisHash() {
        return getGenesisHeader().getHash();
    }

    private String getGenesisHeaderHex() {
        return switch(this) {
            case MAINNET -> "010000000000000000000000000000000000000000000000000000000000000000000000d9ced4ed1130f7b7faad9be25323ffafa33232a17c3edf6cfd97bee6bafbdd97b9aa8e4ef0ff0f1ecd513f7c";
            case TESTNET -> "010000000000000000000000000000000000000000000000000000000000000000000000d9ced4ed1130f7b7faad9be25323ffafa33232a17c3edf6cfd97bee6bafbdd97f60ba158f0ff0f1ee1790400";
            case REGTEST -> "010000000000000000000000000000000000000000000000000000000000000000000000d9ced4ed1130f7b7faad9be25323ffafa33232a17c3edf6cfd97bee6bafbdd97dae5494dffff7f2000000000";
            case SIGNET -> "0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a008f4d5fae77031e8ad22203";
            case TESTNET4 -> "0100000000000000000000000000000000000000000000000000000000000000000000004e7b2b9128fe0291db0693af2ae418b767e657cd407e80cb1434221eaea7a07a046f3566ffff001dbb0c7817";
        };
    }

    /**
     * The pinned header hashes compiled in for this network, one per difficulty period. Regtest has none, anchoring at its genesis header instead.
     */
    public HeaderCheckpoints getHeaderCheckpoints() {
        return HeaderCheckpoints.get(this);
    }

    public boolean hasP2PKHAddressPrefix(String address) {
        return hasAddressPrefix(address, params().p2pkhAddressPrefix());
    }

    public boolean hasP2SHAddressPrefix(String address) {
        return hasAddressPrefix(address, params().p2shAddressPrefix());
    }

    public boolean hasBech32AddressPrefix(String address) {
        return hasHrpPrefix(address, params().bech32AddressHrp());
    }

    public boolean hasMwebAddressPrefix(String address) {
        return hasHrpPrefix(address, params().mwebAddressHrp());
    }

    public boolean hasSilentPaymentsAddressPrefix(String address) {
        return hasHrpPrefix(address, params().spAddressHrp());
    }

    /**
     * False when the chain has no such address type at all. Dogecoin, Pepecoin and Luckycoin have
     * neither segwit nor MWEB, so their HRPs are null and no address can carry one - callers must not
     * dereference the HRP directly.
     */
    private static boolean hasHrpPrefix(String address, String hrp) {
        return hrp != null && address != null && address.toLowerCase(Locale.ROOT).startsWith(hrp);
    }

    /**
     * A prefix of more than one character enumerates alternatives, not a literal prefix - a single
     * address version byte can encode to different leading characters depending on the hash that
     * follows it (Litecoin testnet P2PKH is "m" or "n"; Dogecoin P2SH is "9" or "A").
     */
    private static boolean hasAddressPrefix(String address, String prefixes) {
        if(prefixes == null) {
            return false;
        }

        for(String prefix : prefixes.split("")) {
            if(address.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    public static Network get() {
        if(currentNetwork == null) {
            currentNetwork = MAINNET;
        }

        return currentNetwork;
    }

    public static Network getCanonical() {
        return get() == TESTNET4 ? TESTNET : get();
    }

    public static Network[] canonicalValues() {
        return CANONICAL_VALUES;
    }

    public static void set(Network network) {
        if(currentNetwork != null && network != currentNetwork && !isTest()) {
            throw new IllegalStateException("Network already set to " + currentNetwork.getName());
        }

        currentNetwork = network;
    }

    private static boolean isTest() {
        return System.getProperty("org.gradle.test.worker") != null;
    }

    @Override
    public String toString() {
        return getName();
    }
}
