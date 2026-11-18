package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.wallet.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.sparrowwallet.drongo.protocol.ScriptOpCodes.*;

public class DoginalsMintTest {
    private static final ECKey KEY = ECKey.fromPrivate(Utils.hexToBytes("0404040404040404040404040404040404040404040404040404040404040404"));

    private static Map<String, Transaction> vectors() throws Exception {
        try(java.io.InputStream in = DoginalsMintTest.class.getResourceAsStream("carrier-vectors.txt")) {
            Map<String, Transaction> vectors = new HashMap<>();
            new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.split(" ")).forEach(v -> vectors.put(v[1], new Transaction(Utils.hexToBytes(v[4]))));
            return vectors;
        }
    }

    private static byte[] bytes(ScriptChunk chunk) {
        return new Script(List.of(chunk)).getProgram();
    }

    private static int size(List<ScriptChunk> piece) {
        return new Script(piece).getProgram().length;
    }

    /** The envelope pushes of a vector's first input: every chunk before the signature and the lock script. */
    private static List<ScriptChunk> envelope(Transaction tx) {
        List<ScriptChunk> chunks = tx.getInputs().getFirst().getScriptSig().getChunks();
        return chunks.subList(0, chunks.size() - 2);
    }

    /** The content type and body an envelope (cut into the given pieces, in order) writes. */
    private static Map.Entry<String, byte[]> content(List<List<ScriptChunk>> pieces) {
        List<ScriptChunk> all = pieces.stream().flatMap(List::stream).toList();
        String type = new String(all.get(2).data, StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for(int i = 4; i < all.size(); i += 2) {
            body.writeBytes(all.get(i).data);
        }
        return Map.entry(type, body.toByteArray());
    }

    private static void assertPiecesMatch(List<List<ScriptChunk>> expected, List<List<ScriptChunk>> actual) {
        Assertions.assertEquals(expected.size(), actual.size());
        for(int p = 0; p < expected.size(); p++) {
            Assertions.assertEquals(expected.get(p).size(), actual.get(p).size(), "piece " + p);
            for(int c = 0; c < expected.get(p).size(); c++) {
                Assertions.assertArrayEquals(bytes(expected.get(p).get(c)), bytes(actual.get(p).get(c)), "piece " + p + " chunk " + c);
            }
        }
    }

    @Test
    public void envelopePushes() {
        byte[] body = new byte[DoginalsMint.MAX_PART * 2 + 10];
        Arrays.fill(body, (byte)7);
        List<List<ScriptChunk>> pieces = DoginalsMint.pieces("text/plain", body);
        Assertions.assertEquals(1, pieces.size());
        List<ScriptChunk> piece = pieces.getFirst();
        Assertions.assertEquals(9, piece.size());
        Assertions.assertArrayEquals("ord".getBytes(StandardCharsets.UTF_8), piece.get(0).data);
        Assertions.assertEquals(OP_3, piece.get(1).opcode);
        Assertions.assertArrayEquals("text/plain".getBytes(StandardCharsets.UTF_8), piece.get(2).data);
        Assertions.assertEquals(OP_2, piece.get(3).opcode);
        Assertions.assertEquals(DoginalsMint.MAX_PART, piece.get(4).data.length);
        Assertions.assertEquals(OP_1, piece.get(5).opcode);
        Assertions.assertEquals(DoginalsMint.MAX_PART, piece.get(6).data.length);
        Assertions.assertEquals(OP_0, piece.get(7).opcode);
        Assertions.assertEquals(0, piece.get(7).data.length);
        Assertions.assertEquals(10, piece.get(8).data.length);
    }

    @Test
    public void firstPieceStartsWithOrdAlone() {
        //"ord" is shifted on its own, so the count and the content type travel as the first pair
        List<List<ScriptChunk>> pieces = DoginalsMint.pieces("image/png", new byte[20_000]);
        Assertions.assertArrayEquals("ord".getBytes(StandardCharsets.UTF_8), pieces.getFirst().getFirst().data);
        Assertions.assertEquals(1, pieces.getFirst().size() % 2);
        for(List<ScriptChunk> piece : pieces.subList(1, pieces.size())) {
            Assertions.assertEquals(0, piece.size() % 2);
        }
    }

    @Test
    public void piecesStayUnderLimit() {
        byte[] body = new byte[100_000];
        new Random(1).nextBytes(body);
        List<List<ScriptChunk>> pieces = DoginalsMint.pieces("image/webp", body);
        for(List<ScriptChunk> piece : pieces) {
            Assertions.assertTrue(size(piece) <= DoginalsMint.MAX_PIECE, "piece of " + size(piece));
        }
        Assertions.assertArrayEquals(body, content(pieces).getValue());
        Assertions.assertEquals("image/webp", content(pieces).getKey());
        //More than 128 parts: the count is a two-byte push, as doginals.js writes it
        ScriptChunk count = pieces.getFirst().get(1);
        int parts = (body.length + DoginalsMint.MAX_PART - 1) / DoginalsMint.MAX_PART;
        Assertions.assertArrayEquals(new byte[] {(byte)(parts % 256), (byte)(parts / 256)}, count.data);
    }

    @Test
    public void redeemScriptShape() {
        List<ScriptChunk> piece = DoginalsMint.pieces("text/plain", "wow".getBytes(StandardCharsets.UTF_8)).getFirst();
        byte[] redeem = DoginalsMint.redeemScript(ECKey.fromPublicOnly(KEY), piece).getProgram();
        Assertions.assertEquals(34 + 1 + piece.size() + 1, redeem.length);
        Assertions.assertEquals(0x21, redeem[0]);
        Assertions.assertArrayEquals(KEY.getPubKey(), Arrays.copyOfRange(redeem, 1, 34));
        Assertions.assertEquals((byte)0xad, redeem[34]);
        for(int i = 35; i < redeem.length - 1; i++) {
            Assertions.assertEquals((byte)0x75, redeem[i]);
        }
        Assertions.assertEquals((byte)0x51, redeem[redeem.length - 1]);

        List<ScriptChunk> scriptSig = new ArrayList<>(piece);
        scriptSig.add(ScriptChunk.fromData(new byte[72]));
        scriptSig.add(ScriptChunk.fromData(redeem));
        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.ZERO_HASH, 0, new Script(scriptSig));
        Assertions.assertTrue(CarrierScan.hasScriptSigEnvelope(tx));
    }

    @Test
    public void matchesMainnetDoginal() throws Exception {
        Map<String, Transaction> vectors = vectors();
        Transaction first = vectors.get("doginals-multi-first");
        Transaction last = vectors.get("doginals-multi-final");
        List<List<ScriptChunk>> mainnet = List.of(envelope(first), envelope(last));
        Map.Entry<String, byte[]> content = content(mainnet);
        assertPiecesMatch(mainnet, DoginalsMint.pieces(content.getKey(), content.getValue()));

        //Its lock script: the inscriber's pubkey, CHECKSIGVERIFY, one DROP per push, TRUE
        List<ScriptChunk> sig = first.getInputs().getFirst().getScriptSig().getChunks();
        ECKey pubKey = ECKey.fromPublicOnly(Arrays.copyOfRange(sig.getLast().data, 1, 34));
        Assertions.assertArrayEquals(sig.getLast().data, DoginalsMint.redeemScript(pubKey, mainnet.getFirst()).getProgram());

        Transaction single = vectors.get("doginals-single-reveal");
        List<List<ScriptChunk>> one = List.of(envelope(single));
        Map.Entry<String, byte[]> singleContent = content(one);
        assertPiecesMatch(one, DoginalsMint.pieces(singleContent.getKey(), singleContent.getValue()));
        List<ScriptChunk> singleSig = single.getInputs().getFirst().getScriptSig().getChunks();
        Assertions.assertArrayEquals(singleSig.getLast().data, DoginalsMint.redeemScript(pubKey, one.getFirst()).getProgram());
    }

    @Test
    public void matchesMainnetPepinal() throws Exception {
        Transaction tx = vectors().get("prc20-doginals-reveal");
        List<List<ScriptChunk>> mainnet = List.of(envelope(tx));
        Map.Entry<String, byte[]> content = content(mainnet);
        Assertions.assertEquals("text/plain;charset=utf-8", content.getKey());
        assertPiecesMatch(mainnet, DoginalsMint.pieces(content.getKey(), content.getValue()));
    }

    private static final List<String> WORDS = List.of("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "));
    private final Address destination = ScriptType.P2PKH.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(java.math.BigInteger.valueOf(42)));

    private Wallet wallet(Coin coin) throws Exception {
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(WORDS), "", 0, DeterministicSeed.Type.BIP39);
        Wallet wallet = new Wallet("doge");
        wallet.setCoin(coin);
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(ScriptType.P2PKH);
        wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.SINGLE_HD, ScriptType.P2PKH.getDefaultDerivation()));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2PKH, wallet.getKeystores(), 1));
        wallet.getNode(KeyPurpose.RECEIVE).fillToIndex(10);
        wallet.getNode(KeyPurpose.CHANGE).fillToIndex(10);
        return wallet;
    }

    private static List<CollectibleTransactions.Pinned> fund(Wallet wallet, long... values) {
        List<WalletNode> nodes = new ArrayList<>(wallet.getNode(KeyPurpose.RECEIVE).getChildren());
        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.ZERO_HASH, 7, new Script(new byte[0]));
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

    private DoginalsMint.Chain chain(Coin coin, int bodyLength, double feeRate) {
        byte[] body = new byte[bodyLength];
        new Random(bodyLength).nextBytes(body);
        return new DoginalsMint.Chain("image/png", body, ECKey.fromPublicOnly(KEY), destination, feeRate, coin);
    }

    /** Every step in one pass from tx 0's output 0. */
    private static List<Transaction> steps(DoginalsMint.Chain chain, Transaction tx0) {
        List<Transaction> steps = new ArrayList<>();
        TransactionOutPoint previous = new TransactionOutPoint(tx0.getTxId(), 0);
        long value = tx0.getOutputs().getFirst().getValue();
        for(int s = 1; s <= chain.steps(); s++) {
            Transaction step = DoginalsMint.step(chain, s, previous, value, KEY);
            steps.add(step);
            previous = new TransactionOutPoint(step.getTxId(), 0);
            value = step.getOutputs().getFirst().getValue();
        }
        return steps;
    }

    @Test
    public void singlePieceChainIsOneStep() throws Exception {
        Wallet wallet = wallet(Coin.DOGECOIN);
        DoginalsMint.Chain chain = new DoginalsMint.Chain("text/plain", "much wow".getBytes(StandardCharsets.UTF_8), ECKey.fromPublicOnly(KEY), destination, 1000, Coin.DOGECOIN);
        Assertions.assertEquals(1, chain.steps());
        Transaction tx0 = DoginalsMint.fund(wallet, chain, fund(wallet, 50_000_000L), 1000).getTransaction();
        Assertions.assertEquals(chain.firstScriptAddress().getOutputScript(), tx0.getOutputs().getFirst().getScript());
        Assertions.assertEquals(ScriptType.P2SH, chain.firstScriptAddress().getScriptType());
        Transaction step1 = steps(chain, tx0).getFirst();
        Assertions.assertEquals(1, step1.getOutputs().size());
        Assertions.assertEquals(destination.getOutputScript(), step1.getOutputs().getFirst().getScript());
        Assertions.assertEquals(DoginalsMint.POSTAGE, step1.getOutputs().getFirst().getValue());
        Assertions.assertEquals(step1.getTxId(), DoginalsMint.inscriptionTxid(step1));
        Assertions.assertTrue(CarrierScan.hasScriptSigEnvelope(step1));
    }

    @Test
    public void feeCarryAddsUp() throws Exception {
        Wallet wallet = wallet(Coin.JUNKCOIN);
        DoginalsMint.Chain chain = chain(Coin.JUNKCOIN, 5_000, 2.0);
        Transaction tx0 = DoginalsMint.fund(wallet, chain, fund(wallet, 50_000_000L), 1.0).getTransaction();
        long fees = 0;
        for(int s = 1; s <= chain.steps(); s++) {
            fees += chain.fee(s);
        }
        Assertions.assertEquals(DoginalsMint.POSTAGE + fees, chain.carriedValue());
        Assertions.assertEquals(chain.carriedValue(), tx0.getOutputs().getFirst().getValue());

        List<Transaction> steps = steps(chain, tx0);
        long previous = tx0.getOutputs().getFirst().getValue();
        for(int s = 1; s <= steps.size(); s++) {
            Transaction step = steps.get(s - 1);
            long fee = previous - step.getOutputs().getFirst().getValue();
            Assertions.assertEquals(chain.fee(s), fee);
            Assertions.assertTrue(Math.abs(fee - step.getVirtualSize() * 2.0) <= step.getVirtualSize() * 1.0 + 1, "step " + s);
            Assertions.assertTrue(step.getInputs().getFirst().getScriptSig().getProgram().length <= 1650, "step " + s);
            previous = step.getOutputs().getFirst().getValue();
        }
        Assertions.assertEquals(DoginalsMint.POSTAGE, steps.getLast().getOutputs().getFirst().getValue());
    }

    @Test
    public void stepSignatureVerifies() throws Exception {
        Wallet wallet = wallet(Coin.DOGECOIN);
        DoginalsMint.Chain chain = chain(Coin.DOGECOIN, 3_000, 1000);
        Transaction tx0 = DoginalsMint.fund(wallet, chain, fund(wallet, 100_000_000L), 1000).getTransaction();
        List<Transaction> steps = steps(chain, tx0);
        for(int s = 1; s <= steps.size(); s++) {
            Transaction step = steps.get(s - 1);
            List<ScriptChunk> scriptSig = step.getInputs().getFirst().getScriptSig().getChunks();
            Script redeem = new Script(scriptSig.getLast().data);
            Assertions.assertArrayEquals(DoginalsMint.redeemScript(chain.pubKey(), chain.pieces().get(s - 1)).getProgram(), redeem.getProgram());
            Sha256Hash hash = step.hashForLegacySignature(0, redeem, SigHash.ALL);
            TransactionSignature signature = TransactionSignature.decodeFromBitcoin(scriptSig.get(scriptSig.size() - 2).data, true);
            Assertions.assertTrue(chain.pubKey().verify(hash, signature), "step " + s);
            Assertions.assertEquals(SigHash.ALL, signature.getSigHash());
        }
        Assertions.assertThrows(IllegalArgumentException.class, () -> DoginalsMint.step(chain, 1, new TransactionOutPoint(tx0.getTxId(), 0),
                tx0.getOutputs().getFirst().getValue(), ECKey.fromPrivate(Utils.hexToBytes("0505050505050505050505050505050505050505050505050505050505050505"))));
    }

    @Test
    public void surchargeOnEveryOutputBelowSoftDust() throws Exception {
        DoginalsMint.Chain doge = chain(Coin.DOGECOIN, 5_000, 100);
        long carried = DoginalsMint.POSTAGE;
        boolean surcharged = false;
        for(int s = doge.steps(); s >= 1; s--) {
            long output = carried;
            long expected = doge.fee(s) - (output < Coin.DOGECOIN.getDustLimit() ? Coin.DOGECOIN.getSoftDustSurcharge() : 0);
            Assertions.assertTrue(expected > 0 && expected < 1_000_000, "step " + s + " fee " + doge.fee(s));
            surcharged |= output < Coin.DOGECOIN.getDustLimit();
            carried += doge.fee(s);
        }
        Assertions.assertTrue(surcharged);
        //The last step's 100,000 output is under 1 DOGE: its fee carries the 1 DOGE surcharge
        Assertions.assertTrue(doge.fee(doge.steps()) > Coin.DOGECOIN.getSoftDustSurcharge());

        DoginalsMint.Chain junk = chain(Coin.JUNKCOIN, 5_000, 100);
        Assertions.assertTrue(junk.fee(junk.steps()) < 1_000_000);
    }

    @Test
    public void batchSplitAt24() {
        DoginalsMint.Chain chain = chain(Coin.DOGECOIN, 40_000, 1000);
        Assertions.assertTrue(chain.steps() > 24, "steps " + chain.steps());
        Assertions.assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23), DoginalsMint.batch(chain, 1));
        List<Integer> seen = new ArrayList<>();
        int batches = 0;
        for(int first = 1; first <= chain.steps(); batches++) {
            List<Integer> batch = DoginalsMint.batch(chain, first);
            Assertions.assertTrue(batch.size() <= (first == 1 ? 23 : 24));
            seen.addAll(batch);
            first = batch.getLast() + 1;
        }
        Assertions.assertEquals(chain.batches(), batches);
        Assertions.assertEquals(java.util.stream.IntStream.rangeClosed(1, chain.steps()).boxed().toList(), seen);
        //A resume mid-batch finishes that batch, never runs past its end
        Assertions.assertEquals(java.util.stream.IntStream.rangeClosed(10, 23).boxed().toList(), DoginalsMint.batch(chain, 10));
        Assertions.assertEquals(24, DoginalsMint.batch(chain, 24).getFirst());
    }

    @Test
    public void rebuiltBatchMatchesOneShotChain() throws Exception {
        Wallet wallet = wallet(Coin.DOGECOIN);
        DoginalsMint.Chain chain = chain(Coin.DOGECOIN, 40_000, 1000);
        Transaction tx0 = DoginalsMint.fund(wallet, chain, fund(wallet, 900_000_000L), 1000).getTransaction();
        List<Transaction> oneShot = steps(chain, tx0);
        Transaction step23 = oneShot.get(22);
        TransactionOutPoint previous = new TransactionOutPoint(step23.getTxId(), 0);
        long value = step23.getOutputs().getFirst().getValue();
        for(int s : DoginalsMint.batch(chain, 24)) {
            Transaction rebuilt = DoginalsMint.step(chain, s, previous, value, KEY);
            Assertions.assertEquals(oneShot.get(s - 1).getTxId(), rebuilt.getTxId(), "step " + s);
            previous = new TransactionOutPoint(rebuilt.getTxId(), 0);
            value = rebuilt.getOutputs().getFirst().getValue();
        }
    }

    @Test
    public void noRbfOnChain() throws Exception {
        Wallet wallet = wallet(Coin.PEPECOIN);
        DoginalsMint.Chain chain = chain(Coin.PEPECOIN, 3_000, 1000);
        Transaction tx0 = DoginalsMint.fund(wallet, chain, fund(wallet, 30_000_000L, 40_000_000L), 1000).getTransaction();
        List<Transaction> all = new ArrayList<>(List.of(tx0));
        all.addAll(steps(chain, tx0));
        for(Transaction tx : all) {
            for(TransactionInput input : tx.getInputs()) {
                Assertions.assertEquals(0xffffffffL, input.getSequenceNumber());
            }
        }
    }

    @Test
    public void endReturnsToWallet() throws Exception {
        Wallet wallet = wallet(Coin.DOGECOIN);
        DoginalsMint.Chain chain = chain(Coin.DOGECOIN, 40_000, 1000);
        Transaction tx0 = DoginalsMint.fund(wallet, chain, fund(wallet, 900_000_000L), 1000).getTransaction();
        List<Transaction> steps = steps(chain, tx0);
        Address back = wallet.getFreshNode(KeyPurpose.RECEIVE).getAddress();

        //After batch 0 the newest chain output is step 23's: ending spends it with piece 23, as step 24 would
        Transaction step23 = steps.get(22);
        long value = step23.getOutputs().getFirst().getValue();
        Transaction end = DoginalsMint.end(chain, 24, new TransactionOutPoint(step23.getTxId(), 0), value, KEY, back);
        Assertions.assertEquals(1, end.getOutputs().size());
        Assertions.assertEquals(back.getOutputScript(), end.getOutputs().getFirst().getScript());
        long fee = value - end.getOutputs().getFirst().getValue();
        //Sized with a 73-byte signature; a low-R one is 71 or 72
        Assertions.assertTrue(fee >= Math.ceil(end.getVirtualSize() * 1000) && fee <= Math.ceil((end.getVirtualSize() + 2) * 1000), "fee " + fee);
        Assertions.assertEquals(0xffffffffL, end.getInputs().getFirst().getSequenceNumber());
        List<ScriptChunk> scriptSig = end.getInputs().getFirst().getScriptSig().getChunks();
        Script redeem = new Script(scriptSig.getLast().data);
        Assertions.assertArrayEquals(DoginalsMint.redeemScript(chain.pubKey(), chain.pieces().get(23)).getProgram(), redeem.getProgram());
        TransactionSignature signature = TransactionSignature.decodeFromBitcoin(scriptSig.get(scriptSig.size() - 2).data, true);
        Assertions.assertTrue(chain.pubKey().verify(end.hashForLegacySignature(0, redeem, SigHash.ALL), signature));

        //Before step 1 the funding output itself is ended
        Transaction early = DoginalsMint.end(chain, 1, new TransactionOutPoint(tx0.getTxId(), 0), chain.carriedValue(), KEY, back);
        Assertions.assertEquals(tx0.getTxId(), early.getInputs().getFirst().getOutpoint().getHash());

        //An output too small to pay its own fee cannot be ended
        Assertions.assertThrows(IllegalArgumentException.class, () -> DoginalsMint.end(chain, 24, new TransactionOutPoint(step23.getTxId(), 0), 1_000, KEY, back));
    }

    @Test
    public void insufficientFundsNamesTheCoin() throws Exception {
        Wallet wallet = wallet(Coin.DOGECOIN);
        DoginalsMint.Chain chain = chain(Coin.DOGECOIN, 3_000, 1000);
        InsufficientFundsException e = Assertions.assertThrows(InsufficientFundsException.class,
                () -> DoginalsMint.fund(wallet, chain, fund(wallet, 200_000L), 1000));
        Assertions.assertTrue(e.getMessage().contains("DOGE"), e.getMessage());
        Assertions.assertFalse(e.getMessage().contains("LTC"), e.getMessage());
    }

    /** Bitcoin Core's CheckMinimalPush, which Dogecoin and Pepecoin enforce on scriptSigs as policy. */
    private static boolean minimal(ScriptChunk chunk) {
        if(chunk.data == null) {
            return true;
        }
        int length = chunk.data.length;
        if(length == 0) {
            return chunk.opcode == OP_0;
        }
        if(length == 1 && ((chunk.data[0] >= 1 && chunk.data[0] <= 16) || (chunk.data[0] & 0xff) == 0x81)) {
            return false;
        }
        if(length <= 75) {
            return chunk.opcode == length;
        }
        if(length <= 255) {
            return chunk.opcode == OP_PUSHDATA1;
        }
        return chunk.opcode == OP_PUSHDATA2;
    }

    @Test
    public void singleByteLastPartStaysMinimal() {
        //A body one byte past a part boundary whose last byte is a small number: a one-byte push of it is not minimal
        for(int last : new int[] {0x01, 0x0a, 0x10, 0x81, 0x00, 0x41}) {
            byte[] body = new byte[DoginalsMint.MAX_PART * 3 + 1];
            Arrays.fill(body, (byte)'x');
            body[body.length - 1] = (byte)last;
            List<List<ScriptChunk>> pieces = DoginalsMint.pieces("text/plain", body);
            for(List<ScriptChunk> piece : pieces) {
                for(ScriptChunk chunk : piece) {
                    Assertions.assertTrue(minimal(new Script(new Script(List.of(chunk)).getProgram()).getChunks().getFirst()), "last byte " + last + " chunk " + chunk);
                }
            }
            Assertions.assertArrayEquals(body, content(pieces).getValue(), "last byte " + last);
        }
        //Every body length near the boundaries stays minimal and whole
        Assertions.assertThrows(IllegalArgumentException.class, () -> DoginalsMint.pieces("image/png", new byte[] {0x05}));
        for(int length = 2; length < 1_000; length++) {
            byte[] body = new byte[length];
            Arrays.fill(body, (byte)0x05);
            List<List<ScriptChunk>> pieces = DoginalsMint.pieces("image/png", body);
            for(List<ScriptChunk> piece : pieces) {
                for(ScriptChunk chunk : piece) {
                    Assertions.assertTrue(minimal(new Script(new Script(List.of(chunk)).getProgram()).getChunks().getFirst()), "length " + length);
                }
            }
            Assertions.assertArrayEquals(body, content(pieces).getValue(), "length " + length);
        }
    }

    @Test
    public void oversizeRefused() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> DoginalsMint.pieces("text/plain", new byte[0]));
        Assertions.assertThrows(IllegalArgumentException.class, () -> DoginalsMint.pieces("x".repeat(DoginalsMint.MAX_CONTENT_TYPE + 1), new byte[1]));
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class,
                () -> DoginalsMint.pieces("image/png", new byte[DoginalsMint.MAX_BODY + 1]));
        Assertions.assertTrue(e.getMessage().contains(String.format("%,d", DoginalsMint.MAX_BODY)), e.getMessage());
        Assertions.assertDoesNotThrow(() -> DoginalsMint.pieces("image/png", new byte[DoginalsMint.MAX_BODY]));
    }
}
