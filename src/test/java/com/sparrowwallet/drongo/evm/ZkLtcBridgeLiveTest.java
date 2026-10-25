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
 * Spends test coins from a throwaway seed (env ZK_BRIDGE_SEED: a file holding its BIP39 words). Withdraw sends
 * 0.001 zkLTC from LiteForge to the same address on Sepolia (env ZK_WITHDRAW=1); claim executes a confirmed
 * withdrawal on Sepolia (env ZK_CLAIM_TX=the LiteForge tx hash), which needs Sepolia ETH in the account.
 */
public class ZkLtcBridgeLiveTest {
    private static EvmAccount account() throws Exception {
        String seedPath = System.getenv("ZK_BRIDGE_SEED");
        Assumptions.assumeTrue(seedPath != null);
        List<String> words = Arrays.asList(Files.readString(Path.of(seedPath)).trim().split("\\s+"));
        return EvmAccount.fromSeed(new DeterministicSeed(new ArrayList<>(words), "", 0, DeterministicSeed.Type.BIP39), 0);
    }

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
    public void withdrawToSepolia() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("ZK_WITHDRAW")));
        EvmAccount account = account();
        LitVmRpcClient l2 = new LitVmRpcClient(ZkLtcBridge.L2);
        l2.verifyChain();
        BigInteger amount = Wei.parse("0.001");
        Eip1559Transaction tx = l2.prepareCall(account.getAddress(), ZkLtcBridge.ARB_SYS, amount, ZkLtcBridge.withdrawCall(account.getAddress()));
        Assertions.assertTrue(l2.getBalance(account.getAddress()).compareTo(tx.maxCost()) >= 0, "account needs test zkLTC");
        String hash = l2.sendRawTransaction(account.sign(tx));
        System.out.println("withdrawEth " + hash + " from " + account.getAddress());
        Optional<LitVmRpcClient.Receipt> receipt = await(l2, hash);
        Assertions.assertTrue(receipt.isPresent() && receipt.get().success(), "withdrawEth must be mined and succeed");

        ZkLtcBridge.Withdrawal withdrawal = ZkLtcBridge.findWithdrawal(l2, hash);
        Assertions.assertNotNull(withdrawal);
        Assertions.assertEquals(account.getAddress(), withdrawal.destination());
        Assertions.assertEquals(account.getAddress(), withdrawal.caller());
        Assertions.assertEquals(amount, withdrawal.callValue());
        System.out.println("position " + withdrawal.position() + ", L2 block " + withdrawal.arbBlockNum());
    }

    @Test
    public void claimOnSepolia() throws Exception {
        String l2Hash = System.getenv("ZK_CLAIM_TX");
        Assumptions.assumeTrue(l2Hash != null);
        EvmAccount account = account();
        LitVmRpcClient l1 = new LitVmRpcClient(ZkLtcBridge.L1);
        LitVmRpcClient l2 = new LitVmRpcClient(ZkLtcBridge.L2);
        l1.verifyChain();
        l2.verifyChain();
        ZkLtcBridge.Withdrawal withdrawal = ZkLtcBridge.findWithdrawal(l2, l2Hash);
        Assertions.assertNotNull(withdrawal);
        ZkLtcBridge.Confirmed confirmed = ZkLtcBridge.confirmedState(l1, l2);
        Assumptions.assumeTrue(withdrawal.position().compareTo(confirmed.sendCount()) < 0, "withdrawal not confirmed yet");
        Assumptions.assumeFalse(ZkLtcBridge.isClaimed(l1, withdrawal), "already claimed");

        BigInteger before = l1.getTokenBalance(ZkLtcBridge.ZKLTC_L1, withdrawal.destination());
        Eip1559Transaction tx = l1.prepareCall(account.getAddress(), ZkLtcBridge.OUTBOX, BigInteger.ZERO, ZkLtcBridge.claimCall(l2, withdrawal, confirmed.sendCount()));
        String hash = l1.sendRawTransaction(account.sign(tx));
        System.out.println("executeTransaction " + hash);
        Optional<LitVmRpcClient.Receipt> receipt = await(l1, hash);
        Assertions.assertTrue(receipt.isPresent() && receipt.get().success(), "executeTransaction must be mined and succeed");
        Assertions.assertTrue(ZkLtcBridge.isClaimed(l1, withdrawal));
        Assertions.assertEquals(before.add(withdrawal.callValue()), l1.getTokenBalance(ZkLtcBridge.ZKLTC_L1, withdrawal.destination()));
    }
}
