package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.protocol.Sha256Hash;

/**
 * One transaction of a Doginals chain. genesis: it starts the envelope ("ord"), so its txid names the inscription.
 * last: it completes the envelope, so the inscription sits on the first unit of its output 0. previousTxid is the
 * transaction its input 0 spends, the chain's step before.
 */
public record DoginalsStep(boolean genesis, boolean last, Sha256Hash previousTxid) {
}
