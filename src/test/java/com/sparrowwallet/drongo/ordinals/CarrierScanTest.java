package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class CarrierScanTest {
    private static final String P2PKH = "76a914" + "11".repeat(20) + "88ac";
    //Litecoin mainnet d61bf79134486f169c0e719fca6b76a8b34bb9413d54ebfdbff605289cb12fa5
    private static final String PLAIN_LTC = "02000000000101878a9c672c7b4d9ca186c3cbf6d3b045b592e8a1f7d3c5650d394291cd02fad30100000000fdffffff021416070400000000160014248050c08b32ad37711a73e3b237e4af914a71bcf45303a304000000160014accd53bf99da0a8241a53f570f570395b8b4146d02473044022018d94b3d4367acccf4bd8ab6965289ebd0b209d15ed6585ae974093e0dba1ca902206be66fc371eff5ecc273984c7d75731b2778ead7f818be494ee5395fbfad0e3a012102a709654305b6593a2b92c6430365c9b12e29410a69c2763b47fbf79a6c16cffa0dec3000";

    private static void push(ByteArrayOutputStream out, byte[] data) {
        if(data.length < 0x4c) {
            out.write(data.length);
        } else {
            out.write(0x4c);
            out.write(data.length);
        }
        out.writeBytes(data);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /** The lock script doginals.js spends: pubkey CHECKSIGVERIFY, one OP_DROP per pushed chunk, OP_TRUE. */
    private static byte[] partialRedeemScript(int drops) {
        ByteArrayOutputStream script = new ByteArrayOutputStream();
        push(script, new byte[33]);
        script.write(0xad);
        for(int i = 0; i < drops; i++) {
            script.write(0x75);
        }
        script.write(0x51);
        return script.toByteArray();
    }

    private static Transaction spendingScriptSig(byte[] scriptSig, long value) {
        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.ZERO_HASH, 0, new Script(scriptSig));
        tx.addOutput(value, new Script(Utils.hexToBytes(P2PKH)));
        return tx;
    }

    /** "ord", one piece, content type, the countdown to 0 with its data, then signature and the partial lock script. */
    static byte[] doginalsScriptSig() {
        ByteArrayOutputStream sig = new ByteArrayOutputStream();
        push(sig, ascii("ord"));
        sig.write(0x51);
        push(sig, ascii("text/plain;charset=utf-8"));
        sig.write(0x00);
        push(sig, ascii("{\"p\":\"drc-20\",\"op\":\"transfer\",\"tick\":\"dogi\",\"amt\":\"1\"}"));
        push(sig, new byte[72]);
        push(sig, partialRedeemScript(5));
        return sig.toByteArray();
    }

    @Test
    public void doginalsRevealIsFound() {
        Transaction reveal = spendingScriptSig(doginalsScriptSig(), 100000);
        Assertions.assertTrue(CarrierScan.hasScriptSigEnvelope(reveal));
        Assertions.assertTrue(CarrierScan.marksOutputs(reveal));
    }

    /** A later transaction of a long inscription's chain has no "ord" push, only countdown chunks and the lock script. */
    @Test
    public void doginalsContinuationIsFound() {
        ByteArrayOutputStream sig = new ByteArrayOutputStream();
        sig.write(0x52);
        push(sig, new byte[200]);
        sig.write(0x51);
        push(sig, new byte[200]);
        push(sig, new byte[72]);
        push(sig, partialRedeemScript(4));
        Assertions.assertTrue(CarrierScan.hasScriptSigEnvelope(spendingScriptSig(sig.toByteArray(), 100000)));
    }

    @Test
    public void plainP2pkhSpendIsClean() {
        ByteArrayOutputStream sig = new ByteArrayOutputStream();
        push(sig, new byte[72]);
        push(sig, new byte[33]);
        Transaction tx = spendingScriptSig(sig.toByteArray(), 100000);
        Assertions.assertFalse(CarrierScan.hasScriptSigEnvelope(tx));
        Assertions.assertFalse(CarrierScan.marksOutputs(tx));
    }

    @Test
    public void multisigP2shSpendIsClean() {
        ByteArrayOutputStream redeem = new ByteArrayOutputStream();
        redeem.write(0x52);
        push(redeem, new byte[33]);
        push(redeem, new byte[33]);
        push(redeem, new byte[33]);
        redeem.write(0x53);
        redeem.write(0xae);
        ByteArrayOutputStream sig = new ByteArrayOutputStream();
        sig.write(0x00);
        push(sig, new byte[72]);
        push(sig, new byte[72]);
        push(sig, redeem.toByteArray());
        Assertions.assertFalse(CarrierScan.hasScriptSigEnvelope(spendingScriptSig(sig.toByteArray(), 100000)));
    }

    @Test
    public void taprootEnvelopeAndRunestoneMarkOutputs() {
        Transaction reveal = OrdinalsScanTest.spending(List.of(new byte[64], OrdinalsScanTest.inscriptionTapscript(), new byte[33]));
        Assertions.assertTrue(CarrierScan.marksOutputs(reveal));

        Transaction runes = OrdinalsScanTest.spending(List.of(new byte[72], new byte[33]));
        Assertions.assertFalse(CarrierScan.marksOutputs(runes));
        runes.addOutput(0, new Script(Utils.hexToBytes("6a5d00")));
        Assertions.assertTrue(CarrierScan.marksOutputs(runes));
    }

    @Test
    public void dunestoneIsFound() {
        Transaction tx = spendingScriptSig(new byte[0], 100000);
        tx.addOutput(0, new Script(Utils.hexToBytes("6a04deadbeef")));
        Assertions.assertFalse(CarrierScan.hasDunestone(tx));
        tx.addOutput(0, new Script(Utils.hexToBytes("6a014400")));
        Assertions.assertTrue(CarrierScan.hasDunestone(tx));
        Assertions.assertTrue(CarrierScan.marksOutputs(tx));
    }

    @Test
    public void markedTransactionFlagsEveryOutputButOpReturn() {
        Transaction reveal = spendingScriptSig(doginalsScriptSig(), 100000);
        reveal.addOutput(5000000, new Script(Utils.hexToBytes(P2PKH)));
        reveal.addOutput(0, new Script(Utils.hexToBytes("6a00")));
        Assertions.assertTrue(CarrierScan.mayCarry(Coin.DOGECOIN, reveal, 0, 100000));
        Assertions.assertTrue(CarrierScan.mayCarry(Coin.DOGECOIN, reveal, 1, 5000000));
        Assertions.assertFalse(CarrierScan.mayCarry(Coin.DOGECOIN, reveal, 2, 0));
    }

    /** A transferred inscription leaves no trace in the transaction that delivers it, so its usual value is the hint. */
    @Test
    public void postageSizedOutputMayCarry() {
        ByteArrayOutputStream sig = new ByteArrayOutputStream();
        push(sig, new byte[72]);
        push(sig, new byte[33]);
        Transaction plain = spendingScriptSig(sig.toByteArray(), 100000);
        Assertions.assertTrue(CarrierScan.mayCarry(Coin.DOGECOIN, plain, 0, 100000));
        Assertions.assertTrue(CarrierScan.mayCarry(Coin.PEPECOIN, plain, 0, 100000));
        Assertions.assertTrue(CarrierScan.mayCarry(Coin.JUNKCOIN, plain, 0, 100000));
        Assertions.assertTrue(CarrierScan.mayCarry(Coin.LITECOIN, null, 0, 10000));
        Assertions.assertTrue(CarrierScan.mayCarry(Coin.BELLSCOIN, null, 0, 1000));
        Assertions.assertTrue(CarrierScan.mayCarry(Coin.BELLSCOIN, null, 0, 100000));
        Assertions.assertFalse(CarrierScan.mayCarry(Coin.DOGECOIN, plain, 0, 100001));
        Assertions.assertFalse(CarrierScan.mayCarry(Coin.LITECOIN, null, 0, 100000));
        Assertions.assertFalse(CarrierScan.mayCarry(Coin.LUCKYCOIN, null, 0, 100000));
        Assertions.assertFalse(CarrierScan.mayCarry(Coin.DINGOCOIN, null, 0, 100000000));
    }

    /** Real mainnet inscriptions and runes, and a Dingo NFT listing that must not flag; shared with the mobile app's tests. */
    @Test
    public void mainnetVectorsMarkTheirOutputs() throws Exception {
        try(java.io.InputStream in = CarrierScanTest.class.getResourceAsStream("carrier-vectors.txt")) {
            List<String[]> vectors = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#")).map(line -> line.split(" ")).toList();
            Assertions.assertEquals(10, vectors.size());
            for(String[] vector : vectors) {
                String name = vector[0] + " " + vector[1];
                Transaction tx = new Transaction(Utils.hexToBytes(vector[4]));
                Assertions.assertEquals(vector[2], tx.getTxId().toString(), name);
                Assertions.assertEquals(Boolean.parseBoolean(vector[3]), CarrierScan.marksOutputs(tx), name);
            }
        }
    }

    /** An ordinary spend from the same chains carries nothing and has no postage-sized output. */
    @Test
    public void plainMainnetSpendIsClean() {
        //Litecoin 1-in 2-out P2WPKH payment
        Transaction tx = new Transaction(Utils.hexToBytes(PLAIN_LTC));
        Assertions.assertFalse(CarrierScan.marksOutputs(tx));
        for(int i = 0; i < tx.getOutputs().size(); i++) {
            Assertions.assertFalse(CarrierScan.mayCarry(Coin.LITECOIN, tx, i, tx.getOutputs().get(i).getValue()));
        }
    }
}
