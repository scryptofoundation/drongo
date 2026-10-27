package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionInput;
import com.sparrowwallet.drongo.protocol.TransactionOutput;

/**
 * Quick checks for whether a transaction could create inscriptions or move runes. They are byte scans, deliberately
 * conservative: a false positive only means the outputs are treated as unknown until an ord server has seen them.
 */
public final class OrdinalsScan {
    //OP_FALSE OP_IF OP_PUSHBYTES_3 "ord", the start of every inscription envelope
    private static final byte[] ENVELOPE = {0x00, 0x63, 0x03, 0x6f, 0x72, 0x64};

    private OrdinalsScan() {
    }

    public static boolean hasEnvelope(Transaction tx) {
        for(TransactionInput input : tx.getInputs()) {
            if(input.getWitness() == null) {
                continue;
            }
            for(byte[] push : input.getWitness().getPushes()) {
                if(contains(push, ENVELOPE)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True if any output is OP_RETURN OP_13, the runestone marker. */
    public static boolean hasRunestone(Transaction tx) {
        for(TransactionOutput output : tx.getOutputs()) {
            byte[] script = output.getScriptBytes();
            if(script.length >= 2 && script[0] == 0x6a && script[1] == 0x5d) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(byte[] data, byte[] pattern) {
        outer:
        for(int i = 0; i + pattern.length <= data.length; i++) {
            for(int j = 0; j < pattern.length; j++) {
                if(data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
