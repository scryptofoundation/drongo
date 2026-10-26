package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Uniswap v3 on Base Sepolia (official deployment: SwapRouter02 and QuoterV2). Quotes every fee tier and uses the
 * best. Native ETH goes in as msg.value with tokenIn = WETH, and comes out through the router's unwrapWETH9.
 */
public final class UniswapV3Venue implements SwapVenue {
    static final EvmAddress FACTORY = EvmAddress.fromString("0x4752ba5DBc23f44D87826276BF6Fd6b1C372aD24");
    static final EvmAddress ROUTER = EvmAddress.fromString("0x94cC0AaC535CCDB3C01d6787D6413C739ae12bc4");
    static final EvmAddress QUOTER = EvmAddress.fromString("0xC5290058841028F1614F3A6F0F5816cAd0df5E27");
    static final EvmAddress WETH = EvmAddress.fromString("0x4200000000000000000000000000000000000006");
    public static final Erc20Token USDC = new Erc20Token(EvmAddress.fromString("0x036CbD53842c5426634e7929541eC2318f3dCF7e"), "USDC", "USDC", 6);
    //SwapRouter02 reads recipient address(2) as "keep in the router", for a later unwrapWETH9 in the same multicall
    private static final EvmAddress ADDRESS_THIS = EvmAddress.fromString("0x0000000000000000000000000000000000000002");
    private static final int[] FEE_TIERS = {100, 500, 3000, 10000};
    //TickMath bounds: with no price limit a swap that runs out of liquidity stops one step inside them, having used
    //only part of its input. Such a quote is a partial fill and is refused.
    private static final BigInteger MIN_SQRT_RATIO = new BigInteger("4295128739");
    private static final BigInteger MAX_SQRT_RATIO = new BigInteger("1461446703485210103287273052203988822378723970342");
    private static final EvmAddress NONE = EvmAddress.fromBytes(new byte[20]);

    @Override
    public LitVmNetwork network() {
        return LitVmNetwork.BASE_SEPOLIA;
    }

    @Override
    public String name() {
        return "Uniswap v3";
    }

    @Override
    public EvmAddress spender() {
        return ROUTER;
    }

    @Override
    public List<Erc20Token> knownTokens() {
        return List.of(USDC);
    }

    @Override
    public void verify(LitVmRpcClient client) throws IOException {
        if(client.getNetwork() != network()) {
            throw new IOException(name() + " is configured for " + network().getDisplayName());
        }
        try {
            expect(client, ROUTER, "factory()", FACTORY);
            expect(client, ROUTER, "WETH9()", WETH);
            expect(client, QUOTER, "factory()", FACTORY);
            expect(client, QUOTER, "WETH9()", WETH);
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid router response: " + e.getMessage());
        }
    }

    static void expect(LitVmRpcClient client, EvmAddress contract, String getter, EvmAddress expected) throws IOException {
        EvmAddress actual = Abi.decodeAddress(client.ethCall(contract, Abi.selector(getter)), 0);
        if(!expected.equals(actual)) {
            throw new IOException(contract + " reports " + getter + " = " + actual + ", expected " + expected);
        }
    }

    private static EvmAddress address(Erc20Token token) {
        return token == null ? WETH : token.contract();
    }

    @Override
    public Quote quote(LitVmRpcClient client, Erc20Token tokenIn, Erc20Token tokenOut, BigInteger amountIn) throws IOException {
        EvmAddress in = address(tokenIn);
        EvmAddress out = address(tokenOut);
        if(in.equals(out)) {
            throw new IOException("Choose two different assets");
        }
        Quote best = null;
        List<String> errors = new ArrayList<>();
        for(int fee : FEE_TIERS) {
            try {
                EvmAddress pool = Abi.decodeAddress(client.ethCall(FACTORY, Abi.encodeCall("getPool(address,address,uint24)", in, out, BigInteger.valueOf(fee))), 0);
                if(pool.equals(NONE)) {
                    continue;
                }
                //quoteExactInputSingle takes a static tuple, which encodes like its fields in order
                byte[] result = client.ethCall(QUOTER, Abi.encodeCall("quoteExactInputSingle((address,address,uint256,uint24,uint160))",
                        in, out, amountIn, BigInteger.valueOf(fee), BigInteger.ZERO));
                BigInteger amountOut = Abi.decodeUint(result, 0);
                BigInteger sqrtPriceAfter = Abi.decodeUint(result, 1);
                if(sqrtPriceAfter.compareTo(MIN_SQRT_RATIO.add(BigInteger.ONE)) <= 0 || sqrtPriceAfter.compareTo(MAX_SQRT_RATIO.subtract(BigInteger.ONE)) >= 0) {
                    errors.add(fee + ": not enough liquidity for the whole amount");
                    continue;
                }
                if(amountOut.signum() > 0 && (best == null || amountOut.compareTo(best.amountOut()) > 0)) {
                    best = new Quote(tokenIn, tokenOut, amountIn, amountOut, List.of(in, out), fee);
                }
            } catch(IOException | IllegalArgumentException e) {
                errors.add(fee + ": " + e.getMessage());
            }
        }
        if(best == null) {
            throw new IOException("No Uniswap v3 pool can fill this swap" + (errors.isEmpty() ? "" : " (" + String.join("; ", errors) + ")"));
        }
        return best;
    }

    @Override
    public Call swapCall(Quote quote, EvmAddress recipient, BigInteger minOut, long deadlineSeconds) {
        boolean nativeIn = quote.tokenIn() == null;
        boolean nativeOut = quote.tokenOut() == null;
        BigInteger fee = BigInteger.valueOf(quote.fee());
        List<byte[]> calls = new ArrayList<>();
        //exactInputSingle on SwapRouter02 takes (tokenIn, tokenOut, fee, recipient, amountIn, amountOutMinimum, sqrtPriceLimitX96)
        calls.add(Abi.encodeCall("exactInputSingle((address,address,uint24,address,uint256,uint256,uint160))",
                quote.path().get(0), quote.path().get(1), fee, nativeOut ? ADDRESS_THIS : recipient, quote.amountIn(), minOut, BigInteger.ZERO));
        if(nativeOut) {
            calls.add(Abi.encodeCall("unwrapWETH9(uint256,address)", minOut, recipient));
        }
        if(nativeIn) {
            //The router wraps only what the pool takes; anything left would sit in the router for anyone to sweep
            calls.add(Abi.selector("refundETH()"));
        }
        byte[] data = Abi.encodeCall("multicall(uint256,bytes[])", BigInteger.valueOf(deadlineSeconds), new Abi.BytesArray(calls));
        return new Call(ROUTER, nativeIn ? quote.amountIn() : BigInteger.ZERO, data);
    }
}
