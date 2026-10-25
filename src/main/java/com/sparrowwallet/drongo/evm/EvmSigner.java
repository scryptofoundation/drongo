package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.crypto.ECDSASignature;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;

import java.math.BigInteger;

/**
 * ECDSA signing for EVM transactions.
 *
 * Deliberately not {@link ECKey#signEcdsa}: that grinds the nonce until R is low, a Bitcoin size
 * optimisation that makes signatures differ from every other EVM signer for about half of all hashes.
 * Here the nonce is plain RFC 6979 (deterministic, no RNG involved), S is canonicalised low as EIP-2
 * requires, and the recovery id becomes the transaction's y-parity.
 */
public final class EvmSigner {
    private EvmSigner() {}

    public record Signature(int recoveryId, BigInteger r, BigInteger s) {}

    public static Signature sign(byte[] hash, ECKey key) {
        if(hash.length != 32) {
            throw new IllegalArgumentException("EVM signing hashes are 32 bytes");
        }
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, new ECPrivateKeyParameters(new BigInteger(1, key.getPrivKeyBytes()), ECKey.CURVE));
        BigInteger[] components = signer.generateSignature(hash);
        ECDSASignature signature = new ECDSASignature(components[0], components[1]).toCanonicalised();
        int recoveryId = key.findRecoveryId(Sha256Hash.wrap(hash), signature);
        return new Signature(recoveryId, signature.r, signature.s);
    }

    /** The address that produced a signature over the given hash. */
    public static EvmAddress recoverSigner(byte[] hash, Signature signature) {
        ECKey recovered = ECKey.recoverFromSignature(signature.recoveryId(), new ECDSASignature(signature.r(), signature.s()), Sha256Hash.wrap(hash), false);
        if(recovered == null) {
            throw new IllegalArgumentException("Signature does not recover to a public key");
        }
        return EvmAddress.fromPublicKey(recovered);
    }
}
