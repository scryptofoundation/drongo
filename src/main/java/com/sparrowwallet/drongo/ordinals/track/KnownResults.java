package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.protocol.Sha256Hash;

import java.util.Optional;

public interface KnownResults {
    Optional<KnownOutput> forOutput(Sha256Hash txid, int vout);

    KnownResults NONE = (txid, vout) -> Optional.empty();
}