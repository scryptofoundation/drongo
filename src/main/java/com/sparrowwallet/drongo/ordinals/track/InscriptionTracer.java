package com.sparrowwallet.drongo.ordinals.track;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.ordinals.OrdinalsScan;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionInput;

import java.io.IOException;
import java.util.*;

/**
 * Traces a coin's units back through its history until each piece is shown clean (it reaches a block before the
 * chain's first inscription, or a mined coin's reward) or inscribed (it reaches the transaction that created an
 * inscription on it), within a budget of server lookups. Anything not shown clean leaves the coin UNCHECKED.
 */
public final class InscriptionTracer {
    public static final int DEFAULT_BUDGET = 200;
    public static final int DEEPER_BUDGET = 2_000;

    static final String BUDGET_USED = "Lookup budget used up; use Check deeper";
    static final String SERVER_ERROR = "Server error, will retry next sync: %s";
    static final String FEES_NEED_DEEPER = "Mined coin fees need Check deeper";
    static final String DOES_NOT_ADD_UP = "Mined coin does not add up";
    static final String THROUGH_MWEB = "Came through MWEB";
    static final String HEIGHT_UNKNOWN = "Mined coin height unknown";

    private final Coin coin;
    private final Network network;
    private final ChainSource source;
    private final KnownResults known;
    private final TxCache cache;

    public InscriptionTracer(Coin coin, Network network, ChainSource source, KnownResults known, TxCache cache) {
        this.coin = coin;
        this.network = network;
        this.source = source;
        this.known = known == null ? KnownResults.NONE : known;
        this.cache = cache;
    }

    private static final class BudgetUsedException extends Exception {
        BudgetUsedException() {
            super(null, null, false, false);
        }
    }

    /** One trace's working state. */
    private final class Run {
        final int budget;
        final boolean walkBlocks;
        final Deque<Piece> queue = new ArrayDeque<>();
        final List<Found> found = new ArrayList<>();
        final List<Piece> open = new ArrayList<>();
        String deadEnd;
        int lookups;
        boolean usedChain;

        Run(int budget, boolean walkBlocks) {
            this.budget = budget;
            this.walkBlocks = walkBlocks;
        }

        ChainTx fetch(Sha256Hash txid) throws IOException, BudgetUsedException {
            ChainTx cached = cache.get(txid);
            if(cached != null) {
                return cached;
            }
            if(lookups >= budget) {
                throw new BudgetUsedException();
            }
            ChainTx tx = source.transaction(txid);
            if(tx == null || !tx.tx().getTxId().equals(txid)) {
                throw new IOException("The server returned another transaction for " + txid);
            }
            if(tx.fromServer()) {
                lookups++;
                usedChain = true;
            }
            cache.put(tx);
            return tx;
        }

        List<Long> inputValues(Transaction tx) throws IOException, BudgetUsedException {
            List<Long> values = new ArrayList<>();
            for(TransactionInput input : tx.getInputs()) {
                Transaction parent = fetch(input.getOutpoint().getHash()).tx();
                int index = (int)input.getOutpoint().getIndex();
                if(index >= parent.getOutputs().size()) {
                    throw new IOException("Transaction " + parent.getTxId() + " has no output " + index);
                }
                values.add(parent.getOutputs().get(index).getValue());
            }
            return values;
        }

        /** Counts a server call that is not a transaction fetch (a block's fee list, a position lookup). */
        void serverCall() throws BudgetUsedException {
            if(lookups >= budget) {
                throw new BudgetUsedException();
            }
            lookups++;
            usedChain = true;
        }

        void deadEnd(String reason) {
            if(deadEnd == null) {
                deadEnd = reason;
            }
        }

        void foundAt(Piece piece, String id, long offsetInOutput, ChainTx mint) {
            found.add(new Found(id, piece.coinOffset() + (offsetInOutput - piece.range().start()), null, mint.tx().getTxId(), mint.height(), mint.blockTime()));
        }
    }

    /**
     * Traces output vout of txid, or continues from resume when it holds the pieces a cut-off trace left open. budget
     * caps the server lookups; walkBlocks lets a mined coin's fees be worked out one block transaction at a time.
     */
    public TraceResult trace(Sha256Hash txid, int vout, List<Piece> resume, int budget, boolean walkBlocks) {
        Run run = new Run(budget, walkBlocks);
        String stopped = null;
        try {
            if(resume == null || resume.isEmpty()) {
                Transaction tx = run.fetch(txid).tx();
                if(vout < 0 || vout >= tx.getOutputs().size()) {
                    throw new IOException("Transaction " + txid + " has no output " + vout);
                }
                long value = tx.getOutputs().get(vout).getValue();
                if(value <= 0) {
                    return new TraceResult(Verdict.CLEAN, List.of(), null, List.of(), run.lookups, run.usedChain);
                }
                run.queue.add(new Piece(txid, vout, new UnitRange(0, value), 0));
            } else {
                run.queue.addAll(resume);
            }
        } catch(BudgetUsedException e) {
            return new TraceResult(Verdict.UNCHECKED, List.of(), BUDGET_USED, List.copyOf(resume == null ? List.of() : resume), run.lookups, run.usedChain);
        } catch(IOException e) {
            return new TraceResult(Verdict.UNCHECKED, List.of(), String.format(SERVER_ERROR, e.getMessage()), List.copyOf(resume == null ? List.of() : resume),
                    run.lookups, run.usedChain);
        }

        while(!run.queue.isEmpty()) {
            Piece piece = run.queue.poll();
            try {
                step(run, piece);
            } catch(BudgetUsedException e) {
                stopped = BUDGET_USED;
            } catch(IOException e) {
                stopped = String.format(SERVER_ERROR, e.getMessage());
            } catch(RuntimeException e) {
                stopped = String.format(SERVER_ERROR, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            if(stopped != null) {
                run.open.add(piece);
                run.open.addAll(run.queue);
                run.queue.clear();
            }
        }

        List<Found> found = distinct(run.found);
        String reason = stopped != null ? stopped : run.deadEnd;
        Verdict verdict = !found.isEmpty() ? Verdict.INSCRIBED : reason != null ? Verdict.UNCHECKED : Verdict.CLEAN;
        //A dead end (an unproven MWEB transaction, a coinbase that cannot be resolved) leaves nothing to resume: resuming only the other pieces
        //would forget it, so the next trace starts over from the coin and meets it again
        List<Piece> open = run.deadEnd != null ? List.of() : List.copyOf(run.open);
        return new TraceResult(verdict, found, verdict == Verdict.CLEAN ? null : reason, open, run.lookups, run.usedChain);
    }

    private void step(Run run, Piece piece) throws IOException, BudgetUsedException {
        if(piece.vout() != Piece.FEE) {
            Optional<KnownOutput> knownOutput = known.forOutput(piece.txid(), piece.vout());
            if(knownOutput.isPresent() && knownOutput.get().verdict() != Verdict.UNCHECKED) {
                for(Found f : knownOutput.get().found()) {
                    if(f.offset() < 0) {
                        run.found.add(f.withOffset(-1));
                    } else if(f.offset() >= piece.range().start() && f.offset() < piece.range().end()) {
                        run.found.add(f.withOffset(piece.coinOffset() + (f.offset() - piece.range().start())));
                    }
                }
                return;
            }
        }

        ChainTx chainTx = run.fetch(piece.txid());
        Transaction tx = chainTx.tx();
        if(chainTx.height() > 0 && chainTx.height() < ChainRules.firstInscriptionHeight(coin, network)) {
            return;
        }
        if(tx.isHogEx()) {
            //Units that come out of MWEB (a peg-out, or the pool's own output) carry no history to follow, so they are clean;
            //but only a confirmed transaction with the MWEB flag and null extension is a HogEx by consensus (only a block's
            //last transaction may be one). The output script shape alone anyone can build, so it stays a dead end.
            if(chainTx.height() > 0 && tx.hasNullMwebExtension()) {
                return;
            }
            run.deadEnd(THROUGH_MWEB);
            return;
        }
        if(tx.isCoinBase()) {
            //Without its height a coinbase's reward cannot be known (a server that gave no height reads as height 0)
            if(chainTx.height() <= 0) {
                run.deadEnd(HEIGHT_UNKNOWN);
                return;
            }
            minedCoin(run, piece, chainTx);
            return;
        }

        //A Doginals inscription sits on the first unit of the last step's outputs (output 1 when output 0 holds nothing)
        Optional<DoginalsStep> doginals = InscriptionLocator.doginals(tx);
        if(doginals.isPresent() && doginals.get().last() && UnitFlow.outputTotal(tx) > 0) {
            Piece first = UnitFlow.place(tx, new UnitRange(0, 1), 0).getFirst();
            if(first.vout() == piece.vout() && first.range().start() >= piece.range().start() && first.range().start() < piece.range().end()) {
                String genesis = genesisTxid(run, tx, doginals.get());
                if(genesis != null) {
                    run.foundAt(piece, genesis + "i0", first.range().start(), chainTx);
                }
            }
        }

        List<Long> inputValues = run.inputValues(tx);
        if(OrdinalsScan.hasEnvelope(tx)) {
            for(Creation creation : InscriptionLocator.ord(tx, inputValues)) {
                if(creation.vout() == Creation.ANYWHERE) {
                    run.found.add(new Found(creation.inscriptionId(), -1, null, tx.getTxId(), chainTx.height(), chainTx.blockTime()));
                } else if(creation.vout() == piece.vout() && creation.offset() >= piece.range().start() && creation.offset() < piece.range().end()) {
                    run.foundAt(piece, creation.inscriptionId(), creation.offset(), chainTx);
                }
            }
        }
        run.queue.addAll(UnitFlow.back(tx, inputValues, piece));
    }

    /**
     * The txid that names a Doginals inscription: the chain's first step ("ord" first), found by walking input 0 back.
     * Null when the walk reaches a transaction that is no step (so this was no Doginals chain); "?" when the budget runs
     * out first, which still counts as an inscription.
     */
    private String genesisTxid(Run run, Transaction last, DoginalsStep step) throws IOException {
        Transaction current = last;
        DoginalsStep currentStep = step;
        try {
            for(int steps = 0; !currentStep.genesis(); steps++) {
                if(steps >= InscriptionContent.MAX_DOGINALS_STEPS) {
                    return "?";
                }
                current = run.fetch(currentStep.previousTxid()).tx();
                Optional<DoginalsStep> previous = InscriptionLocator.doginals(current);
                if(previous.isEmpty() || previous.get().last()) {
                    return null;
                }
                currentStep = previous.get();
            }
        } catch(BudgetUsedException e) {
            return "?";
        }
        return current.getTxId().toString();
    }

    /** A piece on a coinbase: reward units are clean; fee units are traced into the block transactions that paid them. */
    private void minedCoin(Run run, Piece piece, ChainTx coinbase) throws IOException, BudgetUsedException {
        Transaction tx = coinbase.tx();
        long total = UnitFlow.outputTotal(tx);
        long outputStart = 0;
        for(int i = 0; i < piece.vout(); i++) {
            outputStart += tx.getOutputs().get(i).getValue();
        }
        UnitRange flow = piece.range().shift(outputStart);

        //A height read from confirmations can be one off as a block arrives; at a halving the smaller reward is the safe one
        OptionalLong coded = smallestSubsidy(coinbase.height());
        if(coded.isPresent() && flow.end() <= coded.getAsLong()) {
            return;
        }

        Optional<List<BlockFee>> fees = blockFees(run, coinbase);
        if(fees.isEmpty()) {
            run.deadEnd(FEES_NEED_DEEPER);
            run.open.add(piece);
            return;
        }
        long feeTotal = 0;
        for(BlockFee fee : fees.get()) {
            feeTotal += fee.fee();
        }
        long reward = coded.isPresent() ? coded.getAsLong() : total - feeTotal;
        if(reward < 0 || reward + feeTotal != total) {
            run.deadEnd(DOES_NOT_ADD_UP);
            return;
        }
        if(flow.end() <= reward) {
            return;
        }

        UnitRange feePart = flow.intersect(new UnitRange(reward, total));
        long cursor = reward;
        for(BlockFee fee : fees.get()) {
            if(fee.fee() > 0) {
                UnitRange shared = new UnitRange(cursor, cursor + fee.fee()).intersect(feePart);
                if(shared != null) {
                    run.queue.add(new Piece(fee.txid(), Piece.FEE, shared.shift(-cursor), piece.coinOffset() + (shared.start() - flow.start())));
                }
            }
            cursor += fee.fee();
        }
    }

    /**
     * The fees of the coinbase's block in block order: from the server when it lists them (Esplora), otherwise, when
     * walkBlocks allows, by fetching each block transaction and its inputs. Empty when neither is possible.
     */
    private OptionalLong smallestSubsidy(int height) {
        OptionalLong smallest = OptionalLong.empty();
        for(int h = Math.max(0, height - 1); h <= height + 1; h++) {
            OptionalLong subsidy = ChainRules.subsidy(coin, network, h);
            if(subsidy.isEmpty()) {
                return OptionalLong.empty();
            }
            smallest = smallest.isEmpty() || subsidy.getAsLong() < smallest.getAsLong() ? subsidy : smallest;
        }
        return smallest;
    }

    private Optional<List<BlockFee>> blockFees(Run run, ChainTx coinbase) throws IOException, BudgetUsedException {
        Sha256Hash blockHash = coinbase.blockHash();
        if(blockHash == null) {
            //The app's verbose answers carry no block hash: ask for the hash at the coinbase's height
            run.serverCall();
            blockHash = source.blockHash(coinbase.height()).orElse(null);
        }
        run.serverCall();
        Optional<List<BlockFee>> listed = blockHash == null ? Optional.empty() : source.blockFees(coinbase.height(), blockHash);
        if(listed.isPresent() || !run.walkBlocks) {
            return listed;
        }
        //Position 0 must be this coinbase: otherwise the server cannot walk blocks (or walks another), and an empty walk
        //would read as a block without fees
        run.serverCall();
        Optional<Sha256Hash> first = source.txidAt(coinbase.height(), 0);
        if(first.isEmpty() || !first.get().equals(coinbase.tx().getTxId())) {
            return Optional.empty();
        }
        List<BlockFee> walked = new ArrayList<>();
        for(int position = 1; ; position++) {
            run.serverCall();
            Optional<Sha256Hash> txid = source.txidAt(coinbase.height(), position);
            if(txid.isEmpty()) {
                break;
            }
            Transaction blockTx = run.fetch(txid.get()).tx();
            if(blockTx.isHogEx()) {
                //Litecoin's MWEB fees are not a HogEx's input-output difference; a coinbase that needs them does not add up
                continue;
            }
            walked.add(new BlockFee(txid.get(), UnitFlow.fee(blockTx, run.inputValues(blockTx))));
        }
        return Optional.of(walked);
    }

    private static List<Found> distinct(List<Found> found) {
        Map<String, Found> byId = new LinkedHashMap<>();
        for(Found f : found) {
            Found existing = byId.get(f.inscriptionId());
            if(existing == null || (existing.offset() < 0 && f.offset() >= 0)) {
                byId.put(f.inscriptionId(), f);
            }
        }
        return new ArrayList<>(byId.values());
    }
}
