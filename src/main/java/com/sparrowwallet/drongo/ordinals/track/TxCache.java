package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.protocol.Sha256Hash;

import java.util.LinkedHashMap;
import java.util.Map;

/** The transactions traces have read, shared between traces of one coin and network, least recently used dropped first. */
public final class TxCache {
    public static final int SIZE = 20_000;
    private final Map<Sha256Hash, ChainTx> transactions = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Sha256Hash, ChainTx> eldest) {
            return size() > SIZE;
        }
    };

    public synchronized ChainTx get(Sha256Hash txid) {
        return transactions.get(txid);
    }

    public synchronized void put(ChainTx tx) {
        transactions.put(tx.tx().getTxId(), tx);
    }
}