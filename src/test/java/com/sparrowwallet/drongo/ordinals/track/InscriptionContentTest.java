package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.ordinals.DoginalsMint;
import com.sparrowwallet.drongo.ordinals.Inscription;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.Transaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static com.sparrowwallet.drongo.ordinals.track.TrackFixtures.*;

public class InscriptionContentTest {
    @Test
    public void ordText() {
        Transaction reveal = tx(List.of(out(hash(1), 0)), List.of(envelope("hello")), 10_000);
        InscriptionContent content = InscriptionContent.ord(reveal, 0);
        Assertions.assertEquals(Inscription.TEXT, content.contentType());
        Assertions.assertNull(content.contentEncoding());
        Assertions.assertEquals("hello", new String(content.body(), StandardCharsets.UTF_8));
    }

    @Test
    public void ordSecondEnvelopeAndLongBody() {
        byte[] big = new byte[1_300];
        Arrays.fill(big, (byte)'x');
        Script none = null;
        Inscription large = new Inscription("image/png", big, null);
        Transaction reveal = tx(List.of(out(hash(1), 0), out(hash(2), 0)), Arrays.asList(envelope("first"), large.revealScript(KEY)), 10_000);
        InscriptionContent content = InscriptionContent.ord(reveal, 1);
        Assertions.assertEquals("image/png", content.contentType());
        Assertions.assertArrayEquals(big, content.body());
        Assertions.assertNull(InscriptionContent.ord(reveal, 2));
    }

    @Test
    public void fromChain() throws Exception {
        FakeChainSource chain = new FakeChainSource();
        Transaction reveal = chain.add(tx(List.of(out(hash(1), 0)), List.of(envelope("on chain")), 10_000), 100);
        Assertions.assertEquals("on chain", new String(InscriptionContent.fromChain(reveal.getTxId() + "i0", reveal.getTxId(), chain).body(), StandardCharsets.UTF_8));

        DoginalsMint.Chain mint = doginalsChain(Coin.DOGECOIN, 4_000);
        List<Transaction> steps = doginalsSteps(mint, hash(9), 100_000_000_000L);
        for(Transaction step : steps) {
            chain.add(step, 101);
        }
        InscriptionContent doginal = InscriptionContent.fromChain(steps.getFirst().getTxId() + "i0", steps.getLast().getTxId(), chain);
        Assertions.assertArrayEquals(mint.body(), doginal.body());
        Assertions.assertNull(InscriptionContent.fromChain(steps.getFirst().getTxId() + "i0", null, chain));
    }

    @Test
    public void doginalsContent() {
        DoginalsMint.Chain mint = doginalsChain(Coin.DOGECOIN, 4_000);
        List<Transaction> steps = doginalsSteps(mint, hash(9), 100_000_000_000L);
        InscriptionContent content = InscriptionContent.doginals(steps);
        Assertions.assertEquals("image/png", content.contentType());
        Assertions.assertArrayEquals(mint.body(), content.body());
    }
}
