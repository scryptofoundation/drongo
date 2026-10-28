package com.sparrowwallet.drongo;

import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.drongo.protocol.HeaderCheckpoints;
import com.sparrowwallet.drongo.protocol.Sha256Hash;

import java.math.BigInteger;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public enum Network {
    MAINNET("mainnet", "Mainnet", "mainnet", 48, "L", 50, "M", "ltc", "ltcsp", "ltcspscan", "ltcspspend", "ltcmweb", ExtendedKey.Header.xprv, ExtendedKey.Header.xpub, 128, 9332),
    TESTNET("testnet", "Testnet", "testnet", 111, "mn", 58, "Q", "tltc", "tltcsp", "tltcspscan", "tltcspspend", "tmweb", ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 191, 19332),
    REGTEST("regtest", "Regtest", "regtest", 111, "mn", 196, "2", "rltc", "rltcsp", "tltcspscan", "tltcspspend", "tmweb", ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 239, 19443),
    SIGNET("signet", "Signet", "signet", 111, "mn", 196, "2", "tb", "tsp", "tspscan", "tspspend", "tmweb", ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 239, 38332),
    TESTNET4("testnet4", "Testnet4", "testnet4", 111, "mn", 196, "2", "tb", "tsp", "tspscan", "tspspend", "tmweb", ExtendedKey.Header.tprv, ExtendedKey.Header.tpub, 239, 48332);

    public static final String BLOCK_HEIGHT_PROPERTY = "com.sparrowwallet.blockHeight";
    private static final Network[] CANONICAL_VALUES = new Network[]{MAINNET, TESTNET, REGTEST, SIGNET};

    Network(String name, String displayName, String home, int p2pkhAddressHeader, String p2pkhAddressPrefix, int p2shAddressHeader, String p2shAddressPrefix, String bech32AddressHrp, String spAddressHrp, String spScanKeyHrp, String spSpendKeyHrp, String mwebAddressHrp, ExtendedKey.Header xprvHeader, ExtendedKey.Header xpubHeader, int dumpedPrivateKeyHeader, int defaultPort) {
        this.name = name;
        this.displayName = displayName;
        this.home = home;
        this.p2pkhAddressHeader = p2pkhAddressHeader;
        this.p2pkhAddressPrefix = p2pkhAddressPrefix;
        this.p2shAddressHeader = p2shAddressHeader;
        this.p2shAddressPrefix = p2shAddressPrefix;
        this.bech32AddressHrp = bech32AddressHrp;
        this.spAddressHrp = spAddressHrp;
        this.spScanKeyHrp = spScanKeyHrp;
        this.spSpendKeyHrp = spSpendKeyHrp;
        this.mwebAddressHrp = mwebAddressHrp;
        this.xprvHeader = xprvHeader;
        this.xpubHeader = xpubHeader;
        this.dumpedPrivateKeyHeader = dumpedPrivateKeyHeader;
        this.defaultPort = defaultPort;
    }

    private final String name;
    private final String displayName;
    private final String home;
    private final int p2pkhAddressHeader;
    private final String p2pkhAddressPrefix;
    private final int p2shAddressHeader;
    private final String p2shAddressPrefix;
    private final String bech32AddressHrp;
    private final String spAddressHrp;
    private final String spScanKeyHrp;
    private final String spSpendKeyHrp;
    private final String mwebAddressHrp;
    private final ExtendedKey.Header xprvHeader;
    private final ExtendedKey.Header xpubHeader;
    private final int dumpedPrivateKeyHeader;
    private final int defaultPort;

    private static Network currentNetwork;
    private static final Map<Network, BlockHeader> GENESIS_HEADERS = new EnumMap<>(Network.class);

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
        return p2pkhAddressHeader;
    }

    public int getP2SHAddressHeader() {
        return p2shAddressHeader;
    }

    public String getBech32AddressHRP() {
        return bech32AddressHrp;
    }

    public String getSilentPaymentsAddressHrp() {
        return spAddressHrp;
    }

    public String getSilentPaymentsScanKeyHrp() {
        return spScanKeyHrp;
    }

    public String getSilentPaymentsSpendKeyHrp() {
        return spSpendKeyHrp;
    }

    public String getMwebAddressHrp() {
        return mwebAddressHrp;
    }

    public ExtendedKey.Header getXprvHeader() {
        return xprvHeader;
    }

    public ExtendedKey.Header getXpubHeader() {
        return xpubHeader;
    }

    public int getDumpedPrivateKeyHeader() {
        return dumpedPrivateKeyHeader;
    }

    public int getDefaultPort() {
        return defaultPort;
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
     * so header verification stays off until both exist.
     */
    public boolean supportsHeaderVerification() {
        return false;
    }

    /**
     * The network's genesis block header, parsed from its compiled-in serialization on first use.
     */
    public BlockHeader getGenesisHeader() {
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
        for(String prefix : p2pkhAddressPrefix.split("")) {
            if(address.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    public boolean hasP2SHAddressPrefix(String address) {
        return address.startsWith(p2shAddressPrefix);
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
