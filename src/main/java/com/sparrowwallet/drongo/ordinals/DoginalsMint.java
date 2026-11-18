package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.P2SHAddress;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.wallet.InsufficientFundsException;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletTransaction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static com.sparrowwallet.drongo.protocol.ScriptOpCodes.*;

/**
 * Mints an inscription the way doginals.js does on Dogecoin and the chains that copied it (Pepecoin, Junkcoin), which
 * have no taproot. The envelope is "ord", the part count, the content type, then for each part of at most MAX_PART bytes
 * a countdown number and the part. It is cut into pieces of at most MAX_PIECE bytes, and each piece is revealed in the
 * scriptSig of one transaction spending a P2SH output whose redeem script checks the minter's signature and drops the
 * piece's pushes. Each transaction pays the next piece's P2SH, and the last pays the destination: the inscription sits on
 * that output, and its id is the first reveal's txid with index 0.
 *
 * The redeem script only drops the piece's pushes, as doginals.js writes it, so the signature does not cover them: a
 * relayer could swap a piece for other pushes of the same count, changing the txid and the content. Committing to each
 * push's hash would take the scriptSig past the 1,650-byte standard limit and break the format indexers read, so instead
 * every step is rebuilt here deterministically and the caller compares each step on chain with its rebuilt txid before
 * signing more.
 */
public final class DoginalsMint {
    public static final int MAX_PART = 240;
    public static final int MAX_PIECE = 1500;
    public static final int MAX_CONTENT_TYPE = 520;
    public static final int MAX_BODY = 2_000_000;
    public static final int BATCH = 24;
    public static final long POSTAGE = 100_000;
    private static final byte[] ORD = "ord".getBytes(StandardCharsets.UTF_8);

    //A low-R DER signature with its sighash byte is at most 72 bytes; sizing with 73 never underpays
    private static final int SIGNATURE_BYTES = 73;
    private static final long SEQUENCE = 0xffffffffL;

    private DoginalsMint() {}

    /**
     * One mint's whole chain, fixed by its content, key, destination, fee rate and coin: tx 0 funds the first piece's
     * P2SH with the postage plus every later fee, and steps 1 to steps() each reveal one piece, the last paying the
     * destination. Pieces and fees are worked out once here; they are the same every time the chain is rebuilt.
     */
    public static final class Chain {
        private final String contentType;
        private final byte[] body;
        private final ECKey pubKey;
        private final Address destination;
        private final double feeRate;
        private final Coin coin;
        private final List<List<ScriptChunk>> pieces;
        private final List<Script> redeemScripts;
        private final long[] fees;
        private final long[] outputs;

        public Chain(String contentType, byte[] body, ECKey pubKey, Address destination, double feeRate, Coin coin) {
            if(destination == null) {
                throw new IllegalArgumentException("A destination is required");
            }
            if(!(feeRate > 0)) {
                throw new IllegalArgumentException("The fee rate must be above zero");
            }
            this.contentType = contentType;
            this.body = body.clone();
            this.pubKey = ECKey.fromPublicOnly(pubKey);
            this.destination = destination;
            this.feeRate = feeRate;
            this.coin = coin;
            this.pieces = DoginalsMint.pieces(contentType, body);
            this.redeemScripts = pieces.stream().map(piece -> redeemScript(this.pubKey, piece)).toList();

            //Backwards from the destination's postage: each step's output carries the fees of every step after it
            int steps = pieces.size();
            fees = new long[steps + 1];
            outputs = new long[steps + 1];
            outputs[steps] = POSTAGE;
            for(int s = steps; s >= 1; s--) {
                Transaction sizing = unsigned(s, new TransactionOutPoint(Sha256Hash.ZERO_HASH, 0), outputs[s]);
                List<ScriptChunk> scriptSig = new ArrayList<>(pieces.get(s - 1));
                scriptSig.add(push(new byte[SIGNATURE_BYTES]));
                scriptSig.add(push(redeemScripts.get(s - 1).getProgram()));
                sizing.getInputs().getFirst().setScriptBytes(new Script(scriptSig).getProgram());
                long fee = (long)Math.ceil(Math.ceil(sizing.getVirtualSize()) * feeRate);
                fees[s] = fee + (outputs[s] < coin.getDustLimit() ? coin.getSoftDustSurcharge() : 0);
                outputs[s - 1] = outputs[s] + fees[s];
            }
        }

        public String contentType() {
            return contentType;
        }

        public byte[] body() {
            return body.clone();
        }

        public ECKey pubKey() {
            return pubKey;
        }

        public Address destination() {
            return destination;
        }

        public double feeRate() {
            return feeRate;
        }

        public Coin coin() {
            return coin;
        }

        public List<List<ScriptChunk>> pieces() {
            return pieces;
        }

        /** The transactions after tx 0: one per piece. */
        public int steps() {
            return pieces.size();
        }

        /** The fee step s pays, with the coin's soft dust surcharge when its output is under the dust limit. */
        public long fee(int step) {
            checkStep(step);
            return fees[step];
        }

        /** What tx 0 locks in the first piece's P2SH: the postage plus every step's fee. */
        public long carriedValue() {
            return outputs[0];
        }

        /** The fixed batches: tx 0 and steps 1 to BATCH - 1, then BATCH steps each. */
        public int batches() {
            int after = Math.max(0, steps() - (BATCH - 1));
            return 1 + (after + BATCH - 1) / BATCH;
        }

        public Address firstScriptAddress() {
            return scriptAddress(0);
        }

        private Address scriptAddress(int piece) {
            return new P2SHAddress(Utils.sha256hash160(redeemScripts.get(piece).getProgram()));
        }

        private void checkStep(int step) {
            if(step < 1 || step > steps()) {
                throw new IllegalArgumentException("This mint has steps 1 to " + steps() + ", not " + step);
            }
        }

        //Step s: spends the previous P2SH, pays the next piece's P2SH or, last, the destination
        private Transaction unsigned(int step, TransactionOutPoint previous, long value) {
            Transaction tx = new Transaction();
            tx.setVersion(1);
            tx.addInput(previous.getHash(), previous.getIndex(), new Script(new byte[0])).setSequenceNumber(SEQUENCE);
            tx.addOutput(value, step == steps() ? destination.getOutputScript() : scriptAddress(step).getOutputScript());
            return tx;
        }
    }

    /** Tx 0: [the first piece's P2SH with the carried value, change to the wallet], fixed order, no RBF. */
    public static WalletTransaction fund(Wallet wallet, Chain chain, List<CollectibleTransactions.Pinned> feeCandidates, double minRelayFeeRate)
            throws InsufficientFundsException {
        Address first = chain.firstScriptAddress();
        long value = chain.carriedValue();
        long surcharge = value < chain.coin().getDustLimit() ? chain.coin().getSoftDustSurcharge() : 0;
        TransactionOutput output = new TransactionOutput(null, value, first.getOutputScript());
        return CollectibleTransactions.build(wallet, List.of(), List.of(new CollectibleTransactions.Planned(output, first, null, null)),
                feeCandidates, chain.feeRate(), minRelayFeeRate, surcharge, SEQUENCE);
    }

    /**
     * Step s (1-based), signed: spends previous (output 0 of tx s - 1) with scriptSig [piece s - 1, signature, redeem
     * script], and pays the next piece's P2SH with what is left after its fee, or on the last step exactly POSTAGE to the
     * destination. Signatures are RFC 6979, so the same inputs always give the same transaction.
     */
    public static Transaction step(Chain chain, int step, TransactionOutPoint previous, long previousValue, ECKey privateKey) {
        chain.checkStep(step);
        if(!Arrays.equals(privateKey.getPubKey(), chain.pubKey().getPubKey())) {
            throw new IllegalArgumentException("The key does not match this mint's key");
        }
        long value = step == chain.steps() ? POSTAGE : previousValue - chain.fee(step);
        if(previousValue - chain.fee(step) < chain.outputs[step]) {
            throw new IllegalArgumentException("The previous output holds less than this step's fee and what it carries on");
        }
        return sign(chain, step, chain.unsigned(step, previous, value), privateKey);
    }

    /**
     * Ends a mint early: spends the output step s would spend (tx 0's for step 1) with piece s - 1's unlock, as step s
     * would, but pays what it holds less the fee to the given address. The envelope on chain stays incomplete, so it is
     * no inscription.
     */
    public static Transaction end(Chain chain, int step, TransactionOutPoint previous, long previousValue, ECKey privateKey, Address to) {
        chain.checkStep(step);
        if(!Arrays.equals(privateKey.getPubKey(), chain.pubKey().getPubKey())) {
            throw new IllegalArgumentException("The key does not match this mint's key");
        }
        Transaction sizing = new Transaction();
        sizing.setVersion(1);
        sizing.addInput(previous.getHash(), previous.getIndex(), new Script(new byte[0])).setSequenceNumber(SEQUENCE);
        sizing.addOutput(previousValue, to.getOutputScript());
        List<ScriptChunk> unlock = new ArrayList<>(chain.pieces().get(step - 1));
        unlock.add(push(new byte[SIGNATURE_BYTES]));
        unlock.add(push(chain.redeemScripts.get(step - 1).getProgram()));
        sizing.getInputs().getFirst().setScriptBytes(new Script(unlock).getProgram());
        long fee = (long)Math.ceil(Math.ceil(sizing.getVirtualSize()) * chain.feeRate());
        if(previousValue - fee < chain.coin().getDustLimit()) {
            fee += chain.coin().getSoftDustSurcharge();
        }
        long value = previousValue - fee;
        if(value <= 0 || value < chain.coin().getHardDustLimit()) {
            throw new IllegalArgumentException("The locked output holds " + previousValue + " " + chain.coin().getSubunit() + ", too little to pay the fee of ending the mint");
        }
        Transaction tx = new Transaction();
        tx.setVersion(1);
        tx.addInput(previous.getHash(), previous.getIndex(), new Script(new byte[0])).setSequenceNumber(SEQUENCE);
        tx.addOutput(value, to.getOutputScript());
        return sign(chain, step, tx, privateKey);
    }

    private static Transaction sign(Chain chain, int step, Transaction tx, ECKey privateKey) {
        Script redeem = chain.redeemScripts.get(step - 1);
        TransactionSignature signature = privateKey.sign(tx.hashForLegacySignature(0, redeem, SigHash.ALL), SigHash.ALL, TransactionSignature.Type.ECDSA);
        List<ScriptChunk> scriptSig = new ArrayList<>(chain.pieces().get(step - 1));
        scriptSig.add(push(signature.encodeToBitcoin()));
        scriptSig.add(push(redeem.getProgram()));
        tx.getInputs().getFirst().setScriptBytes(new Script(scriptSig).getProgram());
        return tx;
    }

    /** The P2SH address the output spent by step s pays: piece s - 1's. */
    public static Address spentScriptAddress(Chain chain, int step) {
        chain.checkStep(step);
        return chain.scriptAddress(step - 1);
    }

    /** The steps from the given one to the end of the fixed batch that holds it (a resume finishes its batch). */
    public static List<Integer> batch(Chain chain, int firstStep) {
        chain.checkStep(firstStep);
        int last = firstStep < BATCH ? BATCH - 1 : (firstStep / BATCH) * BATCH + BATCH - 1;
        return java.util.stream.IntStream.rangeClosed(firstStep, Math.min(last, chain.steps())).boxed().toList();
    }

    /** The txid in the inscription's id, written as <txid>i0: the first step, where the envelope starts. */
    public static Sha256Hash inscriptionTxid(Transaction step1) {
        return step1.getTxId();
    }

    /** The envelope cut into the pieces each transaction reveals, in order. */
    public static List<List<ScriptChunk>> pieces(String contentType, byte[] body) {
        byte[] type = contentType.getBytes(StandardCharsets.UTF_8);
        if(body.length == 0) {
            throw new IllegalArgumentException("There is nothing to inscribe");
        }
        if(type.length > MAX_CONTENT_TYPE) {
            throw new IllegalArgumentException("The content type is longer than " + MAX_CONTENT_TYPE + " bytes");
        }
        if(body.length > MAX_BODY) {
            throw new IllegalArgumentException(String.format("The content is too large to inscribe: at most %,d bytes", MAX_BODY));
        }

        List<byte[]> parts = new ArrayList<>();
        for(int from = 0; from < body.length; from += MAX_PART) {
            parts.add(Arrays.copyOfRange(body, from, Math.min(from + MAX_PART, body.length)));
        }
        //A one-byte push of 1 to 16 or 0x81 is not minimal and nodes refuse the scriptSig, so such a last byte takes one more
        //from the part before it (indexers join parts of any size)
        if(!minimalPush(parts.getLast())) {
            if(parts.size() == 1) {
                throw new IllegalArgumentException("A one-byte inscription of this value cannot be written as a Doginal");
            }
            byte[] before = parts.get(parts.size() - 2);
            parts.set(parts.size() - 2, Arrays.copyOf(before, before.length - 1));
            parts.set(parts.size() - 1, new byte[] {before[before.length - 1], parts.getLast()[0]});
        }
        LinkedList<ScriptChunk> envelope = new LinkedList<>();
        envelope.add(push(ORD));
        envelope.add(number(parts.size()));
        envelope.add(push(type));
        for(int n = 0; n < parts.size(); n++) {
            envelope.add(number(parts.size() - n - 1));
            envelope.add(push(parts.get(n)));
        }

        //As doginals.js: "ord" alone first, then pairs while the piece fits, the pair that overflows moving to the next piece
        List<List<ScriptChunk>> pieces = new ArrayList<>();
        while(!envelope.isEmpty()) {
            List<ScriptChunk> piece = new ArrayList<>();
            if(pieces.isEmpty()) {
                piece.add(envelope.removeFirst());
            }
            while(size(piece) <= MAX_PIECE && !envelope.isEmpty()) {
                piece.add(envelope.removeFirst());
                piece.add(envelope.removeFirst());
            }
            if(size(piece) > MAX_PIECE) {
                envelope.addFirst(piece.removeLast());
                envelope.addFirst(piece.removeLast());
            }
            pieces.add(piece);
        }
        return pieces;
    }

    /** The redeem script revealing a piece: the minter's pubkey, CHECKSIGVERIFY, one DROP per push of the piece, TRUE. */
    public static Script redeemScript(ECKey pubKey, List<ScriptChunk> piece) {
        List<ScriptChunk> chunks = new ArrayList<>();
        chunks.add(push(pubKey.getPubKey()));
        chunks.add(ScriptChunk.fromOpcode(OP_CHECKSIGVERIFY));
        for(int i = 0; i < piece.size(); i++) {
            chunks.add(ScriptChunk.fromOpcode(OP_DROP));
        }
        chunks.add(ScriptChunk.fromOpcode(OP_TRUE));
        return new Script(chunks);
    }

    private static int size(List<ScriptChunk> piece) {
        return new Script(piece).getProgram().length;
    }

    private static boolean minimalPush(byte[] data) {
        return data.length != 1 || !((data[0] >= 1 && data[0] <= 16) || (data[0] & 0xff) == 0x81);
    }

    //doginals.js bufferToChunk: an empty buffer is OP_0, a single byte is still a push (never OP_N)
    private static ScriptChunk push(byte[] data) {
        if(data.length == 0) {
            return ScriptChunk.fromOpcode(OP_0);
        }
        int opcode = data.length < OP_PUSHDATA1 ? data.length : data.length <= 0xff ? OP_PUSHDATA1 : OP_PUSHDATA2;
        return new ScriptChunk(opcode, data.clone());
    }

    //doginals.js numberToChunk: 0 is OP_0, 1..16 OP_N, then one byte below 128, else two bytes little-endian
    private static ScriptChunk number(int n) {
        if(n == 0) {
            return ScriptChunk.fromOpcode(OP_0);
        }
        if(n <= 16) {
            return ScriptChunk.fromOpcode(OP_1 + n - 1);
        }
        return n < 128 ? new ScriptChunk(1, new byte[] {(byte)n}) : new ScriptChunk(2, new byte[] {(byte)(n % 256), (byte)(n / 256)});
    }
}
