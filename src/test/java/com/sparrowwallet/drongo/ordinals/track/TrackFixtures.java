package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.ordinals.DoginalsMint;
import com.sparrowwallet.drongo.ordinals.Inscription;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Transactions for the tracker tests: plain spends, ord reveals and Doginals chains. */
final class TrackFixtures {
    static final ECKey KEY = ECKey.fromPrivate(Utils.hexToBytes("0404040404040404040404040404040404040404040404040404040404040404"));
    static final Script PLAIN = new Script(new byte[] {0x51});

    private TrackFixtures() {
    }

    static Sha256Hash hash(int n) {
        return Sha256Hash.wrap(String.format("%064x", n));
    }

    /** One input per entry of spends (txid, vout), with the given witness scripts (null for none), paying outputs. */
    static Transaction tx(List<TransactionOutPoint> spends, List<Script> tapscripts, long... outputs) {
        Transaction tx = new Transaction();
        tx.setVersion(2);
        for(int i = 0; i < spends.size(); i++) {
            TransactionOutPoint spend = spends.get(i);
            Script tapscript = tapscripts == null ? null : tapscripts.get(i);
            if(tapscript == null) {
                tx.addInput(spend.getHash(), spend.getIndex(), new Script(new byte[0]));
            } else {
                TransactionWitness witness = new TransactionWitness(tx, List.of(new byte[64], tapscript.getProgram(), new byte[33]));
                tx.addInput(spend.getHash(), spend.getIndex(), new Script(new byte[0]), witness);
            }
        }
        for(long value : outputs) {
            tx.addOutput(value, PLAIN);
        }
        return tx;
    }

    static TransactionOutPoint out(Sha256Hash txid, long vout) {
        return new TransactionOutPoint(txid, vout);
    }

    /** A plain spend of the given outpoints. */
    static Transaction spend(List<TransactionOutPoint> spends, long... outputs) {
        return tx(spends, null, outputs);
    }

    static Script envelope(String text) {
        return Inscription.text(text).revealScript(KEY);
    }

    /** An ord envelope with a pointer field (tag 2, little endian), built by hand. */
    static Script envelopeWithPointer(String text, long pointer) {
        List<ScriptChunk> chunks = new ArrayList<>();
        chunks.add(push(KEY.getPubKeyXCoord()));
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_CHECKSIG));
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_FALSE));
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_IF));
        chunks.add(push("ord".getBytes()));
        chunks.add(push(new byte[] {1}));
        chunks.add(push(Inscription.TEXT.getBytes()));
        chunks.add(push(new byte[] {2}));
        chunks.add(push(littleEndian(pointer)));
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_0));
        chunks.add(push(text.getBytes()));
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_ENDIF));
        return new Script(chunks);
    }

    /** An ord envelope with one extra field, its tag and value chunks given as written. */
    static Script envelopeWithFields(String text, ScriptChunk tag, ScriptChunk value) {
        List<ScriptChunk> chunks = new ArrayList<>();
        chunks.add(push(KEY.getPubKeyXCoord()));
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_CHECKSIG));
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_FALSE));
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_IF));
        chunks.add(push("ord".getBytes()));
        chunks.add(push(new byte[] {1}));
        chunks.add(push(Inscription.TEXT.getBytes()));
        chunks.add(tag);
        chunks.add(value);
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_0));
        chunks.add(push(text.getBytes()));
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_ENDIF));
        return new Script(chunks);
    }

    /** A direct push, never an OP_N, as ord envelopes write their tags. */
    static ScriptChunk push(byte[] data) {
        return new ScriptChunk(data.length, data);
    }

    static byte[] littleEndian(long value) {
        List<Byte> bytes = new ArrayList<>();
        while(value != 0) {
            bytes.add((byte)(value & 0xff));
            value >>>= 8;
        }
        byte[] out = new byte[bytes.size()];
        for(int i = 0; i < out.length; i++) {
            out[i] = bytes.get(i);
        }
        return out;
    }

    static Address destination() {
        return com.sparrowwallet.drongo.protocol.ScriptType.P2PKH.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(java.math.BigInteger.valueOf(42)));
    }

    static DoginalsMint.Chain doginalsChain(Coin coin, int bodyLength) {
        byte[] body = new byte[bodyLength];
        new Random(bodyLength).nextBytes(body);
        return new DoginalsMint.Chain("image/png", body, ECKey.fromPublicOnly(KEY), destination(), 1000, coin);
    }

    /** Every step of a Doginals chain, the first spending output 0 of funding. */
    static List<Transaction> doginalsSteps(DoginalsMint.Chain chain, Sha256Hash funding, long fundingValue) {
        List<Transaction> steps = new ArrayList<>();
        TransactionOutPoint previous = new TransactionOutPoint(funding, 0);
        long value = fundingValue;
        for(int s = 1; s <= chain.steps(); s++) {
            Transaction step = DoginalsMint.step(chain, s, previous, value, KEY);
            steps.add(step);
            previous = new TransactionOutPoint(step.getTxId(), 0);
            value = step.getOutputs().getFirst().getValue();
        }
        return steps;
    }
}
