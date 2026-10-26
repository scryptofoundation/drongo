package com.sparrowwallet.drongo.wallet;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.Transaction;

public class CoinbaseTxoFilter implements TxoFilter {
    private final Wallet wallet;

    public CoinbaseTxoFilter(Wallet wallet) {
        this.wallet = wallet;
    }

    @Override
    public boolean isEligible(BlockTransactionHashIndex candidate) {
        //Disallow immature coinbase outputs; maturity differs by coin (and for Dogecoin by the coinbase's height)
        BlockTransaction blockTransaction = wallet.getWalletTransaction(candidate.getHash());
        if(blockTransaction != null && blockTransaction.getTransaction() != null && blockTransaction.getTransaction().isCoinBase()
            && wallet.getStoredBlockHeight() != null && candidate.getConfirmations(wallet.getStoredBlockHeight()) < maturity(candidate)) {
            return false;
        }

        return true;
    }

    private int maturity(BlockTransactionHashIndex candidate) {
        Network network = wallet.getNetwork() != null ? wallet.getNetwork() : Network.get();
        return candidate.getHeight() > 0 ? wallet.getCoin().getCoinbaseMaturity(network, candidate.getHeight()) : Transaction.COINBASE_MATURITY_THRESHOLD;
    }
}
