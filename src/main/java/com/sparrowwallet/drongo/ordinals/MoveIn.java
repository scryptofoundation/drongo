package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.psbt.PSBTProofException;

import java.util.ArrayList;
import java.util.List;

/**
 * Sweeps everything an ord-litecoin wallet holds into this wallet in one transaction, signed with keys from its dump.
 *
 * Inputs: [inscribed..., runic..., plain...]. Outputs: [one per inscribed input, with that input's exact value, to its own
 * destination; runes POSTAGE (when any input holds runes); plain LTC; runestone with no edicts and its pointer on the runes
 * output (when any input holds runes)]. Because each inscribed input fills its own output exactly, every inscription keeps
 * its offset, and the runestone sends all runes from every input to the runes output. The fee comes from the runic and
 * plain inputs only.
 */
public final class MoveIn {
    private MoveIn() {}

    /** An output found at one of the dump's addresses, with what ord says it holds. */
    public record Found(TransactionOutput output, OrdWalletDump.DumpedKey key, ScriptType type, List<String> inscriptions, boolean runic) {
        public boolean inscribed() {
            return inscriptions != null && !inscriptions.isEmpty();
        }
    }

    public static Transaction sweep(List<Found> found, List<Address> inscriptionDestinations, Address runesDestination, Address plainDestination,
                                    double feeRate, double minRelayFeeRate) {
        List<Found> inscribed = found.stream().filter(Found::inscribed).toList();
        List<Found> runic = found.stream().filter(f -> !f.inscribed() && f.runic()).toList();
        List<Found> plain = found.stream().filter(f -> !f.inscribed() && !f.runic()).toList();
        boolean anyRunic = found.stream().anyMatch(Found::runic);

        if(found.isEmpty()) {
            throw new IllegalArgumentException("Nothing was found to move in");
        }
        if(inscriptionDestinations.size() != inscribed.size()) {
            throw new IllegalArgumentException("Each output holding inscriptions needs its own destination address");
        }
        List<Address> destinations = new ArrayList<>(inscriptionDestinations);
        destinations.add(plainDestination);
        if(anyRunic) {
            destinations.add(runesDestination);
        }
        if(destinations.stream().anyMatch(address -> address == null || address.getScriptType() == ScriptType.MWEB)) {
            throw new IllegalArgumentException("Inscriptions and runes cannot be moved to an MWEB address: ord does not follow coins into MWEB");
        }

        List<Found> inputs = new ArrayList<>(inscribed);
        inputs.addAll(runic);
        inputs.addAll(plain);

        long feePool = runic.stream().mapToLong(f -> f.output().getValue()).sum() + plain.stream().mapToLong(f -> f.output().getValue()).sum();
        long postage = anyRunic ? CollectibleTransactions.POSTAGE : 0;
        Integer runesIndex = anyRunic ? inscribed.size() : null;

        Transaction sizing = transaction(inputs, inscriptionDestinations, runesDestination, plainDestination, 0, runesIndex, true);
        long fee = (long)Math.ceil(sizing.getVirtualSize() * feeRate);
        if(feeRate == minRelayFeeRate) {
            fee++;
        }
        long plainValue = feePool - postage - fee;
        TransactionOutput probe = new TransactionOutput(null, Math.max(plainValue, 0), plainDestination.getOutputScript());
        if(plainValue <= plainDestination.getScriptType().getDustThreshold(probe, Transaction.DUST_RELAY_TX_FEE)) {
            throw new IllegalArgumentException("The plain LTC in the ord wallet does not cover the fee" + (anyRunic ? " and the runes output" : "")
                    + ": fund one of its addresses with a little more LTC and try again");
        }

        Transaction transaction = transaction(inputs, inscriptionDestinations, runesDestination, plainDestination, plainValue, runesIndex, false);
        return sign(transaction, inputs);
    }

    private static Transaction transaction(List<Found> inputs, List<Address> inscriptionDestinations, Address runesDestination, Address plainDestination,
                                           long plainValue, Integer runesIndex, boolean dummySignatures) {
        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        for(Found input : inputs) {
            TransactionSignature dummy = TransactionSignature.dummy(input.type() == ScriptType.P2TR ? TransactionSignature.Type.SCHNORR : TransactionSignature.Type.ECDSA);
            TransactionInput txInput = dummySignatures ? input.type().addSpendingInput(transaction, input.output(), input.key().publicKey(), dummy)
                    : transaction.addInput(input.output().getHash(), input.output().getIndex(), new Script(new byte[0]));
            txInput.setSequenceNumber(TransactionInput.SEQUENCE_RBF_ENABLED);
        }
        for(int i = 0; i < inscriptionDestinations.size(); i++) {
            transaction.addOutput(inputs.get(i).output().getValue(), inscriptionDestinations.get(i));
        }
        if(runesIndex != null) {
            transaction.addOutput(CollectibleTransactions.POSTAGE, runesDestination);
        }
        transaction.addOutput(plainValue, plainDestination);
        if(runesIndex != null) {
            transaction.addOutput(0, Runestone.script(List.of(), runesIndex));
        }
        return transaction;
    }

    /** Signs each input with its dumped key, as the private key sweep does. The signing keys are dropped when done. */
    private static Transaction sign(Transaction transaction, List<Found> inputs) {
        PSBT psbt = new PSBT(transaction);
        //Every input's previous output is needed before hashing for a taproot signature
        for(int i = 0; i < inputs.size(); i++) {
            psbt.getPsbtInputs().get(i).setWitnessUtxo(inputs.get(i).output());
        }

        for(int i = 0; i < inputs.size(); i++) {
            Found input = inputs.get(i);
            PSBTInput psbtInput = psbt.getPsbtInputs().get(i);
            ECKey pubKey = input.key().publicKey();
            if(input.type() == ScriptType.P2SH_P2WPKH) {
                psbtInput.setRedeemScript(ScriptType.P2WPKH.getOutputScript(pubKey));
            }
            if(!psbtInput.sign(input.type().getOutputKey(input.key().signingKey()))) {
                throw new IllegalStateException("Could not sign the input spending " + input.output().getHash() + ":" + input.output().getIndex());
            }
            TransactionSignature signature = psbtInput.isTaproot() ? psbtInput.getTapKeyPathSignature() : psbtInput.getPartialSignature(pubKey);
            TransactionInput finalized = input.type().addSpendingInput(new Transaction(), input.output(), pubKey, signature);
            psbtInput.setFinalScriptSig(finalized.getScriptSig());
            psbtInput.setFinalScriptWitness(finalized.getWitness());
        }

        try {
            return psbt.extractTransaction();
        } catch(PSBTProofException e) {
            throw new IllegalStateException("Could not finish the move-in transaction", e);
        }
    }
}
