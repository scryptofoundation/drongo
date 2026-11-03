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
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

public class CollectibleTransactionsTest {
    private static final List<String> WORDS = List.of("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "));
    private Coin previousCoin;
    private Wallet wallet;
    private Address recipient;
    private final List<WalletNode> nodes = new ArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        previousCoin = Coin.beginScope(Coin.LITECOIN);
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(WORDS), "", 0, DeterministicSeed.Type.BIP39);
        wallet = new Wallet("taproot");
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(ScriptType.P2TR);
        Keystore keystore = Keystore.fromSeed(seed, PolicyType.SINGLE_HD, ScriptType.P2TR.getDefaultDerivation());
        wallet.getKeystores().add(keystore);
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2TR, wallet.getKeystores(), 1));
        WalletNode receive = wallet.getNode(KeyPurpose.RECEIVE);
        receive.fillToIndex(10);
        wallet.getNode(KeyPurpose.CHANGE).fillToIndex(10);
        nodes.addAll(receive.getChildren());
        recipient = ScriptType.P2TR.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(BigInteger.valueOf(42)));
    }

    @AfterEach
    public void tearDown() {
        if(dogeScope != null) {
            Coin.endScope(dogeScope);
        }
        Coin.endScope(previousCoin);
    }

    private Coin dogeScope;

    /** Replaces the Litecoin taproot wallet with a Dogecoin P2PKH one, for the per-coin postage tests. */
    private void useDogecoin() throws Exception {
        dogeScope = Coin.beginScope(Coin.DOGECOIN);
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(WORDS), "", 0, DeterministicSeed.Type.BIP39);
        wallet = new Wallet("doge");
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(ScriptType.P2PKH);
        Keystore keystore = Keystore.fromSeed(seed, PolicyType.SINGLE_HD, ScriptType.P2PKH.getDefaultDerivation());
        wallet.getKeystores().add(keystore);
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2PKH, wallet.getKeystores(), 1));
        WalletNode receive = wallet.getNode(KeyPurpose.RECEIVE);
        receive.fillToIndex(10);
        wallet.getNode(KeyPurpose.CHANGE).fillToIndex(10);
        nodes.clear();
        nodes.addAll(receive.getChildren());
        recipient = ScriptType.P2PKH.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(BigInteger.valueOf(42)));
    }

    /** A confirmed transaction paying each value to a receive node in turn, registered with the wallet. */
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

    @Test
    public void inscriptionIsInputZeroAndOutputZeroKeepsItsValue() throws Exception {
        List<CollectibleTransactions.Pinned> utxos = fund(10_000, 100_000, 50_000);
        WalletTransaction wtx = CollectibleTransactions.sendInscription(wallet, utxos.get(0), recipient, utxos.subList(1, 3), 2.0, 1.0);
        Transaction tx = wtx.getTransaction();
        Assertions.assertEquals(utxos.get(0).utxo().getHash(), tx.getInputs().get(0).getOutpoint().getHash());
        Assertions.assertEquals(0, tx.getInputs().get(0).getOutpoint().getIndex());
        Assertions.assertEquals(10_000, tx.getOutputs().get(0).getValue());
        Assertions.assertEquals(recipient.getOutputScript(), tx.getOutputs().get(0).getScript());
    }

    @Test
    public void postagePerCoin() {
        Assertions.assertEquals(10_000, CollectibleTransactions.postage(Coin.LITECOIN, ScriptType.P2TR));
        Assertions.assertEquals(1_000, CollectibleTransactions.postage(Coin.BELLSCOIN, ScriptType.P2TR));
        Assertions.assertEquals(100_000, CollectibleTransactions.postage(Coin.BELLSCOIN, ScriptType.P2WPKH));
        Assertions.assertEquals(100_000, CollectibleTransactions.postage(Coin.DOGECOIN, ScriptType.P2PKH));
        Assertions.assertEquals(100_000, CollectibleTransactions.postage(Coin.JUNKCOIN, ScriptType.P2PKH));
    }

    @Test
    public void offsetZeroSendsWholeOutput() throws Exception {
        useDogecoin();
        List<CollectibleTransactions.Pinned> utxos = fund(100_000, 5_000_000);
        Transaction tx = CollectibleTransactions.sendInscription(wallet, utxos.get(0), 0, 1, recipient, utxos.subList(1, 2), 2.0, 1.0).getTransaction();
        Assertions.assertEquals(100_000, tx.getOutputs().get(0).getValue());
        Assertions.assertEquals(utxos.get(0).utxo().getHash(), tx.getInputs().get(0).getOutpoint().getHash());
        Assertions.assertEquals(0, tx.getInputs().get(0).getOutpoint().getIndex());
    }

    @Test
    public void splitAtOffset() throws Exception {
        useDogecoin();
        List<CollectibleTransactions.Pinned> utxos = fund(5_000_000, 1_000_000);
        WalletTransaction wtx = CollectibleTransactions.sendInscription(wallet, utxos.get(0), 300_000, 1, recipient, utxos.subList(1, 2), 2.0, 1.0);
        Transaction tx = wtx.getTransaction();
        Assertions.assertEquals(400_000, tx.getOutputs().get(0).getValue());
        Assertions.assertEquals(recipient.getOutputScript(), tx.getOutputs().get(0).getScript());
        //The 4,600,000 left over covers the fee and the soft dust surcharge, so the fee candidate stays unspent
        Assertions.assertEquals(1, tx.getInputs().size());
        Assertions.assertEquals(0, tx.getInputs().get(0).getOutpoint().getIndex());
        Assertions.assertEquals(2, tx.getOutputs().size());
        Assertions.assertInstanceOf(WalletTransaction.ChangeOutput.class, wtx.getOutputs().get(1));
        Assertions.assertEquals(4_600_000 - wtx.getFee(), tx.getOutputs().get(1).getValue());
    }

    @Test
    public void noSplitWithSeveralInscriptions() throws Exception {
        useDogecoin();
        List<CollectibleTransactions.Pinned> utxos = fund(5_000_000, 1_000_000);
        Transaction tx = CollectibleTransactions.sendInscription(wallet, utxos.get(0), 300_000, 2, recipient, utxos.subList(1, 2), 2.0, 1.0).getTransaction();
        Assertions.assertEquals(5_000_000, tx.getOutputs().get(0).getValue());
    }

    @Test
    public void litecoinSixArgUnchanged() throws Exception {
        List<CollectibleTransactions.Pinned> utxos = fund(10_000, 100_000, 50_000);
        Transaction six = CollectibleTransactions.sendInscription(wallet, utxos.get(0), recipient, utxos.subList(1, 3), 2.0, 1.0).getTransaction();
        Transaction eight = CollectibleTransactions.sendInscription(wallet, utxos.get(0), 0, 1, recipient, utxos.subList(1, 3), 2.0, 1.0).getTransaction();
        Assertions.assertEquals(10_000, six.getOutputs().get(0).getValue());
        Assertions.assertEquals(2, six.getOutputs().size());
        Assertions.assertEquals(Utils.bytesToHex(eight.bitcoinSerialize()), Utils.bytesToHex(six.bitcoinSerialize()));
    }
    @Test
    public void dogeSendPaysSoftDustSurcharge() throws Exception {
        useDogecoin();
        List<CollectibleTransactions.Pinned> utxos = fund(100_000, 5_000_000);
        WalletTransaction wtx = CollectibleTransactions.sendInscription(wallet, utxos.get(0), 0, 1, recipient, utxos.subList(1, 2), 2.0, 1.0);
        long sizeFee = (long)Math.ceil(wtx.getTransaction().getVirtualSize() * 2.0);
        Assertions.assertTrue(wtx.getFee() >= sizeFee + Coin.DOGECOIN.getSoftDustSurcharge(), "fee " + wtx.getFee() + " vs size fee " + sizeFee);
        Assertions.assertEquals(1_000_000, Coin.DOGECOIN.getSoftDustSurcharge());
    }

    @Test
    public void belowHardDustRefused() throws Exception {
        useDogecoin();
        List<CollectibleTransactions.Pinned> utxos = fund(99_999, 5_000_000);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleTransactions.sendInscription(wallet, utxos.get(0), 0, 1, recipient, utxos.subList(1, 2), 2.0, 1.0));
    }

    @Test
    public void litecoinNoSurcharge() throws Exception {
        List<CollectibleTransactions.Pinned> utxos = fund(10_000, 100_000);
        WalletTransaction wtx = CollectibleTransactions.sendInscription(wallet, utxos.get(0), 0, 1, recipient, utxos.subList(1, 2), 2.0, 1.0);
        Assertions.assertEquals(0, Coin.LITECOIN.getSoftDustSurcharge());
        Assertions.assertEquals((long)Math.ceil(wtx.getTransaction().getVirtualSize() * 2.0), wtx.getFee());
    }
    @Test
    public void mintRuneOutputs() throws Exception {
        List<CollectibleTransactions.Pinned> utxos = fund(100_000);
        WalletTransaction wtx = CollectibleTransactions.mintRune(wallet, new RuneId(111, 1), recipient, utxos, 2.0, 1.0);
        Transaction tx = wtx.getTransaction();
        Assertions.assertTrue(wtx.isFixedOutputOrder());
        Assertions.assertEquals(3, tx.getOutputs().size());
        Assertions.assertEquals(recipient.getOutputScript(), tx.getOutputs().get(0).getScript());
        Assertions.assertEquals(CollectibleTransactions.POSTAGE, tx.getOutputs().get(0).getValue());
        //Mint 111:1, pointer 0: the minted runes go to the recipient
        Assertions.assertEquals("6a5d06146f14011600", Utils.bytesToHex(tx.getOutputs().get(1).getScript().getProgram()));
        Assertions.assertEquals(0, tx.getOutputs().get(1).getValue());
        Assertions.assertTrue(wtx.getOutputs().get(2) instanceof WalletTransaction.ChangeOutput);

        Address mweb = new MwebAddress(new byte[66]);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleTransactions.mintRune(wallet, new RuneId(111, 1), mweb, utxos, 2.0, 1.0));
    }

    @Test
    public void collectibleMovesAreRecognisedForFeeBumps() throws Exception {
        List<CollectibleTransactions.Pinned> utxos = fund(10_000, 100_000, 100_000, 50_000);
        utxos.get(0).utxo().setStatus(Status.PROTECTED);
        Transaction inscriptionSend = CollectibleTransactions.sendInscription(wallet, utxos.get(0), recipient, utxos.subList(1, 2), 2.0, 1.0).getTransaction();
        Assertions.assertTrue(CollectibleTransactions.movesCollectibles(wallet, inscriptionSend), "spends a protected output");

        //A rune input not yet stamped is still caught by the runestone
        Transaction runeSend = CollectibleTransactions.sendRunes(wallet, utxos.subList(3, 4), recipient, new RuneId(111, 1), BigInteger.TEN,
                utxos.subList(2, 3), 2.0, 1.0).getTransaction();
        Assertions.assertTrue(CollectibleTransactions.movesCollectibles(wallet, runeSend), "carries a runestone");

        Transaction plain = new Transaction();
        plain.addInput(utxos.get(2).utxo().getHash(), utxos.get(2).utxo().getIndex(), new Script(new byte[0]));
        plain.addOutput(90_000, recipient);
        Assertions.assertFalse(CollectibleTransactions.movesCollectibles(wallet, plain));

        //A mint's commit looks plain, but a fee bump would orphan its reveal
        wallet.getPendingMints().add(new PendingMint(PendingMint.Kind.INSCRIPTION, "m/0/1", new byte[32], plain.getTxId(), 0, 10_330, "text/plain", null));
        Assertions.assertTrue(CollectibleTransactions.movesCollectibles(wallet, plain), "a pending mint's commit");

        //The record may live in the seed's taproot wallet file rather than the funding wallet's
        wallet.getPendingMints().clear();
        Wallet taprootFile = new Wallet("taproot file");
        taprootFile.getPendingMints().add(new PendingMint(PendingMint.Kind.INSCRIPTION, "m/0/1", new byte[32], plain.getTxId(), 0, 10_330, "text/plain", null));
        Assertions.assertFalse(CollectibleTransactions.movesCollectibles(wallet, plain, List.of()));
        Assertions.assertTrue(CollectibleTransactions.movesCollectibles(wallet, plain, List.of(taprootFile)), "a commit recorded in another wallet file");
    }

    @Test
    public void feeComesFromCandidatesLargestFirstAndChangeIsLast() throws Exception {
        List<CollectibleTransactions.Pinned> utxos = fund(10_000, 50_000, 100_000);
        WalletTransaction wtx = CollectibleTransactions.sendInscription(wallet, utxos.get(0), recipient, utxos.subList(1, 3), 2.0, 1.0);
        Transaction tx = wtx.getTransaction();
        Assertions.assertEquals(2, tx.getInputs().size());
        Assertions.assertEquals(2, tx.getInputs().get(1).getOutpoint().getIndex(), "the 100,000 candidate is used first");
        Assertions.assertEquals(2, tx.getOutputs().size());
        Assertions.assertInstanceOf(WalletTransaction.ChangeOutput.class, wtx.getOutputs().get(1));
        long in = 10_000 + 100_000;
        long out = tx.getOutputs().stream().mapToLong(TransactionOutput::getValue).sum();
        Assertions.assertEquals(wtx.getFee(), in - out);
    }

    @Test
    public void noChangeWhenRemainderIsDust() throws Exception {
        List<CollectibleTransactions.Pinned> utxos = fund(10_000, 400);
        //At 1 lit/vB a 2-in 1-out taproot spend costs about 160; the remaining ~240 is below the change dust threshold
        WalletTransaction wtx = CollectibleTransactions.sendInscription(wallet, utxos.get(0), recipient, utxos.subList(1, 2), 1.0, 1.0);
        Assertions.assertEquals(1, wtx.getTransaction().getOutputs().size());
        Assertions.assertEquals(400, wtx.getFee());
    }

    /** Change below the wallet's coin's dust limit is never made, whichever coin has the focus while the send is built. */
    @Test
    public void changeDustFollowsTheWalletsCoinNotTheFocusedOne() throws Exception {
        useDogecoin();
        wallet.setCoin(Coin.DOGECOIN);
        //100,000 inscription plus 1,000,000 surcharge, a fee under 2,000, and about 500,000 left: dust on Dogecoin, not on Litecoin
        List<CollectibleTransactions.Pinned> utxos = fund(100_000, 1_502_000);
        Coin focused = Coin.beginScope(Coin.LITECOIN);
        WalletTransaction wtx;
        try {
            wtx = CollectibleTransactions.sendInscription(wallet, utxos.get(0), 0, 1, recipient, utxos.subList(1, 2), 2.0, 1.0);
        } finally {
            Coin.endScope(focused);
        }
        Assertions.assertEquals(1, wtx.getTransaction().getOutputs().size(), "no change under Dogecoin's dust limit");
        Assertions.assertEquals(1_502_000, wtx.getFee());
    }

    @Test
    public void notEnoughFeeCandidatesThrows() {
        List<CollectibleTransactions.Pinned> utxos = fund(10_000, 50);
        Assertions.assertThrows(InsufficientFundsException.class, () -> CollectibleTransactions.sendInscription(wallet, utxos.get(0), recipient, utxos.subList(1, 2), 1.0, 1.0));
        Assertions.assertThrows(InsufficientFundsException.class, () -> CollectibleTransactions.sendInscription(wallet, utxos.get(0), recipient, List.of(), 1.0, 1.0));
    }

    @Test
    public void runeSendLayout() throws Exception {
        List<CollectibleTransactions.Pinned> utxos = fund(10_000, 200_000);
        RuneId id = new RuneId(111, 1);
        WalletTransaction wtx = CollectibleTransactions.sendRunes(wallet, utxos.subList(0, 1), recipient, id, BigInteger.valueOf(10_000), utxos.subList(1, 2), 2.0, 1.0);
        List<TransactionOutput> outputs = wtx.getTransaction().getOutputs();
        Assertions.assertEquals(4, outputs.size());
        Assertions.assertEquals(recipient.getOutputScript(), outputs.get(0).getScript());
        Assertions.assertEquals(CollectibleTransactions.POSTAGE, outputs.get(0).getValue());
        Assertions.assertEquals(CollectibleTransactions.POSTAGE, outputs.get(1).getValue());
        Assertions.assertInstanceOf(WalletTransaction.WalletNodeOutput.class, wtx.getOutputs().get(1), "the rune change belongs to this wallet");
        Assertions.assertEquals(Runestone.script(List.of(new Runestone.Edict(id, BigInteger.valueOf(10_000), 0)), 1), outputs.get(2).getScript());
        Assertions.assertEquals(0, outputs.get(2).getValue());
        Assertions.assertInstanceOf(WalletTransaction.ChangeOutput.class, wtx.getOutputs().get(3));
    }

    @Test
    public void runeSendWithoutLtcChangeKeepsIndexes() throws Exception {
        //Rune input 10,000 pays the two postages less 10,000; the fee candidate leaves only dust after the fee
        List<CollectibleTransactions.Pinned> utxos = fund(10_000, 10_000 + 900);
        WalletTransaction wtx = CollectibleTransactions.sendRunes(wallet, utxos.subList(0, 1), recipient, new RuneId(111, 1), BigInteger.ONE, utxos.subList(1, 2), 1.0, 1.0);
        List<TransactionOutput> outputs = wtx.getTransaction().getOutputs();
        Assertions.assertEquals(3, outputs.size());
        Assertions.assertEquals(Runestone.script(List.of(new Runestone.Edict(new RuneId(111, 1), BigInteger.ONE, 0)), 1), outputs.get(2).getScript());
    }

    @Test
    public void feeIsAtLeastRateTimesVsize() throws Exception {
        List<CollectibleTransactions.Pinned> utxos = fund(10_000, 100_000);
        WalletTransaction wtx = CollectibleTransactions.sendInscription(wallet, utxos.get(0), recipient, utxos.subList(1, 2), 5.0, 1.0);
        Assertions.assertTrue(wtx.getFee() >= Math.ceil(wtx.getTransaction().getVirtualSize() * 5.0));
    }

    @Test
    public void mwebRecipientRefused() {
        List<CollectibleTransactions.Pinned> utxos = fund(10_000, 100_000);
        Address mweb = new MwebAddress(new byte[66]);
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleTransactions.sendInscription(wallet, utxos.get(0), mweb, utxos.subList(1, 2), 1.0, 1.0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> CollectibleTransactions.sendRunes(wallet, utxos.subList(0, 1), mweb, new RuneId(1, 1), BigInteger.ONE, utxos.subList(1, 2), 1.0, 1.0));
    }

    @Test
    public void psbtKeepsTheOutputOrder() throws Exception {
        List<CollectibleTransactions.Pinned> utxos = fund(10_000, 200_000);
        for(int i = 0; i < 20; i++) {
            WalletTransaction wtx = CollectibleTransactions.sendRunes(wallet, utxos.subList(0, 1), recipient, new RuneId(111, 1), BigInteger.TEN, utxos.subList(1, 2), 2.0, 1.0);
            PSBT psbt = wtx.createPSBT();
            for(int j = 0; j < wtx.getTransaction().getOutputs().size(); j++) {
                Assertions.assertEquals(wtx.getTransaction().getOutputs().get(j).getScript(), psbt.getTransaction().getOutputs().get(j).getScript());
            }
        }
    }
}
