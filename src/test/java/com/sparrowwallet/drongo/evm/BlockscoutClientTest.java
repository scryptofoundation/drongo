package com.sparrowwallet.drongo.evm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

public class BlockscoutClientTest {
    private static final EvmAddress ME = EvmAddress.fromString("0x840e41D4484041A43f9307a90389edeB3d111fb3");
    private static final EvmAddress OTHER = EvmAddress.fromString("0x000000000000000000000000000000000000dEaD");

    @Test
    public void nativeChangeCountsValueAndFee() {
        BlockscoutClient.Entry sent = new BlockscoutClient.Entry("0x01", null, 1L, ME, OTHER, BigInteger.valueOf(100), BigInteger.valueOf(3), false, null, List.of());
        Assertions.assertEquals(BigInteger.valueOf(-103), sent.nativeChange(ME));
        Assertions.assertEquals(BigInteger.valueOf(100), sent.nativeChange(OTHER));
        BlockscoutClient.Entry failed = new BlockscoutClient.Entry("0x02", null, 1L, ME, OTHER, BigInteger.valueOf(100), BigInteger.valueOf(3), true, null, List.of());
        Assertions.assertEquals(BigInteger.valueOf(-3), failed.nativeChange(ME), "a failed transaction still pays its fee");
    }

    /** Read-only: the test account's LiteForge history; set LITVM_LIVE=1 to run. */
    @Test
    public void liveHistory() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("LITVM_LIVE")));
        List<BlockscoutClient.Entry> history = new BlockscoutClient(LitVmNetwork.LITEFORGE_TESTNET).history(ME);
        Assertions.assertFalse(history.isEmpty());
        Assertions.assertTrue(history.stream().anyMatch(e -> e.tokenTransfers().stream().anyMatch(t -> t.symbol().equals("wtJKC"))));
        for(BlockscoutClient.Entry entry : history.subList(0, Math.min(5, history.size()))) {
            System.out.println(entry.time() + " " + entry.hash().substring(0, 12) + " " + entry.method() + " " + Wei.format(entry.nativeChange(ME)) + " zkLTC "
                    + entry.tokenTransfers().stream().map(t -> t.format() + " " + t.symbol()).toList());
        }
    }
}
