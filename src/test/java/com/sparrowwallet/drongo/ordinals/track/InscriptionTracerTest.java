package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.ordinals.DoginalsMint;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutPoint;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.sparrowwallet.drongo.ordinals.track.TrackFixtures.*;

public class InscriptionTracerTest {
    private final FakeChainSource chain = new FakeChainSource();
    private final Map<String, KnownOutput> known = new HashMap<>();

    private InscriptionTracer tracer(Coin coin, Network network) {
        return new InscriptionTracer(coin, network, chain, (txid, vout) -> Optional.ofNullable(known.get(txid + ":" + vout)), new TxCache());
    }

    private InscriptionTracer litecoin() {
        return tracer(Coin.LITECOIN, Network.MAINNET);
    }

    /** A plain transaction at a height below Litecoin's first inscription, paying one output of the value. */
    private Transaction old(int n, long value) {
        return chain.add(spend(List.of(out(hash(n), 0)), value), 2_000_000);
    }

    @Test
    public void cleanBelowCutoff() {
        Transaction parent = old(1, 10_000);
        Transaction coin = chain.add(spend(List.of(out(parent.getTxId(), 0)), 9_000), 2_500_000);
        TraceResult result = litecoin().trace(coin.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.CLEAN, result.verdict());
        Assertions.assertEquals(2, result.lookups());
        Assertions.assertTrue(result.usedChain());
    }

    @Test
    public void foundInscription() {
        Transaction parent = old(1, 10_000);
        Transaction reveal = chain.add(tx(List.of(out(parent.getTxId(), 0)), List.of(envelope("hi")), 10_000), 2_500_000);
        Transaction coin = chain.add(spend(List.of(out(reveal.getTxId(), 0)), 10_000), 2_500_001);
        TraceResult result = litecoin().trace(coin.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.INSCRIBED, result.verdict());
        Assertions.assertEquals(1, result.found().size());
        Found found = result.found().getFirst();
        Assertions.assertEquals(reveal.getTxId() + "i0", found.inscriptionId());
        Assertions.assertEquals(0, found.offset());
        Assertions.assertNull(found.number());
        Assertions.assertEquals(reveal.getTxId(), found.mintTxid());
        Assertions.assertEquals(2_500_000, found.mintHeight());
    }

    @Test
    public void inscriptionElsewhere() {
        Transaction parent = old(1, 15_000);
        Transaction reveal = chain.add(tx(List.of(out(parent.getTxId(), 0)), List.of(envelopeWithPointer("hi", 12_000)), 10_000, 5_000), 2_500_000);
        Transaction coin = chain.add(spend(List.of(out(reveal.getTxId(), 0)), 10_000), 2_500_001);
        Assertions.assertEquals(Verdict.CLEAN, litecoin().trace(coin.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false).verdict());
        Assertions.assertEquals(Verdict.INSCRIBED, litecoin().trace(reveal.getTxId(), 1, List.of(), InscriptionTracer.DEFAULT_BUDGET, false).verdict());
    }

    @Test
    public void foundOffsetIsInTheCoin() {
        //Two 5,000 inputs, the second a reveal's output carrying an inscription at its offset 0: merged, it sits at 5,000
        Transaction p1 = old(1, 5_000);
        Transaction p2 = old(2, 5_000);
        Transaction reveal = chain.add(tx(List.of(out(p2.getTxId(), 0)), List.of(envelope("x")), 5_000), 2_500_000);
        Transaction coin = chain.add(spend(List.of(out(p1.getTxId(), 0), out(reveal.getTxId(), 0)), 10_000), 2_500_001);
        TraceResult result = litecoin().trace(coin.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(5_000, result.found().getFirst().offset());
    }

    @Test
    public void budgetThenResume() {
        Transaction previous = old(1, 10_000);
        for(int i = 0; i < 5; i++) {
            previous = chain.add(spend(List.of(out(previous.getTxId(), 0)), 10_000), 2_500_000 + i);
        }
        TraceResult cut = litecoin().trace(previous.getTxId(), 0, List.of(), 2, false);
        Assertions.assertEquals(Verdict.UNCHECKED, cut.verdict());
        Assertions.assertEquals("Lookup budget used up; use Check deeper", cut.reason());
        Assertions.assertFalse(cut.open().isEmpty());

        TraceResult resumed = litecoin().trace(previous.getTxId(), 0, cut.open(), 10, false);
        Assertions.assertEquals(Verdict.CLEAN, resumed.verdict());
        Assertions.assertTrue(resumed.open().isEmpty());
    }

    @Test
    public void knownResultsUsed() {
        Transaction walletParent = chain.add(spend(List.of(out(hash(1), 0)), 10_000), 2_500_000, false);
        known.put(walletParent.getTxId() + ":0", new KnownOutput(Verdict.CLEAN, List.of()));
        Transaction coin = chain.add(spend(List.of(out(walletParent.getTxId(), 0)), 9_000), 2_500_001);
        TraceResult result = litecoin().trace(coin.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.CLEAN, result.verdict());
        Assertions.assertEquals(1, result.lookups());
    }

    @Test
    public void knownInscribedCarriesOver() {
        Transaction walletParent = chain.add(spend(List.of(out(hash(1), 0)), 10_000), 2_500_000, false);
        Found inscription = new Found(hash(7) + "i0", 3_000, null, hash(7), 2_400_000, 1L);
        known.put(walletParent.getTxId() + ":0", new KnownOutput(Verdict.INSCRIBED, List.of(inscription)));
        Transaction coin = chain.add(spend(List.of(out(walletParent.getTxId(), 0)), 4_000, 6_000), 2_500_001);
        TraceResult first = litecoin().trace(coin.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.INSCRIBED, first.verdict());
        Assertions.assertEquals(3_000, first.found().getFirst().offset());
        Assertions.assertEquals(Verdict.CLEAN, litecoin().trace(coin.getTxId(), 1, List.of(), InscriptionTracer.DEFAULT_BUDGET, false).verdict());
    }

    /** OP_8 and a 32 byte push: the MWEB pool output a HogEx pays first. */
    private static byte[] hogAddr() {
        byte[] hogAddr = new byte[34];
        hogAddr[0] = 0x58;
        hogAddr[1] = 0x20;
        return hogAddr;
    }

    /** The same transaction with the MWEB flag (8) and the given extension, parsed back as a server would return it. */
    private static Transaction flagged(Transaction plain, byte[] extension) {
        byte[] bytes = plain.bitcoinSerialize();
        java.io.ByteArrayOutputStream withFlag = new java.io.ByteArrayOutputStream();
        withFlag.write(bytes, 0, 4);
        withFlag.write(0);
        withFlag.write(8);
        withFlag.write(bytes, 4, bytes.length - 8);
        withFlag.write(extension, 0, extension.length);
        withFlag.write(bytes, bytes.length - 4, 4);
        return new Transaction(withFlag.toByteArray());
    }

    /**
     * A real HogEx: the MWEB flag with the null extension (the byte 0); input 0 spends the previous HogEx's output 0 (then
     * any peg-ins), output 0 is the MWEB pool, the rest are peg-outs.
     */
    private Transaction hogEx(List<TransactionOutPoint> pegIns, long... pegOuts) {
        Transaction plain = new Transaction();
        plain.setVersion(2);
        plain.addInput(hash(1), 0, new Script(new byte[0]));
        for(TransactionOutPoint pegIn : pegIns) {
            plain.addInput(pegIn.getHash(), pegIn.getIndex(), new Script(new byte[0]));
        }
        plain.addOutput(1_000_000, new Script(hogAddr()));
        for(long value : pegOuts) {
            plain.addOutput(value, PLAIN);
        }
        Transaction hogEx = flagged(plain, new byte[] {0});
        Assertions.assertTrue(hogEx.isHogEx() && hogEx.hasNullMwebExtension(), "fixture is a flagged HogEx");
        return hogEx;
    }

    /** A coin that came out of MWEB has no history to follow: the piece ends clean at the HogEx (the owner's rule, 2026-11-10). */
    @Test
    public void hogExIsClean() {
        Transaction hogEx = chain.add(hogEx(List.of(), 10_000), 2_500_000);
        Transaction coin = chain.add(spend(List.of(out(hogEx.getTxId(), 1)), 9_000), 2_500_001);
        TraceResult result = litecoin().trace(coin.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.CLEAN, result.verdict());
        Assertions.assertNull(result.reason());
        Assertions.assertTrue(result.open().isEmpty());
        Assertions.assertEquals(2, result.lookups());
    }

    /** A coin that is itself a peg-out of a confirmed HogEx is clean in one lookup. */
    @Test
    public void pegOutCoinIsCleanInOneLookup() {
        Transaction hogEx = chain.add(hogEx(List.of(), 10_000), 2_500_000);
        TraceResult result = litecoin().trace(hogEx.getTxId(), 1, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.CLEAN, result.verdict(), result.reason());
        Assertions.assertEquals(1, result.lookups());
    }

    /** An inscribed reveal at 2,500,000, its output 0 holding 10,000 with the inscription on its first unit. */
    private Transaction inscribed() {
        Transaction parent = old(1, 10_000);
        return chain.add(tx(List.of(out(parent.getTxId(), 0)), List.of(envelope("in")), 10_000), 2_500_000);
    }

    /**
     * An inscription moved into MWEB by a peg-in and a peg-out paying the wallet coin: the trace stops at the HogEx and never
     * reaches the inscription, so the coin is clean.
     */
    @Test
    public void inscriptionThroughMwebIsClean() {
        Transaction reveal = inscribed();
        byte[] pegInScript = new byte[34];
        pegInScript[0] = 0x59;
        pegInScript[1] = 0x20;
        Transaction pegIn = new Transaction();
        pegIn.setVersion(2);
        pegIn.addInput(reveal.getTxId(), 0, new Script(new byte[0]));
        pegIn.addOutput(9_000, new Script(pegInScript));
        chain.add(pegIn, 2_500_001);
        Transaction hogEx = chain.add(hogEx(List.of(out(pegIn.getTxId(), 0)), 9_000), 2_500_001);
        Transaction coin = chain.add(spend(List.of(out(hogEx.getTxId(), 1)), 9_000), 2_500_002);
        Assertions.assertEquals(Verdict.INSCRIBED, litecoin().trace(pegIn.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false).verdict(),
                "the inscription went into MWEB");
        TraceResult result = litecoin().trace(coin.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.CLEAN, result.verdict(), result.reason());
        Assertions.assertTrue(result.found().isEmpty());
        Assertions.assertEquals(2, result.lookups(), "the trace stops at the HogEx");
    }

    /**
     * A look-alike anyone can build: input 0 spends some output 0 (1,000), input 1 the inscribed 10,000, output 0 pays
     * poolScript with 1,000 and output 1 carries the inscription to the coin. Unless it is a confirmed, flagged HogEx it
     * must not end the trace clean.
     */
    private TraceResult lookAlike(byte[] poolScript, byte[] extension, int height) {
        Transaction reveal = inscribed();
        Transaction filler = old(2, 1_000);
        Transaction plain = new Transaction();
        plain.setVersion(2);
        plain.addInput(filler.getTxId(), 0, new Script(new byte[0]));
        plain.addInput(reveal.getTxId(), 0, new Script(new byte[0]));
        plain.addOutput(1_000, new Script(poolScript));
        plain.addOutput(10_000, PLAIN);
        Transaction lookAlike = extension == null ? plain : flagged(plain, extension);
        Assertions.assertTrue(lookAlike.isHogEx(), "the look-alike reads as a HogEx");
        chain.add(lookAlike, height);
        Transaction coin = chain.add(spend(List.of(out(lookAlike.getTxId(), 1)), 10_000), 2_500_002);
        return litecoin().trace(coin.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
    }

    /** Review: an unflagged transaction whose output 0 is the plain OP_8 32 byte form is no proof of MWEB. */
    @Test
    public void unflaggedHogExLookAlikeIsUnchecked() {
        TraceResult result = lookAlike(hogAddr(), null, 2_500_001);
        Assertions.assertEquals(Verdict.UNCHECKED, result.verdict());
        Assertions.assertEquals("Came through MWEB", result.reason());
    }

    /** Review: OP_8 OP_PUSHDATA1 0x20 and 32 bytes, the non-minimal form of the pool output, is no proof either. */
    @Test
    public void pushData1HogExLookAlikeIsUnchecked() {
        byte[] script = new byte[35];
        script[0] = 0x58;
        script[1] = 0x4c;
        script[2] = 0x20;
        TraceResult result = lookAlike(script, null, 2_500_001);
        Assertions.assertEquals(Verdict.UNCHECKED, result.verdict());
        Assertions.assertEquals("Came through MWEB", result.reason());
    }

    /** Review: a flagged HogEx not yet in a block has no consensus behind it. */
    @Test
    public void unconfirmedFlaggedHogExIsUnchecked() {
        TraceResult result = lookAlike(hogAddr(), new byte[] {0}, 0);
        Assertions.assertEquals(Verdict.UNCHECKED, result.verdict());
        Assertions.assertEquals("Came through MWEB", result.reason());
    }

    /** A transaction with the MWEB flag that carries an MWEB transaction (a peg-in) is no HogEx: it is traced through. */
    @Test
    public void flaggedPegInIsTracedThrough() {
        Transaction parent = old(2, 10_000);
        Transaction plain = new Transaction();
        plain.setVersion(2);
        plain.addInput(parent.getTxId(), 0, new Script(new byte[0]));
        plain.addOutput(10_000, PLAIN);
        Transaction pegIn = flagged(plain, new byte[] {1, 2, 3});
        Assertions.assertFalse(pegIn.hasNullMwebExtension());
        Assertions.assertFalse(pegIn.isHogEx());
        chain.add(pegIn, 2_500_000);
        Transaction coin = chain.add(spend(List.of(out(pegIn.getTxId(), 0)), 9_000), 2_500_001);
        TraceResult result = litecoin().trace(coin.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.CLEAN, result.verdict(), result.reason());
        Assertions.assertEquals(3, result.lookups());
    }

    /** Review C1: a trace that hit a dead end leaves nothing to resume, so a later resume cannot skip the dead end. */
    @Test
    public void deadEndIsNotResumable() {
        //A coinbase the server gave no height for is a dead end
        Transaction unknownHeight = new Transaction();
        unknownHeight.setVersion(2);
        unknownHeight.addInput(Sha256Hash.ZERO_HASH, 0xffffffffL, new Script(new byte[] {0x01, 0x65}));
        unknownHeight.addOutput(10_000, PLAIN);
        chain.add(unknownHeight, 0);
        Transaction previous = old(2, 10_000);
        for(int i = 0; i < 5; i++) {
            previous = chain.add(spend(List.of(out(previous.getTxId(), 0)), 10_000), 2_500_001 + i);
        }
        Transaction coin = chain.add(spend(List.of(out(unknownHeight.getTxId(), 0), out(previous.getTxId(), 0)), 20_000), 2_500_010);
        TraceResult cut = litecoin().trace(coin.getTxId(), 0, List.of(), 4, false);
        Assertions.assertEquals(Verdict.UNCHECKED, cut.verdict());
        Assertions.assertTrue(cut.open().isEmpty(), "a dead end leaves nothing to resume: " + cut.open());
    }

    /** Review I4: the inscription of a last Doginals step sits on the first unit of the flow, output 1 when output 0 holds nothing. */
    @Test
    public void doginalsFirstUnitAfterAnEmptyOutput() {
        DoginalsMint.Chain mint = doginalsChain(Coin.DOGECOIN, 600);
        Transaction funding = chain.add(spend(List.of(out(hash(1), 0)), 100_000_000_000L), 100);
        Transaction step = doginalsSteps(mint, funding.getTxId(), 100_000_000_000L).getFirst();
        Transaction landed = new Transaction();
        landed.setVersion(1);
        landed.addInput(step.getInputs().getFirst().getOutpoint().getHash(), 0, step.getInputs().getFirst().getScriptSig());
        landed.addOutput(0, new Script(new byte[] {0x6a}));
        landed.addOutput(100_000, PLAIN);
        chain.add(landed, 101);
        TraceResult result = tracer(Coin.DOGECOIN, Network.REGTEST).trace(landed.getTxId(), 1, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.INSCRIBED, result.verdict(), result.reason());
        Assertions.assertEquals(landed.getTxId() + "i0", result.found().getFirst().inscriptionId());
    }

    @Test
    public void unconfirmedNotCutoff() {
        Transaction parent = old(1, 10_000);
        Transaction reveal = chain.add(tx(List.of(out(parent.getTxId(), 0)), List.of(envelope("u")), 10_000), 0);
        Transaction coin = chain.add(spend(List.of(out(reveal.getTxId(), 0)), 10_000), 0);
        Assertions.assertEquals(Verdict.INSCRIBED, litecoin().trace(coin.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false).verdict());
    }

    @Test
    public void serverError() {
        Transaction parent = old(1, 10_000);
        Transaction coin = chain.add(spend(List.of(out(parent.getTxId(), 0)), 9_000), 2_500_000);
        chain.failing.add(parent.getTxId());
        TraceResult result = litecoin().trace(coin.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.UNCHECKED, result.verdict());
        Assertions.assertEquals("Server error, will retry next sync: connection reset", result.reason());
        Assertions.assertFalse(result.open().isEmpty());
    }

    @Test
    public void cacheSavesLookups() {
        Transaction parent = old(1, 10_000);
        Transaction coin = chain.add(spend(List.of(out(parent.getTxId(), 0)), 9_000), 2_500_000);
        InscriptionTracer tracer = litecoin();
        Assertions.assertEquals(2, tracer.trace(coin.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false).lookups());
        TraceResult again = tracer.trace(coin.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.CLEAN, again.verdict());
        Assertions.assertEquals(0, again.lookups());
    }

    @Test
    public void doginalsFound() {
        DoginalsMint.Chain mint = doginalsChain(Coin.DOGECOIN, 4_000);
        Transaction funding = chain.add(spend(List.of(out(hash(1), 0)), 100_000_000_000L), 100);
        List<Transaction> steps = doginalsSteps(mint, funding.getTxId(), 100_000_000_000L);
        for(int i = 0; i < steps.size(); i++) {
            chain.add(steps.get(i), 101 + i);
        }
        Transaction last = steps.getLast();
        TraceResult result = tracer(Coin.DOGECOIN, Network.REGTEST).trace(last.getTxId(), 0, List.of(), InscriptionTracer.DEFAULT_BUDGET, false);
        Assertions.assertEquals(Verdict.INSCRIBED, result.verdict());
        Found found = result.found().getFirst();
        Assertions.assertEquals(steps.getFirst().getTxId() + "i0", found.inscriptionId());
        Assertions.assertEquals(0, found.offset());
        Assertions.assertEquals(last.getTxId(), found.mintTxid());
    }
}
