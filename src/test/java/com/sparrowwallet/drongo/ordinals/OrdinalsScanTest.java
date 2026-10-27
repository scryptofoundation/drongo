package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class OrdinalsScanTest {
    private static byte[] push(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(data.length);
        out.writeBytes(data);
        return out.toByteArray();
    }

    /** The tapscript ord writes: key CHECKSIG, then OP_FALSE OP_IF "ord" 1 content-type 0 body OP_ENDIF. */
    static byte[] inscriptionTapscript() {
        ByteArrayOutputStream script = new ByteArrayOutputStream();
        script.writeBytes(push(new byte[32]));
        script.write(0xac);
        script.write(0x00);
        script.write(0x63);
        script.writeBytes(push("ord".getBytes(StandardCharsets.US_ASCII)));
        script.writeBytes(push(new byte[] {0x01}));
        script.writeBytes(push("text/plain".getBytes(StandardCharsets.US_ASCII)));
        script.write(0x00);
        script.writeBytes(push("hi".getBytes(StandardCharsets.US_ASCII)));
        script.write(0x68);
        return script.toByteArray();
    }

    static Transaction spending(List<byte[]> witnessPushes) {
        Transaction tx = new Transaction();
        tx.setSegwitFlag(Transaction.DEFAULT_SEGWIT_FLAG);
        tx.addInput(Sha256Hash.ZERO_HASH, 0, new Script(List.of()), new TransactionWitness(tx, witnessPushes));
        tx.addOutput(10000, new Script(Utils.hexToBytes("5120" + "00".repeat(32))));
        return tx;
    }

    @Test
    public void envelopeInTapscriptIsFound() {
        Transaction reveal = spending(List.of(new byte[64], inscriptionTapscript(), new byte[33]));
        Assertions.assertTrue(OrdinalsScan.hasEnvelope(reveal));
    }

    @Test
    public void plainSegwitSpendHasNoEnvelope() {
        Transaction plain = spending(List.of(new byte[72], new byte[33]));
        Assertions.assertFalse(OrdinalsScan.hasEnvelope(plain));
    }

    @Test
    public void runestoneOutputIsFound() {
        Transaction tx = spending(List.of(new byte[72], new byte[33]));
        Assertions.assertFalse(OrdinalsScan.hasRunestone(tx));
        tx.addOutput(0, new Script(Utils.hexToBytes("6a04deadbeef")));
        Assertions.assertFalse(OrdinalsScan.hasRunestone(tx));
        tx.addOutput(0, new Script(Utils.hexToBytes("6a5d00")));
        Assertions.assertTrue(OrdinalsScan.hasRunestone(tx));
    }
}
