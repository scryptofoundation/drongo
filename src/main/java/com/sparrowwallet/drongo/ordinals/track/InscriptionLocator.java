package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.protocol.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.sparrowwallet.drongo.protocol.ScriptOpCodes.*;

/**
 * Finds the units a transaction puts inscriptions on, by the rules the indexers follow. Errs towards finding one: an
 * envelope that starts but does not parse still counts, as ord counts malformed envelopes.
 */
public final class InscriptionLocator {
    //OP_FALSE OP_IF OP_PUSHBYTES_3 "ord"
    private static final byte[] ENVELOPE = {0x00, 0x63, 0x03, 0x6f, 0x72, 0x64};
    private static final byte[] ORD = "ord".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TAG_POINTER = {2};

    private InscriptionLocator() {
    }

    /**
     * The inscriptions an ord reveal (Litecoin, Bellscoin) creates, numbered across the transaction in input then
     * envelope order. Each sits on the unit its pointer names when that is below the outputs' total, otherwise on the
     * first unit of the input that carries it.
     */
    public static List<Creation> ord(Transaction tx, List<Long> inputValues) {
        String txid = tx.getTxId().toString();
        long outputs = UnitFlow.outputTotal(tx);
        long total = 0;
        for(long value : inputValues) {
            total += value;
        }

        List<Creation> creations = new ArrayList<>();
        long inputStart = 0;
        for(int i = 0; i < tx.getInputs().size(); i++) {
            TransactionInput input = tx.getInputs().get(i);
            if(input.getWitness() != null) {
                for(byte[] push : input.getWitness().getPushes()) {
                    if(!contains(push, ENVELOPE)) {
                        continue;
                    }
                    for(Long pointer : envelopePointers(push)) {
                        if(pointer != null && pointer == UNREADABLE) {
                            creations.add(new Creation(txid + "i" + creations.size(), Creation.ANYWHERE, -1));
                            continue;
                        }
                        long flowOffset = pointer != null && pointer < outputs ? pointer : inputStart;
                        if(flowOffset >= total) {
                            continue;
                        }
                        Piece placed = UnitFlow.place(tx, new UnitRange(flowOffset, flowOffset + 1), 0).getFirst();
                        creations.add(new Creation(txid + "i" + creations.size(), placed.vout(), placed.range().start()));
                    }
                }
            }
            inputStart += inputValues.get(i);
        }
        return creations;
    }

    /** The ids of the inscriptions an ord reveal creates, in the same order as ord(), without needing input values. */
    public static List<String> ordIds(Transaction tx) {
        String txid = tx.getTxId().toString();
        List<String> ids = new ArrayList<>();
        for(TransactionInput input : tx.getInputs()) {
            if(input.getWitness() == null) {
                continue;
            }
            for(byte[] push : input.getWitness().getPushes()) {
                if(contains(push, ENVELOPE)) {
                    for(int i = envelopePointers(push).size(); i > 0; i--) {
                        ids.add(txid + "i" + ids.size());
                    }
                }
            }
        }
        return ids;
    }

    /**
     * One entry per envelope in a witness push: its pointer, or null when it has none. When the push does not parse
     * as a script, or parses without a whole envelope, each envelope prefix found in the bytes counts once, pointerless.
     */
    private static List<Long> envelopePointers(byte[] push) {
        List<Long> pointers = new ArrayList<>();
        try {
            List<ScriptChunk> chunks = new Script(push).getChunks();
            for(int i = 0; i + 2 < chunks.size(); i++) {
                if(chunks.get(i).opcode == OP_0 && chunks.get(i + 1).opcode == OP_IF && Arrays.equals(chunks.get(i + 2).data, ORD)) {
                    pointers.add(pointer(chunks, i + 3));
                }
            }
        } catch(Exception e) {
            pointers.clear();
        }
        if(pointers.isEmpty()) {
            for(int i = 0; i + ENVELOPE.length <= push.length; i++) {
                if(Arrays.equals(push, i, i + ENVELOPE.length, ENVELOPE, 0, ENVELOPE.length)) {
                    pointers.add(null);
                }
            }
        }
        return pointers;
    }

    //A pointer field that is present but cannot be placed here: the inscription may sit anywhere in the transaction
    static final long UNREADABLE = -1;

    /**
     * The pointer among an envelope's fields (tag, value pairs up to the body's OP_0 or OP_ENDIF) as ord reads it: the
     * first pointer tag counts, tags and values written as OP_1 to OP_16 are the bytes [n], and bytes past the eighth must
     * be zero. Null without a pointer, UNREADABLE when the first pointer's value is not a u64 this code can place.
     */
    private static Long pointer(List<ScriptChunk> chunks, int from) {
        for(int i = from; i + 1 < chunks.size(); i += 2) {
            ScriptChunk tag = chunks.get(i);
            if(tag.opcode == OP_0 || tag.opcode == OP_ENDIF) {
                break;
            }
            if(!Arrays.equals(bytes(tag), TAG_POINTER)) {
                continue;
            }
            byte[] value = bytes(chunks.get(i + 1));
            if(value == null) {
                return UNREADABLE;
            }
            for(int b = 8; b < value.length; b++) {
                if(value[b] != 0) {
                    return UNREADABLE;
                }
            }
            long read = 0;
            for(int b = Math.min(value.length, 8) - 1; b >= 0; b--) {
                read = (read << 8) | (value[b] & 0xff);
            }
            return read < 0 ? UNREADABLE : read;
        }
        return null;
    }

    /** A push's bytes, with OP_0 as no bytes and OP_1 to OP_16 as the single byte n; null for other opcodes. */
    private static byte[] bytes(ScriptChunk chunk) {
        if(chunk.opcode == OP_0) {
            return new byte[0];
        }
        if(chunk.opcode >= OP_1 && chunk.opcode <= OP_16) {
            return new byte[] {(byte)(chunk.opcode - OP_1 + 1)};
        }
        return chunk.data;
    }

    /** The Doginals step input 0 of the transaction reveals, if it is one. */
    public static Optional<DoginalsStep> doginals(Transaction tx) {
        if(tx.getInputs().isEmpty()) {
            return Optional.empty();
        }
        TransactionInput input = tx.getInputs().getFirst();
        List<ScriptChunk> chunks;
        try {
            chunks = input.getScriptSig().getChunks();
        } catch(Exception e) {
            return Optional.empty();
        }
        //The pushes alone decide, as ord-dogecoin reads them (it does not check the redeem script): the piece's pushes,
        //then a signature and the redeem script. A later step can only be told apart by walking back to an "ord" first
        //step, which the tracer does; anything shaped like a piece here is a candidate
        if(chunks.size() < 4 || chunks.getLast().data == null) {
            return Optional.empty();
        }
        List<ScriptChunk> piece = chunks.subList(0, chunks.size() - 2);
        boolean genesis = Arrays.equals(piece.getFirst().data, ORD);
        if(genesis ? piece.size() < 3 || piece.size() % 2 == 0 : piece.size() < 2 || piece.size() % 2 == 1) {
            return Optional.empty();
        }
        boolean last = number(piece.get(piece.size() - 2)) == 0;
        return Optional.of(new DoginalsStep(genesis, last, input.getOutpoint().getHash()));
    }

    /** A Doginals number push: OP_0, OP_1 to OP_16, or little-endian bytes. */
    static long number(ScriptChunk chunk) {
        if(chunk.opcode == OP_0) {
            return 0;
        }
        if(chunk.opcode >= OP_1 && chunk.opcode <= OP_16) {
            return chunk.opcode - OP_1 + 1;
        }
        if(chunk.data == null || chunk.data.length == 0) {
            return 0;
        }
        long value = 0;
        for(int b = Math.min(chunk.data.length, 8) - 1; b >= 0; b--) {
            value = (value << 8) | (chunk.data[b] & 0xff);
        }
        return value;
    }

    private static boolean contains(byte[] data, byte[] pattern) {
        for(int i = 0; i + pattern.length <= data.length; i++) {
            if(Arrays.equals(data, i, i + pattern.length, pattern, 0, pattern.length)) {
                return true;
            }
        }
        return false;
    }
}
