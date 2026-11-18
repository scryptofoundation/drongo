package com.sparrowwallet.drongo.ordinals.track;

/**
 * An inscription a transaction creates, on the unit at offset in output vout (or in its fee when vout is Piece.FEE). vout
 * ANYWHERE (offset -1) means the envelope's pointer could not be read, so the inscription may sit on any unit of the transaction.
 */
public record Creation(String inscriptionId, int vout, long offset) {
    public static final int ANYWHERE = -2;
}
