package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.wallet.*;

import java.math.BigInteger;
import java.util.*;

/**
 * Transactions that move an inscription or runes. Unlike ordinary sends, input and output positions carry meaning:
 * ord follows sats in order (the first input's sats fill the first output), and a runestone names outputs by index.
 * So these are built here in a fixed order, never shuffled, and marked so the PSBT keeps that order too.
 *
 * Inscription send: inputs [inscribed, fee...], outputs [recipient with the inscribed value, LTC change].
 * Rune send: inputs [rune inputs..., fee...], outputs [recipient POSTAGE, own rune change POSTAGE, runestone, LTC change],
 * with the runestone sending the amount to output 0 and everything else on the inputs to output 1 (the pointer).
 * Fee candidates are used largest first; the caller passes only outputs known to carry nothing.
 */
public final class CollectibleTransactions {
    public static final long POSTAGE = 10_000;
    private static final int RECIPIENT_OUTPUT = 0;
    private static final int RUNE_CHANGE_OUTPUT = 1;

    private CollectibleTransactions() {}

    /** A wallet output to spend, with the node that holds its key. */
    public record Pinned(BlockTransactionHashIndex utxo, WalletNode node) {}

    public static WalletTransaction sendInscription(Wallet wallet, Pinned inscribed, Address recipient, List<Pinned> feeCandidates,
                                                    double feeRate, double minRelayFeeRate) throws InsufficientFundsException {
        checkRecipient(recipient);
        List<Planned> outputs = List.of(new Planned(new TransactionOutput(null, inscribed.utxo().getValue(), recipient.getOutputScript()), recipient, null, null));
        return build(wallet, List.of(inscribed), outputs, feeCandidates, feeRate, minRelayFeeRate);
    }

    public static WalletTransaction sendRunes(Wallet wallet, List<Pinned> runeInputs, Address recipient, RuneId id, BigInteger amount,
                                              List<Pinned> feeCandidates, double feeRate, double minRelayFeeRate) throws InsufficientFundsException {
        checkRecipient(recipient);
        if(runeInputs.isEmpty()) {
            throw new IllegalArgumentException("No outputs hold the rune");
        }
        WalletNode runeChange = wallet.getFreshNode(KeyPurpose.CHANGE);
        Script runestone = Runestone.script(List.of(new Runestone.Edict(id, amount, RECIPIENT_OUTPUT)), RUNE_CHANGE_OUTPUT);
        List<Planned> outputs = List.of(
                new Planned(new TransactionOutput(null, POSTAGE, recipient.getOutputScript()), recipient, null, null),
                new Planned(new TransactionOutput(null, POSTAGE, runeChange.getOutputScript()), null, runeChange, null),
                new Planned(new TransactionOutput(null, 0, runestone), null, null, runestone));
        return build(wallet, runeInputs, outputs, feeCandidates, feeRate, minRelayFeeRate);
    }

    private record Planned(TransactionOutput output, Address recipient, WalletNode node, Script script) {}

    private static void checkRecipient(Address recipient) {
        if(recipient == null) {
            throw new IllegalArgumentException("A recipient is required");
        }
        if(recipient.getScriptType() == ScriptType.MWEB) {
            throw new IllegalArgumentException("Inscriptions and runes cannot be sent to an MWEB address: ord does not follow coins into MWEB, so they would be lost");
        }
    }

    private static WalletTransaction build(Wallet wallet, List<Pinned> fixedInputs, List<Planned> planned, List<Pinned> feeCandidates,
                                           double feeRate, double minRelayFeeRate) throws InsufficientFundsException {
        List<Pinned> candidates = new ArrayList<>(feeCandidates);
        candidates.removeIf(candidate -> fixedInputs.stream().anyMatch(fixed -> fixed.utxo().equals(candidate.utxo())));
        candidates.sort(Comparator.comparingLong((Pinned p) -> p.utxo().getValue()).reversed());

        long plannedValue = planned.stream().mapToLong(p -> p.output().getValue()).sum();
        List<Pinned> inputs = new ArrayList<>(fixedInputs);
        for(int used = 0; used <= candidates.size(); used++) {
            if(used > 0) {
                inputs.add(candidates.get(used - 1));
            }
            long inputValue = inputs.stream().mapToLong(p -> p.utxo().getValue()).sum();

            Transaction noChange = transaction(wallet, inputs, planned, null);
            long noChangeFee = fee(noChange, feeRate, minRelayFeeRate);
            if(inputValue - plannedValue < noChangeFee) {
                continue;
            }

            WalletNode changeNode = wallet.getFreshNode(KeyPurpose.CHANGE, planned.stream().map(Planned::node).filter(Objects::nonNull).findFirst().orElse(null));
            Transaction withChange = transaction(wallet, inputs, planned, new TransactionOutput(null, 0, changeNode.getOutputScript()));
            long changeFee = fee(withChange, feeRate, minRelayFeeRate);
            long change = inputValue - plannedValue - changeFee;
            TransactionOutput probe = new TransactionOutput(null, change, changeNode.getOutputScript());
            if(change > wallet.getScriptType().getDustThreshold(probe, Transaction.DUST_RELAY_TX_FEE)) {
                return walletTransaction(wallet, inputs, planned, changeNode, change, changeFee);
            }
            return walletTransaction(wallet, inputs, planned, null, 0, inputValue - plannedValue);
        }

        throw new InsufficientFundsException("Not enough checked LTC to pay the fee for this transaction");
    }

    private static long fee(Transaction transaction, double feeRate, double minRelayFeeRate) {
        long fee = (long)Math.ceil(transaction.getVirtualSize() * feeRate);
        //As in ordinary sends, one extra litoshi at the minimum relay rate covers a longer signature
        return feeRate == minRelayFeeRate ? fee + 1 : fee;
    }

    /** The transaction with dummy signatures, for sizing; the change output's value is not yet known. */
    private static Transaction transaction(Wallet wallet, List<Pinned> inputs, List<Planned> planned, TransactionOutput change) {
        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        for(Pinned input : inputs) {
            Wallet.addDummySpendingInput(transaction, input.node(), previousOutput(input)).setSequenceNumber(TransactionInput.SEQUENCE_RBF_ENABLED);
        }
        for(Planned output : planned) {
            transaction.addOutput(output.output().getValue(), output.output().getScript());
        }
        if(change != null) {
            transaction.addOutput(change.getValue(), change.getScript());
        }
        return transaction;
    }

    private static TransactionOutput previousOutput(Pinned input) {
        BlockTransaction previous = input.node().getWallet().getTransactions().get(input.utxo().getHash());
        if(previous == null) {
            throw new IllegalStateException("The wallet does not hold the transaction of " + input.utxo());
        }
        return previous.getTransaction().getOutputs().get((int)input.utxo().getIndex());
    }

    private static WalletTransaction walletTransaction(Wallet wallet, List<Pinned> inputs, List<Planned> planned, WalletNode changeNode, long change, long fee) {
        Transaction transaction = transaction(wallet, inputs, planned, null);
        List<WalletTransaction.Output> outputs = new ArrayList<>();
        List<Payment> payments = new ArrayList<>();
        Map<WalletNode, Long> changeMap = new LinkedHashMap<>();
        for(int i = 0; i < planned.size(); i++) {
            Planned p = planned.get(i);
            TransactionOutput output = transaction.getOutputs().get(i);
            if(p.recipient() != null) {
                Payment payment = new Payment(p.recipient(), null, output.getValue(), false);
                payments.add(payment);
                outputs.add(new WalletTransaction.PaymentOutput(output, payment));
            } else if(p.node() != null) {
                outputs.add(new WalletTransaction.ChangeOutput(output, p.node(), output.getValue()));
                changeMap.put(p.node(), output.getValue());
            } else {
                outputs.add(new WalletTransaction.NonAddressOutput(output));
            }
        }
        if(changeNode != null) {
            TransactionOutput output = transaction.addOutput(change, changeNode.getOutputScript());
            outputs.add(new WalletTransaction.ChangeOutput(output, changeNode, change));
            changeMap.put(changeNode, change);
        }

        Map<BlockTransactionHashIndex, WalletNode> selected = new LinkedHashMap<>();
        for(Pinned input : inputs) {
            selected.put(input.utxo(), input.node());
        }
        WalletTransaction walletTransaction = new WalletTransaction(wallet, transaction, List.of(new PresetUtxoSelector(selected.keySet(), true, true)),
                List.of(selected), payments, outputs, changeMap, fee);
        walletTransaction.setFixedOutputOrder(true);
        return walletTransaction;
    }
}
