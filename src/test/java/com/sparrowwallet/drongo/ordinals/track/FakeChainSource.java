package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;

import java.io.IOException;
import java.util.*;

/** An in-memory chain for the tracer tests. */
final class FakeChainSource implements ChainSource {
    final Map<Sha256Hash, ChainTx> txs = new HashMap<>();
    final Map<Integer, List<BlockFee>> fees = new HashMap<>();
    final Map<Integer, List<Sha256Hash>> positions = new HashMap<>();
    final Set<Sha256Hash> failing = new HashSet<>();
    //Block hashes the server gives by height, for transactions it returned without one
    final Map<Integer, Sha256Hash> hashes = new HashMap<>();
    int transactionCalls;
    int blockFeeCalls;
    int positionCalls;

    /** Adds a confirmed transaction served by the server, block hash derived from the height. */
    Transaction add(Transaction tx, int height) {
        return add(tx, height, true);
    }

    Transaction add(Transaction tx, int height, boolean fromServer) {
        txs.put(tx.getTxId(), new ChainTx(tx, height, height == 0 ? null : heightHash(height), 1_700_000_000L + height, fromServer));
        return tx;
    }

    static Sha256Hash heightHash(int height) {
        return Sha256Hash.wrap(String.format("%064x", 0xb10c0000L + height));
    }

    @Override
    public ChainTx transaction(Sha256Hash txid) throws IOException {
        transactionCalls++;
        if(failing.contains(txid)) {
            throw new IOException("connection reset");
        }
        ChainTx tx = txs.get(txid);
        if(tx == null) {
            throw new IOException("No such transaction " + txid);
        }
        return tx;
    }

    @Override
    public Optional<List<BlockFee>> blockFees(int height, Sha256Hash blockHash) {
        blockFeeCalls++;
        //Like Esplora: a block's fee list is asked for by its hash
        return blockHash == null ? Optional.empty() : Optional.ofNullable(fees.get(height));
    }

    @Override
    public Optional<Sha256Hash> blockHash(int height) {
        return Optional.ofNullable(hashes.get(height));
    }

    /** Adds a confirmed transaction the server returns without its block hash (the app's verbose answers drop it). */
    Transaction addWithoutHash(Transaction tx, int height) {
        txs.put(tx.getTxId(), new ChainTx(tx, height, null, 1_700_000_000L + height, true));
        return tx;
    }

    @Override
    public Optional<Sha256Hash> txidAt(int height, int position) {
        positionCalls++;
        List<Sha256Hash> block = positions.get(height);
        return block == null || position >= block.size() ? Optional.empty() : Optional.of(block.get(position));
    }
}
