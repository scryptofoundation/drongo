package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class UnitFlowTest {
    static final Sha256Hash PREV0 = Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000a00");
    static final Sha256Hash PREV1 = Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000b00");

    /** A transaction spending output 0 of each given previous txid, paying the given output values. */
    static Transaction tx(List<Sha256Hash> prevs, long... outputs) {
        Transaction tx = new Transaction();
        for(Sha256Hash prev : prevs) {
            tx.addInput(prev, 0, new Script(new byte[0]));
        }
        for(long value : outputs) {
            tx.addOutput(value, new Script(new byte[] {0x51}));
        }
        return tx;
    }

    private final Transaction tx = tx(List.of(PREV0, PREV1), 12_000, 2_000);
    private final List<Long> inputs = List.of(10_000L, 5_000L);

    @Test
    public void backOutputOneLandsInSecondInput() {
        List<Piece> pieces = UnitFlow.back(tx, inputs, new Piece(tx.getTxId(), 1, new UnitRange(0, 2000), 7));
        Assertions.assertEquals(List.of(new Piece(PREV1, 0, new UnitRange(2000, 4000), 7)), pieces);
    }

    @Test
    public void backAcrossTwoInputs() {
        List<Piece> pieces = UnitFlow.back(tx, inputs, new Piece(tx.getTxId(), 0, new UnitRange(9000, 11000), 100));
        Assertions.assertEquals(List.of(new Piece(PREV0, 0, new UnitRange(9000, 10000), 100),
                new Piece(PREV1, 0, new UnitRange(0, 1000), 1100)), pieces);
    }

    @Test
    public void backFee() {
        List<Piece> pieces = UnitFlow.back(tx, inputs, new Piece(tx.getTxId(), Piece.FEE, new UnitRange(0, 1000), 0));
        Assertions.assertEquals(List.of(new Piece(PREV1, 0, new UnitRange(4000, 5000), 0)), pieces);
    }

    @Test
    public void forwardToOutputAndFee() {
        Assertions.assertEquals(List.of(new Piece(tx.getTxId(), 0, new UnitRange(9000, 10000), 3)),
                UnitFlow.forward(tx, inputs, 0, new UnitRange(9000, 10000), 3));
        Assertions.assertEquals(List.of(new Piece(tx.getTxId(), Piece.FEE, new UnitRange(500, 1000), 0)),
                UnitFlow.forward(tx, inputs, 1, new UnitRange(4500, 5000), 0));
    }

    @Test
    public void rangePastOutputRefused() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> UnitFlow.back(tx, inputs, new Piece(tx.getTxId(), 1, new UnitRange(1000, 3000), 0)));
        Assertions.assertEquals(1000, UnitFlow.fee(tx, inputs));
    }

    /** ord-litecoin index.rs list_fee_paying_transaction_range: 50 LTC split in two with a fee of 10. */
    @Test
    public void ordFeePayingTransactionRange() {
        Transaction split = tx(List.of(PREV0), 2_499_999_995L, 2_499_999_995L);
        List<Long> in = List.of(5_000_000_000L);
        Assertions.assertEquals(List.of(new Piece(PREV0, 0, new UnitRange(2_499_999_995L, 4_999_999_990L), 0)),
                UnitFlow.back(split, in, new Piece(split.getTxId(), 1, new UnitRange(0, 2_499_999_995L), 0)));
        Assertions.assertEquals(List.of(new Piece(PREV0, 0, new UnitRange(4_999_999_990L, 5_000_000_000L), 0)),
                UnitFlow.back(split, in, new Piece(split.getTxId(), Piece.FEE, new UnitRange(0, 10), 0)));
    }

    @Test
    public void unitRangeBasics() {
        UnitRange a = new UnitRange(0, 10);
        Assertions.assertEquals(10, a.length());
        Assertions.assertTrue(a.overlaps(new UnitRange(9, 20)));
        Assertions.assertFalse(a.overlaps(new UnitRange(10, 20)));
        Assertions.assertEquals(new UnitRange(5, 10), a.intersect(new UnitRange(5, 20)));
        Assertions.assertNull(a.intersect(new UnitRange(10, 20)));
        Assertions.assertEquals(new UnitRange(5, 15), a.shift(5));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new UnitRange(5, 5));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new UnitRange(-1, 5));
    }
}
