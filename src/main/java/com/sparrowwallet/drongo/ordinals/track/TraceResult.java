package com.sparrowwallet.drongo.ordinals.track;

import java.util.List;

/**
 * What a trace showed. reason says why an UNCHECKED coin is not decided; open holds the pieces still to trace, for a
 * later resume; usedChain is true when any transaction came from the server rather than the wallet.
 */
public record TraceResult(Verdict verdict, List<Found> found, String reason, List<Piece> open, int lookups, boolean usedChain) {
}