package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.protocol.Sha256Hash;

/**
 * Part of a traced coin, found on an output of some transaction (or in its fee when vout is FEE). range is by offset
 * within that output (or fee); coinOffset is the offset in the traced coin that range.start stands for.
 */
public record Piece(Sha256Hash txid, int vout, UnitRange range, long coinOffset) {
    public static final int FEE = -1;
}
