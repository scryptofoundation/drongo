package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.crypto.DeterministicKey;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.crypto.HDKeyDerivation;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.MnemonicException;

/**
 * An EVM account derived from a wallet seed at the standard Ethereum path m/44'/60'/0'/0/index, so the same
 * seed shows the same address here and in MetaMask or any other EVM wallet.
 *
 * Derivation never touches {@code Coin} or {@code Network}: the account is chain-agnostic and the chain is
 * chosen only when a transaction is signed (its chain id is part of the signed payload).
 */
public final class EvmAccount {
    public static final String DERIVATION_PREFIX = "m/44'/60'/0'/0/";

    private final ECKey key;
    private final EvmAddress address;
    private final int index;

    private EvmAccount(ECKey key, int index) {
        this.key = key;
        this.address = EvmAddress.fromPublicKey(key);
        this.index = index;
    }

    public static EvmAccount fromSeed(DeterministicSeed seed, int index) throws MnemonicException {
        if(index < 0) {
            throw new IllegalArgumentException("Account index must be non-negative");
        }
        DeterministicKey current = HDKeyDerivation.createMasterPrivateKey(seed.getSeedBytes());
        for(ChildNumber child : KeyDerivation.parsePath(DERIVATION_PREFIX + index)) {
            current = HDKeyDerivation.deriveChildKey(current, child);
        }
        return new EvmAccount(ECKey.fromPrivate(current.getPrivKeyBytes()), index);
    }

    public static EvmAccount fromPrivateKey(ECKey key) {
        return new EvmAccount(key, -1);
    }

    public EvmAddress getAddress() {
        return address;
    }

    public String getDerivationPath() {
        return index < 0 ? null : DERIVATION_PREFIX + index;
    }

    public Eip1559Transaction.Signed sign(Eip1559Transaction transaction) {
        return transaction.sign(key);
    }
}
