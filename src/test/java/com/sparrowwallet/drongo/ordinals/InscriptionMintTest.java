package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.MwebAddress;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.wallet.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

public class InscriptionMintTest {
    private static final List<String> WORDS = List.of("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "));
    private static final ECKey REVEAL_PRIVATE = ECKey.fromPrivate(Utils.hexToBytes("0202020202020202020202020202020202020202020202020202020202020202"));
    private Coin previousCoin;
    private Wallet wallet;
    private Address destination;
    private final List<WalletNode> nodes = new ArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        previousCoin = Coin.beginScope(Coin.LITECOIN);
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(WORDS), "", 0, DeterministicSeed.Type.BIP39);
        wallet = new Wallet("segwit");
        wallet.setPolicyType(PolicyType.SINGLE);
        wallet.setScriptType(ScriptType.P2WPKH);
        Keystore keystore = Keystore.fromSeed(seed, ScriptType.P2WPKH.getDefaultDerivation());
        wallet.getKeystores().add(keystore);
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, wallet.getKeystores(), 1));
        WalletNode receive = wallet.getNode(KeyPurpose.RECEIVE);
        receive.fillToIndex(10);
        wallet.getNode(KeyPurpose.CHANGE).fillToIndex(10);
        nodes.addAll(receive.getChildren());
        destination = ScriptType.P2TR.getAddress(ECKey.fromPrivate(BigInteger.valueOf(42)));
    }

    @AfterEach
    public void tearDown() {
        Coin.endScope(previousCoin);
    }

    private List<CollectibleTransactions.Pinned> fund(long... values) {
        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.ZERO_HASH, nodes.size(), new Script(new byte[0]));
        for(int i = 0; i < values.length; i++) {
            tx.addOutput(values[i], nodes.get(i).getAddress());
        }
        Sha256Hash hash = tx.getTxId();
        wallet.updateTransactions(Map.of(hash, new BlockTransaction(hash, 100, new Date(), 0L, tx)));
        List<CollectibleTransactions.Pinned> pinned = new ArrayList<>();
        for(int i = 0; i < values.length; i++) {
            BlockTransactionHashIndex utxo = new BlockTransactionHashIndex(hash, 100, new Date(), 0L, i, values[i]);
            nodes.get(i).getTransactionOutputs().add(utxo);
            pinned.add(new CollectibleTransactions.Pinned(utxo, nodes.get(i)));
        }
        return pinned;
    }

    private InscriptionMint.Plan textPlan() {
        return new InscriptionMint.Plan(Inscription.text("hello litecoin"), ECKey.fromPublicOnly(REVEAL_PRIVATE), destination, null, 2.0);
    }

    @Test
    public void commitPaysCommitAddressFirst() throws Exception {
        InscriptionMint.Plan plan = textPlan();
        WalletTransaction commit = InscriptionMint.commit(wallet, plan, fund(100_000, 50_000), 2.0, 1.0);
        Transaction tx = commit.getTransaction();
        Assertions.assertTrue(commit.isFixedOutputOrder());
        Assertions.assertEquals(plan.commitAddress().getOutputScript(), tx.getOutputs().get(0).getScript());
        Assertions.assertEquals(ScriptType.P2TR, plan.commitAddress().getScriptType());
        Assertions.assertEquals(plan.commitValue(), tx.getOutputs().get(0).getValue());
        Assertions.assertEquals(CollectibleTransactions.POSTAGE + plan.revealFee(), plan.commitValue());
        Assertions.assertEquals(2, tx.getOutputs().size());
        Assertions.assertTrue(commit.getOutputs().get(1) instanceof WalletTransaction.ChangeOutput);
        Assertions.assertSame(wallet, ((WalletTransaction.ChangeOutput)commit.getOutputs().get(1)).getWalletNode().getWallet());
    }

    @Test
    public void revealSpendsCommitAndLandsPostage() throws Exception {
        InscriptionMint.Plan plan = textPlan();
        Transaction commit = InscriptionMint.commit(wallet, plan, fund(100_000), 2.0, 1.0).getTransaction();
        Transaction reveal = InscriptionMint.reveal(plan, commit.getOutputs().get(0), REVEAL_PRIVATE);
        TransactionInput input = reveal.getInputs().getFirst();
        Assertions.assertEquals(1, reveal.getInputs().size());
        Assertions.assertEquals(commit.getTxId(), input.getOutpoint().getHash());
        Assertions.assertEquals(0, input.getOutpoint().getIndex());
        Assertions.assertEquals(TransactionInput.SEQUENCE_RBF_ENABLED, input.getSequenceNumber());
        Assertions.assertEquals(1, reveal.getOutputs().size());
        Assertions.assertEquals(CollectibleTransactions.POSTAGE, reveal.getOutputs().getFirst().getValue());
        Assertions.assertEquals(destination.getOutputScript(), reveal.getOutputs().getFirst().getScript());

        List<byte[]> witness = input.getWitness().getPushes();
        Assertions.assertEquals(3, witness.size());
        Assertions.assertEquals(64, witness.get(0).length);
        Assertions.assertArrayEquals(plan.revealScript().getProgram(), witness.get(1));
        Assertions.assertArrayEquals(TapScript.controlBlock(plan.revealKey(), TapScript.leafHash(plan.revealScript())), witness.get(2));
        Assertions.assertTrue(OrdinalsScan.hasEnvelope(reveal));

        Assertions.assertThrows(IllegalArgumentException.class, () -> InscriptionMint.reveal(plan, commit.getOutputs().get(1), REVEAL_PRIVATE));
        ECKey otherKey = ECKey.fromPrivate(Utils.hexToBytes("0303030303030303030303030303030303030303030303030303030303030303"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> InscriptionMint.reveal(plan, commit.getOutputs().get(0), otherKey));
    }

    @Test
    public void revealSignatureVerifies() throws Exception {
        InscriptionMint.Plan plan = textPlan();
        Transaction commit = InscriptionMint.commit(wallet, plan, fund(100_000), 2.0, 1.0).getTransaction();
        TransactionOutput commitOutput = commit.getOutputs().get(0);
        Transaction reveal = InscriptionMint.reveal(plan, commitOutput, REVEAL_PRIVATE);
        Sha256Hash hash = reveal.hashForTaprootSignature(List.of(commitOutput), 0, true, plan.revealScript(), SigHash.DEFAULT, null);
        byte[] signature = reveal.getInputs().getFirst().getWitness().getPushes().getFirst();
        Assertions.assertTrue(plan.revealKey().verify(hash, TransactionSignature.decodeFromBitcoin(TransactionSignature.Type.SCHNORR, signature, false)));
    }

    @Test
    public void revealFeeMatchesRate() throws Exception {
        InscriptionMint.Plan plan = new InscriptionMint.Plan(new Inscription("image/png", new byte[3000], null), ECKey.fromPublicOnly(REVEAL_PRIVATE), destination, null, 3.5);
        Transaction commit = InscriptionMint.commit(wallet, plan, fund(100_000), 2.0, 1.0).getTransaction();
        Transaction reveal = InscriptionMint.reveal(plan, commit.getOutputs().get(0), REVEAL_PRIVATE);
        long fee = commit.getOutputs().get(0).getValue() - reveal.getOutputs().getFirst().getValue();
        Assertions.assertEquals(plan.revealFee(), fee);
        //Fee rates are per virtual byte, the weight divided by 4 and rounded up, as Litecoin Core and ord count it
        Assertions.assertEquals((long)Math.ceil(Math.ceil(reveal.getWeightUnits() / 4.0) * 3.5), fee);
    }

    @Test
    public void oversizeContentRefused() {
        int max = Inscription.maxBodyBytes("image/png");
        ECKey key = ECKey.fromPublicOnly(REVEAL_PRIVATE);
        Assertions.assertDoesNotThrow(() -> new InscriptionMint.Plan(new Inscription("image/png", new byte[max], null), key, destination, null, 1.0));
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new InscriptionMint.Plan(new Inscription("image/png", new byte[max + 1], null), key, destination, null, 1.0));
        Assertions.assertTrue(e.getMessage().contains(String.format("%,d", max)), e.getMessage());
    }

    @Test
    public void etchRevealCarriesCommitmentAndRunestone() throws Exception {
        RuneName name = RuneName.parse("SPARROW•SCRYPT•TEST");
        Runestone.Etching etching = new Runestone.Etching(name, 2, (int)'S', BigInteger.valueOf(500_000), null, false);
        //Like ord, the premine gets its own output after the inscription's, so it can be sent without the inscription
        Script runestone = Runestone.script(List.of(), 1, etching, null);
        Inscription inscription = new Inscription(Inscription.TEXT, "etched".getBytes(), name.commitment());
        InscriptionMint.Plan plan = new InscriptionMint.Plan(inscription, ECKey.fromPublicOnly(REVEAL_PRIVATE), destination, etching, 2.0);
        Assertions.assertEquals(runestone, plan.etchRunestone());
        Assertions.assertEquals(2 * CollectibleTransactions.POSTAGE + plan.revealFee(), plan.commitValue());
        Transaction commit = InscriptionMint.commit(wallet, plan, fund(100_000), 2.0, 1.0).getTransaction();
        Transaction reveal = InscriptionMint.reveal(plan, commit.getOutputs().get(0), REVEAL_PRIVATE);

        Assertions.assertEquals(InscriptionMint.COMMIT_CONFIRMATIONS - 1, reveal.getInputs().getFirst().getSequenceNumber());
        Assertions.assertEquals(3, reveal.getOutputs().size());
        Assertions.assertEquals(destination.getOutputScript(), reveal.getOutputs().get(0).getScript());
        Assertions.assertEquals(CollectibleTransactions.POSTAGE, reveal.getOutputs().get(0).getValue());
        Assertions.assertEquals(destination.getOutputScript(), reveal.getOutputs().get(1).getScript());
        Assertions.assertEquals(CollectibleTransactions.POSTAGE, reveal.getOutputs().get(1).getValue());
        Assertions.assertEquals(runestone, reveal.getOutputs().get(2).getScript());
        Assertions.assertEquals(0, reveal.getOutputs().get(2).getValue());
        String script = Utils.bytesToHex(plan.revealScript().getProgram());
        Assertions.assertTrue(script.contains("010d" + String.format("%02x", name.commitment().length) + Utils.bytesToHex(name.commitment())));
        Assertions.assertEquals((long)Math.ceil(Math.ceil(reveal.getWeightUnits() / 4.0) * 2.0), commit.getOutputs().get(0).getValue() - 2 * CollectibleTransactions.POSTAGE);

        Inscription noCommitment = Inscription.text("etched");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new InscriptionMint.Plan(noCommitment, ECKey.fromPublicOnly(REVEAL_PRIVATE), destination, etching, 2.0));
    }

    @Test
    public void etchWithoutPremineHasNoPremineOutput() throws Exception {
        RuneName name = RuneName.parse("SPARROW•SCRYPT•OPEN");
        Runestone.Terms terms = new Runestone.Terms(BigInteger.valueOf(100), BigInteger.TEN, null, null, null, null);
        Runestone.Etching etching = new Runestone.Etching(name, 0, null, BigInteger.ZERO, terms, false);
        Inscription inscription = new Inscription(Inscription.TEXT, "open".getBytes(), name.commitment());
        InscriptionMint.Plan plan = new InscriptionMint.Plan(inscription, ECKey.fromPublicOnly(REVEAL_PRIVATE), destination, etching, 2.0);
        Assertions.assertEquals(Runestone.script(List.of(), null, etching, null), plan.etchRunestone());
        Assertions.assertEquals(CollectibleTransactions.POSTAGE + plan.revealFee(), plan.commitValue());
        Transaction commit = InscriptionMint.commit(wallet, plan, fund(100_000), 2.0, 1.0).getTransaction();
        Transaction reveal = InscriptionMint.reveal(plan, commit.getOutputs().get(0), REVEAL_PRIVATE);
        Assertions.assertEquals(2, reveal.getOutputs().size());
        Assertions.assertEquals(plan.etchRunestone(), reveal.getOutputs().get(1).getScript());
    }

    @Test
    public void oversizeRunestoneRefused() {
        //Litecoin Core relays OP_RETURN outputs of at most 83 bytes: a larger etching runestone would strand the commit
        RuneName name = RuneName.parse("Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z•Z");
        BigInteger big = BigInteger.TWO.pow(100);
        Runestone.Terms terms = new Runestone.Terms(big, BigInteger.TWO.pow(20), 1L << 40, 1L << 41, 1L << 40, 1L << 41);
        Runestone.Etching etching = new Runestone.Etching(name, 38, 0x10FFFF, big, terms, true);
        Assertions.assertTrue(Runestone.script(List.of(), 1, etching, null).getProgram().length > InscriptionMint.MAX_RUNESTONE_BYTES);
        Inscription inscription = new Inscription(Inscription.TEXT, "big".getBytes(), name.commitment());
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new InscriptionMint.Plan(inscription, ECKey.fromPublicOnly(REVEAL_PRIVATE), destination, etching, 2.0));
        Assertions.assertTrue(e.getMessage().contains("83"), e.getMessage());
    }

    @Test
    public void mwebFundingRefused() {
        Wallet mweb = new Wallet("mweb");
        mweb.setPolicyType(PolicyType.SINGLE);
        mweb.setScriptType(ScriptType.MWEB);
        Assertions.assertThrows(IllegalArgumentException.class, () -> InscriptionMint.commit(mweb, textPlan(), List.of(), 2.0, 1.0));

        Address mwebAddress = new MwebAddress(new byte[66]);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new InscriptionMint.Plan(Inscription.text("x"), ECKey.fromPublicOnly(REVEAL_PRIVATE), mwebAddress, null, 2.0));
    }
}
