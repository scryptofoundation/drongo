package com.sparrowwallet.drongo;

import java.util.Locale;

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

    public boolean hasP2PKHAddressPrefix(String address) {
        return hasAddressPrefix(address, params().p2pkhAddressPrefix());
    }

    public boolean hasP2SHAddressPrefix(String address) {
        return hasAddressPrefix(address, params().p2shAddressPrefix());
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
