package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Coin;
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
 * is the inscription's envelope; the reveal spends it by that script, putting the envelope on chain, and pays the
 * coin's postage to the destination (the inscription sits at its first sat). An etch is the same with the rune's commitment in the
 * envelope and the etching runestone as the reveal's last output, after a second POSTAGE output holding the premine
 * (as ord does, so the premine can be sent without the inscription); ord only accepts it once the commit is
 * COMMIT_CONFIRMATIONS deep, so the reveal's sequence makes it unminable before then.
 *
 * The commit is an ordinary fixed-order transaction of the funding wallet. The reveal is built and signed here with
 * the reveal key's private key, which the caller derives for this one signature and then drops.
 */
public final class InscriptionMint {
    public static final int COMMIT_CONFIRMATIONS = 6;
    //Nodes relay an OP_RETURN output of at most this many bytes (ord refuses larger runestones too)
    public static final int MAX_RUNESTONE_BYTES = 83;

    //Any valid key: only the size of the output script of each type matters for a dust probe
    private static final ECKey DUST_PROBE_KEY = ECKey.fromPrivate(new java.math.BigInteger("11".repeat(32), 16));

    private InscriptionMint() {}

    /**
     * @param revealKey the taproot wallet's key in the envelope script and the commit's internal key (public part)
     * @param etching the rune etched by the reveal, or null for a plain inscription
     * @param coin the funding wallet's coin, which sets the postage and the sequences
     */
    public record Plan(Inscription inscription, ECKey revealKey, Address destination, Runestone.Etching etching, double revealFeeRate, Coin coin) {
        public Plan(Inscription inscription, ECKey revealKey, Address destination, Runestone.Etching etching, double revealFeeRate) {
            this(inscription, revealKey, destination, etching, revealFeeRate, Coin.LITECOIN);
        }

        public Plan {
            mintPostage(coin);
            if(destination == null) {
                throw new IllegalArgumentException("A destination is required");
            }
            if(destination.getScriptType() == ScriptType.MWEB) {
                throw new IllegalArgumentException("Inscriptions cannot be minted to an MWEB address: ord does not follow coins into MWEB");
            }
            if(etching != null && inscription.runeCommitment() == null) {
                throw new IllegalArgumentException("An etch needs the rune's commitment in its envelope");
            }
            if(etching != null && InscriptionMint.etchRunestone(etching).getProgram().length > MAX_RUNESTONE_BYTES) {
                throw new IllegalArgumentException("This etching's runestone is " + InscriptionMint.etchRunestone(etching).getProgram().length + " bytes, and " + coin.getCapitalizedName() + " nodes relay at most "
                        + MAX_RUNESTONE_BYTES + ". Use a shorter name, fewer spacers or fewer mint terms.");
            }
            int weight = inscription.revealWeight(revealOutputs(destination, etching));
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
            int weight = inscription.revealWeight(revealOutputs(destination, etching));
            return (long)Math.ceil(Math.ceil(weight / 4.0) * revealFeeRate);
        }

        /** The value each output of the reveal lands. */
        public long postage() {
            return mintPostage(coin, destination.getScriptType());
        }

        /** Postage for each output the reveal pays, plus the reveal fee. */
        public long commitValue() {
            return postage() * (premineOutput(etching) ? 2 : 1) + revealFee();
        }

        /** The etching runestone, pointing the premine at its own output; null for a plain inscription. */
        public Script etchRunestone() {
            return etching == null ? null : InscriptionMint.etchRunestone(etching);
        }

        boolean etch() {
            return etching != null;
        }
    }

    /** The postage of a minted inscription: Litecoin keeps POSTAGE, Bellscoin lands 1,000 on its taproot destination. */
    public static long mintPostage(Coin coin) {
        return switch(coin) {
            case LITECOIN -> CollectibleTransactions.POSTAGE;
            case BELLSCOIN -> 1_000;
            default -> throw new IllegalArgumentException(coin.getCapitalizedName() + " inscriptions are not minted by commit and reveal");
        };
    }

    /** The coin's postage, raised to the dust limit of the destination's script type if that is higher. P2TR is the default. */
    public static long mintPostage(Coin coin, ScriptType destinationType) {
        return mintPostage(coin, destinationType, dustRelayFee(coin));
    }

    static long mintPostage(Coin coin, ScriptType destinationType, double dustRelayFee) {
        //The type's own dust rule (output size plus the size to spend it), not the focused coin's flat soft limit
        return Math.max(mintPostage(coin), destinationType.getFee(new TransactionOutput(null, 0, destinationType.getOutputScript(DUST_PROBE_KEY.getPubKey())), dustRelayFee, dustRelayFee));
    }

    /**
     * The dust relay fee per virtual byte. Litecoin: DUST_RELAY_TX_FEE as the rest of drongo uses it. Bellscoin:
     * BellsChain/bellscoin v3.1.1-rc2 src/policy/policy.h line 55, DUST_RELAY_TX_FEE{3000} (sat/kvB, so 3 per vB).
     */
    private static double dustRelayFee(Coin coin) {
        return switch(coin) {
            case LITECOIN -> Transaction.DUST_RELAY_TX_FEE;
            case BELLSCOIN -> 3d;
            default -> throw new IllegalArgumentException(coin.getCapitalizedName() + " inscriptions are not minted by commit and reveal");
        };
    }

    /** Litecoin mints signal RBF as they always have; the other coins' mints do not. */
    public static long mintSequence(Coin coin) {
        return coin == Coin.LITECOIN ? TransactionInput.SEQUENCE_RBF_ENABLED : 0xffffffffL;
    }

    private static boolean premineOutput(Runestone.Etching etching) {
        return etching != null && etching.premine() != null && etching.premine().signum() > 0;
    }

    private static Script etchRunestone(Runestone.Etching etching) {
        return Runestone.script(List.of(), premineOutput(etching) ? 1 : null, etching, null);
    }

    //[inscription, premine if any, runestone if etching]
    private static List<Script> revealOutputs(Address destination, Runestone.Etching etching) {
        List<Script> outputs = new ArrayList<>();
        outputs.add(destination.getOutputScript());
        if(premineOutput(etching)) {
            outputs.add(destination.getOutputScript());
        }
        if(etching != null) {
            outputs.add(etchRunestone(etching));
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
                feeCandidates, feeRate, minRelayFeeRate, 0, mintSequence(plan.coin()));
    }

    /**
     * The signed reveal spending the commit output by the envelope script: outputs [destination POSTAGE, destination
     * POSTAGE for a premine, etching runestone if etching], witness [signature, script, control block].
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
        input.setSequenceNumber(plan.etch() ? COMMIT_CONFIRMATIONS - 1 : mintSequence(plan.coin()));
        //The commit output may hold more than planned (an earlier fee estimate); the destination still gets only postage
        reveal.addOutput(plan.postage(), plan.destination().getOutputScript());
        if(premineOutput(plan.etching())) {
            reveal.addOutput(plan.postage(), plan.destination().getOutputScript());
        }
        if(plan.etch()) {
            reveal.addOutput(0, plan.etchRunestone());
        }

        TransactionSignature signature = TapScript.signScriptPath(reveal, 0, List.of(commitOutput), script, revealPrivateKey);
        byte[] controlBlock = TapScript.controlBlock(plan.revealKey(), TapScript.leafHash(script));
        input.setWitness(new TransactionWitness(reveal, List.of(signature.encodeToBitcoin(), script.getProgram(), controlBlock)));
        return reveal;
    }
}
