package com.sparrowwallet.drongo.evm;

import org.bouncycastle.crypto.digests.KeccakDigest;

/**
 * Keccak-256 as used by Ethereum and EVM chains such as LitVM. This is the original Keccak padding, not
 * the NIST SHA3-256 that was standardised later, so the two give different digests for the same input.
 */
public final class Keccak {
    private Keccak() {}

    public static byte[] keccak256(byte[] data) {
        KeccakDigest digest = new KeccakDigest(256);
        digest.update(data, 0, data.length);
        byte[] out = new byte[32];
        digest.doFinal(out, 0);
        return out;
    }
}
