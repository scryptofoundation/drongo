package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptChunk;
import com.sparrowwallet.drongo.protocol.ScriptOpCodes;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Builds a runestone, the OP_RETURN OP_13 output that tells the runes protocol where a transaction's runes go. Only
 * the fields a transfer needs are written: a pointer (where unallocated runes go) and edicts (an amount of a rune to an
 * output). Fields are written in the order ord's encipher uses, pointer before the body, with edicts sorted by rune
 * id and delta-encoded. A runestone that ord cannot parse is a cenotaph and burns every rune on the inputs, so the
 * payload is pushed as plain data pushes only.
 */
public final class Runestone {
    private static final BigInteger TAG_BODY = BigInteger.ZERO;
    private static final BigInteger TAG_POINTER = BigInteger.valueOf(22);
    private static final BigInteger U128_LIMIT = BigInteger.TWO.pow(128);
    private static final int MAX_PUSH = 520;

    private Runestone() {}

    public record Edict(RuneId id, BigInteger amount, int output) {
        public Edict {
            if(amount.signum() < 0 || amount.compareTo(U128_LIMIT) >= 0 || output < 0) {
                throw new IllegalArgumentException("Invalid edict");
            }
        }
    }

    public static Script script(List<Edict> edicts, Integer pointer) {
        List<ScriptChunk> chunks = new ArrayList<>();
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_RETURN));
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_13));
        byte[] payload = payload(edicts, pointer);
        for(int i = 0; i < payload.length; i += MAX_PUSH) {
            byte[] push = Arrays.copyOfRange(payload, i, Math.min(payload.length, i + MAX_PUSH));
            int opcode = push.length <= 75 ? push.length : (push.length <= 0xff ? ScriptOpCodes.OP_PUSHDATA1 : ScriptOpCodes.OP_PUSHDATA2);
            chunks.add(new ScriptChunk(opcode, push));
        }
        return new Script(chunks);
    }

    public static byte[] payload(List<Edict> edicts, Integer pointer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if(pointer != null) {
            if(pointer < 0) {
                throw new IllegalArgumentException("Pointer cannot be negative");
            }
            out.writeBytes(leb128(TAG_POINTER));
            out.writeBytes(leb128(BigInteger.valueOf(pointer)));
        }
        if(!edicts.isEmpty()) {
            out.writeBytes(leb128(TAG_BODY));
            List<Edict> sorted = new ArrayList<>(edicts);
            sorted.sort(Comparator.comparing(Edict::id));
            RuneId previous = new RuneId(0, 0);
            for(Edict edict : sorted) {
                long blockDelta = edict.id().block() - previous.block();
                long txDelta = blockDelta == 0 ? edict.id().tx() - previous.tx() : edict.id().tx();
                out.writeBytes(leb128(BigInteger.valueOf(blockDelta)));
                out.writeBytes(leb128(BigInteger.valueOf(txDelta)));
                out.writeBytes(leb128(edict.amount()));
                out.writeBytes(leb128(BigInteger.valueOf(edict.output())));
                previous = edict.id();
            }
        }
        return out.toByteArray();
    }

    /** Unsigned LEB128, the varint runestones use, for values below 2^128. */
    public static byte[] leb128(BigInteger value) {
        if(value.signum() < 0 || value.compareTo(U128_LIMIT) >= 0) {
            throw new IllegalArgumentException("Value out of range for a runestone integer: " + value);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BigInteger rest = value;
        BigInteger mask = BigInteger.valueOf(0x7f);
        while(rest.compareTo(mask) > 0) {
            out.write(rest.and(mask).intValue() | 0x80);
            rest = rest.shiftRight(7);
        }
        out.write(rest.intValue());
        return out.toByteArray();
    }
}
