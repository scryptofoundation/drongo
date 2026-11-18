package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.sparrowwallet.drongo.ordinals.track.TrackFixtures.*;

public class MinedCoinTest {
    private static final int HEIGHT = 100;
    private static final long REWARD = 5_000_000_000L;
    private final FakeChainSource chain = new FakeChainSource();

    private InscriptionTracer tracer(Coin coin, Network network) {
        return new InscriptionTracer(coin, network, chain, (txid, vout) -> Optional.empty(), new TxCache());
    }

    private Transaction coinbase(long... outputs) {
        Transaction tx = new Transaction();
        tx.setVersion(2);
        tx.addInput(Sha256Hash.ZERO_HASH, 0xffffffffL, new Script(new byte[] {0x01, 0x64}));
        for(long value : outputs) {
            tx.addOutput(value, PLAIN);
        }
        Assertions.assertTrue(tx.isCoinBase());
        return chain.add(tx, HEIGHT);
    }

    /** Transaction A: two 5,000 inputs (the second revealing an inscription) and one 5,000 output, so the inscription goes to its fee. */
    private Transaction feeBurn() {
        Transaction p1 = chain.add(coinbaseLike(1), 50);
        Transaction p2 = chain.add(coinbaseLike(2), 50);
        Script none = null;
        return chain.add(tx(List.of(out(p1.getTxId(), 0), out(p2.getTxId(), 0)), Arrays.asList(none, envelope("burn")), 5_000), HEIGHT);
    }

    /** An earlier coinbase paying exactly the reward, its output 0 (5,000) wholly in the reward part, so it traces clean. */
    private Transaction coinbaseLike(int n) {
        Transaction tx = new Transaction();
        tx.setVersion(2);
        tx.addInput(Sha256Hash.ZERO_HASH, 0xffffffffL, new Script(new byte[] {0x01, (byte)n}));
        tx.addOutput(5_000, PLAIN);
        tx.addOutput(REWARD - 5_000, PLAIN);
        return tx;
    }

    private void esploraFees(Transaction a) {
        chain.fees.put(HEIGHT, List.of(new BlockFee(a.getTxId(), 5_000)));
    }

    @Test
    public void subsidyPartClean() {
        Transaction cb = coinbase(10_000, REWARD - 10_000 + 1_000);
        TraceResult result = tracer(Coin.LITECOIN, Network.REGTEST).trace(cb.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.CLEAN, result.verdict());
        Assertions.assertEquals(1, result.lookups());
        Assertions.assertEquals(0, chain.blockFeeCalls);
    }

    @Test
    public void feeBurnFound() {
        Transaction a = feeBurn();
        esploraFees(a);
        Transaction cb = coinbase(REWARD + 5_000);
        TraceResult result = tracer(Coin.LITECOIN, Network.REGTEST).trace(cb.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.INSCRIBED, result.verdict(), result.reason());
        Assertions.assertEquals(a.getTxId() + "i0", result.found().getFirst().inscriptionId());
        Assertions.assertEquals(REWARD, result.found().getFirst().offset());
    }

    @Test
    public void electrumFeePartUnchecked() {
        feeBurn();
        Transaction cb = coinbase(REWARD, 5_000);
        TraceResult result = tracer(Coin.LITECOIN, Network.REGTEST).trace(cb.getTxId(), 1, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.UNCHECKED, result.verdict());
        Assertions.assertEquals("Mined coin fees need Check deeper", result.reason());
        Assertions.assertEquals(Verdict.CLEAN, tracer(Coin.LITECOIN, Network.REGTEST).trace(cb.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false).verdict());
    }

    @Test
    public void electrumWalk() {
        Transaction a = feeBurn();
        Transaction cb = coinbase(REWARD + 5_000);
        chain.positions.put(HEIGHT, List.of(cb.getTxId(), a.getTxId()));
        TraceResult result = tracer(Coin.LITECOIN, Network.REGTEST).trace(cb.getTxId(), 0, List.of(), InscriptionTracer.DEEPER_BUDGET, true);
        Assertions.assertEquals(Verdict.INSCRIBED, result.verdict(), result.reason());
        Assertions.assertEquals(REWARD, result.found().getFirst().offset());
    }

    /** Review C3: a coinbase whose height the server did not give must not be read against the height-0 reward. */
    @Test
    public void unknownHeightCoinbaseIsUnchecked() {
        Transaction tx = new Transaction();
        tx.setVersion(2);
        tx.addInput(Sha256Hash.ZERO_HASH, 0xffffffffL, new Script(new byte[] {0x01, 0x65}));
        tx.addOutput(1_000_000_000L, PLAIN);
        chain.add(tx, 0);
        TraceResult result = tracer(Coin.LITECOIN, Network.MAINNET).trace(tx.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.UNCHECKED, result.verdict());
        Assertions.assertEquals("Mined coin height unknown", result.reason());
    }

    /** Review C3: a height one off at a halving must not double the reward; the smaller of the neighbours' rewards is used. */
    @Test
    public void nearHalvingUsesTheSmallerReward() {
        Transaction tx = new Transaction();
        tx.setVersion(2);
        tx.addInput(Sha256Hash.ZERO_HASH, 0xffffffffL, new Script(new byte[] {0x01, 0x66}));
        tx.addOutput(4_000_000_000L, PLAIN);
        tx.addOutput(1_000_000_000L, PLAIN);
        chain.add(tx, 149);
        //Reward at 149 is 50 LTC, at 150 25 LTC: units past 25 LTC need the block's fees, which this chain does not have
        TraceResult result = tracer(Coin.LITECOIN, Network.REGTEST).trace(tx.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.UNCHECKED, result.verdict());
    }

    /** Review I2: a coinbase returned without its block hash still gets its block's fees, by the hash at its height. */
    @Test
    public void feesFoundByTheHashAtTheHeight() {
        Transaction a = feeBurn();
        esploraFees(a);
        Transaction tx = new Transaction();
        tx.setVersion(2);
        tx.addInput(Sha256Hash.ZERO_HASH, 0xffffffffL, new Script(new byte[] {0x01, 0x64}));
        tx.addOutput(REWARD + 5_000, PLAIN);
        chain.addWithoutHash(tx, HEIGHT);
        chain.hashes.put(HEIGHT, FakeChainSource.heightHash(HEIGHT));
        TraceResult result = tracer(Coin.LITECOIN, Network.REGTEST).trace(tx.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.INSCRIBED, result.verdict(), result.reason());
    }

    @Test
    public void walkUnsupportedStaysUnchecked() {
        //A server that cannot list block positions must not read as a block with no fees
        feeBurn();
        Transaction cb = coinbase(REWARD + 5_000);
        TraceResult result = tracer(Coin.LITECOIN, Network.REGTEST).trace(cb.getTxId(), 0, List.of(), InscriptionTracer.DEEPER_BUDGET, true);
        Assertions.assertEquals(Verdict.UNCHECKED, result.verdict());
        Assertions.assertEquals("Mined coin fees need Check deeper", result.reason());

        TraceResult noReward = tracer(Coin.PEPECOIN, Network.TESTNET).trace(cb.getTxId(), 0, List.of(), InscriptionTracer.DEEPER_BUDGET, true);
        Assertions.assertEquals(Verdict.UNCHECKED, noReward.verdict());
    }

    @Test
    public void doesNotAddUp() {
        Transaction a = feeBurn();
        esploraFees(a);
        Transaction cb = coinbase(REWARD + 5_001);
        TraceResult result = tracer(Coin.LITECOIN, Network.REGTEST).trace(cb.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.UNCHECKED, result.verdict());
        Assertions.assertEquals("Mined coin does not add up", result.reason());
    }

    @Test
    public void rewardFromFees() {
        Transaction a = chain.add(spend(List.of(out(hash(1), 0)), 1_000), HEIGHT);
        chain.fees.put(HEIGHT, List.of(new BlockFee(a.getTxId(), 5_000)));
        Transaction cb = coinbase(1_000_000, 5_000);
        TraceResult result = tracer(Coin.PEPECOIN, Network.TESTNET).trace(cb.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.CLEAN, result.verdict(), result.reason());
    }

    @Test
    public void noRewardNoFees() {
        Transaction cb = coinbase(1_000_000, 5_000);
        TraceResult result = tracer(Coin.PEPECOIN, Network.TESTNET).trace(cb.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.UNCHECKED, result.verdict());
        Assertions.assertEquals("Mined coin fees need Check deeper", result.reason());
    }
}
