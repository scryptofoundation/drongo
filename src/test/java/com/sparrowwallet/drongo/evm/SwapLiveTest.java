package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Spends test coins from a throwaway seed (env ZK_BRIDGE_SEED: a file of its BIP39 words). With BASE_SWAP_BACK=1 it
 * sells 0.1 USDC for ETH on Base Sepolia: an exact approval, then a swap whose ETH output is unwrapped by the router.
 */
public class SwapLiveTest {
    private static Optional<LitVmRpcClient.Receipt> await(LitVmRpcClient client, String hash) throws Exception {
        for(int i = 0; i < 90; i++) {
            Optional<LitVmRpcClient.Receipt> receipt = client.getReceipt(hash);
            if(receipt.isPresent()) {
                return receipt;
            }
            Thread.sleep(2000);
        }
        return Optional.empty();
    }

    @Test
    public void sellUsdcForEthOnBase() throws Exception {
        String seedPath = System.getenv("ZK_BRIDGE_SEED");
        Assumptions.assumeTrue(seedPath != null && "1".equals(System.getenv("BASE_SWAP_BACK")));
        List<String> words = Arrays.asList(Files.readString(Path.of(seedPath)).trim().split("\\s+"));
        EvmAccount account = EvmAccount.fromSeed(new DeterministicSeed(new ArrayList<>(words), "", 0, DeterministicSeed.Type.BIP39), 0);
        EvmAddress me = account.getAddress();

        LitVmRpcClient base = new LitVmRpcClient(LitVmNetwork.BASE_SEPOLIA);
        base.verifyChain();
        UniswapV3Venue venue = new UniswapV3Venue();
        venue.verify(base);
        BigInteger amount = UniswapV3Venue.USDC.parse("0.1");
        BigInteger usdcBefore = base.getTokenBalance(UniswapV3Venue.USDC, me);
        Assumptions.assumeTrue(usdcBefore.compareTo(amount) >= 0, "needs 0.1 test USDC on Base Sepolia");

        Eip1559Transaction approve = base.prepareCall(me, UniswapV3Venue.USDC.contract(), BigInteger.ZERO, Abi.encodeCall("approve(address,uint256)", venue.spender(), amount));
        String approveHash = base.sendRawTransaction(account.sign(approve));
        Assertions.assertTrue(await(base, approveHash).map(LitVmRpcClient.Receipt::success).orElse(false), "approve " + approveHash);

        SwapVenue.Quote quote = venue.quote(base, UniswapV3Venue.USDC, null, amount);
        BigInteger minOut = SwapVenue.minimumOut(quote.amountOut(), 100);
        SwapVenue.Call call = venue.swapCall(quote, me, minOut, System.currentTimeMillis() / 1000 + 600);
        BigInteger ethBefore = base.getBalance(me);
        Eip1559Transaction swap = base.prepareCall(me, call.to(), call.value(), call.data());
        String swapHash = base.sendRawTransaction(account.sign(swap));
        Optional<LitVmRpcClient.Receipt> receipt = await(base, swapHash);
        Assertions.assertTrue(receipt.map(LitVmRpcClient.Receipt::success).orElse(false), "swap " + swapHash);

        BigInteger ethAfter = base.getBalance(me);
        Assertions.assertEquals(usdcBefore.subtract(amount), base.getTokenBalance(UniswapV3Venue.USDC, me));
        BigInteger maxGasCost = swap.getGasLimit().multiply(swap.getMaxFeePerGas()).add(base.l1DataFee(swap));
        Assertions.assertTrue(ethAfter.compareTo(ethBefore.add(minOut).subtract(maxGasCost)) >= 0, "received at least the minimum, less gas");
        System.out.println("approve " + approveHash + ", swap " + swapHash + ": 0.1 USDC -> quoted " + Wei.format(quote.amountOut()) + " ETH (" + quote.route()
                + "), ETH balance " + Wei.format(ethBefore) + " -> " + Wei.format(ethAfter));
    }
}
