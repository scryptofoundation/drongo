package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionInput;
import com.sparrowwallet.drongo.protocol.TransactionOutput;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordinal theory's first in, first out flow through one transaction: the inputs' units are laid end to end in input
 * order, the outputs take them in output order, and what is left is the fee.
 */
public final class UnitFlow {
    private UnitFlow() {
    }

    /** The pieces on the outpoints tx spends that a piece on one of its outputs (or its fee) came from, in input order. */
    public static List<Piece> back(Transaction tx, List<Long> inputValues, Piece piece) {
        checkValues(tx, inputValues);
        UnitRange flow = toFlow(tx, inputValues, piece.vout(), piece.range());
        List<Piece> pieces = new ArrayList<>();
        long inputStart = 0;
        for(int i = 0; i < tx.getInputs().size(); i++) {
            long value = inputValues.get(i);
            if(value > 0) {
                UnitRange input = new UnitRange(inputStart, inputStart + value);
                UnitRange shared = input.intersect(flow);
                if(shared != null) {
                    TransactionInput txInput = tx.getInputs().get(i);
                    pieces.add(new Piece(txInput.getOutpoint().getHash(), (int)txInput.getOutpoint().getIndex(), shared.shift(-inputStart),
                            piece.coinOffset() + (shared.start() - flow.start())));
                }
            }
            inputStart += value;
        }
        return pieces;
    }

    /** Where a range on input inputIndex goes: pieces on tx's outputs, or on its fee (vout FEE). */
    public static List<Piece> forward(Transaction tx, List<Long> inputValues, int inputIndex, UnitRange range, long coinOffset) {
        checkValues(tx, inputValues);
        if(range.end() > inputValues.get(inputIndex)) {
            throw new IllegalArgumentException("Range " + range + " is past input " + inputIndex + "'s value");
        }
        long inputStart = 0;
        for(int i = 0; i < inputIndex; i++) {
            inputStart += inputValues.get(i);
        }
        return place(tx, range.shift(inputStart), coinOffset);
    }

    /** Places a range of the transaction's flow on its outputs and fee. */
    static List<Piece> place(Transaction tx, UnitRange flow, long coinOffset) {
        Sha256Hash txid = tx.getTxId();
        List<Piece> pieces = new ArrayList<>();
        long outputStart = 0;
        List<TransactionOutput> outputs = tx.getOutputs();
        for(int i = 0; i < outputs.size(); i++) {
            long value = outputs.get(i).getValue();
            if(value > 0) {
                UnitRange shared = new UnitRange(outputStart, outputStart + value).intersect(flow);
                if(shared != null) {
                    pieces.add(new Piece(txid, i, shared.shift(-outputStart), coinOffset + (shared.start() - flow.start())));
                }
            }
            outputStart += value;
        }
        if(flow.end() > outputStart) {
            UnitRange fee = new UnitRange(Math.max(flow.start(), outputStart), flow.end());
            pieces.add(new Piece(txid, Piece.FEE, fee.shift(-outputStart), coinOffset + (fee.start() - flow.start())));
        }
        return pieces;
    }

    public static long fee(Transaction tx, List<Long> inputValues) {
        checkValues(tx, inputValues);
        return sum(inputValues) - outputTotal(tx);
    }

    public static long outputTotal(Transaction tx) {
        long total = 0;
        for(TransactionOutput output : tx.getOutputs()) {
            total += output.getValue();
        }
        return total;
    }

    /** A range on an output (or the fee) as a range of the transaction's flow. */
    static UnitRange toFlow(Transaction tx, List<Long> inputValues, int vout, UnitRange range) {
        long outputs = outputTotal(tx);
        if(vout == Piece.FEE) {
            long fee = sum(inputValues) - outputs;
            if(range.end() > fee) {
                throw new IllegalArgumentException("Range " + range + " is past the fee of " + fee);
            }
            return range.shift(outputs);
        }
        if(vout < 0 || vout >= tx.getOutputs().size()) {
            throw new IllegalArgumentException("No output " + vout);
        }
        long start = 0;
        for(int i = 0; i < vout; i++) {
            start += tx.getOutputs().get(i).getValue();
        }
        if(range.end() > tx.getOutputs().get(vout).getValue()) {
            throw new IllegalArgumentException("Range " + range + " is past output " + vout + "'s value");
        }
        return range.shift(start);
    }

    private static void checkValues(Transaction tx, List<Long> inputValues) {
        if(inputValues.size() != tx.getInputs().size()) {
            throw new IllegalArgumentException("Need one value per input");
        }
        if(sum(inputValues) < outputTotal(tx)) {
            throw new IllegalArgumentException("The inputs hold less than the outputs");
        }
    }

    private static long sum(List<Long> values) {
        long total = 0;
        for(long value : values) {
            total += value;
        }
        return total;
    }
}
