package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.protocol.Sha256Hash;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** What the tracer reads from the coin's server. */
public interface ChainSource {
    ChainTx transaction(Sha256Hash txid) throws IOException;

    /** The block's non-coinbase transactions with their fees, in block order, or empty when the server cannot list them. */
    Optional<List<BlockFee>> blockFees(int height, Sha256Hash blockHash) throws IOException;

    /** The txid at a position in the block at a height (0 is the coinbase), or empty past its end or when unsupported. */
    Optional<Sha256Hash> txidAt(int height, int position) throws IOException;

    /** The hash of the block at a height on the server's chain, for a transaction it returned without one; empty when it cannot say. */
    default Optional<Sha256Hash> blockHash(int height) throws IOException {
        return Optional.empty();
    }
}