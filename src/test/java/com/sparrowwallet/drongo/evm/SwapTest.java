package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.Utils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

public class SwapTest {
    private static final EvmAddress ME = EvmAddress.fromString("0x840e41D4484041A43f9307a90389edeB3d111fb3");
    //Holds test ETH on Base Sepolia, used only as the sender of read-only simulations
    private static final EvmAddress DEAD = EvmAddress.fromString("0x000000000000000000000000000000000000dEaD");

    private static String word(String hex, int index) {
        return hex.substring(8 + index * 64, 8 + (index + 1) * 64);
    }

    @Test
    public void selectorsMatchDecodedTransactions() {
        Assertions.assertEquals("04e45aaf", Utils.bytesToHex(Abi.selector("exactInputSingle((address,address,uint24,address,uint256,uint256,uint160))")));
        Assertions.assertEquals("5ae401dc", Utils.bytesToHex(Abi.selector("multicall(uint256,bytes[])")));
        Assertions.assertEquals("49404b7c", Utils.bytesToHex(Abi.selector("unwrapWETH9(uint256,address)")));
        Assertions.assertEquals("c6a5026a", Utils.bytesToHex(Abi.selector("quoteExactInputSingle((address,address,uint256,uint24,uint160))")));
        Assertions.assertEquals("7ff36ab5", Utils.bytesToHex(Abi.selector("swapExactETHForTokens(uint256,address[],address,uint256)")));
        Assertions.assertEquals("18cbafe5", Utils.bytesToHex(Abi.selector("swapExactTokensForETH(uint256,uint256,address[],address,uint256)")));
        Assertions.assertEquals("38ed1739", Utils.bytesToHex(Abi.selector("swapExactTokensForTokens(uint256,uint256,address[],address,uint256)")));
        Assertions.assertEquals("d06ca61f", Utils.bytesToHex(Abi.selector("getAmountsOut(uint256,address[])")));
        Assertions.assertEquals("e9e05c42", Utils.bytesToHex(Abi.selector("depositTransaction(address,uint256,uint64,bool,bytes)")));
    }

    @Test
    public void encodesMulticallBytesArray() {
        byte[] a = new byte[] {1, 2, 3};
        byte[] b = new byte[40];
        String hex = Utils.bytesToHex(Abi.encodeCall("multicall(uint256,bytes[])", BigInteger.TEN, new Abi.BytesArray(List.of(a, b))));
        Assertions.assertEquals(10, new BigInteger(word(hex, 0), 16).intValue());
        Assertions.assertEquals(0x40, new BigInteger(word(hex, 1), 16).intValue(), "array offset");
        Assertions.assertEquals(2, new BigInteger(word(hex, 2), 16).intValue(), "array length");
        Assertions.assertEquals(0x40, new BigInteger(word(hex, 3), 16).intValue(), "first element offset");
        Assertions.assertEquals(0x80, new BigInteger(word(hex, 4), 16).intValue(), "second element offset: 32 length + 32 padded data");
        Assertions.assertEquals(3, new BigInteger(word(hex, 5), 16).intValue());
        Assertions.assertTrue(word(hex, 6).startsWith("010203"));
        Assertions.assertEquals(40, new BigInteger(word(hex, 7), 16).intValue());
        Assertions.assertEquals((8 + 2) * 64 + 8, hex.length(), "40 bytes pad to two words");
    }

    @Test
    public void buildsNativeSwapsAndSlippage() {
        SwapVenue.Quote quote = new SwapVenue.Quote(null, UniswapV3Venue.USDC, BigInteger.valueOf(100), BigInteger.valueOf(1000),
                List.of(UniswapV3Venue.WETH, UniswapV3Venue.USDC.contract()), 500);
        SwapVenue.Call call = new UniswapV3Venue().swapCall(quote, ME, BigInteger.valueOf(990), 1_800_000_000L);
        Assertions.assertEquals(UniswapV3Venue.ROUTER, call.to());
        Assertions.assertEquals(BigInteger.valueOf(100), call.value(), "native input is sent as value");

        SwapVenue.Quote back = new SwapVenue.Quote(UniswapV3Venue.USDC, null, BigInteger.valueOf(1000), BigInteger.valueOf(100),
                List.of(UniswapV3Venue.USDC.contract(), UniswapV3Venue.WETH), 500);
        String hex = Utils.bytesToHex(new UniswapV3Venue().swapCall(back, ME, BigInteger.valueOf(99), 1_800_000_000L).data());
        Assertions.assertTrue(hex.contains("04e45aaf") && hex.contains("49404b7c"), "native output swaps to the router then unwraps");
        Assertions.assertTrue(hex.contains("0000000000000000000000000000000000000000000000000000000000000002"), "router keeps the WETH for unwrapping");

        SwapVenue.Quote v2 = new SwapVenue.Quote(null, UniswapV2Venue.TUSDC, BigInteger.ONE, BigInteger.TEN, List.of(UniswapV2Venue.WZKLTC, UniswapV2Venue.TUSDC.contract()), 0);
        SwapVenue.Call v2Call = new UniswapV2Venue().swapCall(v2, ME, BigInteger.valueOf(9), 1_800_000_000L);
        Assertions.assertEquals("7ff36ab5", Utils.bytesToHex(Arrays.copyOf(v2Call.data(), 4)));
        Assertions.assertEquals(BigInteger.ONE, v2Call.value());

        Assertions.assertEquals(BigInteger.valueOf(990), SwapVenue.minimumOut(BigInteger.valueOf(1000), 100));
        Assertions.assertThrows(IllegalArgumentException.class, () -> SwapVenue.minimumOut(BigInteger.ONE, 501));
    }

    /** Read-only checks against Base Sepolia, Sepolia and LiteForge; set LITVM_LIVE=1 to run. */
    @Test
    public void liveVenuesQuoteAndSimulate() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("LITVM_LIVE")));
        long deadline = System.currentTimeMillis() / 1000 + 600;

        LitVmRpcClient base = new LitVmRpcClient(LitVmNetwork.BASE_SEPOLIA);
        base.verifyChain();
        UniswapV3Venue v3 = new UniswapV3Venue();
        v3.verify(base);
        Erc20Token usdc = base.readToken(UniswapV3Venue.USDC.contract());
        Assertions.assertEquals(6, usdc.decimals());
        SwapVenue.Quote quote = v3.quote(base, null, UniswapV3Venue.USDC, Wei.parse("0.0001"));
        Assertions.assertTrue(quote.amountOut().signum() > 0);
        SwapVenue.Call call = v3.swapCall(quote, DEAD, SwapVenue.minimumOut(quote.amountOut(), 100), deadline);
        base.simulate(DEAD, call.to(), call.value(), call.data());
        Eip1559Transaction tx = new Eip1559Transaction(84532L, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ONE, BigInteger.valueOf(200_000), call.to(), call.value(), call.data());
        Assertions.assertTrue(base.l1DataFee(tx).signum() > 0, "Base charges an L1 data fee");
        Assertions.assertTrue(v3.quote(base, UniswapV3Venue.USDC, null, BigInteger.valueOf(100_000)).amountOut().signum() > 0);

        LitVmRpcClient sepolia = new LitVmRpcClient(LitVmNetwork.SEPOLIA);
        sepolia.verifyChain();
        BaseBridge.verify(sepolia);

        LitVmRpcClient liteforge = new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET);
        liteforge.verifyChain();
        UniswapV2Venue v2 = new UniswapV2Venue();
        v2.verify(liteforge);
        Assertions.assertEquals(18, liteforge.readToken(UniswapV2Venue.TUSDC.contract()).decimals());
        SwapVenue.Quote v2Quote = v2.quote(liteforge, null, UniswapV2Venue.TUSDC, Wei.parse("0.001"));
        Assertions.assertTrue(v2Quote.amountOut().signum() > 0);
        SwapVenue.Call v2Call = v2.swapCall(v2Quote, ME, SwapVenue.minimumOut(v2Quote.amountOut(), 100), deadline);
        liteforge.simulate(ME, v2Call.to(), v2Call.value(), v2Call.data());
        System.out.println("Base 0.0001 ETH -> " + UniswapV3Venue.USDC.format(quote.amountOut()) + " USDC (" + quote.route() + "); LiteForge 0.001 zkLTC -> "
                + UniswapV2Venue.TUSDC.format(v2Quote.amountOut()) + " tUSDC (" + v2Quote.route() + ")");
    }
}
