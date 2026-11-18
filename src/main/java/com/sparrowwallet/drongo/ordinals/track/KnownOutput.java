package com.sparrowwallet.drongo.ordinals.track;

import java.util.List;

/** A result already decided for an output, such as a wallet coin checked before; found offsets are within that output. */
public record KnownOutput(Verdict verdict, List<Found> found) {
}