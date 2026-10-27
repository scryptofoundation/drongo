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

    /** The reveal of a 32x32 PNG inscribed by ord-litecoin 0.20.1 on the private regtest. */
    private static final String REGTEST_PNG_REVEAL = "020000000001016e63f360b22b8eddcf90b0a65de7a7d9c9683ea543e7b6b1579e8a29ee59b5520000000000fdffffff011027000000000000160014d417eb5d9ab91deb7a6c651b0d6266ae6ff00f200340d06d047b997ce0fe682a3e3490b77edcf37e771bb38f8d931b848e00dfd8e65888051f742f0d61c2154c44697f695e69bf3c1bde770623bc5a17e32735da038ae520c2f2147830b788732f7a7274d5716c9ffe7a76f175d40cae7ade4112da535613ac0063036f7264010109696d6167652f706e67004cad89504e470d0a1a0a0000000d49484452000000200000002002030000000e149267000000206348524d00007a26000080840000fa00000080e8000075300000ea6000003a98000017709cba513c00000009504c5445ffa5000000fffffffff63a2c8000000001624b474402660b7c640000000774494d4507ea0a1b012c2dcb8f0797000000134944415418d36360a01a080582c1c4a00a0000f5c51541aa8a10990000000049454e44ae4260826821c1c2f2147830b788732f7a7274d5716c9ffe7a76f175d40cae7ade4112da53561300000000";

    @Test
    public void regtestRevealIsFound() {
        Transaction reveal = new Transaction(Utils.hexToBytes(REGTEST_PNG_REVEAL));
        Assertions.assertEquals("a8e1009506f22737323fa8d8831c2f9d2b2b6a780e3de47067320a212f429cce", reveal.getTxId().toString());
        Assertions.assertTrue(OrdinalsScan.hasEnvelope(reveal));
        Assertions.assertFalse(OrdinalsScan.hasRunestone(reveal));
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
