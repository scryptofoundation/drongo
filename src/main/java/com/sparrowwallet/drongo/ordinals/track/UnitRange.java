package com.sparrowwallet.drongo.ordinals.track;

/** A run of units (sats, koinu, photons) by offset, start included, end excluded. */
public record UnitRange(long start, long end) {
    public UnitRange {
        if(start < 0 || end <= start) {
            throw new IllegalArgumentException("Not a unit range: [" + start + ", " + end + ")");
        }
    }

    public long length() {
        return end - start;
    }

    public boolean overlaps(UnitRange other) {
        return start < other.end && other.start < end;
    }

    /** The units both ranges hold, or null when they share none. */
    public UnitRange intersect(UnitRange other) {
        long from = Math.max(start, other.start);
        long to = Math.min(end, other.end);
        return from < to ? new UnitRange(from, to) : null;
    }

    public UnitRange shift(long delta) {
        return new UnitRange(start + delta, end + delta);
    }

    @Override
    public String toString() {
        return start + "-" + end;
    }
}
