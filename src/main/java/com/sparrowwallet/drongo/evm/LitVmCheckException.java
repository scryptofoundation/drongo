package com.sparrowwallet.drongo.evm;

import java.io.IOException;

/**
 * A failure this library found itself, by a check of its own, rather than one a server reported: a caller can word it
 * by its {@link Reason} alone. The message is for the log; it can quote what a server or a contract answered, so it is
 * not for the screen.
 */
public final class LitVmCheckException extends IOException {
    public enum Reason {
        /** Creating the contract needs more gas than one transaction can use on the network. */
        CREATION_OVER_GAS_CAP,
        /** No pool of the venue can fill the swap. */
        NO_POOL,
        /** The address holds no contract. */
        NO_CONTRACT,
        /** The contract does not answer as an ERC-20 token. */
        NOT_A_TOKEN,
        /** The token's decimals are more than this library supports. */
        TOKEN_DECIMALS,
        /** The Base portal is paused. */
        PORTAL_PAUSED,
        /** A bridge's contracts do not report what is pinned here. */
        BRIDGE_MISMATCH,
        /** A swap venue's contracts do not report what is pinned here. */
        VENUE_MISMATCH
    }

    private final Reason reason;

    public LitVmCheckException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
