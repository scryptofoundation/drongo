package com.sparrowwallet.drongo.wallet;

/**
 * Two keystores of a wallet give the same key for one of its nodes. This is a fault of the wallet, the same for every transaction, not of any
 * transaction being checked, so a check that meets it has learned nothing about the transaction.
 */
public class DuplicateKeystoreKeysException extends IllegalStateException {
    public DuplicateKeystoreKeysException(String message) {
        super(message);
    }
}