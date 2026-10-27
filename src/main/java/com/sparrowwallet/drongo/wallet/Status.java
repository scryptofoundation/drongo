package com.sparrowwallet.drongo.wallet;

/**
 * The status of a wallet output. Stored as the ordinal, so new values are only ever appended.
 * PROTECTED marks an output that carries an inscription or runes; RELEASED marks one the user chose to spend as
 * plain coin, remembered so a later check does not protect it again.
 */
public enum Status {
    FROZEN, PROTECTED, RELEASED;

    public static boolean isSpendable(Status status) {
        return status == null || status == RELEASED;
    }
}
