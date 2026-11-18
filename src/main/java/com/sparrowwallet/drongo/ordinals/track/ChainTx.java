package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;

/**
 * A transaction as the chain source has it: its height (0 when unconfirmed), block hash and time (null and 0 when
 * unconfirmed), and whether getting it took a server lookup (false for the wallet's own transactions).
 */
public record ChainTx(Transaction tx, int height, Sha256Hash blockHash, long blockTime, boolean fromServer) {
}