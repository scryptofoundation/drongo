package com.sparrowwallet.drongo.evm;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The slice of the Solidity ABI that token and bridge calls need: function selectors, static arguments (address,
 * uint256, bool, bytes32) and dynamic string/bytes arguments, and decoding of uint256, address, bool and string
 * results. Every decode is bounds-checked, since results come from a third-party node.
 */
public final class Abi {
    private static final int WORD = 32;
    private static final int MAX_DYNAMIC_LENGTH = 4096;

    private Abi() {}

    /** The 4-byte selector of a canonical signature such as "transfer(address,uint256)". */
    public static byte[] selector(String signature) {
        return Arrays.copyOf(Keccak.keccak256(signature.getBytes(StandardCharsets.US_ASCII)), 4);
    }

    /**
     * Encodes a call: selector then arguments. Supported argument types: EvmAddress, BigInteger (uint256),
     * Boolean, byte[] of exactly 32 (bytes32), String (dynamic string) and Dynamic (dynamic bytes).
     */
    public static byte[] encodeCall(String signature, Object... args) {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        head.writeBytes(selector(signature));
        int headSize = WORD * args.length;
        for(Object arg : args) {
            if(arg instanceof String text) {
                head.writeBytes(uint(BigInteger.valueOf(headSize + tail.size())));
                writeDynamic(tail, text.getBytes(StandardCharsets.UTF_8));
            } else if(arg instanceof Dynamic dynamic) {
                head.writeBytes(uint(BigInteger.valueOf(headSize + tail.size())));
                writeDynamic(tail, dynamic.bytes());
            } else {
                head.writeBytes(encodeStatic(arg));
            }
        }
        head.writeBytes(tail.toByteArray());
        return head.toByteArray();
    }

    /** Dynamic `bytes` argument (a plain byte[] means bytes32). */
    public record Dynamic(byte[] bytes) {}

    private static byte[] encodeStatic(Object arg) {
        if(arg instanceof EvmAddress address) {
            byte[] word = new byte[WORD];
            System.arraycopy(address.getBytes(), 0, word, WORD - 20, 20);
            return word;
        }
        if(arg instanceof BigInteger number) {
            return uint(number);
        }
        if(arg instanceof Boolean flag) {
            return uint(flag ? BigInteger.ONE : BigInteger.ZERO);
        }
        if(arg instanceof byte[] bytes && bytes.length == WORD) {
            return bytes.clone();
        }
        throw new IllegalArgumentException("Unsupported ABI argument: " + arg);
    }

    private static void writeDynamic(ByteArrayOutputStream out, byte[] bytes) {
        out.writeBytes(uint(BigInteger.valueOf(bytes.length)));
        out.writeBytes(bytes);
        int padding = (WORD - bytes.length % WORD) % WORD;
        out.writeBytes(new byte[padding]);
    }

    static byte[] uint(BigInteger value) {
        if(value.signum() < 0 || value.bitLength() > 256) {
            throw new IllegalArgumentException("uint256 out of range: " + value);
        }
        byte[] raw = value.toByteArray();
        byte[] word = new byte[WORD];
        int length = Math.min(raw.length, WORD);
        System.arraycopy(raw, raw.length - length, word, WORD - length, length);
        return word;
    }

    /** Word `index` of a result as an unsigned integer. */
    public static BigInteger decodeUint(byte[] result, int index) {
        return new BigInteger(1, word(result, index));
    }

    public static EvmAddress decodeAddress(byte[] result, int index) {
        byte[] word = word(result, index);
        for(int i = 0; i < WORD - 20; i++) {
            if(word[i] != 0) {
                throw new IllegalArgumentException("Not an address word");
            }
        }
        return EvmAddress.fromBytes(Arrays.copyOfRange(word, WORD - 20, WORD));
    }

    public static boolean decodeBool(byte[] result, int index) {
        BigInteger value = decodeUint(result, index);
        if(value.compareTo(BigInteger.ONE) > 0) {
            throw new IllegalArgumentException("Not a bool word");
        }
        return value.signum() == 1;
    }

    /**
     * A string result. Also accepts the bytes32 symbol/name that some early tokens return instead of a string,
     * trimmed of trailing zero bytes.
     */
    public static String decodeString(byte[] result) {
        if(result.length == WORD) {
            int end = WORD;
            while(end > 0 && result[end - 1] == 0) {
                end--;
            }
            return new String(result, 0, end, StandardCharsets.UTF_8);
        }
        BigInteger offset = decodeUint(result, 0);
        if(offset.bitLength() > 31 || offset.intValue() + WORD > result.length) {
            throw new IllegalArgumentException("String offset out of range");
        }
        int start = offset.intValue();
        BigInteger length = new BigInteger(1, Arrays.copyOfRange(result, start, start + WORD));
        if(length.bitLength() > 31 || length.intValue() > MAX_DYNAMIC_LENGTH || start + WORD + length.intValue() > result.length) {
            throw new IllegalArgumentException("String length out of range");
        }
        return new String(result, start + WORD, length.intValue(), StandardCharsets.UTF_8);
    }

    private static byte[] word(byte[] result, int index) {
        int start = index * WORD;
        if(result == null || start + WORD > result.length) {
            throw new IllegalArgumentException("Result too short for word " + index);
        }
        return Arrays.copyOfRange(result, start, start + WORD);
    }
}
