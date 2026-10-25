package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.Utils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

public class ZkLtcBridgeTest {
    //A withdrawEth made by someone else on LiteForge on 2026-10-25, used read-only
    private static final String WITHDRAWAL_TX = "0x7a637b3b10efb4002259de7b489389adcaa71caeca41c53ccfd5f9a1a0aff34b";

    @Test
    public void encodesBridgeCalls() {
        Assertions.assertEquals("b79092fd", Utils.bytesToHex(ZkLtcBridge.depositCall(BigInteger.ONE)).substring(0, 8));
        Assertions.assertEquals("25e16063", Utils.bytesToHex(ZkLtcBridge.withdrawCall(ZkLtcBridge.INBOX)).substring(0, 8));
        Assertions.assertEquals("08635a95", Utils.bytesToHex(Abi.selector("executeTransaction(bytes32[],uint256,address,address,uint256,uint256,uint256,uint256,bytes)")));
    }

    @Test
    public void bytes32ArrayRoundTrips() {
        byte[] a = new byte[32];
        byte[] b = new byte[32];
        a[31] = 1;
        b[0] = 2;
        byte[] call = Abi.encodeCall("f(uint256,bytes32[])", BigInteger.TEN, new Abi.Bytes32Array(List.of(a, b)));
        byte[] args = Arrays.copyOfRange(call, 4, call.length);
        List<byte[]> decoded = Abi.decodeBytes32Array(args, 1);
        Assertions.assertEquals(2, decoded.size());
        Assertions.assertArrayEquals(a, decoded.get(0));
        Assertions.assertArrayEquals(b, decoded.get(1));
    }

    /** Read-only check against Sepolia and LiteForge; set LITVM_LIVE=1 to run. */
    @Test
    public void liveProofMatchesConfirmedRoot() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("LITVM_LIVE")));
        LitVmRpcClient l1 = new LitVmRpcClient(ZkLtcBridge.L1);
        LitVmRpcClient l2 = new LitVmRpcClient(ZkLtcBridge.L2);
        l1.verifyChain();
        l2.verifyChain();
        ZkLtcBridge.verifyBridge(l1);

        ZkLtcBridge.Withdrawal withdrawal = ZkLtcBridge.findWithdrawal(l2, WITHDRAWAL_TX);
        Assertions.assertNotNull(withdrawal);
        Assertions.assertTrue(withdrawal.callValue().signum() > 0);

        ZkLtcBridge.Confirmed confirmed = ZkLtcBridge.confirmedState(l1, l2);
        Assumptions.assumeTrue(withdrawal.position().compareTo(confirmed.sendCount()) < 0, "withdrawal not confirmed yet");

        //Recompute the root the Outbox would check, using its own pure helpers
        byte[] item = l1.ethCall(ZkLtcBridge.OUTBOX, Abi.encodeCall("calculateItemHash(address,address,uint256,uint256,uint256,uint256,bytes)",
                withdrawal.caller(), withdrawal.destination(), withdrawal.arbBlockNum(), withdrawal.ethBlockNum(), withdrawal.timestamp(),
                withdrawal.callValue(), new Abi.Dynamic(withdrawal.data())));
        byte[] claim = ZkLtcBridge.claimCall(l2, withdrawal, confirmed.sendCount());
        List<byte[]> proof = Abi.decodeBytes32Array(Arrays.copyOfRange(claim, 4, claim.length), 0);
        byte[] root = l1.ethCall(ZkLtcBridge.OUTBOX, Abi.encodeCall("calculateMerkleRoot(bytes32[],uint256,bytes32)",
                new Abi.Bytes32Array(proof), withdrawal.position(), item));
        Assertions.assertArrayEquals(confirmed.sendRoot(), root);
    }
}
