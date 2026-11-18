package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.ordinals.Dunestone.Decoded;
import com.sparrowwallet.drongo.ordinals.Dunestone.DuneId;
import com.sparrowwallet.drongo.ordinals.Dunestone.Edict;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

public class DunestoneTest {
    private static final String P2PKH = "76a914" + "11".repeat(20) + "88ac";
    //Dogecoin mainnet c04c331cff2af09baf4adf56cda4da6aa9733b4dac05e7ff18f4651cc7564a97: two edicts of ONEDUNE•ONELAMBO
    private static final String TRANSFER = "transfer-onedunelambo.txt";
    private static final DuneId ONEDUNE_ONELAMBO = new DuneId(5095523, 3);
    private static final List<Edict> TRANSFER_EDICTS = List.of(
            new Edict(ONEDUNE_ONELAMBO, BigInteger.valueOf(10000), 2),
            new Edict(ONEDUNE_ONELAMBO, BigInteger.valueOf(10000), 3));

    /** The transaction hex in a fixture under dunes/: the one line that is not a # comment. */
    private static Transaction fixture(String name) throws IOException {
        try(InputStream in = DunestoneTest.class.getResourceAsStream("dunes/" + name)) {
            Assertions.assertNotNull(in, name);
            String hex = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#")).findFirst().orElseThrow();
            return new Transaction(Utils.hexToBytes(hex));
        }
    }

    private static byte[] dunestoneScript(Transaction tx) {
        return tx.getOutputs().stream().map(TransactionOutput::getScriptBytes)
                .filter(script -> script.length > 2 && script[0] == 0x6a && script[1] == 0x01 && script[2] == 0x44)
                .findFirst().orElseThrow();
    }

    private static Transaction withOutputs(String... scriptHex) {
        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        for(String hex : scriptHex) {
            tx.addOutput(hex.startsWith("6a") ? 0 : 100000, new Script(Utils.hexToBytes(hex)));
        }
        return tx;
    }

    @Test
    public void decodesEtching() throws IOException {
        //Dogecoin mainnet cd0a877ea83f338b74c4f11d9be0104396bd6a357c0d05cf79318d758a42a570, BILLION•DOLLAR•DOG
        Decoded decoded = Dunestone.decode(fixture("etching-billiondollardog.txt")).orElseThrow();
        Assertions.assertEquals("BILLIONDOLLARDOG", decoded.etchedName());
        Assertions.assertEquals(1, decoded.divisibility());
        Assertions.assertFalse(decoded.cenotaph());
        Assertions.assertEquals(List.of(), decoded.edicts());
        Assertions.assertNull(decoded.pointer());
    }

    @Test
    public void decodesTransferWithTwoEdicts() throws IOException {
        Transaction tx = fixture(TRANSFER);
        Decoded decoded = Dunestone.decode(tx).orElseThrow();
        Assertions.assertFalse(decoded.cenotaph());
        Assertions.assertNull(decoded.etchedName());
        Assertions.assertEquals(TRANSFER_EDICTS, decoded.edicts());
        Assertions.assertEquals(1, decoded.pointer());
    }

    @Test
    public void scriptRoundTripsDecodedPayload() throws IOException {
        Transaction tx = fixture(TRANSFER);
        Decoded decoded = Dunestone.decode(tx).orElseThrow();
        Assertions.assertArrayEquals(dunestoneScript(tx), Dunestone.script(decoded.edicts(), decoded.pointer()).getProgram());
    }

    @Test
    public void packsDuneIds() {
        DuneId id = new DuneId(5084123, 7);
        Assertions.assertEquals(BigInteger.valueOf(5084123L << 16 | 7), id.packed());
        Assertions.assertEquals(id, DuneId.unpack(id.packed()));
        Assertions.assertEquals(id, DuneId.parse("5084123:7"));
        Assertions.assertEquals("5084123:7", id.toString());
        Assertions.assertThrows(IllegalArgumentException.class, () -> new DuneId(1, 0x10000));
        Assertions.assertThrows(IllegalArgumentException.class, () -> DuneId.parse("5084123"));
    }

    @Test
    public void encodesVarintsAsOrdWithDunes() {
        //varint.rs taproot_annex_format_bip_test_vectors
        Object[][] vectors = {{0L, "00"}, {1L, "01"}, {127L, "7f"}, {128L, "8000"}, {255L, "807f"}, {256L, "8100"},
                {16383L, "fe7f"}, {16384L, "ff00"}, {16511L, "ff7f"}, {65535L, "82fe7f"}, {1L << 32, "8efefeff00"}};
        for(Object[] vector : vectors) {
            Assertions.assertEquals(vector[1], Utils.bytesToHex(Dunestone.varint(BigInteger.valueOf((Long)vector[0]))));
        }
    }

    @Test
    public void deltaEncodesEdictIds() {
        DuneId first = new DuneId(5084000, 3);
        DuneId second = new DuneId(5084000, 9);
        byte[] payload = Dunestone.payload(List.of(new Edict(second, BigInteger.TWO, 1), new Edict(first, BigInteger.ONE, 0)), null);
        String expected = "00" + Utils.bytesToHex(Dunestone.varint(first.packed())) + "01" + "00" + "06" + "02" + "01";
        Assertions.assertEquals(expected, Utils.bytesToHex(payload));

        Transaction tx = withOutputs(P2PKH, P2PKH, Utils.bytesToHex(Dunestone.script(List.of(new Edict(first, BigInteger.ONE, 0), new Edict(second, BigInteger.TWO, 1)), 1).getProgram()));
        Decoded decoded = Dunestone.decode(tx).orElseThrow();
        Assertions.assertEquals(List.of(new Edict(first, BigInteger.ONE, 0), new Edict(second, BigInteger.TWO, 1)), decoded.edicts());
        Assertions.assertEquals(1, decoded.pointer());
        Assertions.assertFalse(decoded.cenotaph());
    }

    @Test
    public void unknownEvenTagIsCenotaph() {
        //Tag 24 (unknown, even) = 1, then an edict of 1 of dune 1:0 to output 0
        Decoded decoded = Dunestone.decode(withOutputs(P2PKH, "6a0144" + "08" + "1801" + "00" + "82ff00" + "01" + "00")).orElseThrow();
        Assertions.assertTrue(decoded.cenotaph());
        Assertions.assertEquals(List.of(new Edict(new DuneId(1, 0), BigInteger.ONE, 0)), decoded.edicts());
        //An unknown odd tag is not
        Assertions.assertFalse(Dunestone.decode(withOutputs(P2PKH, "6a0144" + "02" + "1901")).orElseThrow().cenotaph());
        //Deadline (tag 10) is not read, so it is an unknown even tag too
        Assertions.assertTrue(Dunestone.decode(withOutputs(P2PKH, "6a0144" + "02" + "0a01")).orElseThrow().cenotaph());
        //An edict to an output past the output count is, and so is an id of block 0 with a tx index
        Assertions.assertTrue(Dunestone.decode(withOutputs(P2PKH, "6a0144" + "06" + "0082ff000103")).orElseThrow().cenotaph());
        Assertions.assertTrue(Dunestone.decode(withOutputs(P2PKH, "6a0144" + "04" + "00010100")).orElseThrow().cenotaph());
        //Flags other than etching, terms and turbo are
        Assertions.assertTrue(Dunestone.decode(withOutputs(P2PKH, "6a0144" + "02" + "0208")).orElseThrow().cenotaph());
    }

    @Test
    public void noDunestoneIsEmpty() {
        Assertions.assertEquals(Optional.empty(), Dunestone.decode(withOutputs(P2PKH)));
        //A runestone is not a dunestone
        Assertions.assertEquals(Optional.empty(), Dunestone.decode(withOutputs(P2PKH, "6a5d0400010101")));
        Assertions.assertEquals(Optional.empty(), Dunestone.decode(withOutputs(P2PKH, "6a")));
        //A script error before or inside the dunestone hides it, as in ord-with-dunes
        Assertions.assertEquals(Optional.empty(), Dunestone.decode(withOutputs("04", "6a014400")));
        Assertions.assertEquals(Optional.empty(), Dunestone.decode(withOutputs(P2PKH, "6a014404")));
    }

    @Test
    public void scriptRejectsNegativeOutput() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> Dunestone.script(List.of(new Edict(new DuneId(5084000, 3), BigInteger.ONE, -1)), null));
    }
}
