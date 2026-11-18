package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.protocol.Sha256Hash;

/**
 * An inscription on a coin: its id, the offset of its unit in the coin (-1 when unknown), its number (null unless an
 * indexer gave one), and the transaction, height and time it was minted in.
 */
public record Found(String inscriptionId, long offset, Long number, Sha256Hash mintTxid, int mintHeight, long mintTime) {
    public Found withOffset(long newOffset) {
        return new Found(inscriptionId, newOffset, number, mintTxid, mintHeight, mintTime);
    }
}