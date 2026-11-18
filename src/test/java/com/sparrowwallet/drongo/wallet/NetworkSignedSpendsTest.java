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
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.psbt.PSBTSignatureException;
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

    @TestFactory
    public List<DynamicTest> networkSpendsAreAcceptedAndTheirForgeriesFlagged() {
        return SPENDS.stream().map(spend -> DynamicTest.dynamicTest(spend.toString(), () -> {
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
        //Judged by the chain's rule at the height it was mined at (unconfirmed for the mempool sample)
        Integer height = spend.height() > 0 ? spend.height() : null;
        assertAccepted(wallet, transaction, lookup, walletOutputScripts, height, spend.toString());
        assertFlagged(wallet, redirect(transaction), lookup, walletOutputScripts, height, spend + " outputs changed");
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
     * A Luckycoin mainnet spend signed the way the app signs (PSBT, sign, finalise) carries signatures over the chain id hash, which is what mainnet
     * nodes check, and not over Bitcoin's. The preimages here are built by this test, not by drongo's hash functions.
     */
    @Test
    public void luckycoinMainnetSpendSignedByTheAppCommitsToTheChainId() throws Exception {
        Coin scope = Coin.beginScope(Coin.LUCKYCOIN);
        try {
            Wallet signing = UnsignedInputsAllCoinsTest.buildWallet(Coin.LUCKYCOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH);
            Assertions.assertArrayEquals(LUCKYCOIN_CHAIN_ID, signing.getSigHashPrefix());
            Transaction signed = signedLuckycoinSpend(signing);
            for(int i = 0; i < signed.getInputs().size(); i++) {
                Script spentScript = spentScript(signing, signed, i);
                Assertions.assertTrue(verifiesP2pkh(signed, i, spentScript, LUCKYCOIN_CHAIN_ID), "input " + i + " over Luckycoin's hash");
                Assertions.assertFalse(verifiesP2pkh(signed, i, spentScript, new byte[0]), "input " + i + " not over Bitcoin's");
            }

            Wallet multisig = UnsignedInputsAllCoinsTest.buildWallet(Coin.LUCKYCOIN, PolicyType.MULTI_HD, ScriptType.P2SH);
            Transaction multisigSigned = signedLuckycoinSpend(multisig);
            for(int i = 0; i < multisigSigned.getInputs().size(); i++) {
                List<ScriptChunk> chunks = multisigSigned.getInputs().get(i).getScriptSig().getChunks();
                Script redeemScript = new Script(chunks.getLast().data);
                ECKey[] keys = ScriptType.MULTISIG.getPublicKeysFromScript(redeemScript);
                for(ScriptChunk chunk : chunks.subList(1, chunks.size() - 1)) {
                    TransactionSignature signature = TransactionSignature.decodeFromBitcoin(chunk.data, false);
                    Sha256Hash revival = legacyPreimageHash(multisigSigned, i, redeemScript, LUCKYCOIN_CHAIN_ID);
                    Sha256Hash bitcoin = legacyPreimageHash(multisigSigned, i, redeemScript, new byte[0]);
                    Assertions.assertTrue(Arrays.stream(keys).anyMatch(key -> key.verify(revival, signature)), "multisig input " + i + " over Luckycoin's hash");
                    Assertions.assertTrue(Arrays.stream(keys).noneMatch(key -> key.verify(bitcoin, signature)), "multisig input " + i + " not over Bitcoin's");
                }
            }
        } finally {
            Coin.endScope(scope);
        }
    }

    /**
     * The rule goes by the tip of the chain the transaction is for: at or above 276500 the chain id, below it Bitcoin's hash, on every network.
     * Mainnet is far above it. A test chain with no known tip keeps Bitcoin's hash. No other coin ever takes a prefix.
     */
    @Test
    public void luckycoinChainIdGoesByTheTipHeight() {
        for(Integer tip : Arrays.asList(null, 0, 1000, 275999, 276499, 276500, 10_000_000)) {
            Assertions.assertArrayEquals(LUCKYCOIN_CHAIN_ID, Coin.LUCKYCOIN.getSigHashPrefix(Network.MAINNET, tip), "mainnet tip " + tip);
        }
        for(Network network : List.of(Network.TESTNET, Network.REGTEST)) {
            for(Integer tip : Arrays.asList(null, 0, 1000, 275999, 276499)) {
                Assertions.assertArrayEquals(new byte[0], Coin.LUCKYCOIN.getSigHashPrefix(network, tip), network + " tip " + tip);
            }
            for(Integer tip : List.of(276500, 276501, 10_000_000)) {
                Assertions.assertArrayEquals(LUCKYCOIN_CHAIN_ID, Coin.LUCKYCOIN.getSigHashPrefix(network, tip), network + " tip " + tip);
            }
        }
        for(Coin coin : Coin.values()) {
            if(coin == Coin.LUCKYCOIN) {
                continue;
            }
            for(Network network : List.of(Network.MAINNET, Network.TESTNET, Network.REGTEST)) {
                for(Integer tip : Arrays.asList(null, 0, 276500, 10_000_000)) {
                    Assertions.assertArrayEquals(new byte[0], coin.getSigHashPrefix(network, tip), coin.getName() + " " + network + " tip " + tip);
                }
            }
        }
    }

    /**
     * The private Luckycoin test chains are mined from the testnet genesis and are far below the height, so a wallet there signs by Bitcoin's rule as
     * their nodes check it. A test chain whose tip has reached the height signs with the chain id.
     */
    @Test
    public void luckycoinTestChainSignsByItsTip() throws Exception {
        Network.set(Network.TESTNET);
        Coin scope = Coin.beginScope(Coin.LUCKYCOIN);
        try {
            for(int tip : List.of(1000, 276499, 276500)) {
                Wallet signing = UnsignedInputsAllCoinsTest.buildWallet(Coin.LUCKYCOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH);
                UnsignedInputsAllCoinsTest.fund(signing, 0);
                UnsignedInputsAllCoinsTest.fund(signing, 1);
                PSBT psbt = UnsignedInputsAllCoinsTest.spendPsbt(signing);
                //The tip the wallet knows when it signs, set after the spend is built since the test funding sits at a fixed height above it
                signing.setStoredBlockHeight(tip);
                signing.sign(psbt);
                signing.finalise(psbt);
                Transaction signed = new Transaction(psbt.extractTransaction().bitcoinSerialize());
                boolean revival = tip >= Coin.LUCKYCOIN_CHAIN_ID_HEIGHT;
                for(int i = 0; i < signed.getInputs().size(); i++) {
                    Script spentScript = spentScript(signing, signed, i);
                    Assertions.assertEquals(revival, verifiesP2pkh(signed, i, spentScript, LUCKYCOIN_CHAIN_ID), "tip " + tip + " input " + i + " over Luckycoin's hash");
                    Assertions.assertEquals(!revival, verifiesP2pkh(signed, i, spentScript, new byte[0]), "tip " + tip + " input " + i + " over Bitcoin's hash");
                }
                Assertions.assertTrue(signing.getUnsignedInputs(signed, txid -> UnsignedInputsAllCoinsTest.body(signing, txid)).isEmpty(), "tip " + tip + " accepted by the check");
            }
        } finally {
            Coin.endScope(scope);
        }
    }

    /**
     * A Luckycoin PSBT signed elsewhere by Bitcoin's rule, as a hardware wallet or another signer would, is refused with the reason: when it is read
     * (the app reads every incoming PSBT with its signatures verified), and again by finalise, so it never reaches a broadcast. One signed by the
     * app reads, shows its signing keystore and finalises.
     */
    @Test
    public void luckycoinPsbtSignedByBitcoinsRuleIsRefused() throws Exception {
        Coin scope = Coin.beginScope(Coin.LUCKYCOIN);
        try {
            Wallet wallet = UnsignedInputsAllCoinsTest.buildWallet(Coin.LUCKYCOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH);
            UnsignedInputsAllCoinsTest.fund(wallet, 0);
            UnsignedInputsAllCoinsTest.fund(wallet, 1);

            PSBT bitcoinRule = UnsignedInputsAllCoinsTest.spendPsbt(wallet);
            Assertions.assertEquals(Coin.LUCKYCOIN, bitcoinRule.getCoin());
            byte[] unsigned = bitcoinRule.serialize();
            bitcoinRule.setSigHashPrefix(new byte[0]);
            for(Map.Entry<PSBTInput, WalletNode> entry : wallet.getSigningNodes(bitcoinRule).entrySet()) {
                entry.getKey().sign(wallet.getKeystores().getFirst().getKey(entry.getValue()));
            }
            byte[] serialized = bitcoinRule.serialize();
            PSBTSignatureException readError = Assertions.assertThrows(PSBTSignatureException.class, () -> new PSBT(serialized, true));
            Assertions.assertTrue(readError.getMessage().contains(PSBT.LUCKYCOIN_SIGNATURE_RULE), readError.getMessage());
            IllegalArgumentException finaliseError = Assertions.assertThrows(IllegalArgumentException.class, () -> wallet.finalise(bitcoinRule));
            Assertions.assertTrue(finaliseError.getMessage().contains(PSBT.LUCKYCOIN_SIGNATURE_RULE), finaliseError.getMessage());
            Assertions.assertFalse(bitcoinRule.isFinalized());

            //As the app reads a PSBT from a file, QR code or device (signatures not verified on reading), then combines it with the one it holds
            PSBT incoming = new PSBT(serialized, false);
            PSBT held = new PSBT(unsigned, false);
            PSBTSignatureException combineError = Assertions.assertThrows(PSBTSignatureException.class, () -> held.verifyCombinedSignatures(incoming));
            Assertions.assertTrue(combineError.getMessage().contains(PSBT.LUCKYCOIN_SIGNATURE_RULE), combineError.getMessage());
            Assertions.assertThrows(IllegalArgumentException.class, () -> wallet.finalise(incoming));
            Assertions.assertFalse(incoming.isFinalized());

            //One without the coin mark, from another app, opened beside a Luckycoin wallet: the finalizing wallet takes that wallet's chain
            PSBT unmarked = new PSBT(serialized, false);
            unmarked.getGlobalProprietary().remove(PSBT.COIN_MARK_KEY);
            Assertions.assertNull(unmarked.getCoin());
            Assertions.assertEquals(0, unmarked.getSigHashPrefix().length);
            FinalizingPSBTWallet finalizing = new FinalizingPSBTWallet(unmarked, wallet);
            Assertions.assertArrayEquals(LUCKYCOIN_CHAIN_ID, finalizing.getSigHashPrefix());
            Assertions.assertThrows(IllegalArgumentException.class, () -> finalizing.finalise(unmarked));
            //Without that wallet nothing says it is Luckycoin's, and it would be finalised
            PSBT unmarkedAlone = new PSBT(serialized, false);
            unmarkedAlone.getGlobalProprietary().remove(PSBT.COIN_MARK_KEY);
            new FinalizingPSBTWallet(unmarkedAlone).finalise(unmarkedAlone);
            Assertions.assertTrue(unmarkedAlone.isFinalized());

            PSBT appSigned = UnsignedInputsAllCoinsTest.spendPsbt(wallet);
            wallet.sign(appSigned);
            PSBT read = new PSBT(appSigned.serialize(), true);
            Assertions.assertTrue(wallet.getSignedKeystores(read).values().stream().allMatch(signatures -> signatures.size() == 1), "signing keystore shown");
            wallet.finalise(read);
            Assertions.assertTrue(read.isFinalized());
        } finally {
            Coin.endScope(scope);
        }
    }

    /**
     * A mined Luckycoin transaction is checked by the rule at the height of its own block (ConnectBlock), not by the tip's: one mined below 276500
     * carries Bitcoin's hash and must be accepted at its height, though the mainnet tip rule would flag it; one mined at or above carries the chain id.
     * The spends are real, from luckyscan.org (see NetworkSpends).
     */
    @Test
    public void luckycoinSpendsAreJudgedByTheHeightTheyWereMinedAt() {
        Coin scope = Coin.beginScope(Coin.LUCKYCOIN);
        try {
            NetworkSpend below = SPENDS.stream().filter(s -> s.label().equals("p2pkh mined below 276500")).findFirst().orElseThrow();
            NetworkSpend justAbove = SPENDS.stream().filter(s -> s.label().equals("p2pkh mined just above 276500")).findFirst().orElseThrow();
            NetworkSpend recent = SPENDS.stream().filter(s -> s.coin() == Coin.LUCKYCOIN && s.label().equals("p2pkh")).findFirst().orElseThrow();
            Assertions.assertTrue(below.height() < Coin.LUCKYCOIN_CHAIN_ID_HEIGHT);
            Assertions.assertTrue(justAbove.height() >= Coin.LUCKYCOIN_CHAIN_ID_HEIGHT && justAbove.height() < Coin.LUCKYCOIN_CHAIN_ID_HEIGHT + 2000);

            for(NetworkSpend spend : List.of(below, justAbove, recent)) {
                Transaction transaction = new Transaction(Utils.hexToBytes(spend.rawTransaction()));
                Map<Sha256Hash, Transaction> spentTransactions = spentTransactions(spend);
                Function<Sha256Hash, Transaction> lookup = spentTransactions::get;
                Map<Script, WalletNode> walletOutputScripts = new LinkedHashMap<>();
                Wallet wallet = watchWallet(spend, transaction, lookup, walletOutputScripts);
                assertAccepted(wallet, transaction, lookup, walletOutputScripts, spend.height(), spend + " at its height");
                if(spend == below) {
                    //Judged as an unconfirmed mainnet spend, or as if mined at the height, it would be hidden
                    assertFlagged(wallet, transaction, lookup, walletOutputScripts, null, spend + " by the mainnet tip rule");
                    assertFlagged(wallet, transaction, lookup, walletOutputScripts, Coin.LUCKYCOIN_CHAIN_ID_HEIGHT, spend + " as if mined at 276500");
                } else {
                    assertFlagged(wallet, transaction, lookup, walletOutputScripts, Coin.LUCKYCOIN_CHAIN_ID_HEIGHT - 1, spend + " as if mined at 276499");
                }
            }
        } finally {
            Coin.endScope(scope);
        }
    }

    /**
     * drongo's legacy and witness v0 hashes, with and without the chain id, against preimages built here from the parts each scheme lists
     */
    @Test
    public void signatureHashesWithTheChainIdMatchPreimagesBuiltHere() {
        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.of(new byte[] {1}), 3, new Script(new byte[0]));
        transaction.addInput(Sha256Hash.of(new byte[] {2}), 0, new Script(new byte[0]));
        transaction.getInputs().get(1).setSequenceNumber(0xfffffffdL);
        transaction.addOutput(123_456_789L, ScriptType.P2PKH.getOutputScript(ATTACKER_HASH));
        transaction.addOutput(42_000L, ScriptType.P2WPKH.getOutputScript(Utils.hexToBytes("cc00000000000000000000000000000000000033")));
        transaction.setLocktime(276600);
        Script scriptCode = ScriptType.P2PKH.getOutputScript(Utils.hexToBytes("dd00000000000000000000000000000000000044"));

        for(int i = 0; i < 2; i++) {
            for(byte[] prefix : List.of(new byte[0], LUCKYCOIN_CHAIN_ID)) {
                Assertions.assertEquals(witnessPreimageHash(transaction, i, scriptCode, 5_000_000L, prefix),
                        transaction.hashForWitnessSignature(i, scriptCode.getProgram(), 5_000_000L, SigHash.ALL.value, prefix), "witness v0 input " + i + " prefix " + prefix.length);
                Assertions.assertEquals(legacyPreimageHash(transaction, i, scriptCode, prefix),
                        transaction.hashForLegacySignature(i, scriptCode.getProgram(), SigHash.ALL.value, prefix), "legacy input " + i + " prefix " + prefix.length);
            }
            Assertions.assertNotEquals(witnessPreimageHash(transaction, i, scriptCode, 5_000_000L, new byte[0]), witnessPreimageHash(transaction, i, scriptCode, 5_000_000L, LUCKYCOIN_CHAIN_ID));
        }
    }

    private static Transaction signedLuckycoinSpend(Wallet wallet) throws Exception {
        UnsignedInputsAllCoinsTest.fund(wallet, 0);
        UnsignedInputsAllCoinsTest.fund(wallet, 1);
        return UnsignedInputsAllCoinsTest.signedSpend(wallet);
    }

    private static Script spentScript(Wallet wallet, Transaction transaction, int index) {
        TransactionInput input = transaction.getInputs().get(index);
        return UnsignedInputsAllCoinsTest.body(wallet, input.getOutpoint().getHash()).getOutputs().get((int)input.getOutpoint().getIndex()).getScript();
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
        return pubKey.verify(legacyPreimageHash(transaction, index, spentScript, chainId), signature);
    }

    /**
     * The legacy SIGHASH_ALL signature hash built here, not by drongo's hash function: the transaction without witnesses, every script sig emptied but
     * the signed input's, which carries the script code, then the sighash type, all optionally preceded by a chain id
     */
    private static Sha256Hash legacyPreimageHash(Transaction transaction, int index, Script scriptCode, byte[] chainId) {
        Transaction copy = new Transaction(transaction.bitcoinSerialize(false));
        for(int i = 0; i < copy.getInputs().size(); i++) {
            copy.getInputs().get(i).setScriptBytes(i == index ? scriptCode.getProgram() : new byte[0]);
        }
        byte[] preimage = Utils.concat(copy.bitcoinSerialize(false), new byte[] {SigHash.ALL.value, 0, 0, 0});
        return Sha256Hash.twiceOf(Utils.concat(chainId, preimage));
    }

    /**
     * The BIP143 SIGHASH_ALL signature hash built here from the parts the BIP lists, not by drongo's hash function, optionally preceded by a chain id
     * as Luckycoin's SignatureHash writes it before the version (interpreter.cpp line 1203)
     */
    private static Sha256Hash witnessPreimageHash(Transaction transaction, int index, Script scriptCode, long amount, byte[] chainId) {
        java.io.ByteArrayOutputStream prevouts = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream sequences = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream outputs = new java.io.ByteArrayOutputStream();
        for(TransactionInput input : transaction.getInputs()) {
            prevouts.writeBytes(Utils.reverseBytes(input.getOutpoint().getHash().getBytes()));
            prevouts.writeBytes(le32(input.getOutpoint().getIndex()));
            sequences.writeBytes(le32(input.getSequenceNumber()));
        }
        for(TransactionOutput output : transaction.getOutputs()) {
            outputs.writeBytes(le64(output.getValue()));
            outputs.writeBytes(new VarInt(output.getScriptBytes().length).encode());
            outputs.writeBytes(output.getScriptBytes());
        }
        TransactionInput input = transaction.getInputs().get(index);
        java.io.ByteArrayOutputStream preimage = new java.io.ByteArrayOutputStream();
        preimage.writeBytes(chainId);
        preimage.writeBytes(le32(transaction.getVersion()));
        preimage.writeBytes(Sha256Hash.hashTwice(prevouts.toByteArray()));
        preimage.writeBytes(Sha256Hash.hashTwice(sequences.toByteArray()));
        preimage.writeBytes(Utils.reverseBytes(input.getOutpoint().getHash().getBytes()));
        preimage.writeBytes(le32(input.getOutpoint().getIndex()));
        preimage.writeBytes(new VarInt(scriptCode.getProgram().length).encode());
        preimage.writeBytes(scriptCode.getProgram());
        preimage.writeBytes(le64(amount));
        preimage.writeBytes(le32(input.getSequenceNumber()));
        preimage.writeBytes(Sha256Hash.hashTwice(outputs.toByteArray()));
        preimage.writeBytes(le32(transaction.getLocktime()));
        preimage.writeBytes(le32(SigHash.ALL.value));
        return Sha256Hash.twiceOf(preimage.toByteArray());
    }

    private static byte[] le32(long value) {
        return new byte[] {(byte)value, (byte)(value >> 8), (byte)(value >> 16), (byte)(value >> 24)};
    }

    private static byte[] le64(long value) {
        byte[] bytes = new byte[8];
        for(int i = 0; i < 8; i++) {
            bytes[i] = (byte)(value >> (8 * i));
        }
        return bytes;
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
        assertAccepted(wallet, transaction, lookup, walletOutputScripts, null, message);
    }

    private static void assertAccepted(Wallet wallet, Transaction transaction, Function<Sha256Hash, Transaction> lookup, Map<Script, WalletNode> walletOutputScripts,
                                       Integer height, String message) {
        Assertions.assertEquals(transaction.getInputs().size(), wallet.getSigningNodes(transaction, lookup, walletOutputScripts).size(), message + ": inputs judged");
        Assertions.assertEquals(Set.of(), wallet.getUnsignedInputs(transaction, lookup, walletOutputScripts, height), message + ": accepted");
        Assertions.assertEquals(Set.of(), wallet.getInvalidlySignedInputs(transaction, lookup, walletOutputScripts, height), message + ": no definite finding");
    }

    private static void assertFlagged(Wallet wallet, Transaction transaction, Function<Sha256Hash, Transaction> lookup, Map<Script, WalletNode> walletOutputScripts, String message) {
        assertFlagged(wallet, transaction, lookup, walletOutputScripts, null, message);
    }

    private static void assertFlagged(Wallet wallet, Transaction transaction, Function<Sha256Hash, Transaction> lookup, Map<Script, WalletNode> walletOutputScripts,
                                      Integer height, String message) {
        Assertions.assertEquals(transaction.getInputs().size(), wallet.getSigningNodes(transaction, lookup, walletOutputScripts).size(), message + ": inputs judged");
        Assertions.assertEquals(transaction.getInputs().size(), wallet.getUnsignedInputs(transaction, lookup, walletOutputScripts, height).size(), message + ": every input flagged");
        Assertions.assertEquals(transaction.getInputs().size(), wallet.getInvalidlySignedInputs(transaction, lookup, walletOutputScripts, height).size(), message + ": a definite finding");
    }

    private static Transaction redirect(Transaction transaction) {
        Transaction altered = new Transaction(transaction.bitcoinSerialize());
        altered.getOutputs().getFirst().setScriptBytes(ScriptType.P2PKH.getOutputScript(ATTACKER_HASH).getProgram());
        altered = new Transaction(altered.bitcoinSerialize());
        Assertions.assertNotEquals(transaction.getTxId(), altered.getTxId());
        return altered;
    }
}
