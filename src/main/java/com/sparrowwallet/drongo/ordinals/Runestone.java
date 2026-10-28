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
 * Builds a runestone, the OP_RETURN OP_13 output that tells the runes protocol where a transaction's runes go: an
 * etching (a new rune), a mint (of an existing rune's open terms), a pointer (where unallocated runes go) and edicts
 * (an amount of a rune to an output). Fields are written in the order ord's encipher uses (etching fields, mint,
 * pointer, then the body), with edicts sorted by rune id and delta-encoded. A runestone that ord cannot parse is a cenotaph and burns every rune on the inputs, so the
 * payload is pushed as plain data pushes only.
 */
public final class Runestone {
    private static final BigInteger TAG_BODY = BigInteger.ZERO;
    private static final BigInteger TAG_DIVISIBILITY = BigInteger.valueOf(1);
    private static final BigInteger TAG_FLAGS = BigInteger.valueOf(2);
    private static final BigInteger TAG_SPACERS = BigInteger.valueOf(3);
    private static final BigInteger TAG_RUNE = BigInteger.valueOf(4);
    private static final BigInteger TAG_SYMBOL = BigInteger.valueOf(5);
    private static final BigInteger TAG_PREMINE = BigInteger.valueOf(6);
    private static final BigInteger TAG_CAP = BigInteger.valueOf(8);
    private static final BigInteger TAG_AMOUNT = BigInteger.valueOf(10);
    private static final BigInteger TAG_HEIGHT_START = BigInteger.valueOf(12);
    private static final BigInteger TAG_HEIGHT_END = BigInteger.valueOf(14);
    private static final BigInteger TAG_OFFSET_START = BigInteger.valueOf(16);
    private static final BigInteger TAG_OFFSET_END = BigInteger.valueOf(18);
    private static final BigInteger TAG_MINT = BigInteger.valueOf(20);
    private static final BigInteger TAG_POINTER = BigInteger.valueOf(22);
    private static final int FLAG_ETCHING = 1;
    private static final int FLAG_TERMS = 2;
    private static final int FLAG_TURBO = 4;
    private static final int MAX_DIVISIBILITY = 38;
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

    /** Open mint terms: an amount per mint, at most cap mints, within absolute heights and heights relative to the etching. */
    public record Terms(BigInteger amount, BigInteger cap, Long heightStart, Long heightEnd, Long offsetStart, Long offsetEnd) {}

    /**
     * A new rune. The total supply (premine plus cap times amount) must fit in 128 bits, or ord treats the runestone as a
     * cenotaph and the etching is lost.
     */
    public record Etching(RuneName name, int divisibility, Integer symbol, BigInteger premine, Terms terms, boolean turbo) {
        public Etching {
            if(name == null) {
                throw new IllegalArgumentException("An etching needs a rune name");
            }
            if(divisibility < 0 || divisibility > MAX_DIVISIBILITY) {
                throw new IllegalArgumentException("Divisibility must be between 0 and " + MAX_DIVISIBILITY);
            }
            if(symbol != null && !Character.isValidCodePoint(symbol)) {
                throw new IllegalArgumentException("Invalid rune symbol");
            }
            BigInteger supply = premine == null ? BigInteger.ZERO : premine;
            if(terms != null && terms.amount() != null && terms.cap() != null) {
                supply = supply.add(terms.amount().multiply(terms.cap()));
            }
            if(supply.signum() < 0 || supply.compareTo(U128_LIMIT) >= 0) {
                throw new IllegalArgumentException("The rune's total supply is too large");
            }
        }
    }

    public static Script script(List<Edict> edicts, Integer pointer) {
        return script(edicts, pointer, null, null);
    }

    public static Script script(List<Edict> edicts, Integer pointer, Etching etching, RuneId mint) {
        List<ScriptChunk> chunks = new ArrayList<>();
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_RETURN));
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_13));
        byte[] payload = payload(edicts, pointer, etching, mint);
        for(int i = 0; i < payload.length; i += MAX_PUSH) {
            byte[] push = Arrays.copyOfRange(payload, i, Math.min(payload.length, i + MAX_PUSH));
            int opcode = push.length <= 75 ? push.length : (push.length <= 0xff ? ScriptOpCodes.OP_PUSHDATA1 : ScriptOpCodes.OP_PUSHDATA2);
            chunks.add(new ScriptChunk(opcode, push));
        }
        return new Script(chunks);
    }

    public static byte[] payload(List<Edict> edicts, Integer pointer) {
        return payload(edicts, pointer, null, null);
    }

    public static byte[] payload(List<Edict> edicts, Integer pointer, Etching etching, RuneId mint) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if(etching != null) {
            int flags = FLAG_ETCHING | (etching.terms() != null ? FLAG_TERMS : 0) | (etching.turbo() ? FLAG_TURBO : 0);
            field(out, TAG_FLAGS, BigInteger.valueOf(flags));
            field(out, TAG_RUNE, etching.name().value());
            if(etching.divisibility() > 0) {
                field(out, TAG_DIVISIBILITY, BigInteger.valueOf(etching.divisibility()));
            }
            if(etching.name().spacers() > 0) {
                field(out, TAG_SPACERS, BigInteger.valueOf(etching.name().spacers()));
            }
            if(etching.symbol() != null) {
                field(out, TAG_SYMBOL, BigInteger.valueOf(etching.symbol()));
            }
            if(etching.premine() != null && etching.premine().signum() > 0) {
                field(out, TAG_PREMINE, etching.premine());
            }
            Terms terms = etching.terms();
            if(terms != null) {
                field(out, TAG_AMOUNT, terms.amount());
                field(out, TAG_CAP, terms.cap());
                field(out, TAG_HEIGHT_START, terms.heightStart() == null ? null : BigInteger.valueOf(terms.heightStart()));
                field(out, TAG_HEIGHT_END, terms.heightEnd() == null ? null : BigInteger.valueOf(terms.heightEnd()));
                field(out, TAG_OFFSET_START, terms.offsetStart() == null ? null : BigInteger.valueOf(terms.offsetStart()));
                field(out, TAG_OFFSET_END, terms.offsetEnd() == null ? null : BigInteger.valueOf(terms.offsetEnd()));
            }
        }
        if(mint != null) {
            field(out, TAG_MINT, BigInteger.valueOf(mint.block()));
            field(out, TAG_MINT, BigInteger.valueOf(mint.tx()));
        }
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

    private static void field(ByteArrayOutputStream out, BigInteger tag, BigInteger value) {
        if(value != null) {
            out.writeBytes(leb128(tag));
            out.writeBytes(leb128(value));
        }
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
