package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * A Uniswap v2 router on LiteForge: the Drunken Cats deployment (verified UniswapV2Router02, the deepest zkLTC pool
 * against a stable test token). There is no official Uniswap deployment on LiteForge. Routes go direct or through
 * WzkLTC, whichever pays more.
 */
public final class UniswapV2Venue implements SwapVenue {
    static final EvmAddress ROUTER = EvmAddress.fromString("0x35e8FE95948E1F589751121085c044C6094d993b");
    static final EvmAddress FACTORY = EvmAddress.fromString("0xe8aDcf45C359eB63aAf0e0a129463600151A0291");
    static final EvmAddress WZKLTC = EvmAddress.fromString("0xeb29947D9C1cD59AF2B413b47505bf89a47BE0D4");
    public static final Erc20Token TUSDC = new Erc20Token(EvmAddress.fromString("0xd26D4B6C25F823F978d90734c3E36B429B8708e1"), "tUSDC", "Test USDC", 18);

    @Override
    public LitVmNetwork network() {
        return LitVmNetwork.LITEFORGE_TESTNET;
    }

    @Override
    public String name() {
        return "Drunken Cats (Uniswap v2)";
    }

    @Override
    public EvmAddress spender() {
        return ROUTER;
    }

    @Override
    public List<Erc20Token> knownTokens() {
        return List.of(TUSDC);
    }

    @Override
    public void verify(LitVmRpcClient client) throws IOException {
        if(client.getNetwork() != network()) {
            throw new IOException(name() + " is configured for " + network().getDisplayName());
        }
        try {
            UniswapV3Venue.expect(client, ROUTER, "factory()", FACTORY);
            UniswapV3Venue.expect(client, ROUTER, "WETH()", WZKLTC);
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid router response: " + e.getMessage());
        }
    }

    private static EvmAddress address(Erc20Token token) {
        return token == null ? WZKLTC : token.contract();
    }

    @Override
    public Quote quote(LitVmRpcClient client, Erc20Token tokenIn, Erc20Token tokenOut, BigInteger amountIn) throws IOException {
        EvmAddress in = address(tokenIn);
        EvmAddress out = address(tokenOut);
        if(in.equals(out)) {
            throw new IOException("Choose two different assets");
        }
        List<List<EvmAddress>> paths = new ArrayList<>();
        paths.add(List.of(in, out));
        if(!in.equals(WZKLTC) && !out.equals(WZKLTC)) {
            paths.add(List.of(in, WZKLTC, out));
        }
        Quote best = null;
        for(List<EvmAddress> path : paths) {
            try {
                List<byte[]> amounts = Abi.decodeBytes32Array(client.ethCall(ROUTER, Abi.encodeCall("getAmountsOut(uint256,address[])", amountIn, new Abi.AddressArray(path))), 0);
                if(amounts.size() != path.size()) {
                    continue;
                }
                BigInteger amountOut = new BigInteger(1, amounts.getLast());
                if(amountOut.signum() > 0 && (best == null || amountOut.compareTo(best.amountOut()) > 0)) {
                    best = new Quote(tokenIn, tokenOut, amountIn, amountOut, path, 0);
                }
            } catch(IOException | IllegalArgumentException e) {
                //no pair along this path
            }
        }
        if(best == null) {
            throw new IOException("No " + name() + " pool can fill this swap");
        }
        return best;
    }

    @Override
    public Call swapCall(Quote quote, EvmAddress recipient, BigInteger minOut, long deadlineSeconds) {
        Abi.AddressArray path = new Abi.AddressArray(quote.path());
        BigInteger deadline = BigInteger.valueOf(deadlineSeconds);
        if(quote.tokenIn() == null) {
            return new Call(ROUTER, quote.amountIn(), Abi.encodeCall("swapExactETHForTokens(uint256,address[],address,uint256)", minOut, path, recipient, deadline));
        }
        if(quote.tokenOut() == null) {
            return new Call(ROUTER, BigInteger.ZERO, Abi.encodeCall("swapExactTokensForETH(uint256,uint256,address[],address,uint256)", quote.amountIn(), minOut, path, recipient, deadline));
        }
        return new Call(ROUTER, BigInteger.ZERO, Abi.encodeCall("swapExactTokensForTokens(uint256,uint256,address[],address,uint256)", quote.amountIn(), minOut, path, recipient, deadline));
    }
}
