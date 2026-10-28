package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.P2TRAddress;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.wallet.InsufficientFundsException;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletTransaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Mints an inscription the way ord does, in two transactions. The commit pays a taproot output whose only script leaf
 * is the inscription's envelope; the reveal spends it by that script, putting the envelope on chain, and pays POSTAGE
 * to the destination (the inscription sits at its first sat). An etch is the same with the rune's commitment in the
 * envelope and the etching runestone as the reveal's second output; ord only accepts it once the commit is
 * COMMIT_CONFIRMATIONS deep, so the reveal's sequence makes it unminable before then.
 *
 * The commit is an ordinary fixed-order transaction of the funding wallet. The reveal is built and signed here with
 * the reveal key's private key, which the caller derives for this one signature and then drops.
 */
public final class InscriptionMint {
    public static final int COMMIT_CONFIRMATIONS = 6;

    private InscriptionMint() {}

    /**
     * @param revealKey the taproot wallet's key in the envelope script and the commit's internal key (public part)
     * @param etchRunestone the etching runestone (pointer 0), or null for a plain inscription
     */
    public record Plan(Inscription inscription, ECKey revealKey, Address destination, Script etchRunestone, double revealFeeRate) {
        public Plan {
            if(destination == null) {
                throw new IllegalArgumentException("A destination is required");
            }
            if(destination.getScriptType() == ScriptType.MWEB) {
                throw new IllegalArgumentException("Inscriptions cannot be minted to an MWEB address: ord does not follow coins into MWEB");
            }
            if(etchRunestone != null && inscription.runeCommitment() == null) {
                throw new IllegalArgumentException("An etch needs the rune's commitment in its envelope");
            }
            int weight = inscription.revealWeight(revealOutputs(destination, etchRunestone));
            if(weight > Inscription.MAX_REVEAL_WEIGHT) {
                throw new IllegalArgumentException(String.format("The content is too large to inscribe: at most %,d bytes of %s fit in one transaction",
                        Inscription.maxBodyBytes(inscription.contentType()), inscription.contentType()));
            }
        }

        public Script revealScript() {
            return inscription.revealScript(revealKey);
        }

        /** SHA-256 of the reveal script, kept with a pending mint to check the content given to finish it. */
        public byte[] scriptHash() {
            return Sha256Hash.hash(revealScript().getProgram());
        }

        public Address commitAddress() {
            return new P2TRAddress(TapScript.outputKey(revealKey, TapScript.leafHash(revealScript())).getPubKeyXCoord());
        }

        public long revealFee() {
            int weight = inscription.revealWeight(revealOutputs(destination, etchRunestone));
            return (long)Math.ceil(Math.ceil(weight / 4.0) * revealFeeRate);
        }

        public long commitValue() {
            return CollectibleTransactions.POSTAGE + revealFee();
        }

        boolean etch() {
            return etchRunestone != null;
        }
    }

    private static List<Script> revealOutputs(Address destination, Script etchRunestone) {
        List<Script> outputs = new ArrayList<>();
        outputs.add(destination.getOutputScript());
        if(etchRunestone != null) {
            outputs.add(etchRunestone);
        }
        return outputs;
    }

    /** The commit: [commit address with postage plus the reveal fee, LTC change to the funding wallet], fixed order. */
    public static WalletTransaction commit(Wallet funding, Plan plan, List<CollectibleTransactions.Pinned> feeCandidates, double feeRate,
                                           double minRelayFeeRate) throws InsufficientFundsException {
        if(funding.getScriptType() == ScriptType.MWEB) {
            throw new IllegalArgumentException("A mint cannot be paid from MWEB directly: move the coins out of MWEB first");
        }
        Address commitAddress = plan.commitAddress();
        TransactionOutput commitOutput = new TransactionOutput(null, plan.commitValue(), commitAddress.getOutputScript());
        return CollectibleTransactions.build(funding, List.of(), List.of(new CollectibleTransactions.Planned(commitOutput, commitAddress, null, null)),
                feeCandidates, feeRate, minRelayFeeRate);
    }

    /**
     * The signed reveal spending the commit output by the envelope script: outputs [destination POSTAGE, etching
     * runestone if etching], witness [signature, script, control block].
     */
    public static Transaction reveal(Plan plan, TransactionOutput commitOutput, ECKey revealPrivateKey) {
        if(!commitOutput.getScript().equals(plan.commitAddress().getOutputScript())) {
            throw new IllegalArgumentException("The output does not pay this mint's commit address");
        }
        if(commitOutput.getValue() < plan.commitValue()) {
            throw new IllegalArgumentException("The commit output holds less than the postage and the reveal fee");
        }
        if(!Arrays.equals(revealPrivateKey.getPubKeyXCoord(), plan.revealKey().getPubKeyXCoord())) {
            throw new IllegalArgumentException("The key does not match this mint's reveal key");
        }
        Transaction commit = (Transaction)commitOutput.getParent();
        Script script = plan.revealScript();

        Transaction reveal = new Transaction();
        reveal.setVersion(2);
        TransactionInput input = reveal.addInput(commit.getTxId(), commitOutput.getIndex(), new Script(new byte[0]), new TransactionWitness(reveal, List.of()));
        input.setSequenceNumber(plan.etch() ? COMMIT_CONFIRMATIONS - 1 : TransactionInput.SEQUENCE_RBF_ENABLED);
        //The commit output may hold more than planned (an earlier fee estimate); the destination still gets only postage
        reveal.addOutput(CollectibleTransactions.POSTAGE, plan.destination().getOutputScript());
        if(plan.etch()) {
            reveal.addOutput(0, plan.etchRunestone());
        }

        TransactionSignature signature = TapScript.signScriptPath(reveal, 0, List.of(commitOutput), script, revealPrivateKey);
        byte[] controlBlock = TapScript.controlBlock(plan.revealKey(), TapScript.leafHash(script));
        input.setWitness(new TransactionWitness(reveal, List.of(signature.encodeToBitcoin(), script.getProgram(), controlBlock)));
        return reveal;
    }
}
