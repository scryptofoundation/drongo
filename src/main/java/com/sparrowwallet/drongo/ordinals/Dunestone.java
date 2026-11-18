package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptChunk;
import com.sparrowwallet.drongo.protocol.ScriptOpCodes;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A dunestone, the OP_RETURN output that tells the Dunes protocol (Dogecoin, sirduney/ord-with-dunes) where a
 * transaction's dunes go. It is OP_RETURN, a push of "D", then pushes whose concatenated bytes are a list of integers:
 * tag/value pairs, then tag 0 (Body) and edicts as (id delta, amount, output) triples. Unlike runes:
 * <ul>
 * <li>an integer is the big-endian varint of the Taproot annex BIP (each continuation byte carries an offset of one),
 * not LEB128;</li>
 * <li>an edict id is one integer, {@code block << 16 | tx}, delta-encoded against the previous edict's id;</li>
 * <li>the tag numbers differ (Pointer is 12), and a mint is an edict whose id has bit 48 set.</li>
 * </ul>
 * Decoding follows ord-with-dunes' {@code Dunestone::decipher}: the first output that is OP_RETURN then a push of "D"
 * holds the dunestone; a script error in an output before it, or in its own pushes, means no dunestone at all. A
 * dunestone that the indexer reads as a cenotaph burns every dune on the transaction's inputs.
 */
public final class Dunestone {
    private static final int TAG_BODY = 0;
    private static final int TAG_DIVISIBILITY = 1;
    private static final int TAG_FLAGS = 2;
    private static final int TAG_SPACERS = 3;
    private static final int TAG_DUNE = 4;
    private static final int TAG_SYMBOL = 5;
    private static final int TAG_LIMIT = 6;
    private static final int TAG_OFFSET_END = 8;
    private static final int TAG_POINTER = 12;
    private static final int TAG_HEIGHT_START = 14;
    private static final int TAG_OFFSET_START = 16;
    private static final int TAG_HEIGHT_END = 18;
    private static final int TAG_CAP = 20;
    private static final int TAG_PREMINE = 22;
    private static final int FLAG_ETCHING = 0;
    private static final int FLAG_TERMS = 1;
    private static final int FLAG_TURBO = 2;
    private static final int MAX_DIVISIBILITY = 38;
    private static final BigInteger U128_MAX = BigInteger.TWO.pow(128).subtract(BigInteger.ONE);
    private static final BigInteger U64_MAX = BigInteger.TWO.pow(64).subtract(BigInteger.ONE);
    private static final BigInteger MAX_LIMIT = U64_MAX;
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger TWO_POW_7 = BigInteger.valueOf(128);
    private static final byte[] PROTOCOL = {0x44};
    private static final int MAX_PUSH = 520;

    private Dunestone() {}

    /** A dune's id: the block and index of the transaction that etched it, written block:tx and packed as block << 16 | tx. */
    public record DuneId(long block, int tx) implements Comparable<DuneId> {
        public DuneId {
            if(block < 0 || tx < 0 || tx > 0xffff) {
                throw new IllegalArgumentException("Invalid dune id " + block + ":" + tx);
            }
        }

        public BigInteger packed() {
            return BigInteger.valueOf(block).shiftLeft(16).or(BigInteger.valueOf(tx));
        }

        public static DuneId unpack(BigInteger packed) {
            if(packed.signum() < 0 || packed.shiftRight(16).compareTo(LONG_MAX) > 0) {
                throw new IllegalArgumentException("Not a dune id: " + packed);
            }
            return new DuneId(packed.shiftRight(16).longValueExact(), packed.intValue() & 0xffff);
        }

        public static DuneId parse(String text) {
            if(text == null || !text.matches("\\d{1,18}:\\d{1,5}")) {
                throw new IllegalArgumentException("Not a dune id: " + text);
            }
            int colon = text.indexOf(':');
            return new DuneId(Long.parseLong(text.substring(0, colon)), Integer.parseInt(text.substring(colon + 1)));
        }

        @Override
        public int compareTo(DuneId other) {
            return packed().compareTo(other.packed());
        }

        @Override
        public String toString() {
            return block + ":" + tx;
        }
    }

    /**
     * An amount of a dune to an output. An output equal to the transaction's output count spreads the amount over every
     * output that is not an OP_RETURN.
     */
    public record Edict(DuneId id, BigInteger amount, int output) {
        public Edict {
            if(id == null || amount == null || amount.signum() < 0 || amount.compareTo(U128_MAX) > 0 || output < 0) {
                throw new IllegalArgumentException("Invalid edict");
            }
        }
    }

    /**
     * What a dunestone says. The edicts are the ones written in the payload (the indexer adds an edict of the premine to
     * output 1 when etching, which is not listed). etchedName and divisibility are null when there is no etching or the
     * etching leaves them out. A cenotaph burns every dune on the inputs.
     */
    public record Decoded(List<Edict> edicts, Integer pointer, String etchedName, Integer divisibility, boolean cenotaph) {}

    public static Script script(List<Edict> edicts, Integer pointer) {
        List<ScriptChunk> chunks = new ArrayList<>();
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_RETURN));
        chunks.add(new ScriptChunk(PROTOCOL.length, PROTOCOL));
        byte[] payload = payload(edicts, pointer);
        for(int i = 0; i < payload.length; i += MAX_PUSH) {
            byte[] push = Arrays.copyOfRange(payload, i, Math.min(payload.length, i + MAX_PUSH));
            int opcode = push.length <= 75 ? push.length : (push.length <= 0xff ? ScriptOpCodes.OP_PUSHDATA1 : ScriptOpCodes.OP_PUSHDATA2);
            chunks.add(new ScriptChunk(opcode, push));
        }
        return new Script(chunks);
    }

    /** The integers after the "D" push, as ord-with-dunes' encipher writes them: the pointer, then the sorted, delta-encoded edicts. */
    public static byte[] payload(List<Edict> edicts, Integer pointer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if(pointer != null) {
            if(pointer < 0) {
                throw new IllegalArgumentException("Pointer cannot be negative");
            }
            out.writeBytes(varint(BigInteger.valueOf(TAG_POINTER)));
            out.writeBytes(varint(BigInteger.valueOf(pointer)));
        }
        if(!edicts.isEmpty()) {
            out.writeBytes(varint(BigInteger.valueOf(TAG_BODY)));
            List<Edict> sorted = new ArrayList<>(edicts);
            sorted.sort(Comparator.comparing(Edict::id));
            BigInteger previous = BigInteger.ZERO;
            for(Edict edict : sorted) {
                BigInteger id = edict.id().packed();
                out.writeBytes(varint(id.subtract(previous)));
                out.writeBytes(varint(edict.amount()));
                out.writeBytes(varint(BigInteger.valueOf(edict.output())));
                previous = id;
            }
        }
        return out.toByteArray();
    }

    public static Optional<Decoded> decode(Transaction tx) {
        byte[] payload;
        try {
            payload = findPayload(tx);
        } catch(ScriptError e) {
            return Optional.empty();
        }
        if(payload == null) {
            return Optional.empty();
        }

        List<BigInteger> integers = integers(payload);
        int outputCount = tx.getOutputs().size();
        List<Edict> edicts = new ArrayList<>();
        Map<BigInteger, BigInteger> fields = new LinkedHashMap<>();
        boolean cenotaph = false;
        for(int i = 0; i < integers.size(); i += 2) {
            BigInteger tag = integers.get(i);
            if(tag.signum() == 0) {
                BigInteger id = BigInteger.ZERO;
                for(int j = i + 1; j + 3 <= integers.size(); j += 3) {
                    id = id.add(integers.get(j)).min(U128_MAX);
                    Edict edict = edict(id, integers.get(j + 1), integers.get(j + 2), outputCount);
                    if(edict == null) {
                        cenotaph = true;
                    } else {
                        edicts.add(edict);
                    }
                }
                break;
            }
            if(i + 1 >= integers.size()) {
                break;
            }
            fields.putIfAbsent(tag, integers.get(i + 1));
        }

        BigInteger pointerValue = take(fields, TAG_POINTER);
        Integer pointer = pointerValue != null && pointerValue.compareTo(INT_MAX) <= 0 ? pointerValue.intValueExact() : null;
        BigInteger divisibilityValue = take(fields, TAG_DIVISIBILITY);
        Integer divisibility = divisibilityValue != null && divisibilityValue.compareTo(BigInteger.valueOf(MAX_DIVISIBILITY)) <= 0 ? divisibilityValue.intValueExact() : null;
        BigInteger limit = take(fields, TAG_LIMIT);
        if(limit != null) {
            limit = limit.min(MAX_LIMIT);
        }
        BigInteger dune = take(fields, TAG_DUNE);
        BigInteger cap = take(fields, TAG_CAP);
        BigInteger premine = take(fields, TAG_PREMINE);
        for(int tag : new int[] {TAG_SPACERS, TAG_SYMBOL, TAG_HEIGHT_START, TAG_HEIGHT_END, TAG_OFFSET_START, TAG_OFFSET_END}) {
            take(fields, tag);
        }
        BigInteger flags = take(fields, TAG_FLAGS);
        if(flags == null) {
            flags = BigInteger.ZERO;
        }
        boolean etching = flags.testBit(FLAG_ETCHING);
        flags = flags.clearBit(FLAG_ETCHING).clearBit(FLAG_TERMS).clearBit(FLAG_TURBO);

        BigInteger supply = orZero(premine).add(orZero(cap).multiply(orZero(limit)));
        boolean overflow = orZero(cap).multiply(orZero(limit)).compareTo(U128_MAX) > 0 || supply.compareTo(U128_MAX) > 0;
        boolean unknownEvenTag = fields.keySet().stream().anyMatch(tag -> !tag.testBit(0));

        String etchedName = etching && dune != null ? new RuneName(dune, 0).letters() : null;
        return Optional.of(new Decoded(List.copyOf(edicts), pointer, etchedName, etching ? divisibility : null,
                cenotaph || overflow || flags.signum() != 0 || unknownEvenTag));
    }

    /** ord-with-dunes' Edict::from_integers: the id's block must fit 64 bits, block 0 only with tx 0, the output at most the output count. */
    private static Edict edict(BigInteger id, BigInteger amount, BigInteger output, int outputCount) {
        BigInteger block = id.shiftRight(16);
        if(block.compareTo(U64_MAX) > 0) {
            return null;
        }
        if(block.signum() == 0 && id.signum() > 0) {
            return null;
        }
        if(output.compareTo(BigInteger.valueOf(outputCount)) > 0) {
            return null;
        }
        if(block.compareTo(LONG_MAX) > 0) {
            //A valid id to ord-with-dunes, but beyond any real block; no dune can have it
            return null;
        }
        return new Edict(DuneId.unpack(id), amount, output.intValueExact());
    }

    private static BigInteger take(Map<BigInteger, BigInteger> fields, int tag) {
        return fields.remove(BigInteger.valueOf(tag));
    }

    private static BigInteger orZero(BigInteger value) {
        return value == null ? BigInteger.ZERO : value;
    }

    /** The concatenated pushes after OP_RETURN "D" in the first output that has them, or null when no output does. */
    private static byte[] findPayload(Transaction tx) throws ScriptError {
        for(TransactionOutput output : tx.getOutputs()) {
            byte[] script = output.getScriptBytes();
            int[] position = {0};
            Instruction first = next(script, position);
            if(first == null || first.push() != null || first.opcode() != ScriptOpCodes.OP_RETURN) {
                continue;
            }
            Instruction second = next(script, position);
            if(second == null || second.push() == null || !Arrays.equals(second.push(), PROTOCOL)) {
                continue;
            }
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            Instruction instruction;
            while((instruction = next(script, position)) != null) {
                if(instruction.push() != null) {
                    payload.writeBytes(instruction.push());
                }
            }
            return payload.toByteArray();
        }
        return null;
    }

    private record Instruction(int opcode, byte[] push) {}

    private static final class ScriptError extends Exception {}

    /** rust-bitcoin's non-minimal instruction reader: pushes (including OP_0 and OP_PUSHDATA1/2/4) or a bare opcode. */
    private static Instruction next(byte[] script, int[] position) throws ScriptError {
        int i = position[0];
        if(i >= script.length) {
            return null;
        }
        int opcode = script[i] & 0xff;
        i++;
        long length;
        if(opcode <= 75) {
            length = opcode;
        } else if(opcode == ScriptOpCodes.OP_PUSHDATA1 || opcode == ScriptOpCodes.OP_PUSHDATA2 || opcode == ScriptOpCodes.OP_PUSHDATA4) {
            int size = opcode == ScriptOpCodes.OP_PUSHDATA1 ? 1 : (opcode == ScriptOpCodes.OP_PUSHDATA2 ? 2 : 4);
            if(script.length - i < size) {
                throw new ScriptError();
            }
            length = 0;
            for(int b = 0; b < size; b++) {
                length |= (long)(script[i + b] & 0xff) << (8 * b);
            }
            i += size;
        } else {
            position[0] = i;
            return new Instruction(opcode, null);
        }
        if(script.length - i < length) {
            throw new ScriptError();
        }
        byte[] push = Arrays.copyOfRange(script, i, i + (int)length);
        position[0] = i + (int)length;
        return new Instruction(opcode, push);
    }

    /** ord-with-dunes' Dunestone::integers: varints until the payload ends, the last one possibly cut short. */
    private static List<BigInteger> integers(byte[] payload) {
        List<BigInteger> integers = new ArrayList<>();
        int i = 0;
        while(i < payload.length) {
            BigInteger n = BigInteger.ZERO;
            while(true) {
                if(i >= payload.length) {
                    break;
                }
                int b = payload[i] & 0xff;
                n = n.multiply(TWO_POW_7).min(U128_MAX);
                i++;
                if(b < 128) {
                    n = n.add(BigInteger.valueOf(b)).min(U128_MAX);
                    break;
                }
                n = n.add(BigInteger.valueOf(b - 127)).min(U128_MAX);
            }
            integers.add(n);
        }
        return integers;
    }

    /** ord-with-dunes' varint::encode_to_vec: big-endian groups of seven bits, every byte but the last with the high bit set and an offset of one. */
    static byte[] varint(BigInteger value) {
        if(value.signum() < 0 || value.compareTo(U128_MAX) > 0) {
            throw new IllegalArgumentException("Value out of range for a dunestone integer: " + value);
        }
        byte[] out = new byte[19];
        int i = 18;
        BigInteger n = value;
        out[i] = (byte)(n.intValue() & 0x7f);
        while(n.compareTo(BigInteger.valueOf(0x7f)) > 0) {
            n = n.shiftRight(7).subtract(BigInteger.ONE);
            i--;
            out[i] = (byte)((n.intValue() & 0x7f) | 0x80);
        }
        return Arrays.copyOfRange(out, i, 19);
    }
}
