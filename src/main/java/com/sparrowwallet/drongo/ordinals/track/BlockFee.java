package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.protocol.Sha256Hash;

/** A block transaction's fee, listed in block order. */
public record BlockFee(Sha256Hash txid, long fee) {
}