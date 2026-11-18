package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Lists the NFTs an account holds in one collection, using only the RPC: a known collection by its own rule (the
 * Vault Badge's computed ids), an enumerable one by index. Any other collection needs an explorer and is not listed.
 *
 * Every token found is confirmed with ownerOf, so a contract that names a token the owner does not hold has it
 * dropped. The work is bounded: at most {@link #MAX_TOKENS} tokens, for each the call that finds it plus ownerOf and
 * tokenURI, and an overall deadline of 60 seconds, from the start of the listing (reading the collection included,
 * when this reads it). No call starts after the deadline and each call's timeout, connecting included, is cut to the
 * time left ({@link LitVmRpcClient#withDeadline}), so a listing ends by the deadline, 60 seconds, plus the time to
 * handle the last answer. Whatever is not looked up counts in {@code more}.
 *
 * Failures: the collection-level reads (the collection itself, worlds(), balanceOf) throw IOException, for the caller
 * to handle per collection. For one token, the contract's own answers (a revert, another owner, an unreadable or
 * hosted URI) are not failures: the token is dropped or listed without metadata. A failure to get an answer at all
 * (the network, a rate limit, a node fault, a response over the client's cap) is counted in {@code failed}: on ownerOf
 * the token is dropped, on tokenURI it stays listed without metadata.
 *
 * How a listing ends decides where the rest is counted: tokens beyond {@link #MAX_TOKENS} (the wallet's cap) and those
 * after a revert of tokenOfOwnerByIndex (the contract's answer) count in {@code more}; those not reached because a call
 * got no answer or the deadline passed count in {@code failed}, since a refresh may reach them.
 */
public final class NftLister {
    public static final int MAX_TOKENS = 200;
    static final long DEADLINE_NANOS = 60_000_000_000L;

    private NftLister() {}

    public sealed interface Result permits Listed, NeedsExplorer, NotACollection {}

    /**
     * The tokens found; how many more the owner may hold that were not looked up (at most Integer.MAX_VALUE); how many
     * could not be read because no readable answer came (dropped, or listed without metadata); and whether the endpoint
     * limited requests (HTTP 429) on a token's call, which stops the listing at once with the rest counted in failed.
     * A 429 on a collection-level read (the collection, worlds(), balanceOf) is thrown instead.
     */
    public record Listed(List<Nft> tokens, int more, int failed, boolean rateLimited) implements Result {
        public Listed {
            tokens = List.copyOf(tokens);
        }

        public Listed(List<Nft> tokens, int more, int failed) {
            this(tokens, more, failed, false);
        }

        public Listed(List<Nft> tokens, int more) {
            this(tokens, more, 0);
        }
    }

    /** An ERC-721 collection that neither enumerates its tokens nor is known: listing it needs an explorer. */
    public record NeedsExplorer() implements Result {}

    /** The contract is not an ERC-721 collection. */
    public record NotACollection() implements Result {}

    /** Whether a collection can be listed without an explorer: it is known on this network, or enumerable. */
    public static boolean canList(LitVmNetwork network, Erc721Collection collection) {
        return KnownCollections.isKnown(network, collection.contract()) || collection.enumerable();
    }

    public static Result list(LitVmRpcClient client, EvmAddress contract, EvmAddress owner) throws IOException {
        return list(client, contract, owner, System::nanoTime);
    }

    /** As {@link #list(LitVmRpcClient, EvmAddress, EvmAddress)} for a collection already read, which is not read again. */
    public static Result list(LitVmRpcClient client, Erc721Collection collection, EvmAddress owner) throws IOException {
        return list(client, collection, owner, System::nanoTime);
    }

    static Result list(LitVmRpcClient client, EvmAddress contract, EvmAddress owner, LongSupplier nanoTime) throws IOException {
        long start = nanoTime.getAsLong();
        LitVmRpcClient bounded = client.until(start + DEADLINE_NANOS, nanoTime);
        Optional<Erc721Collection> read = Erc721.read(bounded, contract);
        if(read.isEmpty()) {
            return new NotACollection();
        }
        return listCollection(bounded, read.get(), owner, start, nanoTime);
    }

    static Result list(LitVmRpcClient client, Erc721Collection collection, EvmAddress owner, LongSupplier nanoTime) throws IOException {
        long start = nanoTime.getAsLong();
        return listCollection(client.until(start + DEADLINE_NANOS, nanoTime), collection, owner, start, nanoTime);
    }

    private static Result listCollection(LitVmRpcClient client, Erc721Collection collection, EvmAddress owner, long start, LongSupplier nanoTime)
            throws IOException {
        Optional<KnownCollections.Known> known = KnownCollections.known(client.getNetwork(), collection.contract());
        if(known.isPresent()) {
            return listCandidates(client, collection, owner, known.get().tokenIds().candidates(client, collection.contract(), owner), start, nanoTime);
        }
        if(collection.enumerable()) {
            return listEnumerable(client, collection, owner, start, nanoTime);
        }
        return new NeedsExplorer();
    }

    /**
     * What looking up one token gave: kept (with or without its metadata), dropped by the contract's answer, out of
     * time, or stopped by a rate limit: before the token was confirmed, or after (kept without metadata).
     */
    private enum Outcome {KEPT, DROPPED, OUT_OF_TIME, RATE_LIMITED, KEPT_THEN_RATE_LIMITED}

    /** Running totals of a listing. */
    private static final class Tally {
        final List<Nft> tokens = new ArrayList<>();
        final Set<BigInteger> seen = new HashSet<>();
        int failed;
        boolean rateLimited;
    }

    private static Result listCandidates(LitVmRpcClient client, Erc721Collection collection, EvmAddress owner, KnownCollections.Candidates candidates,
                                         long start, LongSupplier nanoTime) {
        Tally tally = new Tally();
        List<BigInteger> ids = candidates.tokenIds();
        BigInteger more = candidates.notLookedUp();
        for(int i = 0; i < ids.size(); i++) {
            if(tally.tokens.size() == MAX_TOKENS) {
                more = more.add(BigInteger.valueOf(ids.size() - i));
                break;
            }
            Outcome outcome = pastDeadline(start, nanoTime) ? Outcome.OUT_OF_TIME
                    : tally.seen.add(ids.get(i)) ? confirm(client, collection, owner, ids.get(i), tally) : Outcome.DROPPED;
            if(outcome == Outcome.OUT_OF_TIME || outcome == Outcome.RATE_LIMITED) {
                //This candidate and the rest were not reached
                tally.failed += ids.size() - i;
                break;
            }
            if(outcome == Outcome.KEPT_THEN_RATE_LIMITED) {
                //This candidate is kept (and counted for its metadata); the rest were not reached
                tally.failed += ids.size() - i - 1;
                break;
            }
        }
        return new Listed(tally.tokens, toInt(more), tally.failed, tally.rateLimited);
    }

    private static Result listEnumerable(LitVmRpcClient client, Erc721Collection collection, EvmAddress owner, long start, LongSupplier nanoTime)
            throws IOException {
        BigInteger balance = Erc721.balanceOf(client, collection.contract(), owner);
        //A contract can answer up to 2^256 - 1: compare before narrowing
        int toVisit = balance.min(BigInteger.valueOf(MAX_TOKENS)).intValueExact();
        Tally tally = new Tally();
        int visited = 0;
        boolean unreached = false;
        while(visited < toVisit) {
            if(pastDeadline(start, nanoTime)) {
                unreached = true;
                break;
            }
            BigInteger tokenId;
            try {
                tokenId = Erc721.tokenOfOwnerByIndex(client, collection.contract(), owner, BigInteger.valueOf(visited));
            } catch(LitVmRpcClient.LitVmRpcException e) {
                //A revert is the contract's answer and ends the listing; any other node error is a failure to reach the rest
                unreached = !e.isRevert();
                break;
            } catch(LitVmRpcClient.RateLimitedException e) {
                tally.rateLimited = true;
                unreached = true;
                break;
            } catch(IOException e) {
                unreached = true;
                break;
            }
            Outcome outcome = tally.seen.add(tokenId) ? confirm(client, collection, owner, tokenId, tally) : Outcome.DROPPED;
            if(outcome == Outcome.OUT_OF_TIME || outcome == Outcome.RATE_LIMITED) {
                unreached = true;
                break;
            }
            visited++;
            if(outcome == Outcome.KEPT_THEN_RATE_LIMITED) {
                unreached = true;
                break;
            }
        }
        //Within the cap, what was not visited is unread when the listing could not reach it and not listed when the
        //contract ended it; beyond the cap it is not listed
        BigInteger beyondCap = balance.subtract(BigInteger.valueOf(toVisit));
        int rest = toVisit - visited;
        if(unreached) {
            tally.failed += rest;
            return new Listed(tally.tokens, toInt(beyondCap), tally.failed, tally.rateLimited);
        }
        return new Listed(tally.tokens, toInt(beyondCap.add(BigInteger.valueOf(rest))), tally.failed, tally.rateLimited);
    }

    /**
     * Confirms a token with ownerOf and reads its metadata, adding it to the tally when the owner holds it. Only a
     * revert or a readable answer naming another owner (the zero address included) drops the token silently; a call
     * that gets no readable answer counts as failed. One refused for lack of time leaves the token not looked up, unless
     * ownerOf had already confirmed it, when it is kept without metadata and counted as failed. A rate limit stops the
     * listing the same way, and marks the tally.
     */
    private static Outcome confirm(LitVmRpcClient client, Erc721Collection collection, EvmAddress owner, BigInteger tokenId, Tally tally) {
        try {
            if(!Erc721.ownerOf(client, collection.contract(), tokenId).map(owner::equals).orElse(false)) {
                return Outcome.DROPPED;
            }
        } catch(LitVmRpcClient.DeadlineException e) {
            return Outcome.OUT_OF_TIME;
        } catch(LitVmRpcClient.RateLimitedException e) {
            tally.rateLimited = true;
            return Outcome.RATE_LIMITED;
        } catch(IOException e) {
            tally.failed++;
            return Outcome.DROPPED;
        }
        Optional<NftMetadata> metadata;
        Outcome outcome = Outcome.KEPT;
        try {
            metadata = Erc721.tokenUri(client, collection.contract(), tokenId).flatMap(NftMetadata::parse);
        } catch(IOException e) {
            metadata = Optional.empty();
            tally.failed++;
            if(e instanceof LitVmRpcClient.RateLimitedException) {
                tally.rateLimited = true;
                outcome = Outcome.KEPT_THEN_RATE_LIMITED;
            }
        }
        tally.tokens.add(new Nft(collection, tokenId, metadata));
        return outcome;
    }

    private static boolean pastDeadline(long start, LongSupplier nanoTime) {
        return nanoTime.getAsLong() - start >= DEADLINE_NANOS;
    }

    private static int toInt(BigInteger count) {
        return count.signum() <= 0 ? 0 : count.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValueExact();
    }
}
