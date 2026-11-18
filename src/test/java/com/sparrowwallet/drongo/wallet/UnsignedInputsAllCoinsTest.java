package com.sparrowwallet.drongo.wallet;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.P2PKHAddress;
import com.sparrowwallet.drongo.crypto.ECDSASignature;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/**
 * The check that keeps a server from putting a forged spend of the wallet's coins into its history (getUnsignedInputs), on every coin and every
 * script type the app lets a wallet use there. Each case signs a two input spend the way the app does (PSBT, sign, finalise, extract), shows the
 * check accepts it, and shows it flags copies a server could forge from it: outputs changed with the signatures kept, signatures made by another key
 * (with that key's public key and with the wallet's), a signature hash flag changed, and for multisig too few distinct signatures.
 * <p>
 * Signing and the check share drongo's signature hash, so these cases show they agree with each other, not with a chain. NetworkSignedSpendsTest holds
 * them to signatures each chain accepted, Luckycoin's chain id included.
 */
public class UnsignedInputsAllCoinsTest {
    private static final String TEST_MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    private static final int HEIGHT = 800000;
    private static final long FUNDING_VALUE = 5_000_000_000L;
    private static final long PAYMENT_VALUE = 7_000_000_000L;
    private static final ECKey ATTACKER = ECKey.fromPrivate(Utils.hexToBytes("1111111111111111111111111111111111111111111111111111111111111111"));
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
     * The script types the cases below run over, as the app offers them, so a coin gaining or losing one is noticed here
     */
    @Test
    public void scriptTypesOfferedPerCoin() {
        Map<Coin, List<ScriptType>> single = new LinkedHashMap<>();
        Map<Coin, List<ScriptType>> multi = new LinkedHashMap<>();
        for(Coin coin : Coin.values()) {
            Coin previous = Coin.beginScope(coin);
            try {
                single.put(coin, ScriptType.getAddressableScriptTypes(PolicyType.SINGLE_HD));
                multi.put(coin, ScriptType.getAddressableScriptTypes(PolicyType.MULTI_HD));
            } finally {
                Coin.endScope(previous);
            }
        }

        List<ScriptType> segwitSingle = List.of(ScriptType.P2PKH, ScriptType.P2SH_P2WPKH, ScriptType.P2WPKH, ScriptType.P2TR);
        List<ScriptType> segwitMulti = List.of(ScriptType.P2SH, ScriptType.P2SH_P2WSH, ScriptType.P2WSH);
        List<ScriptType> ltcSingle = new ArrayList<>(segwitSingle);
        ltcSingle.add(ScriptType.MWEB);
        Assertions.assertEquals(ltcSingle, single.get(Coin.LITECOIN));
        Assertions.assertEquals(segwitMulti, multi.get(Coin.LITECOIN));
        Assertions.assertEquals(segwitSingle, single.get(Coin.BELLSCOIN));
        Assertions.assertEquals(segwitMulti, multi.get(Coin.BELLSCOIN));
        for(Coin coin : List.of(Coin.DOGECOIN, Coin.PEPECOIN, Coin.LUCKYCOIN, Coin.DINGOCOIN, Coin.JUNKCOIN)) {
            Assertions.assertEquals(List.of(ScriptType.P2PKH), single.get(coin), coin.getName());
            Assertions.assertEquals(List.of(ScriptType.P2SH), multi.get(coin), coin.getName());
        }
    }

    @TestFactory
    public List<DynamicTest> honestSpendIsAcceptedAndForgeriesAreFlagged() {
        List<DynamicTest> tests = new ArrayList<>();
        for(Coin coin : Coin.values()) {
            for(PolicyType policyType : List.of(PolicyType.SINGLE_HD, PolicyType.MULTI_HD)) {
                Coin previous = Coin.beginScope(coin);
                List<ScriptType> scriptTypes;
                try {
                    scriptTypes = ScriptType.getAddressableScriptTypes(policyType);
                } finally {
                    Coin.endScope(previous);
                }
                for(ScriptType scriptType : scriptTypes) {
                    if(scriptType == ScriptType.MWEB) {
                        continue;   //MWEB wallets are synced through the MWEB daemon and never reach this check
                    }
                    tests.add(DynamicTest.dynamicTest(coin.getName() + " " + policyType.getName() + " " + scriptType.getName(), () -> {
                        Coin scope = Coin.beginScope(coin);
                        try {
                            checkCase(coin, policyType, scriptType);
                        } finally {
                            Coin.endScope(scope);
                        }
                    }));
                }
            }
        }

        Assertions.assertEquals(2 * 7 + 5 * 2, tests.size());
        return tests;
    }

    private void checkCase(Coin coin, PolicyType policyType, ScriptType scriptType) throws Exception {
        Wallet wallet = buildWallet(coin, policyType, scriptType);
        fund(wallet, 0);
        fund(wallet, 1);
        Transaction signed = signedSpend(wallet);
        String name = coin.getName() + " " + scriptType.getName();

        Assertions.assertEquals(2, signed.getInputs().size(), name);
        assertAccepted(wallet, signed, name + " honest spend");

        //Outputs changed, the wallet's own signatures kept
        Transaction redirected = redirect(signed);
        assertFlagged(wallet, redirected, name + " outputs changed");

        //Signatures by another key over the forged transaction, with that key's public key and with the wallet's
        assertFlagged(wallet, resign(wallet, redirected, scriptType, true), name + " attacker signatures and key");
        assertFlagged(wallet, resign(wallet, redirected, scriptType, false), name + " attacker signatures, wallet key");
        //The same on the honest transaction: the outputs are right but the signer is not
        assertFlagged(wallet, resign(wallet, signed, scriptType, false), name + " honest outputs, attacker signatures");

        //The honest signatures with another sighash flag: the hash they were made over is not the one the flag asks for
        List<Byte> flags = scriptType == ScriptType.P2TR
                ? List.of(SigHash.ALL.value, SigHash.NONE.value, SigHash.SINGLE.value, SigHash.ANYONECANPAY_ALL.value)
                : List.of(SigHash.NONE.value, SigHash.SINGLE.value, SigHash.ANYONECANPAY_ALL.value, SigHash.ANYONECANPAY_NONE.value, SigHash.ANYONECANPAY_SINGLE.value);
        for(byte flag : flags) {
            assertFlagged(wallet, editSignatures(signed, scriptType, sigs -> sigs.stream().map(sig -> withFlag(sig, scriptType, flag)).toList()),
                    name + " sighash flag " + flag);
            assertFlagged(wallet, editSignatures(redirected, scriptType, sigs -> sigs.stream().map(sig -> withFlag(sig, scriptType, flag)).toList()),
                    name + " outputs changed, sighash flag " + flag);
        }

        if(policyType == PolicyType.MULTI_HD) {
            Assertions.assertEquals(2, signatureSlots(signed.getInputs().getFirst(), scriptType).size(), name + " carries two signatures");
            //One of two signatures removed
            assertFlagged(wallet, editSignatures(signed, scriptType, sigs -> List.of(sigs.getFirst())), name + " one signature removed");
            //One signature given twice: one keystore, not two
            assertFlagged(wallet, editSignatures(signed, scriptType, sigs -> List.of(sigs.getFirst(), sigs.getFirst())), name + " one signature twice");
            //One honest signature beside one by another key
            Transaction mixed = resign(wallet, signed, scriptType, false);
            List<byte[]> attackerSigs = new ArrayList<>();
            for(TransactionInput input : mixed.getInputs()) {
                attackerSigs.add(signatureSlots(input, scriptType).getFirst());
            }
            Transaction halfForged = new Transaction(signed.bitcoinSerialize());
            for(int i = 0; i < halfForged.getInputs().size(); i++) {
                List<byte[]> honest = signatureSlots(halfForged.getInputs().get(i), scriptType);
                setSignatureSlots(halfForged, i, scriptType, List.of(honest.getFirst(), attackerSigs.get(i)), null);
            }
            assertFlagged(wallet, new Transaction(halfForged.bitcoinSerialize()), name + " one honest signature, one by another key");
        }
    }

    /**
     * Before strict DER (BIP66 and its ports on Litecoin and Dogecoin) chains accepted looser signature encodings, and they accept high S values, so an
     * old mined spend can carry either. drongo reads transaction signatures as consensus does (ECDSASignature.decodeFromDERLax, ported from Bitcoin
     * Core's ecdsa_signature_parse_der_lax), so each of these verifies, mined or not. One consensus cannot read is no signature at all, and the spend
     * is not shown signed, mined or not.
     */
    @Test
    public void looseSignatureEncodingsOfOlderChains() throws Exception {
        Coin scope = Coin.beginScope(Coin.DOGECOIN);
        try {
            Wallet wallet = buildWallet(Coin.DOGECOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH);
            fund(wallet, 0);
            fund(wallet, 1);
            Transaction signed = signedSpend(wallet);
            Function<Sha256Hash, Transaction> lookup = txid -> body(wallet, txid);
            Map<Script, WalletNode> scripts = wallet.getWalletOutputScripts();
            int mined = 2_000_000;

            //Each input's own signature, re-encoded
            List<ECDSASignature> signatures = new ArrayList<>();
            for(TransactionInput input : signed.getInputs()) {
                byte[] slot = signatureSlots(input, ScriptType.P2PKH).getFirst();
                signatures.add(ECDSASignature.decodeFromDER(Arrays.copyOf(slot, slot.length - 1)));
            }

            Map<String, java.util.function.IntFunction<byte[]>> accepted = new LinkedHashMap<>();
            accepted.put("long form sequence length", i -> encode(integer(signatures.get(i).r), integer(signatures.get(i).s), true, false, new byte[0]));
            accepted.put("wrong sequence length", i -> {
                byte[] bytes = encode(integer(signatures.get(i).r), integer(signatures.get(i).s), false, false, new byte[0]);
                bytes[1] = (byte)0x7f;
                return bytes;
            });
            accepted.put("padded R", i -> encode(Utils.concat(new byte[] {0, 0}, integer(signatures.get(i).r)), integer(signatures.get(i).s), false, false, new byte[0]));
            accepted.put("unpadded R read as negative", i -> encode(unsigned(signatures.get(i).r), integer(signatures.get(i).s), false, false, new byte[0]));
            accepted.put("long form integer lengths", i -> encode(integer(signatures.get(i).r), integer(signatures.get(i).s), false, true, new byte[0]));
            accepted.put("garbage after S", i -> encode(integer(signatures.get(i).r), integer(signatures.get(i).s), false, false, new byte[] {1, 2, 3}));
            accepted.put("high S", i -> encode(integer(signatures.get(i).r), integer(ECKey.CURVE.getN().subtract(signatures.get(i).s)), false, false, new byte[0]));
            for(Map.Entry<String, java.util.function.IntFunction<byte[]>> variant : accepted.entrySet()) {
                Transaction reencoded = editEachInput(signed, variant.getValue());
                Assertions.assertEquals(Set.of(), wallet.getUnsignedInputs(reencoded, lookup, scripts, null), variant.getKey() + ": unconfirmed, accepted");
                Assertions.assertEquals(Set.of(), wallet.getUnsignedInputs(reencoded, lookup, scripts, mined), variant.getKey() + ": mined, accepted");
            }

            //Not an integer where R should be: consensus finds no signature, so the spend is not shown signed, mined or not
            Transaction unreadable = editEachInput(signed, i -> {
                byte[] bytes = encode(integer(signatures.get(i).r), integer(signatures.get(i).s), false, false, new byte[0]);
                bytes[2] = 0x03;
                return bytes;
            });
            Assertions.assertEquals(2, wallet.getUnsignedInputs(unreadable, lookup, scripts, null).size(), "unconfirmed, left out");
            Assertions.assertEquals(2, wallet.getUnsignedInputs(unreadable, lookup, scripts, mined).size(), "mined, left out");

            //A readable signature over other outputs is not signed either
            Assertions.assertEquals(2, wallet.getUnsignedInputs(redirect(signed), lookup, scripts, mined).size(), "mined forgery left out");
        } finally {
            Coin.endScope(scope);
        }
    }

    /** An integer as DER has it: two's complement, a zero byte before a high bit */
    private static byte[] integer(BigInteger value) {
        return value.toByteArray();
    }

    /** An integer's magnitude without the zero byte DER needs before a high bit, which strict DER reads as negative */
    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    /** A signature with SIGHASH_ALL, built byte by byte in the encoding asked for */
    private static byte[] encode(byte[] r, byte[] s, boolean longFormSequence, boolean longFormIntegers, byte[] garbage) {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        for(byte[] value : List.of(r, s)) {
            body.write(0x02);
            if(longFormIntegers) {
                body.write(0x81);
            }
            body.write(value.length);
            body.writeBytes(value);
        }
        body.writeBytes(garbage);
        java.io.ByteArrayOutputStream sequence = new java.io.ByteArrayOutputStream();
        sequence.write(0x30);
        if(longFormSequence) {
            sequence.write(0x81);
        }
        sequence.write(body.size());
        sequence.writeBytes(body.toByteArray());
        sequence.write(SigHash.ALL.value);
        return sequence.toByteArray();
    }

    /**
     * A copy of a wallet keeps its network, which with its stored tip decides a Luckycoin test chain's signature hash: a copy signs as the wallet does
     */
    @Test
    public void walletCopyKeepsItsNetwork() throws Exception {
        Coin scope = Coin.beginScope(Coin.LUCKYCOIN);
        try {
            Wallet wallet = buildWallet(Coin.LUCKYCOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH);
            wallet.setNetwork(Network.TESTNET);
            wallet.setStoredBlockHeight(1000);
            Assertions.assertEquals(Network.MAINNET, Network.get());
            Assertions.assertEquals(0, wallet.getSigHashPrefix().length);
            Wallet copy = wallet.copy();
            Assertions.assertEquals(Network.TESTNET, copy.getNetwork());
            Assertions.assertArrayEquals(wallet.getSigHashPrefix(), copy.getSigHashPrefix());
        } finally {
            Coin.endScope(scope);
        }
    }

    /**
     * Consensus reads a P2PKH input's signature from the second-to-last push of its script sig and a P2PK input's from the last, whatever comes before:
     * anyone relaying or mining a spend can put a push in front and it stays valid. A push after the public key moves the signature out of its place,
     * and the spend is not valid.
     */
    @Test
    public void legacySignaturesAreReadWhereConsensusReadsThem() throws Exception {
        Coin scope = Coin.beginScope(Coin.DOGECOIN);
        try {
            Wallet wallet = buildWallet(Coin.DOGECOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH);
            fund(wallet, 0);
            fund(wallet, 1);
            Transaction signed = signedSpend(wallet);
            Function<Sha256Hash, Transaction> lookup = txid -> body(wallet, txid);
            Map<Script, WalletNode> scripts = wallet.getWalletOutputScripts();
            ScriptChunk extra = ScriptChunk.fromData(new byte[] {1, 2, 3});

            Transaction prepended = editEachScriptSig(signed, chunks -> {
                List<ScriptChunk> edited = new ArrayList<>(List.of(extra));
                edited.addAll(chunks);
                return edited;
            });
            Assertions.assertEquals(Set.of(), wallet.getUnsignedInputs(prepended, lookup, scripts, null), "push in front, unconfirmed");
            Assertions.assertEquals(Set.of(), wallet.getUnsignedInputs(prepended, lookup, scripts, 2_000_000), "push in front, mined");

            Transaction appended = editEachScriptSig(signed, chunks -> {
                List<ScriptChunk> edited = new ArrayList<>(chunks);
                edited.add(extra);
                return edited;
            });
            Assertions.assertEquals(2, wallet.getUnsignedInputs(appended, lookup, scripts, null).size(), "push after the key");

            byte[] signature = signed.getInputs().getFirst().getScriptSig().getChunks().getFirst().data;
            Transaction p2pk = new Transaction();
            p2pk.addInput(Sha256Hash.of(new byte[] {5}), 0, new Script(List.of(extra, ScriptChunk.fromData(signature))));
            p2pk.addInput(Sha256Hash.of(new byte[] {6}), 0, new Script(List.of(ScriptChunk.fromData(signature), extra)));
            List<TransactionSignature> read = Wallet.getSignatures(p2pk.getInputs().getFirst(), ScriptType.P2PK);
            Assertions.assertEquals(1, read.size(), "P2PK, push in front");
            Assertions.assertArrayEquals(signature, read.getFirst().encodeToBitcoin(), "P2PK, push in front");
            Assertions.assertEquals(List.of(), Wallet.getSignatures(p2pk.getInputs().get(1), ScriptType.P2PK), "P2PK, push after the signature");
        } finally {
            Coin.endScope(scope);
        }
    }

    /**
     * A Taproot signature of 65 bytes names its sighash type in the last byte, and consensus refuses 0x00 there (SIGHASH_DEFAULT is only ever the
     * 64 byte form), so an honest key path signature with 0x00 appended is no signature
     */
    @Test
    public void taprootSignatureWithAnExplicitDefaultSighashIsNoSignature() throws Exception {
        Coin scope = Coin.beginScope(Coin.LITECOIN);
        try {
            Wallet wallet = buildWallet(Coin.LITECOIN, PolicyType.SINGLE_HD, ScriptType.P2TR);
            fund(wallet, 0);
            fund(wallet, 1);
            Transaction signed = signedSpend(wallet);
            byte[] honest = signatureSlots(signed.getInputs().getFirst(), ScriptType.P2TR).getFirst();
            Assertions.assertEquals(64, honest.length);
            Assertions.assertThrows(IllegalArgumentException.class, () -> TransactionSignature.decodeFromBitcoin(TransactionSignature.Type.SCHNORR,
                    Arrays.copyOf(honest, 65), false));

            Transaction explicitDefault = editSignatures(signed, ScriptType.P2TR, sigs -> sigs.stream().map(sig -> Arrays.copyOf(sig, 65)).toList());
            Function<Sha256Hash, Transaction> lookup = txid -> body(wallet, txid);
            Map<Script, WalletNode> scripts = wallet.getWalletOutputScripts();
            Assertions.assertEquals(2, wallet.getUnsignedInputs(explicitDefault, lookup, scripts, null).size(), "unconfirmed");
            Assertions.assertEquals(2, wallet.getUnsignedInputs(explicitDefault, lookup, scripts, HEIGHT + 1).size(), "mined");
        } finally {
            Coin.endScope(scope);
        }
    }

    private static Transaction editEachScriptSig(Transaction transaction, java.util.function.UnaryOperator<List<ScriptChunk>> edit) {
        Transaction edited = new Transaction(transaction.bitcoinSerialize());
        for(TransactionInput input : edited.getInputs()) {
            input.setScriptBytes(new Script(edit.apply(input.getScriptSig().getChunks())).getProgram());
        }
        return new Transaction(edited.bitcoinSerialize());
    }

    private static Transaction editEachInput(Transaction transaction, java.util.function.IntFunction<byte[]> signature) {
        Transaction edited = new Transaction(transaction.bitcoinSerialize());
        for(int i = 0; i < edited.getInputs().size(); i++) {
            setSignatureSlots(edited, i, ScriptType.P2PKH, List.of(signature.apply(i)), null);
        }
        return new Transaction(edited.bitcoinSerialize());
    }

    private static void assertAccepted(Wallet wallet, Transaction transaction, String message) {
        Function<Sha256Hash, Transaction> lookup = txid -> body(wallet, txid);
        Map<Script, WalletNode> scripts = wallet.getWalletOutputScripts();
        //Every input is the wallet's and so was judged: an empty result from inputs it did not recognise would prove nothing
        Assertions.assertEquals(transaction.getInputs().size(), wallet.getSigningNodes(transaction, lookup, scripts).size(), message + ": inputs judged");
        Assertions.assertEquals(Set.of(), wallet.getUnsignedInputs(transaction, lookup, scripts), message + ": accepted");
    }

    private static void assertFlagged(Wallet wallet, Transaction transaction, String message) {
        Function<Sha256Hash, Transaction> lookup = txid -> body(wallet, txid);
        Map<Script, WalletNode> scripts = wallet.getWalletOutputScripts();
        Assertions.assertEquals(transaction.getInputs().size(), wallet.getSigningNodes(transaction, lookup, scripts).size(), message + ": inputs judged");
        Assertions.assertEquals(transaction.getInputs().size(), wallet.getUnsignedInputs(transaction, lookup, scripts).size(), message + ": every input flagged");
    }

    static Wallet buildWallet(Coin coin, PolicyType policyType, ScriptType scriptType) throws MnemonicException {
        Wallet wallet = new Wallet(coin.getName() + " " + scriptType.getName());
        wallet.setCoin(coin);
        wallet.setPolicyType(policyType);
        wallet.setScriptType(scriptType);
        int keystores = policyType == PolicyType.MULTI_HD ? 3 : 1;
        for(int i = 0; i < keystores; i++) {
            DeterministicSeed seed = new DeterministicSeed(TEST_MNEMONIC, keystores == 1 ? "" : "keystore" + (i + 1), 0, DeterministicSeed.Type.BIP39);
            Keystore keystore = Keystore.fromSeed(seed, policyType, scriptType.getDefaultDerivation());
            keystore.setLabel("Keystore " + (i + 1));
            wallet.getKeystores().add(keystore);
        }
        wallet.setDefaultPolicy(Policy.getPolicy(policyType, scriptType, wallet.getKeystores(), keystores > 1 ? 2 : null));
        wallet.setStoredBlockHeight(HEIGHT + 6);
        wallet.getNode(KeyPurpose.RECEIVE).fillToIndex(1);
        return wallet;
    }

    static Transaction fund(Wallet wallet, int index) {
        WalletNode addressNode = wallet.getNode(KeyPurpose.RECEIVE).getChildren().stream().filter(node -> node.getIndex() == index).findFirst().orElseThrow();
        Transaction funding = new Transaction();
        funding.addInput(Sha256Hash.of(new byte[] {(byte)index, 1}), index, new Script(new byte[0]));
        funding.addOutput(FUNDING_VALUE, wallet.getAddress(addressNode));
        wallet.updateTransactions(Map.of(funding.getTxId(), new BlockTransaction(funding.getTxId(), HEIGHT, new Date(), 0L, funding)));
        addressNode.getTransactionOutputs().add(new BlockTransactionHashIndex(funding.getTxId(), HEIGHT, new Date(), 0L, 0, FUNDING_VALUE));
        return funding;
    }

    /**
     * Signed as the app signs: a PSBT from the wallet transaction, signed by every keystore, finalised and extracted, then read back from its
     * serialization as the server would return it
     */
    static Transaction signedSpend(Wallet wallet) throws Exception {
        PSBT psbt = spendPsbt(wallet);
        wallet.sign(psbt);
        wallet.finalise(psbt);
        return new Transaction(psbt.extractTransaction().bitcoinSerialize());
    }

    /** The unsigned PSBT of a spend of both funded outputs, as the app creates it */
    static PSBT spendPsbt(Wallet wallet) throws Exception {
        TransactionParameters params = new TransactionParameters(List.of(new PriorityUtxoSelector(HEIGHT + 6)), Collections.emptyList(),
                List.of(new Payment(new P2PKHAddress(Utils.hexToBytes("cc00000000000000000000000000000000000033")), "test", PAYMENT_VALUE, false)),
                Collections.emptyList(), Collections.emptySet(), 10.0d, 1.0d, 1.0d, null, HEIGHT + 6, false, false, true,
                (w, utxos, outputs, vSize, rate) -> 0L);
        return wallet.createWalletTransaction(params).createPSBT();
    }


    static Transaction redirect(Transaction signed) {
        Transaction altered = new Transaction(signed.bitcoinSerialize());
        altered.getOutputs().getFirst().setScriptBytes(ScriptType.P2PKH.getOutputScript(ATTACKER_HASH).getProgram());
        altered = new Transaction(altered.bitcoinSerialize());
        Assertions.assertNotEquals(signed.getTxId(), altered.getTxId());
        return altered;
    }

    /**
     * Replaces every signature with one by the attacker's key over the signature hash of this very transaction, so each is a valid signature, just
     * not the wallet's. Where the input carries a single public key it is swapped for the attacker's when asked.
     */
    private static Transaction resign(Wallet wallet, Transaction transaction, ScriptType scriptType, boolean attackerPubKey) {
        Transaction forged = new Transaction(transaction.bitcoinSerialize());
        List<TransactionOutput> spentOutputs = new ArrayList<>();
        for(TransactionInput input : forged.getInputs()) {
            spentOutputs.add(body(wallet, input.getOutpoint().getHash()).getOutputs().get((int)input.getOutpoint().getIndex()));
        }

        List<List<byte[]>> signatures = new ArrayList<>();
        for(int i = 0; i < forged.getInputs().size(); i++) {
            TransactionInput input = forged.getInputs().get(i);
            Sha256Hash hash = signatureHash(forged, i, input, spentOutputs, scriptType);
            byte[] sig = scriptType == ScriptType.P2TR
                    ? ATTACKER.getTweakedOutputKey().sign(hash, SigHash.DEFAULT, TransactionSignature.Type.SCHNORR).encodeToBitcoin()
                    : ATTACKER.sign(hash, SigHash.ALL, TransactionSignature.Type.ECDSA).encodeToBitcoin();
            signatures.add(signatureSlots(input, scriptType).stream().map(s -> sig).toList());
        }
        for(int i = 0; i < forged.getInputs().size(); i++) {
            setSignatureSlots(forged, i, scriptType, signatures.get(i), attackerPubKey ? ATTACKER.getPubKey() : null);
        }

        return new Transaction(forged.bitcoinSerialize());
    }

    private static Sha256Hash signatureHash(Transaction transaction, int index, TransactionInput input, List<TransactionOutput> spentOutputs, ScriptType scriptType) {
        TransactionOutput spent = spentOutputs.get(index);
        return switch(scriptType) {
            case P2PKH -> transaction.hashForLegacySignature(index, spent.getScript(), SigHash.ALL);
            case P2SH -> transaction.hashForLegacySignature(index, lastScriptSigScript(input), SigHash.ALL);
            case P2SH_P2WPKH, P2WPKH -> transaction.hashForWitnessSignature(index,
                    ScriptType.P2PKH.getOutputScript(Utils.sha256hash160(input.getWitness().getPushes().get(1))), spent.getValue(), SigHash.ALL);
            case P2SH_P2WSH, P2WSH -> transaction.hashForWitnessSignature(index, new Script(input.getWitness().getPushes().getLast()), spent.getValue(), SigHash.ALL);
            case P2TR -> transaction.hashForTaprootSignature(spentOutputs, index, false, null, SigHash.DEFAULT, null);
            default -> throw new IllegalArgumentException(scriptType.getName());
        };
    }

    private static Script lastScriptSigScript(TransactionInput input) {
        return new Script(input.getScriptSig().getChunks().getLast().data);
    }

    /**
     * The signature pushes of an input, in order: from the script sig for the legacy types, from the witness for the others
     */
    static List<byte[]> signatureSlots(TransactionInput input, ScriptType scriptType) {
        return switch(scriptType) {
            case P2PKH -> List.of(input.getScriptSig().getChunks().getFirst().data);
            case P2SH -> input.getScriptSig().getChunks().subList(1, input.getScriptSig().getChunks().size() - 1).stream().map(chunk -> chunk.data).toList();
            case P2SH_P2WPKH, P2WPKH, P2TR -> List.of(input.getWitness().getPushes().getFirst());
            case P2SH_P2WSH, P2WSH -> input.getWitness().getPushes().subList(1, input.getWitness().getPushes().size() - 1);
            default -> throw new IllegalArgumentException(scriptType.getName());
        };
    }

    static void setSignatureSlots(Transaction transaction, int index, ScriptType scriptType, List<byte[]> signatures, byte[] pubKey) {
        TransactionInput input = transaction.getInputs().get(index);
        switch(scriptType) {
            case P2PKH -> {
                byte[] key = pubKey != null ? pubKey : input.getScriptSig().getChunks().get(1).data;
                input.setScriptBytes(new Script(List.of(ScriptChunk.fromData(signatures.getFirst()), ScriptChunk.fromData(key))).getProgram());
            }
            case P2SH -> {
                List<ScriptChunk> chunks = input.getScriptSig().getChunks();
                List<ScriptChunk> edited = new ArrayList<>();
                edited.add(chunks.getFirst());
                signatures.forEach(sig -> edited.add(ScriptChunk.fromData(sig)));
                edited.add(chunks.getLast());
                input.setScriptBytes(new Script(edited).getProgram());
            }
            case P2SH_P2WPKH, P2WPKH -> {
                byte[] key = pubKey != null ? pubKey : input.getWitness().getPushes().get(1);
                input.setWitness(new TransactionWitness(transaction, List.of(signatures.getFirst(), key)));
            }
            case P2TR -> input.setWitness(new TransactionWitness(transaction, List.of(signatures.getFirst())));
            case P2SH_P2WSH, P2WSH -> {
                List<byte[]> pushes = input.getWitness().getPushes();
                List<byte[]> edited = new ArrayList<>();
                edited.add(pushes.getFirst());
                edited.addAll(signatures);
                edited.add(pushes.getLast());
                input.setWitness(new TransactionWitness(transaction, edited));
            }
            default -> throw new IllegalArgumentException(scriptType.getName());
        }
    }

    private static Transaction editSignatures(Transaction transaction, ScriptType scriptType, Function<List<byte[]>, List<byte[]>> edit) {
        Transaction edited = new Transaction(transaction.bitcoinSerialize());
        for(int i = 0; i < edited.getInputs().size(); i++) {
            setSignatureSlots(edited, i, scriptType, edit.apply(signatureSlots(edited.getInputs().get(i), scriptType)), null);
        }
        return new Transaction(edited.bitcoinSerialize());
    }

    private static byte[] withFlag(byte[] signature, ScriptType scriptType, byte flag) {
        if(scriptType == ScriptType.P2TR) {
            byte[] flagged = Arrays.copyOf(signature, 65);
            flagged[64] = flag;
            return flagged;
        }
        byte[] flagged = Arrays.copyOf(signature, signature.length);
        flagged[flagged.length - 1] = flag;
        return flagged;
    }

    static Transaction body(Wallet wallet, Sha256Hash txid) {
        BlockTransaction blockTransaction = wallet.getWalletTransaction(txid);
        return blockTransaction == null ? null : blockTransaction.getTransaction();
    }
}
