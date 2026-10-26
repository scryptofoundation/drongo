package com.sparrowwallet.drongo.evm;

/**
 * EVM networks the LitVM tab uses: LitVM, Litecoin's EVM rollup; Sepolia, the Ethereum testnet its native zkLTC
 * bridge settles on; and Base Sepolia, an OP Stack testnet with Uniswap liquidity, reached from Sepolia.
 *
 * Only the LiteForge testnet exists. As of October 2026 LitVM mainnet has no published chain id or RPC
 * endpoint, so there is deliberately no MAINNET constant: add one only once those are officially published
 * and verified, never by guessing, because the chain id is what binds a signature to its chain.
 */
public enum LitVmNetwork {
    LITEFORGE_TESTNET("LitVM LiteForge Testnet", 4441L, "https://liteforge.rpc.caldera.xyz/http",
            "https://liteforge.explorer.caldera.xyz", "zkLTC", true),
    SEPOLIA("Ethereum Sepolia", 11155111L, "https://ethereum-sepolia-rpc.publicnode.com",
            "https://eth-sepolia.blockscout.com", "ETH", true),
    BASE_SEPOLIA("Base Sepolia", 84532L, "https://sepolia.base.org",
            "https://base-sepolia.blockscout.com", "ETH", true);

    private final String displayName;
    private final long chainId;
    private final String rpcUrl;
    private final String explorerUrl;
    private final String symbol;
    private final boolean testnet;

    LitVmNetwork(String displayName, long chainId, String rpcUrl, String explorerUrl, String symbol, boolean testnet) {
        this.displayName = displayName;
        this.chainId = chainId;
        this.rpcUrl = rpcUrl;
        this.explorerUrl = explorerUrl;
        this.symbol = symbol;
        this.testnet = testnet;
    }

    public String getDisplayName() { return displayName; }
    public long getChainId() { return chainId; }
    public String getRpcUrl() { return rpcUrl; }
    public String getExplorerUrl() { return explorerUrl; }
    public String getSymbol() { return symbol; }
    public boolean isTestnet() { return testnet; }

    /** OP Stack chains charge an L1 data fee on top of gas, read from their GasPriceOracle predeploy. */
    public boolean isOpStack() { return this == BASE_SEPOLIA; }

    public String explorerTxUrl(String txHash) {
        return explorerUrl + "/tx/" + txHash;
    }

    public String explorerAddressUrl(EvmAddress address) {
        return explorerUrl + "/address/" + address;
    }
}
