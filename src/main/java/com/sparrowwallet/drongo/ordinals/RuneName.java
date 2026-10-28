package com.sparrowwallet.drongo.ordinals;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * A rune's name as ord encodes it: the letters A to Z as a bijective base-26 number below 2^128, with spacers as a
 * bit field where bit i means a spacer after the i-th letter. Spacers are written as {@code •} (a {@code .} is read
 * as one too).
 */
public record RuneName(BigInteger value, int spacers) {
    public static final char SPACER = '•';
    private static final BigInteger MAX = BigInteger.TWO.pow(128).subtract(BigInteger.ONE);
    private static final BigInteger BASE = BigInteger.valueOf(26);

    public RuneName {
        if(value.signum() < 0 || value.compareTo(MAX) > 0) {
            throw new IllegalArgumentException("A rune name must be below 2^128");
        }
    }

    public static RuneName parse(String spaced) {
        if(spaced == null || spaced.isEmpty()) {
            throw new IllegalArgumentException("A rune name cannot be empty");
        }
        StringBuilder letters = new StringBuilder();
        int spacers = 0;
        boolean lastWasSpacer = false;
        for(char c : spaced.toCharArray()) {
            if(c >= 'A' && c <= 'Z') {
                letters.append(c);
                lastWasSpacer = false;
            } else if(c == SPACER || c == '.') {
                if(letters.isEmpty()) {
                    throw new IllegalArgumentException("A rune name cannot start with a spacer");
                }
                if(lastWasSpacer) {
                    throw new IllegalArgumentException("A rune name cannot have two spacers in a row");
                }
                if(letters.length() > 32) {
                    throw new IllegalArgumentException("Too many letters before a spacer");
                }
                spacers |= 1 << (letters.length() - 1);
                lastWasSpacer = true;
            } else {
                throw new IllegalArgumentException("A rune name has only the capital letters A to Z and spacers, not '" + c + "'");
            }
        }
        if(lastWasSpacer) {
            throw new IllegalArgumentException("A rune name cannot end with a spacer");
        }

        BigInteger value = BigInteger.ZERO;
        for(int i = 0; i < letters.length(); i++) {
            if(i > 0) {
                value = value.add(BigInteger.ONE);
            }
            value = value.multiply(BASE).add(BigInteger.valueOf(letters.charAt(i) - 'A'));
            if(value.compareTo(MAX) > 0) {
                throw new IllegalArgumentException("A rune name is too long");
            }
        }
        return new RuneName(value, spacers);
    }

    /** The name's letters without spacers. */
    public String letters() {
        StringBuilder letters = new StringBuilder();
        BigInteger n = value.add(BigInteger.ONE);
        while(n.signum() > 0) {
            BigInteger[] qr = n.subtract(BigInteger.ONE).divideAndRemainder(BASE);
            letters.append((char)('A' + qr[1].intValue()));
            n = qr[0];
        }
        return letters.reverse().toString();
    }

    /** The bytes the reveal's envelope must carry (tag 13) for an etching of this name: little-endian, trailing zeros trimmed. */
    public byte[] commitment() {
        byte[] bigEndian = value.toByteArray();
        byte[] littleEndian = new byte[bigEndian.length];
        for(int i = 0; i < bigEndian.length; i++) {
            littleEndian[i] = bigEndian[bigEndian.length - 1 - i];
        }
        int end = littleEndian.length;
        while(end > 0 && littleEndian[end - 1] == 0) {
            end--;
        }
        return Arrays.copyOf(littleEndian, end);
    }

    @Override
    public String toString() {
        String letters = letters();
        StringBuilder spaced = new StringBuilder();
        for(int i = 0; i < letters.length(); i++) {
            spaced.append(letters.charAt(i));
            if(i < letters.length() - 1 && (spacers & (1 << i)) != 0) {
                spaced.append(SPACER);
            }
        }
        return spaced.toString();
    }
}
