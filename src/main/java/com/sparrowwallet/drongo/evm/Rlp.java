package com.sparrowwallet.drongo.evm;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;

/**
 * Recursive Length Prefix encoding, the serialisation EVM transactions are signed and broadcast in.
 *
 * Items are byte arrays, non-negative integers (encoded big-endian with no leading zeros, zero as the
 * empty string) and lists of items.
 */
public final class Rlp {
    private Rlp() {}

    public static byte[] encode(Object item) {
        if(item instanceof byte[] bytes) {
            return encodeBytes(bytes);
        }
        if(item instanceof BigInteger value) {
            return encodeBytes(toMinimalBytes(value));
        }
        if(item instanceof Long value) {
            return encode(BigInteger.valueOf(value));
        }
        if(item instanceof Integer value) {
            return encode(BigInteger.valueOf(value));
        }
        if(item instanceof List<?> list) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for(Object element : list) {
                body.writeBytes(encode(element));
            }
            byte[] payload = body.toByteArray();
            return concat(header(0xc0, payload.length), payload);
        }

        throw new IllegalArgumentException("Cannot RLP-encode " + (item == null ? "null" : item.getClass().getName()));
    }

    static byte[] toMinimalBytes(BigInteger value) {
        if(value.signum() < 0) {
            throw new IllegalArgumentException("RLP integers must be non-negative");
        }
        if(value.signum() == 0) {
            return new byte[0];
        }
        byte[] bytes = value.toByteArray();
        if(bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static byte[] encodeBytes(byte[] bytes) {
        if(bytes.length == 1 && (bytes[0] & 0xff) < 0x80) {
            return bytes;
        }
        return concat(header(0x80, bytes.length), bytes);
    }

    private static byte[] header(int offset, int length) {
        if(length < 56) {
            return new byte[] {(byte)(offset + length)};
        }
        byte[] lengthBytes = toMinimalBytes(BigInteger.valueOf(length));
        return concat(new byte[] {(byte)(offset + 55 + lengthBytes.length)}, lengthBytes);
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
