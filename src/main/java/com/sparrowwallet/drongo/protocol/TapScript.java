package com.sparrowwallet.drongo.protocol;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Taproot script path spending for a tree of one leaf (BIP341), as ord's commit and reveal use it: the commit pays to
 * the internal key tweaked with the leaf's hash, and the reveal spends it with [signature, script, control block].
 */
public final class TapScript {
    private TapScript() {}

    /** The TapLeaf hash of a tapscript leaf (leaf version 0xc0). For a single leaf this is also the merkle root. */
    public static byte[] leafHash(Script script) {
        ByteArrayOutputStream leaf = new ByteArrayOutputStream();
        leaf.write(Transaction.LEAF_VERSION_TAPSCRIPT);
        byte[] program = script.getProgram();
        leaf.writeBytes(new VarInt(program.length).encode());
        leaf.writeBytes(program);
        return Utils.taggedHash("TapLeaf", leaf.toByteArray());
    }

    /** The output key Q = P + TapTweak(P || root)G, with P the internal key taken with an even y. */
    public static ECKey outputKey(ECKey internalKey, byte[] merkleRoot) {
        ECKey evenKey = ECKey.fromPublicOnly(Utils.concat(new byte[] {0x02}, internalKey.getPubKeyXCoord()));
        byte[] tweak = Utils.taggedHash("TapTweak", Utils.concat(evenKey.getPubKeyXCoord(), merkleRoot));
        return evenKey.add(ECKey.fromPrivate(tweak), true);
    }

    /** The control block for the only leaf: the leaf version with the output key's parity, then the x-only internal key. */
    public static byte[] controlBlock(ECKey internalKey, byte[] merkleRoot) {
        int parity = outputKey(internalKey, merkleRoot).hasOddYCoord() ? 1 : 0;
        return Utils.concat(new byte[] {(byte)(Transaction.LEAF_VERSION_TAPSCRIPT | parity)}, internalKey.getPubKeyXCoord());
    }

    /** A SIGHASH_DEFAULT Schnorr signature of the input spending a one-leaf output by its script. */
    public static TransactionSignature signScriptPath(Transaction transaction, int inputIndex, List<TransactionOutput> spentUtxos, Script script, ECKey privateKey) {
        Sha256Hash hash = transaction.hashForTaprootSignature(spentUtxos, inputIndex, true, script, SigHash.DEFAULT, null);
        return privateKey.sign(hash, SigHash.DEFAULT, TransactionSignature.Type.SCHNORR);
    }
}
