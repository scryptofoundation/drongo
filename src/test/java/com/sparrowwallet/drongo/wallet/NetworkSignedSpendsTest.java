package com.sparrowwallet.drongo.wallet;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.crypto.SchnorrSignature;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.*;
import java.util.function.Function;

import static com.sparrowwallet.drongo.wallet.NetworkSpends.*;

/**
 * Signatures each chain's own nodes accepted are the ground truth for its signature hash rules. For each real spend in {@link NetworkSpends} a watch
 * wallet holding exactly the keys the spend reveals is built, and the check that keeps forged spends out of the history (getUnsignedInputs) must accept
 * the real spend and flag it once its outputs are changed. Taproot key path spends reveal only the tweaked output key, which no keystore can produce,
 * so for those the signature hash is checked against the signatures directly.
 */
public class NetworkSignedSpendsTest {
    private static final byte[] ATTACKER_HASH = Utils.hexToBytes("bb00000000000000000000000000000000000022");

    private Network previousNetwork;

    @BeforeEach
    public void setUp() {
        previousNetwork = Network.get();
        Network.set(Network.MAINNET);
    }

    @AfterEach
    public void tearDown() {
        Network.set(previousNetwork);
        Coin.set(Coin.LITECOIN);
    }

    /**
     * Luckycoin's replay protected signature hash: from height 276500 (all of mainnet now) the preimage of every signature hash starts with this chain
     * id, serialized as a uint256. LuckycoinFoundation/Luckycoin @8b1fa2b5: script/interpreter.h line 111 (CHAIN_ID, "LUCKYCOIN_REVIVAL"),
     * script/interpreter.cpp SignatureHash lines 1203 and 1247, validation.cpp GetChainSigVersion line 611.
     */
    private static final byte[] LUCKYCOIN_CHAIN_ID = Utils.reverseBytes(Utils.hexToBytes("000000000000000000000000000000" + "4c55434b59434f494e5f5245564956414c"));

    /**
     * Network spends the check gets wrong today, each pinned by its own test below rather than run with the rest
     */
    private static final Set<String> KNOWN_DEFECTS = Set.of("b001140170e57c567796b95ff719bb88265d29db82f90da30b0b9bf2f7ef47e0");

    @TestFactory
    public List<DynamicTest> networkSpendsAreAcceptedAndTheirForgeriesFlagged() {
        return SPENDS.stream().filter(spend -> !KNOWN_DEFECTS.contains(spend.txid())).map(spend -> DynamicTest.dynamicTest(spend.toString(), () -> {
            Coin scope = Coin.beginScope(spend.coin());
            try {
                check(spend);
            } finally {
                Coin.endScope(scope);
            }
        })).toList();
    }

    private static void check(NetworkSpend spend) {
        Transaction transaction = new Transaction(Utils.hexToBytes(spend.rawTransaction()));
        Assertions.assertEquals(spend.txid(), transaction.getTxId().toString(), "txid");
        Map<Sha256Hash, Transaction> spentTransactions = spentTransactions(spend);
        Function<Sha256Hash, Transaction> lookup = spentTransactions::get;

        if(spend.scriptType() == ScriptType.P2TR) {
            checkTaprootSignatures(spend, transaction, lookup);
            return;
        }

        Map<Script, WalletNode> walletOutputScripts = new LinkedHashMap<>();
        Wallet wallet = watchWallet(spend, transaction, lookup, walletOutputScripts);
        assertAccepted(wallet, transaction, lookup, walletOutputScripts, spend.toString());
        assertFlagged(wallet, redirect(transaction), lookup, walletOutputScripts, spend + " outputs changed");
    }

    /**
     * A Litecoin peg-in as a server returns it before it is mined: the canonical transaction with its MWEB data after the witnesses (flag 9), read
     * from litecoinspace.org's mempool. Once mined, explorers return it without that data (flag 1), the block keeping it in the extension block.
     * Litecoin Core's signature hash reads only vin, vout, version and lock time, so the MWEB data must change neither the txid nor the verdict
     * (the verdict on this form is one of the network spends above).
     */
    @Test
    public void pegInFromTheMempoolCarriesItsMwebDataAndTheMinedTxid() {
        NetworkSpend spend = SPENDS.stream().filter(s -> s.label().equals("peg-in as the mempool served it")).findFirst().orElseThrow();
        byte[] mempoolForm = Utils.hexToBytes(spend.rawTransaction());
        byte[] minedForm = Utils.hexToBytes(MEMPOOL_PEG_IN_AS_MINED);
        Assertions.assertEquals(0x09, mempoolForm[5], "witness and MWEB flags");
        Assertions.assertEquals(0x01, minedForm[5], "witness flag only");
        Assertions.assertTrue(mempoolForm.length > minedForm.length + 1000, "carries the MWEB transaction");

        Transaction pegIn = new Transaction(mempoolForm);
        Transaction mined = new Transaction(minedForm);
        Assertions.assertEquals(9, pegIn.getSegwitFlag());
        Assertions.assertTrue(pegIn.getOutputs().stream().anyMatch(output -> ScriptType.MWEB_PEGIN.isScriptType(output.getScript())), "carries a peg-in output");
        Assertions.assertEquals(mined.getTxId(), pegIn.getTxId());
        Assertions.assertEquals(spend.txid(), pegIn.getTxId().toString());
        Assertions.assertArrayEquals(mempoolForm, pegIn.bitcoinSerialize(), "MWEB data kept on reserialization");
        for(int i = 0; i < pegIn.getInputs().size(); i++) {
            Assertions.assertEquals(mined.hashForWitnessSignature(i, new Script(new byte[25]), 1L, SigHash.ALL),
                    pegIn.hashForWitnessSignature(i, new Script(new byte[25]), 1L, SigHash.ALL), "witness signature hash ignores the MWEB data");
            Assertions.assertEquals(mined.hashForLegacySignature(i, new Script(new byte[25]), SigHash.ALL),
                    pegIn.hashForLegacySignature(i, new Script(new byte[25]), SigHash.ALL), "legacy signature hash ignores the MWEB data");
        }
    }

    /**
     * Latent hazard, pinned: a transaction object carrying MWEB sentinel inputs (MwebServer.psbtExtract adds them to the PSBT era transaction, and
     * HeadersController stores that object as the wallet's record of an MWEB send) gets a different witness signature hash, because
     * Transaction.hashForWitnessSignature reads the raw input list where every other path filters the sentinels out. Were such a record ever held by a
     * wallet the check runs on, its own signed segwit inputs would be judged unsigned. Today it is only stored in an MWEB wallet, which the check
     * never runs on, and the record is the unsigned PSBT era transaction anyway.
     */
    @Test
    public void knownHazardMwebSentinelInputsChangeTheWitnessSignatureHash() {
        NetworkSpend spend = SPENDS.stream().filter(s -> s.label().equals("peg-in")).findFirst().orElseThrow();
        Transaction pegIn = new Transaction(Utils.hexToBytes(spend.rawTransaction()));
        Transaction withSentinels = new Transaction(Utils.hexToBytes(spend.rawTransaction()));
        withSentinels.setMwebTxId(pegIn.getTxId());
        withSentinels.addMwebOutputId(Sha256Hash.of(new byte[] {1}));
        Assertions.assertEquals(pegIn.getTxId(), withSentinels.getTxId());
        Assertions.assertEquals(pegIn.getInputs().size(), withSentinels.getInputs().size());

        Assertions.assertEquals(pegIn.hashForLegacySignature(0, new Script(new byte[25]), SigHash.ALL),
                withSentinels.hashForLegacySignature(0, new Script(new byte[25]), SigHash.ALL), "legacy signature hash leaves the sentinels out");
        Assertions.assertNotEquals(pegIn.hashForWitnessSignature(0, new Script(new byte[25]), 1L, SigHash.ALL),
                withSentinels.hashForWitnessSignature(0, new Script(new byte[25]), 1L, SigHash.ALL),
                "witness signature hash takes the sentinels into hashPrevouts and hashSequence (Transaction.hashForWitnessSignature reads the raw input list)");
    }

    /**
     * A peg-out reaches the wallet as an output of the block's HogEx, which is serialized with the MWEB flag and a null extension. Its txid, and so
     * the lookup a later spend of it needs, must come out right
     */
    @Test
    public void hogExSpentByTheWalletParses() {
        NetworkSpend spend = SPENDS.stream().filter(s -> s.label().contains("HogEx")).findFirst().orElseThrow();
        Spent hogExOutput = spend.spent().getFirst();
        Transaction hogEx = new Transaction(Utils.hexToBytes(hogExOutput.rawTransaction()));
        Assertions.assertTrue(hogEx.hasNullMwebExtension());
        Assertions.assertTrue(hogEx.isHogEx());
        Assertions.assertTrue(ScriptType.MWEB_HOGEX.isScriptType(hogEx.getOutputs().getFirst().getScript()));
        Assertions.assertEquals(hogExOutput.txid(), hogEx.getTxId().toString());
        Assertions.assertEquals(hogExOutput.value(), hogEx.getOutputs().get(hogExOutput.vout()).getValue());
    }

    /**
     * The ground truth for Luckycoin, computed here without drongo's signature hash: the mainnet signature verifies over the chain id followed by the
     * legacy preimage, and not over the legacy preimage alone, which is Bitcoin's rule and what Dogecoin, Pepecoin, Junkcoin and Dingocoin keep
     */
    @Test
    public void luckycoinMainnetSignaturesCommitToTheRevivalChainId() {
        NetworkSpend luckycoin = SPENDS.stream().filter(s -> s.coin() == Coin.LUCKYCOIN).findFirst().orElseThrow();
        Transaction transaction = new Transaction(Utils.hexToBytes(luckycoin.rawTransaction()));
        Script spentScript = new Script(Utils.hexToBytes(luckycoin.spent().getFirst().script()));
        Assertions.assertTrue(luckycoin.height() >= 276500);
        Assertions.assertTrue(verifiesP2pkh(transaction, 0, spentScript, LUCKYCOIN_CHAIN_ID), "chain id and legacy preimage");
        Assertions.assertFalse(verifiesP2pkh(transaction, 0, spentScript, new byte[0]), "legacy preimage alone");

        for(Coin coin : List.of(Coin.DOGECOIN, Coin.PEPECOIN, Coin.JUNKCOIN, Coin.DINGOCOIN)) {
            NetworkSpend spend = SPENDS.stream().filter(s -> s.coin() == coin && s.scriptType() == ScriptType.P2PKH).findFirst().orElseThrow();
            Transaction other = new Transaction(Utils.hexToBytes(spend.rawTransaction()));
            Map<Sha256Hash, Transaction> spent = spentTransactions(spend);
            for(int i = 0; i < other.getInputs().size(); i++) {
                TransactionInput input = other.getInputs().get(i);
                Script script = spent.get(input.getOutpoint().getHash()).getOutputs().get((int)input.getOutpoint().getIndex()).getScript();
                Assertions.assertTrue(verifiesP2pkh(other, i, script, new byte[0]), coin.getName() + " input " + i + " legacy preimage alone");
                Assertions.assertFalse(verifiesP2pkh(other, i, script, LUCKYCOIN_CHAIN_ID), coin.getName() + " input " + i + " with the Luckycoin chain id");
            }
        }
    }

    /**
     * Known defect, pinned so a fix flips it: drongo hashes Luckycoin as Bitcoin, without the chain id. The check therefore judges a real mainnet
     * Luckycoin spend from the wallet unsigned (it would be dropped from the history with a warning that the server is dishonest), and every Luckycoin
     * signature drongo makes commits to a hash mainnet nodes do not check, so they reject the transaction. When fixed, this test fails: move the
     * spend out of KNOWN_DEFECTS and replace this test.
     */
    @Test
    public void knownDefectLuckycoinMainnetSignatureHashLacksTheChainId() throws Exception {
        NetworkSpend luckycoin = SPENDS.stream().filter(s -> s.coin() == Coin.LUCKYCOIN).findFirst().orElseThrow();
        Coin scope = Coin.beginScope(Coin.LUCKYCOIN);
        try {
            Transaction transaction = new Transaction(Utils.hexToBytes(luckycoin.rawTransaction()));
            Map<Sha256Hash, Transaction> spentTransactions = spentTransactions(luckycoin);
            Function<Sha256Hash, Transaction> lookup = spentTransactions::get;
            Map<Script, WalletNode> walletOutputScripts = new LinkedHashMap<>();
            Wallet wallet = watchWallet(luckycoin, transaction, lookup, walletOutputScripts);
            Assertions.assertEquals(1, wallet.getSigningNodes(transaction, lookup, walletOutputScripts).size());
            Assertions.assertEquals(1, wallet.getUnsignedInputs(transaction, lookup, walletOutputScripts).size(), "the real spend is judged unsigned");

            //A spend signed by a Luckycoin wallet here, the way the app signs: its signatures do not verify under the hash mainnet checks
            Wallet signing = UnsignedInputsAllCoinsTest.buildWallet(Coin.LUCKYCOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH);
            UnsignedInputsAllCoinsTest.fund(signing, 0);
            UnsignedInputsAllCoinsTest.fund(signing, 1);
            Transaction signed = UnsignedInputsAllCoinsTest.signedSpend(signing);
            for(int i = 0; i < signed.getInputs().size(); i++) {
                TransactionInput input = signed.getInputs().get(i);
                Script spentScript = UnsignedInputsAllCoinsTest.body(signing, input.getOutpoint().getHash()).getOutputs().get((int)input.getOutpoint().getIndex()).getScript();
                Assertions.assertTrue(verifiesP2pkh(signed, i, spentScript, new byte[0]), "input " + i + " signed over Bitcoin's legacy hash");
                Assertions.assertFalse(verifiesP2pkh(signed, i, spentScript, LUCKYCOIN_CHAIN_ID), "input " + i + " not over Luckycoin's");
            }
        } finally {
            Coin.endScope(scope);
        }
    }

    /**
     * Verifies a P2PKH input's signature over a legacy SIGHASH_ALL preimage built here (the transaction without witnesses, every script sig emptied
     * but the signed input's, which carries the spent script), optionally preceded by a chain id
     */
    private static boolean verifiesP2pkh(Transaction transaction, int index, Script spentScript, byte[] chainId) {
        List<ScriptChunk> chunks = transaction.getInputs().get(index).getScriptSig().getChunks();
        TransactionSignature signature;
        try {
            signature = TransactionSignature.decodeFromBitcoin(chunks.get(0).data, false);
        } catch(SignatureDecodeException e) {
            throw new IllegalStateException(e);
        }
        Assertions.assertEquals(SigHash.ALL.value, signature.sighashFlags);
        ECKey pubKey = ECKey.fromPublicOnly(chunks.get(1).data);

        Transaction copy = new Transaction(transaction.bitcoinSerialize(false));
        for(int i = 0; i < copy.getInputs().size(); i++) {
            copy.getInputs().get(i).setScriptBytes(i == index ? spentScript.getProgram() : new byte[0]);
        }
        byte[] preimage = Utils.concat(copy.bitcoinSerialize(false), new byte[] {SigHash.ALL.value, 0, 0, 0});
        return pubKey.verify(Sha256Hash.twiceOf(Utils.concat(chainId, preimage)), signature);
    }

    private static void checkTaprootSignatures(NetworkSpend spend, Transaction transaction, Function<Sha256Hash, Transaction> lookup) {
        List<TransactionOutput> spentOutputs = new ArrayList<>();
        for(TransactionInput input : transaction.getInputs()) {
            spentOutputs.add(lookup.apply(input.getOutpoint().getHash()).getOutputs().get((int)input.getOutpoint().getIndex()));
        }

        for(int i = 0; i < spentOutputs.size(); i++) {
            byte[] script = spentOutputs.get(i).getScriptBytes();
            Assertions.assertTrue(ScriptType.P2TR.isScriptType(spentOutputs.get(i).getScript()), "input " + i + " spends a taproot output");
            List<byte[]> pushes = transaction.getInputs().get(i).getWitness().getPushes();
            Assertions.assertEquals(1, pushes.size(), "key path spend");
            byte[] witness = pushes.getFirst();
            byte sigHashType = witness.length == 65 ? witness[64] : SigHash.DEFAULT.byteValue();
            SchnorrSignature signature = SchnorrSignature.decode(Arrays.copyOf(witness, 64));
            byte[] outputKey = Arrays.copyOfRange(script, 2, 34);
            Assertions.assertTrue(signature.verify(transaction.hashForTaprootSignature(spentOutputs, i, false, null, sigHashType, null).getBytes(), outputKey),
                    spend + " input " + i);
            Transaction redirected = redirect(transaction);
            Assertions.assertFalse(signature.verify(redirected.hashForTaprootSignature(spentOutputs, i, false, null, sigHashType, null).getBytes(), outputKey),
                    spend + " input " + i + " outputs changed");
        }
    }

    private static Map<Sha256Hash, Transaction> spentTransactions(NetworkSpend spend) {
        Map<Sha256Hash, Transaction> transactions = new HashMap<>();
        Map<String, List<Spent>> byTxid = new LinkedHashMap<>();
        spend.spent().forEach(spent -> byTxid.computeIfAbsent(spent.txid(), txid -> new ArrayList<>()).add(spent));
        for(Map.Entry<String, List<Spent>> entry : byTxid.entrySet()) {
            Spent first = entry.getValue().getFirst();
            Transaction transaction;
            if(first.rawTransaction() != null) {
                transaction = new Transaction(Utils.hexToBytes(first.rawTransaction()));
                Assertions.assertEquals(first.txid(), transaction.getTxId().toString(), "spent txid");
            } else {
                //Only the spent outputs, as the explorer reports them, placed at their indexes
                int last = entry.getValue().stream().mapToInt(Spent::vout).max().orElseThrow();
                transaction = new Transaction();
                for(int i = 0; i <= last; i++) {
                    int index = i;
                    Spent spent = entry.getValue().stream().filter(s -> s.vout() == index).findFirst().orElse(null);
                    transaction.addOutput(spent == null ? 0L : spent.value(), new Script(spent == null ? new byte[0] : Utils.hexToBytes(spent.script())));
                }
            }
            for(Spent spent : entry.getValue()) {
                TransactionOutput output = transaction.getOutputs().get(spent.vout());
                Assertions.assertEquals(spent.value(), output.getValue(), "spent value");
                Assertions.assertEquals(spent.script(), Utils.bytesToHex(output.getScriptBytes()), "spent script");
            }
            transactions.put(Sha256Hash.wrap(entry.getKey()), transaction);
        }

        return transactions;
    }

    /**
     * A wallet whose keystores answer, for the node standing for each input, with the public keys that input reveals. It holds no extended keys, but
     * the check never derives: it maps the spent output script to a node and asks the node's keystores for their keys.
     */
    private static Wallet watchWallet(NetworkSpend spend, Transaction transaction, Function<Sha256Hash, Transaction> lookup, Map<Script, WalletNode> walletOutputScripts) {
        ScriptType scriptType = spend.scriptType();
        boolean multisig = scriptType == ScriptType.P2SH || scriptType == ScriptType.P2SH_P2WSH || scriptType == ScriptType.P2WSH;
        List<List<ECKey>> inputKeys = new ArrayList<>();
        Integer threshold = null;
        for(TransactionInput input : transaction.getInputs()) {
            if(multisig) {
                Script script = scriptType == ScriptType.P2SH ? new Script(input.getScriptSig().getChunks().getLast().data) : new Script(input.getWitness().getPushes().getLast());
                Assertions.assertTrue(ScriptType.MULTISIG.isScriptType(script), spend + " spends a multisig script");
                inputKeys.add(Arrays.asList(ScriptType.MULTISIG.getPublicKeysFromScript(script)));
                int inputThreshold = ScriptType.MULTISIG.getThreshold(script);
                Assertions.assertTrue(threshold == null || threshold == inputThreshold, "one threshold");
                threshold = inputThreshold;
            } else if(scriptType == ScriptType.P2PKH) {
                inputKeys.add(List.of(ECKey.fromPublicOnly(input.getScriptSig().getChunks().get(1).data)));
            } else {
                inputKeys.add(List.of(ECKey.fromPublicOnly(input.getWitness().getPushes().get(1))));
            }
        }

        int keystoreCount = inputKeys.getFirst().size();
        Wallet wallet = new Wallet(spend.toString());
        wallet.setCoin(spend.coin());
        wallet.setPolicyType(multisig ? PolicyType.MULTI_HD : PolicyType.SINGLE_HD);
        wallet.setScriptType(scriptType);
        for(int k = 0; k < keystoreCount; k++) {
            int keystoreIndex = k;
            Keystore keystore = new Keystore("Keystore " + (k + 1)) {
                @Override
                public ECKey getPubKey(WalletNode walletNode) {
                    return inputKeys.get(walletNode.getIndex()).get(keystoreIndex);
                }
            };
            wallet.getKeystores().add(keystore);
        }
        wallet.setDefaultPolicy(Policy.getPolicy(wallet.getPolicyType(), scriptType, wallet.getKeystores(), threshold));

        for(int i = 0; i < transaction.getInputs().size(); i++) {
            TransactionInput input = transaction.getInputs().get(i);
            Script spentScript = lookup.apply(input.getOutpoint().getHash()).getOutputs().get((int)input.getOutpoint().getIndex()).getScript();
            Assertions.assertTrue(scriptType.isScriptType(spentScript), spend + " input " + i + " spends a " + scriptType.getName() + " output");
            walletOutputScripts.putIfAbsent(spentScript, new WalletNode(wallet, KeyPurpose.RECEIVE, i));
        }

        return wallet;
    }

    private static void assertAccepted(Wallet wallet, Transaction transaction, Function<Sha256Hash, Transaction> lookup, Map<Script, WalletNode> walletOutputScripts, String message) {
        Assertions.assertEquals(transaction.getInputs().size(), wallet.getSigningNodes(transaction, lookup, walletOutputScripts).size(), message + ": inputs judged");
        Assertions.assertEquals(Set.of(), wallet.getUnsignedInputs(transaction, lookup, walletOutputScripts), message + ": accepted");
    }

    private static void assertFlagged(Wallet wallet, Transaction transaction, Function<Sha256Hash, Transaction> lookup, Map<Script, WalletNode> walletOutputScripts, String message) {
        Assertions.assertEquals(transaction.getInputs().size(), wallet.getSigningNodes(transaction, lookup, walletOutputScripts).size(), message + ": inputs judged");
        Assertions.assertEquals(transaction.getInputs().size(), wallet.getUnsignedInputs(transaction, lookup, walletOutputScripts).size(), message + ": every input flagged");
    }

    private static Transaction redirect(Transaction transaction) {
        Transaction altered = new Transaction(transaction.bitcoinSerialize());
        altered.getOutputs().getFirst().setScriptBytes(ScriptType.P2PKH.getOutputScript(ATTACKER_HASH).getProgram());
        altered = new Transaction(altered.bitcoinSerialize());
        Assertions.assertNotEquals(transaction.getTxId(), altered.getTxId());
        return altered;
    }
}
