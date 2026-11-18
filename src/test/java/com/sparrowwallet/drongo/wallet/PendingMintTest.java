package com.sparrowwallet.drongo.wallet;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

public class PendingMintTest {
    private static final Sha256Hash FUNDING = Sha256Hash.wrap("1dbec95328953c22c5801c8045c9f3c190ca457d7413bf221ad7dfc6a00f5059");

    @Test
    public void doginalRecordCopies() {
        byte[] content = "much wow".getBytes(StandardCharsets.UTF_8);
        byte[] scriptHash = new byte[32];
        scriptHash[0] = 9;
        PendingMint mint = PendingMint.doginal("m/44'/3'/0'/0/4", scriptHash, FUNDING, 4_200_000, "text/plain", content, 1000, "DFakeDestination");
        mint.setId(12L);
        Assertions.assertEquals(PendingMint.Kind.DOGINAL, mint.getKind());
        Assertions.assertEquals(FUNDING, mint.getCommitTxid());
        Assertions.assertEquals(0, mint.getCommitVout());
        Assertions.assertEquals(4_200_000, mint.getCommitValue());
        Assertions.assertEquals(0, mint.getStepsBroadcast());

        PendingMint advanced = mint.withStepsBroadcast(7);
        Assertions.assertEquals(7, advanced.getStepsBroadcast());
        Assertions.assertEquals(0, mint.getStepsBroadcast());
        Assertions.assertEquals(12L, advanced.getId());

        PendingMint copy = advanced.copy();
        Assertions.assertEquals(12L, copy.getId());
        Assertions.assertEquals(PendingMint.Kind.DOGINAL, copy.getKind());
        Assertions.assertEquals("m/44'/3'/0'/0/4", copy.getKeyPath());
        Assertions.assertArrayEquals(scriptHash, copy.getScriptHash());
        Assertions.assertArrayEquals(content, copy.getContent());
        Assertions.assertEquals(1000, copy.getFeeRate());
        Assertions.assertEquals("DFakeDestination", copy.getDestination());
        Assertions.assertEquals(7, copy.getStepsBroadcast());
        Assertions.assertEquals("text/plain", copy.getContentType());

        //The record's content is its own copy
        content[0] = 'x';
        Assertions.assertEquals('m', copy.getContent()[0]);
    }

    @Test
    public void inscriptionRecordHasNoDoginalFields() {
        PendingMint mint = new PendingMint(PendingMint.Kind.INSCRIPTION, "m/86'/2'/0'/0/1", new byte[32], FUNDING, 0, 10_330, "text/plain", null);
        Assertions.assertNull(mint.getContent());
        Assertions.assertNull(mint.getDestination());
        Assertions.assertEquals(0, mint.getFeeRate());
        Assertions.assertEquals(0, mint.getStepsBroadcast());
        Assertions.assertThrows(IllegalStateException.class, () -> mint.withStepsBroadcast(1));
        Assertions.assertNull(mint.copy().getContent());
    }

    @Test
    public void doginalNeedsItsContent() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> PendingMint.doginal("m/44'/3'/0'/0/4", new byte[32], FUNDING, 4_200_000, "text/plain", new byte[0], 1000, "D"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> PendingMint.doginal("m/44'/3'/0'/0/4", new byte[32], FUNDING, 4_200_000, "text/plain", new byte[1], 1000, null));
    }
}
