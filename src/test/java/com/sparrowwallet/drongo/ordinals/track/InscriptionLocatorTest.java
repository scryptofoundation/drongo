package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.ordinals.DoginalsMint;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptChunk;
import com.sparrowwallet.drongo.protocol.ScriptOpCodes;
import com.sparrowwallet.drongo.protocol.Transaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.sparrowwallet.drongo.ordinals.track.TrackFixtures.*;

public class InscriptionLocatorTest {
    @Test
    public void ordInputZero() {
        Transaction reveal = tx(List.of(out(hash(1), 0)), List.of(envelope("a")), 10_000);
        Assertions.assertEquals(List.of(new Creation(reveal.getTxId() + "i0", 0, 0)), InscriptionLocator.ord(reveal, List.of(10_000L)));
    }

    @Test
    public void ordSecondInput() {
        Script none = null;
        Transaction reveal = tx(List.of(out(hash(1), 0), out(hash(2), 0)), Arrays.asList(none, envelope("b")), 15_000, 5_000);
        Assertions.assertEquals(List.of(new Creation(reveal.getTxId() + "i0", 0, 10_000)),
                InscriptionLocator.ord(reveal, List.of(10_000L, 10_000L)));
    }

    @Test
    public void ordPointer() {
        Transaction reveal = tx(List.of(out(hash(1), 0)), List.of(envelopeWithPointer("c", 12_000)), 10_000, 5_000);
        Assertions.assertEquals(List.of(new Creation(reveal.getTxId() + "i0", 1, 2_000)), InscriptionLocator.ord(reveal, List.of(15_000L)));

        Transaction ignored = tx(List.of(out(hash(1), 0)), List.of(envelopeWithPointer("c", 20_000)), 10_000, 5_000);
        Assertions.assertEquals(List.of(new Creation(ignored.getTxId() + "i0", 0, 0)), InscriptionLocator.ord(ignored, List.of(15_000L)));
    }

    @Test
    public void ordToFee() {
        Script none = null;
        Transaction reveal = tx(List.of(out(hash(1), 0), out(hash(2), 0)), Arrays.asList(none, envelope("d")), 5_000);
        Assertions.assertEquals(List.of(new Creation(reveal.getTxId() + "i0", Piece.FEE, 0)),
                InscriptionLocator.ord(reveal, List.of(5_000L, 5_000L)));
    }

    @Test
    public void ordTwoEnvelopesOneInput() {
        List<ScriptChunk> chunks = new ArrayList<>(envelope("e").getChunks());
        chunks.addAll(envelope("f").getChunks().subList(2, envelope("f").getChunks().size()));
        Transaction reveal = tx(List.of(out(hash(1), 0)), List.of(new Script(chunks)), 10_000);
        Assertions.assertEquals(List.of(new Creation(reveal.getTxId() + "i0", 0, 0), new Creation(reveal.getTxId() + "i1", 0, 0)),
                InscriptionLocator.ord(reveal, List.of(10_000L)));
    }

    @Test
    public void ordMalformedStillCounts() {
        //OP_FALSE OP_IF push "ord", then a push that claims 10 bytes but has 2
        byte[] broken = {0x20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte)0xac, 0x00, 0x63, 0x03, 0x6f, 0x72, 0x64, 0x0a, 0x01, 0x02};
        Transaction reveal = tx(List.of(out(hash(1), 0)), List.of(new Script(broken)), 10_000);
        Assertions.assertEquals(List.of(new Creation(reveal.getTxId() + "i0", 0, 0)), InscriptionLocator.ord(reveal, List.of(10_000L)));
    }

    /** Review I5: ord reads tag and value pushes written as OP_N, and a pointer padded past 8 bytes with zeros. */
    @Test
    public void pointerAsOpNumberAndPadded() {
        Transaction small = tx(List.of(out(hash(1), 0)), List.of(envelopeWithFields("p", ScriptChunk.fromOpcode(ScriptOpCodes.OP_2), ScriptChunk.fromOpcode(ScriptOpCodes.OP_5))), 10_000);
        Assertions.assertEquals(List.of(new Creation(small.getTxId() + "i0", 0, 5)), InscriptionLocator.ord(small, List.of(10_000L)));

        byte[] padded = {(byte)0xe0, 0x2e, 0, 0, 0, 0, 0, 0, 0, 0};
        Transaction wide = tx(List.of(out(hash(1), 0)), List.of(envelopeWithFields("p", push(new byte[] {2}), push(padded))), 10_000, 5_000);
        Assertions.assertEquals(List.of(new Creation(wide.getTxId() + "i0", 1, 2_000)), InscriptionLocator.ord(wide, List.of(15_000L)));
    }

    /** Review I5: a pointer ord could read but this code cannot place puts the inscription anywhere in the transaction. */
    @Test
    public void unreadablePointerIsAnywhere() {
        byte[] huge = {1, 1, 1, 1, 1, 1, 1, 1, 1};
        Transaction reveal = tx(List.of(out(hash(1), 0)), List.of(envelopeWithFields("p", push(new byte[] {2}), push(huge))), 10_000, 5_000);
        Assertions.assertEquals(List.of(new Creation(reveal.getTxId() + "i0", Creation.ANYWHERE, -1)), InscriptionLocator.ord(reveal, List.of(15_000L)));
    }

    /** Review I4: ord-dogecoin follows the pushes, not the redeem script, so a piece under another redeem script still counts. */
    @Test
    public void doginalsUnderAnotherRedeemScript() {
        DoginalsMint.Chain chain = doginalsChain(Coin.DOGECOIN, 600);
        Transaction step = doginalsSteps(chain, hash(9), 100_000_000_000L).getFirst();
        List<ScriptChunk> chunks = new ArrayList<>(step.getInputs().getFirst().getScriptSig().getChunks());
        chunks.set(chunks.size() - 1, push(new byte[] {0x51, 0x51}));
        Transaction other = new Transaction();
        other.setVersion(1);
        other.addInput(hash(9), 0, new Script(chunks));
        other.addOutput(100_000, PLAIN);
        DoginalsStep found = InscriptionLocator.doginals(other).orElseThrow();
        Assertions.assertTrue(found.genesis());
        Assertions.assertTrue(found.last());
    }

    @Test
    public void ordIdsNeedNoInputValues() {
        Script none = null;
        Transaction reveal = tx(List.of(out(hash(1), 0), out(hash(2), 0)), Arrays.asList(none, envelope("b")), 15_000, 5_000);
        Assertions.assertEquals(List.of(reveal.getTxId() + "i0"), InscriptionLocator.ordIds(reveal));
        Assertions.assertEquals(List.of(), InscriptionLocator.ordIds(spend(List.of(out(hash(1), 0)), 1_000)));
    }

    @Test
    public void plainSpendCreatesNothing() {
        Transaction plain = spend(List.of(out(hash(1), 0)), 10_000);
        Assertions.assertEquals(List.of(), InscriptionLocator.ord(plain, List.of(10_000L)));
        Assertions.assertTrue(InscriptionLocator.doginals(plain).isEmpty());
    }

    @Test
    public void doginalsChainSteps() {
        DoginalsMint.Chain chain = doginalsChain(Coin.DOGECOIN, 4_000);
        Assertions.assertTrue(chain.steps() >= 3, "a 4,000-byte body takes several steps");
        List<Transaction> steps = doginalsSteps(chain, hash(9), 100_000_000_000L);

        DoginalsStep first = InscriptionLocator.doginals(steps.getFirst()).orElseThrow();
        Assertions.assertTrue(first.genesis());
        Assertions.assertFalse(first.last());
        Assertions.assertEquals(hash(9), first.previousTxid());

        DoginalsStep middle = InscriptionLocator.doginals(steps.get(1)).orElseThrow();
        Assertions.assertFalse(middle.genesis());
        Assertions.assertFalse(middle.last());

        DoginalsStep last = InscriptionLocator.doginals(steps.getLast()).orElseThrow();
        Assertions.assertFalse(last.genesis());
        Assertions.assertTrue(last.last());
        Assertions.assertEquals(steps.get(steps.size() - 2).getTxId(), last.previousTxid());
    }

    @Test
    public void doginalsSingleStepIsGenesisAndLast() {
        DoginalsMint.Chain chain = doginalsChain(Coin.DOGECOIN, 600);
        Assertions.assertEquals(1, chain.steps());
        DoginalsStep only = InscriptionLocator.doginals(doginalsSteps(chain, hash(9), 100_000_000_000L).getFirst()).orElseThrow();
        Assertions.assertTrue(only.genesis());
        Assertions.assertTrue(only.last());
    }

    @Test
    public void numberChunks() {
        Assertions.assertEquals(0, InscriptionLocator.number(ScriptChunk.fromOpcode(ScriptOpCodes.OP_0)));
        Assertions.assertEquals(16, InscriptionLocator.number(ScriptChunk.fromOpcode(ScriptOpCodes.OP_16)));
        Assertions.assertEquals(100, InscriptionLocator.number(new ScriptChunk(1, new byte[] {100})));
        Assertions.assertEquals(300, InscriptionLocator.number(new ScriptChunk(2, new byte[] {44, 1})));
    }
}
