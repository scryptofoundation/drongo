package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

/**
 * A DEX the wallet can swap on, built and signed locally. A null token means the network's native coin; venues
 * wrap and unwrap it themselves. Router addresses are pinned and checked against the chain by {@link #verify}, so
 * a changed deployment is refused rather than followed.
 */
public interface SwapVenue {
    LitVmNetwork network();

    String name();

    /** The contract a token input must be approved to before swapping. */
    EvmAddress spender();

    /** Tokens with known liquidity on this venue, offered alongside the user's tracked tokens. */
    List<Erc20Token> knownTokens();

    void verify(LitVmRpcClient client) throws IOException;

    /** The best route found for selling amountIn of tokenIn; throws if there is none. */
    Quote quote(LitVmRpcClient client, Erc20Token tokenIn, Erc20Token tokenOut, BigInteger amountIn) throws IOException;

    /** The router call for a quote, paying the output to recipient and reverting below minOut or after deadline. */
    Call swapCall(Quote quote, EvmAddress recipient, BigInteger minOut, long deadlineSeconds);

    /**
     * A quote: path is the token route (native as the wrapped token) and fee the v3 fee tier in hundredths of a
     * bip, or 0 for a v2 route.
     */
    record Quote(Erc20Token tokenIn, Erc20Token tokenOut, BigInteger amountIn, BigInteger amountOut, List<EvmAddress> path, int fee) {
        public String route() {
            return fee == 0 ? path.size() - 1 + " hop route" : "pool fee " + fee / 10_000.0 + "%";
        }
    }

    record Call(EvmAddress to, BigInteger value, byte[] data) {}

    int MAX_SLIPPAGE_BPS = 500;

    /** The least output accepted for a quote at the given slippage, in basis points (at most 5%). */
    static BigInteger minimumOut(BigInteger amountOut, int slippageBps) {
        if(slippageBps < 0 || slippageBps > MAX_SLIPPAGE_BPS) {
            throw new IllegalArgumentException("Slippage must be between 0% and " + MAX_SLIPPAGE_BPS / 100 + "%");
        }
        return amountOut.multiply(BigInteger.valueOf(10_000L - slippageBps)).divide(BigInteger.valueOf(10_000));
    }

    static List<SwapVenue> forNetwork(LitVmNetwork network) {
        return switch(network) {
            case BASE_SEPOLIA -> List.of(new UniswapV3Venue());
            case LITEFORGE_TESTNET -> List.of(new UniswapV2Venue());
            default -> List.of();
        };
    }
}
