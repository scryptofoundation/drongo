package com.sparrowwallet.drongo.ordinals;

/**
 * A rune's id: the block and transaction index of the transaction that etched it, written block:tx.
 */
public record RuneId(long block, int tx) implements Comparable<RuneId> {
    public RuneId {
        if(block < 0 || tx < 0) {
            throw new IllegalArgumentException("Rune id parts cannot be negative");
        }
    }

    public static RuneId parse(String text) {
        if(text == null || !text.matches("\\d{1,18}:\\d{1,9}")) {
            throw new IllegalArgumentException("Not a rune id: " + text);
        }
        int colon = text.indexOf(':');
        return new RuneId(Long.parseLong(text.substring(0, colon)), Integer.parseInt(text.substring(colon + 1)));
    }

    @Override
    public int compareTo(RuneId other) {
        int byBlock = Long.compare(block, other.block);
        return byBlock != 0 ? byBlock : Integer.compare(tx, other.tx);
    }

    @Override
    public String toString() {
        return block + ":" + tx;
    }
}
