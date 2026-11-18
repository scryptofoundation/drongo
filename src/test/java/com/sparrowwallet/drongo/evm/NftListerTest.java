package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Listing a holder's NFTs against the fake JSON-RPC node of {@link Erc721Test}. */
public class NftListerTest {
    private static final EvmAddress BADGE = EvmAddress.fromString("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3");
    private static final EvmAddress HOLDER = EvmAddress.fromString("0x840e41D4484041A43f9307a90389edeB3d111fb3");
    private static final EvmAddress OTHER = EvmAddress.fromString("0x000000000000000000000000000000000000dEaD");
    private static final EvmAddress COLLECTION = EvmAddress.fromString("0x00000000000000000000000000000000000c0ffe");

    private Erc721Test.FakeNode node;

    @BeforeEach
    public void startNode() throws IOException {
        node = new Erc721Test.FakeNode();
    }

    @AfterEach
    public void stopNode() {
        node.close();
    }

    @Test
    public void listsTheVaultBadgesAnAccountHolds() throws IOException {
        FakeCollection badge = vaultBadge(4);
        badge.owners.put(world(HOLDER, 1), HOLDER);
        badge.owners.put(world(HOLDER, 3), HOLDER);
        badge.owners.put(world(OTHER, 2), OTHER);
        badge.uris.put(world(HOLDER, 1), Erc721Test.Reply.result(Erc721Test.resource("vault-badge-world-1-eth-call-result.txt").substring(2)));
        node.contract(BADGE, badge);

        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), BADGE, HOLDER));
        Assertions.assertEquals(0, listed.more());
        Assertions.assertEquals(List.of(world(HOLDER, 1), world(HOLDER, 3)), listed.tokens().stream().map(Nft::tokenId).toList());
        Nft first = listed.tokens().get(0);
        Assertions.assertEquals("Super Litecoin Vault Badge: World 1", first.metadata().orElseThrow().name());
        Assertions.assertEquals("Token " + world(HOLDER, 3), listed.tokens().get(1).metadata().orElseThrow().name());
        Assertions.assertEquals(BADGE, first.collection().contract());
        Assertions.assertTrue(first.collection().soulbound());
        //Read the collection (5 interface queries, name, symbol), worlds(), then ownerOf for 4 worlds and tokenURI for the 2 held
        Assertions.assertEquals(7 + 1 + 4 + 2, node.calls("eth_call").size());
    }

    @Test
    public void anAccountWithNoneGetsAnEmptyList() throws IOException {
        FakeCollection badge = vaultBadge(4);
        badge.owners.put(world(OTHER, 1), OTHER);
        node.contract(BADGE, badge);
        Assertions.assertEquals(new NftLister.Listed(List.of(), 0), NftLister.list(node.client(), BADGE, HOLDER));

        FakeCollection enumerable = enumerable(List.of());
        node.contract(COLLECTION, enumerable);
        Assertions.assertEquals(new NftLister.Listed(List.of(), 0), NftLister.list(node.client(), COLLECTION, HOLDER));
    }

    @Test
    public void enumerableCollectionsListByIndex() throws IOException {
        node.contract(COLLECTION, enumerable(ids(1000, 1001, 1002)));
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(ids(1000, 1001, 1002), listed.tokens().stream().map(Nft::tokenId).toList());
        Assertions.assertEquals("Token 1001", listed.tokens().get(1).metadata().orElseThrow().name());
        Assertions.assertEquals("Drunken Cats", listed.tokens().get(0).collection().name());
        Assertions.assertTrue(listed.tokens().get(0).collection().enumerable());
        Assertions.assertEquals(0, listed.more());
    }

    @Test
    public void aTokenTheContractDoesNotSayTheOwnerHoldsIsDropped() throws IOException {
        //Index 1 names a token someone else holds, index 2 one that does not exist, index 3 repeats index 0
        FakeCollection lying = enumerable(ids(1000, 1001, 1002, 1000, 1004));
        lying.owners.put(BigInteger.valueOf(1001), OTHER);
        lying.owners.remove(BigInteger.valueOf(1002));
        node.contract(COLLECTION, lying);
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(ids(1000, 1004), listed.tokens().stream().map(Nft::tokenId).toList());
        Assertions.assertEquals(0, listed.more());
        //A revert or another owner is the contract's answer, not a failure: nothing is counted as unread
        Assertions.assertEquals(0, listed.failed());

        //Owner checks also hold for a known collection whose ownerOf names another account
        FakeCollection badge = vaultBadge(2);
        badge.owners.put(world(HOLDER, 1), OTHER);
        node.contract(BADGE, badge);
        Assertions.assertEquals(new NftLister.Listed(List.of(), 0), NftLister.list(node.client(), BADGE, HOLDER));
    }

    @Test
    public void aCollectionThatIsNeitherNeedsAnExplorer() throws IOException {
        FakeCollection plain = enumerable(ids(1));
        plain.enumerable = false;
        node.contract(COLLECTION, plain);
        Assertions.assertEquals(new NftLister.NeedsExplorer(), NftLister.list(node.client(), COLLECTION, HOLDER));

        //The badge's computed ids belong to its address on LiteForge: the same code anywhere else is just another collection
        node.contract(COLLECTION, vaultBadge(4));
        Assertions.assertEquals(new NftLister.NeedsExplorer(), NftLister.list(node.client(), COLLECTION, HOLDER));
    }

    @Test
    public void anErc20IsNotACollection() throws IOException {
        node.contract(COLLECTION, data -> switch(data.substring(0, 8)) {
            case "06fdde03" -> Erc721Test.Reply.result(string("Test Token"));
            case "313ce567" -> Erc721Test.Reply.result(word(18));
            default -> Erc721Test.Reply.revert();
        });
        Assertions.assertEquals(new NftLister.NotACollection(), NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(new NftLister.NotACollection(), NftLister.list(node.client(), OTHER, HOLDER));
    }

    @Test
    public void listingStopsAtTwoHundred() throws IOException {
        List<BigInteger> many = new ArrayList<>();
        for(int i = 0; i < 500; i++) {
            many.add(BigInteger.valueOf(5000 + i));
        }
        node.contract(COLLECTION, enumerable(many));
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(NftLister.MAX_TOKENS, listed.tokens().size());
        //The cap is the wallet's choice: what lies beyond it counts as not listed
        Assertions.assertEquals(300, listed.more());
        Assertions.assertEquals(0, listed.failed());
        //The collection (7), balanceOf, then the index, owner and URI of each of the 200
        Assertions.assertEquals(7 + 1 + 3 * NftLister.MAX_TOKENS, node.calls("eth_call").size());

        //A balance of 2^256 - 1 is compared before it is narrowed
        FakeCollection huge = enumerable(many);
        huge.balance = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);
        node.contract(COLLECTION, huge);
        listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(NftLister.MAX_TOKENS, listed.tokens().size());
        Assertions.assertEquals(Integer.MAX_VALUE, listed.more());
    }

    @Test
    public void worldsIsCapped() throws IOException {
        FakeCollection badge = vaultBadge(0);
        badge.worlds = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);
        badge.owners.put(world(HOLDER, 64), HOLDER);
        badge.owners.put(world(HOLDER, 65), HOLDER);
        node.contract(BADGE, badge);
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), BADGE, HOLDER));
        Assertions.assertEquals(List.of(world(HOLDER, 64)), listed.tokens().stream().map(Nft::tokenId).toList());
        Assertions.assertEquals(Integer.MAX_VALUE, listed.more());
        Assertions.assertEquals(7 + 1 + 64 + 1, node.calls("eth_call").size());

        //Worlds beyond the cap are not looked up, and count as not listed
        badge.worlds = BigInteger.valueOf(70);
        listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), BADGE, HOLDER));
        Assertions.assertEquals(6, listed.more());
    }

    @Test
    public void metadataFailureStillListsTheToken() throws IOException {
        FakeCollection collection = enumerable(ids(1, 2, 3, 4));
        collection.uris.put(BigInteger.valueOf(1), Erc721Test.Reply.revert());
        collection.uris.put(BigInteger.valueOf(2), Erc721Test.Reply.result(string("https://example.com/2.json")));
        collection.uris.put(BigInteger.valueOf(3), Erc721Test.Reply.nodeError());
        node.contract(COLLECTION, collection);
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(ids(1, 2, 3, 4), listed.tokens().stream().map(Nft::tokenId).toList());
        Assertions.assertTrue(listed.tokens().get(0).metadata().isEmpty());
        Assertions.assertTrue(listed.tokens().get(1).metadata().isEmpty());
        Assertions.assertTrue(listed.tokens().get(2).metadata().isEmpty());
        Assertions.assertEquals("Token 4", listed.tokens().get(3).metadata().orElseThrow().name());
        //Only the node error is a failure to read; the revert and the hosted URI are the contract's answers
        Assertions.assertEquals(1, listed.failed());
    }

    @Test
    public void aFailureOnOneTokenDoesNotEndTheListing() throws IOException {
        //A node error (or an EVM fault, which surfaces the same way) on one token's ownerOf drops that token only
        FakeCollection collection = enumerable(ids(1, 2, 3));
        collection.overrides.put("6352211e" + word(2), Erc721Test.Reply.nodeError());
        node.contract(COLLECTION, collection);
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(ids(1, 3), listed.tokens().stream().map(Nft::tokenId).toList());
        Assertions.assertEquals(1, listed.failed());

        FakeCollection badge = vaultBadge(3);
        badge.owners.put(world(HOLDER, 1), HOLDER);
        badge.owners.put(world(HOLDER, 3), HOLDER);
        badge.overrides.put("6352211e" + word(world(HOLDER, 1)), Erc721Test.Reply.nodeError());
        node.contract(BADGE, badge);
        listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), BADGE, HOLDER));
        Assertions.assertEquals(List.of(world(HOLDER, 3)), listed.tokens().stream().map(Nft::tokenId).toList());
        Assertions.assertEquals(1, listed.failed());
    }

    @Test
    public void whenEveryOwnerOfFailsTheTokensAreCountedNotLost() throws IOException {
        //A rate limit on every ownerOf must not read as "holds nothing"
        FakeCollection badge = vaultBadge(3);
        for(int world = 1; world <= 3; world++) {
            badge.owners.put(world(HOLDER, world), HOLDER);
            badge.overrides.put("6352211e" + word(world(HOLDER, world)), Erc721Test.Reply.nodeError());
        }
        node.contract(BADGE, badge);
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), BADGE, HOLDER));
        Assertions.assertEquals(List.of(), listed.tokens());
        Assertions.assertEquals(0, listed.more());
        Assertions.assertEquals(3, listed.failed());

        FakeCollection collection = enumerable(ids(1, 2));
        collection.overrides.put("6352211e" + word(1), Erc721Test.Reply.nodeError());
        collection.overrides.put("6352211e" + word(2), Erc721Test.Reply.nodeError());
        node.contract(COLLECTION, collection);
        listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(List.of(), listed.tokens());
        Assertions.assertEquals(0, listed.more());
        Assertions.assertEquals(2, listed.failed());
    }

    @Test
    public void aRepeatedIdIsListedOnce() throws IOException {
        node.contract(COLLECTION, enumerable(ids(7, 7, 8, 7)));
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(ids(7, 8), listed.tokens().stream().map(Nft::tokenId).toList());
        Assertions.assertEquals(0, listed.more());
        Assertions.assertEquals(0, listed.failed());
    }

    @Test
    public void aCollectionAlreadyReadIsNotReadAgain() throws IOException {
        node.contract(COLLECTION, enumerable(ids(1, 2)));
        Erc721Collection collection = Erc721.read(node.client(), COLLECTION).orElseThrow();
        int calls = node.calls("eth_call").size();
        int codes = node.calls("eth_getCode").size();
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), collection, HOLDER));
        Assertions.assertEquals(ids(1, 2), listed.tokens().stream().map(Nft::tokenId).toList());
        //balanceOf, then the index, owner and URI of each token: no code, interface, name or symbol queries
        Assertions.assertEquals(1 + 3 * 2, node.calls("eth_call").size() - calls);
        Assertions.assertEquals(codes, node.calls("eth_getCode").size());
    }

    @Test
    public void listabilityIsDecidedFromTheCollectionAlone() {
        Assertions.assertTrue(NftLister.canList(LitVmNetwork.LITEFORGE_TESTNET, new Erc721Collection(BADGE, "Super Litecoin Vault Badge", "SLVB", false, true)));
        Assertions.assertTrue(NftLister.canList(LitVmNetwork.LITEFORGE_TESTNET, new Erc721Collection(COLLECTION, "Cats", "CATS", true, false)));
        Assertions.assertFalse(NftLister.canList(LitVmNetwork.LITEFORGE_TESTNET, new Erc721Collection(COLLECTION, "Cats", "CATS", false, false)));
        Assertions.assertFalse(NftLister.canList(LitVmNetwork.SEPOLIA, new Erc721Collection(BADGE, "Super Litecoin Vault Badge", "SLVB", false, true)));
    }

    @Test
    public void noCallStartsAfterTheDeadline() throws IOException {
        node.contract(COLLECTION, enumerable(ids(1)));
        long[] now = {0};
        long tenSeconds = 10_000_000_000L;
        LitVmRpcClient bounded = node.client().until(tenSeconds, () -> now[0]);
        Assertions.assertTrue(bounded.hasCode(COLLECTION));
        now[0] = tenSeconds;
        Assertions.assertThrows(LitVmRpcClient.DeadlineException.class, () -> bounded.hasCode(COLLECTION));
        Assertions.assertThrows(LitVmRpcClient.DeadlineException.class, () -> bounded.ethCall(COLLECTION, Abi.selector("name()"), 100));
        Assertions.assertEquals(1, node.calls("eth_getCode").size());
        Assertions.assertEquals(0, node.calls("eth_call").size());
        //A deadline inside another keeps the earlier one
        Assertions.assertThrows(LitVmRpcClient.DeadlineException.class, () -> node.client().until(tenSeconds, () -> now[0]).until(2 * tenSeconds, () -> now[0]).hasCode(COLLECTION));
        //A call never waits longer than the time left, connecting included: with a nanosecond left it cannot connect
        Assertions.assertThrows(java.net.http.HttpTimeoutException.class, () -> node.client().until(1, () -> 0L).hasCode(COLLECTION));
        Assertions.assertEquals(java.time.Duration.ofNanos(5), LitVmRpcClient.timeoutWithin(5));
        Assertions.assertEquals(LitVmRpcClient.REQUEST_TIMEOUT, LitVmRpcClient.timeoutWithin(Long.MAX_VALUE));
    }

    @Test
    public void aCollectionLevelFailureIsThrown() throws IOException {
        FakeCollection collection = enumerable(ids(1));
        collection.overrides.put("70a08231" + "000000000000000000000000" + hex(HOLDER), Erc721Test.Reply.nodeError());
        node.contract(COLLECTION, collection);
        Assertions.assertThrows(IOException.class, () -> NftLister.list(node.client(), COLLECTION, HOLDER));
        collection.overrides.put("70a08231" + "000000000000000000000000" + hex(HOLDER), Erc721Test.Reply.revert());
        Assertions.assertThrows(IOException.class, () -> NftLister.list(node.client(), COLLECTION, HOLDER));

        FakeCollection badge = vaultBadge(4);
        badge.overrides.put("23c4f6e3", Erc721Test.Reply.nodeError());
        node.contract(BADGE, badge);
        Assertions.assertThrows(IOException.class, () -> NftLister.list(node.client(), BADGE, HOLDER));
        badge.overrides.put("23c4f6e3", Erc721Test.Reply.result(""));
        Assertions.assertThrows(IOException.class, () -> NftLister.list(node.client(), BADGE, HOLDER));

        node.reply(Erc721Test.Reply.http(503));
        Assertions.assertThrows(IOException.class, () -> NftLister.list(node.client(), BADGE, HOLDER));
    }

    @Test
    public void aRevertFromTokenOfOwnerByIndexEndsTheListing() throws IOException {
        FakeCollection collection = enumerable(ids(1, 2, 3, 4, 5));
        collection.overrides.put("2f745c59" + "000000000000000000000000" + hex(HOLDER) + word(2), Erc721Test.Reply.revert());
        node.contract(COLLECTION, collection);
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(ids(1, 2), listed.tokens().stream().map(Nft::tokenId).toList());
        //The contract's own answer: the rest counts as not listed, not as unread
        Assertions.assertEquals(3, listed.more());
        Assertions.assertEquals(0, listed.failed());
    }

    @Test
    public void aFailureFromTokenOfOwnerByIndexCountsTheRestAsFailed() throws IOException {
        //No answer at index 2 (a rate limit): the three not reached could not be read, so a refresh may find them
        FakeCollection collection = enumerable(ids(1, 2, 3, 4, 5));
        collection.overrides.put("2f745c59" + "000000000000000000000000" + hex(HOLDER) + word(2), Erc721Test.Reply.nodeError());
        node.contract(COLLECTION, collection);
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(ids(1, 2), listed.tokens().stream().map(Nft::tokenId).toList());
        Assertions.assertEquals(0, listed.more());
        Assertions.assertEquals(3, listed.failed());
    }

    @Test
    public void anOversizedResponseToOwnerOfIsAFailureNotAnAnswer() throws IOException {
        //A whole response over the client's cap is the transport's failure: the token is counted, not silently dropped
        FakeCollection collection = enumerable(ids(1, 2));
        collection.overrides.put("6352211e" + word(2), Erc721Test.Reply.result("ab".repeat(600_000)));
        node.contract(COLLECTION, collection);
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(ids(1), listed.tokens().stream().map(Nft::tokenId).toList());
        Assertions.assertEquals(1, listed.failed());
    }

    @Test
    public void onlyARevertOrAnotherOwnerDropsATokenSilently() throws IOException {
        //1: a word with bits above the address; 2: an empty answer; 3: the zero address; 4: another owner; 5: held
        FakeCollection collection = enumerable(ids(1, 2, 3, 4, 5, 6));
        collection.overrides.put("6352211e" + word(1), Erc721Test.Reply.result("01" + "00".repeat(11) + hex(HOLDER)));
        collection.overrides.put("6352211e" + word(2), Erc721Test.Reply.result(""));
        collection.overrides.put("6352211e" + word(3), Erc721Test.Reply.result(word(0)));
        collection.owners.put(BigInteger.valueOf(4), OTHER);
        collection.owners.remove(BigInteger.valueOf(6));
        node.contract(COLLECTION, collection);
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(ids(5), listed.tokens().stream().map(Nft::tokenId).toList());
        //The two unreadable answers are failures; the zero address, the other owner and the revert (6) are answers
        Assertions.assertEquals(2, listed.failed());
        Assertions.assertEquals(0, listed.more());
        Assertions.assertFalse(listed.rateLimited());

        //An answer over the call's own cap is a failure too
        FakeCollection oversized = enumerable(ids(1, 2));
        oversized.overrides.put("6352211e" + word(1), Erc721Test.Reply.result("ab".repeat(8_193)));
        node.contract(COLLECTION, oversized);
        listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(ids(2), listed.tokens().stream().map(Nft::tokenId).toList());
        Assertions.assertEquals(1, listed.failed());
    }

    @Test
    public void aRateLimitOnOwnerOfStopsTheListing() throws IOException {
        FakeCollection badge = vaultBadge(4);
        for(int world = 1; world <= 4; world++) {
            badge.owners.put(world(HOLDER, world), HOLDER);
        }
        badge.overrides.put("6352211e" + word(world(HOLDER, 2)), Erc721Test.Reply.http(429));
        node.contract(BADGE, badge);
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), BADGE, HOLDER));
        Assertions.assertEquals(List.of(world(HOLDER, 1)), listed.tokens().stream().map(Nft::tokenId).toList());
        //World 2, whose ownerOf was refused, and worlds 3 and 4, never asked
        Assertions.assertEquals(3, listed.failed());
        Assertions.assertEquals(0, listed.more());
        Assertions.assertTrue(listed.rateLimited());
        //The collection (7), worlds(), ownerOf and tokenURI of world 1, ownerOf of world 2: nothing after the 429
        Assertions.assertEquals(7 + 1 + 2 + 1, node.calls("eth_call").size());

        FakeCollection collection = enumerable(ids(1, 2, 3, 4, 5));
        collection.overrides.put("6352211e" + word(2), Erc721Test.Reply.http(429));
        node.contract(COLLECTION, collection);
        int before = node.calls("eth_call").size();
        listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(ids(1), listed.tokens().stream().map(Nft::tokenId).toList());
        Assertions.assertEquals(4, listed.failed());
        Assertions.assertEquals(0, listed.more());
        Assertions.assertTrue(listed.rateLimited());
        //The collection (7), balanceOf, index, owner and URI of token 1, then index and owner of token 2
        Assertions.assertEquals(7 + 1 + 3 + 2, node.calls("eth_call").size() - before);
    }

    @Test
    public void aRateLimitOnTokenUriKeepsTheTokenAndStopsTheListing() throws IOException {
        FakeCollection badge = vaultBadge(3);
        for(int world = 1; world <= 3; world++) {
            badge.owners.put(world(HOLDER, world), HOLDER);
        }
        badge.uris.put(world(HOLDER, 1), Erc721Test.Reply.http(429));
        node.contract(BADGE, badge);
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), BADGE, HOLDER));
        Assertions.assertEquals(List.of(world(HOLDER, 1)), listed.tokens().stream().map(Nft::tokenId).toList());
        Assertions.assertTrue(listed.tokens().get(0).metadata().isEmpty());
        //World 1's metadata, and worlds 2 and 3, never asked
        Assertions.assertEquals(3, listed.failed());
        Assertions.assertTrue(listed.rateLimited());
        Assertions.assertEquals(7 + 1 + 2, node.calls("eth_call").size());

        FakeCollection collection = enumerable(ids(1, 2, 3));
        collection.uris.put(BigInteger.valueOf(2), Erc721Test.Reply.http(429));
        node.contract(COLLECTION, collection);
        int before = node.calls("eth_call").size();
        listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(ids(1, 2), listed.tokens().stream().map(Nft::tokenId).toList());
        Assertions.assertTrue(listed.tokens().get(1).metadata().isEmpty());
        Assertions.assertEquals(2, listed.failed());
        Assertions.assertEquals(0, listed.more());
        Assertions.assertTrue(listed.rateLimited());
        Assertions.assertEquals(7 + 1 + 3 + 3, node.calls("eth_call").size() - before);
    }

    @Test
    public void aRateLimitOnTokenOfOwnerByIndexStopsTheListing() throws IOException {
        FakeCollection collection = enumerable(ids(1, 2, 3, 4, 5));
        collection.overrides.put("2f745c59" + "000000000000000000000000" + hex(HOLDER) + word(2), Erc721Test.Reply.http(429));
        node.contract(COLLECTION, collection);
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER));
        Assertions.assertEquals(ids(1, 2), listed.tokens().stream().map(Nft::tokenId).toList());
        Assertions.assertEquals(3, listed.failed());
        Assertions.assertEquals(0, listed.more());
        Assertions.assertTrue(listed.rateLimited());
        Assertions.assertEquals(7 + 1 + 3 * 2 + 1, node.calls("eth_call").size());
    }

    @Test
    public void aRateLimitOnACollectionLevelReadIsThrown() throws IOException {
        FakeCollection badge = vaultBadge(4);
        badge.overrides.put("23c4f6e3", Erc721Test.Reply.http(429));
        node.contract(BADGE, badge);
        Assertions.assertThrows(LitVmRpcClient.RateLimitedException.class, () -> NftLister.list(node.client(), BADGE, HOLDER));
        Assertions.assertEquals(7 + 1, node.calls("eth_call").size());

        FakeCollection collection = enumerable(ids(1));
        collection.overrides.put("70a08231" + "000000000000000000000000" + hex(HOLDER), Erc721Test.Reply.http(429));
        node.contract(COLLECTION, collection);
        Assertions.assertThrows(LitVmRpcClient.RateLimitedException.class, () -> NftLister.list(node.client(), COLLECTION, HOLDER));

        FakeCollection unread = enumerable(ids(1));
        unread.overrides.put("01ffc9a7" + "80ac58cd" + "00".repeat(28), Erc721Test.Reply.http(429));
        node.contract(COLLECTION, unread);
        Assertions.assertThrows(LitVmRpcClient.RateLimitedException.class, () -> NftLister.list(node.client(), COLLECTION, HOLDER));
    }

    @Test
    public void theListingStopsAtTheDeadline() throws IOException {
        List<BigInteger> many = new ArrayList<>();
        for(int i = 0; i < 100; i++) {
            many.add(BigInteger.valueOf(7000 + i));
        }
        node.contract(COLLECTION, enumerable(many));
        //Each call the node answers takes a second on this clock
        LongSupplier clock = () -> node.calls("eth_call").size() * 1_000_000_000L;
        NftLister.Listed listed = Assertions.assertInstanceOf(NftLister.Listed.class, NftLister.list(node.client(), COLLECTION, HOLDER, clock));
        Assertions.assertTrue(listed.tokens().size() < 100);
        //Every token is listed or counted as failed (one whose URI ran out of time is listed and counted): running out of
        //time is a failure to reach them, which a refresh may cure, not tokens beyond the wallet's cap
        Assertions.assertEquals(0, listed.more());
        Assertions.assertTrue(listed.tokens().size() + listed.failed() >= 100);
        //No call starts once the 60 seconds are spent, the collection's own reads included
        //(the clock counts eth_call only, so it is the calls that start after 60 of them that must not happen)
        int calls = node.calls("eth_call").size();
        Assertions.assertTrue(calls <= 60, "stopped at the deadline: " + calls);
    }

    @Test
    public void aLookalikeIsNotKnown() {
        Assertions.assertEquals(List.of(BADGE), KnownCollections.on(LitVmNetwork.LITEFORGE_TESTNET));
        Assertions.assertEquals(List.of(), KnownCollections.on(LitVmNetwork.SEPOLIA));
        Assertions.assertTrue(KnownCollections.isKnown(LitVmNetwork.LITEFORGE_TESTNET, BADGE));
        Assertions.assertFalse(KnownCollections.isKnown(LitVmNetwork.SEPOLIA, BADGE));
        Assertions.assertFalse(KnownCollections.isKnown(LitVmNetwork.LITEFORGE_TESTNET, COLLECTION));

        Erc721Collection real = new Erc721Collection(BADGE, "Super Litecoin Vault Badge", "SLVB", false, true);
        Assertions.assertFalse(lookalike(LitVmNetwork.LITEFORGE_TESTNET, real));
        Erc721Collection copy = new Erc721Collection(COLLECTION, "Super Litecoin Vault Badge", "SLVB", false, true);
        Assertions.assertFalse(KnownCollections.isKnown(LitVmNetwork.LITEFORGE_TESTNET, copy.contract()));
        Assertions.assertTrue(lookalike(LitVmNetwork.LITEFORGE_TESTNET, copy));
        //The name shown for the imitated collection is the wallet's own, never the contract's text
        Assertions.assertEquals(java.util.Optional.of("Super Litecoin Vault Badge"),
                KnownCollections.lookalikeOf(LitVmNetwork.LITEFORGE_TESTNET, new Erc721Collection(COLLECTION, "SUPER litecoin vault BADGE", "X", false, false)));
        Assertions.assertEquals(java.util.Optional.empty(), KnownCollections.lookalikeOf(LitVmNetwork.LITEFORGE_TESTNET, real));
        Assertions.assertEquals(java.util.Optional.empty(), KnownCollections.lookalikeOf(LitVmNetwork.LITEFORGE_TESTNET, new Erc721Collection(COLLECTION, "Drunken Cats", "CATS", true, false)));
        //Case, spacing and hidden characters do not make a different name
        Assertions.assertTrue(lookalike(LitVmNetwork.LITEFORGE_TESTNET,
                new Erc721Collection(COLLECTION, "super litecoin " + (char)0x200b + "VAULT badge", "X", false, false)));
        Assertions.assertTrue(lookalike(LitVmNetwork.LITEFORGE_TESTNET, new Erc721Collection(COLLECTION, "Badges", "slvb", false, false)));
        Assertions.assertFalse(lookalike(LitVmNetwork.LITEFORGE_TESTNET, new Erc721Collection(COLLECTION, "Drunken Cats", "CATS", true, false)));
        Assertions.assertFalse(lookalike(LitVmNetwork.LITEFORGE_TESTNET, new Erc721Collection(COLLECTION, "", "", true, false)));
    }

    private static boolean lookalike(LitVmNetwork network, Erc721Collection collection) {
        return KnownCollections.lookalikeOf(network, collection).isPresent();
    }

    private static BigInteger world(EvmAddress owner, int world) {
        return new BigInteger(1, owner.getBytes()).shiftLeft(8).or(BigInteger.valueOf(world));
    }

    private static List<BigInteger> ids(long... values) {
        List<BigInteger> ids = new ArrayList<>();
        for(long value : values) {
            ids.add(BigInteger.valueOf(value));
        }
        return ids;
    }

    private static String word(long value) {
        return String.format(Locale.ROOT, "%064x", value);
    }

    private static String word(BigInteger value) {
        String hex = value.toString(16);
        return "0".repeat(64 - hex.length()) + hex;
    }

    private static String string(String text) {
        return Utils.bytesToHex(Abi.encodeArguments(text));
    }

    private static String hex(EvmAddress address) {
        return Utils.bytesToHex(address.getBytes()).toLowerCase(Locale.ROOT);
    }

    private FakeCollection vaultBadge(int worlds) {
        FakeCollection badge = new FakeCollection("Super Litecoin Vault Badge", "SLVB");
        badge.soulbound = true;
        badge.worlds = BigInteger.valueOf(worlds);
        return badge;
    }

    private FakeCollection enumerable(List<BigInteger> byIndex) {
        FakeCollection collection = new FakeCollection("Drunken Cats", "CATS");
        collection.enumerable = true;
        collection.byIndex.addAll(byIndex);
        collection.balance = BigInteger.valueOf(byIndex.size());
        for(BigInteger id : byIndex) {
            collection.owners.put(id, HOLDER);
        }
        return collection;
    }

    /**
     * An ERC-721 contract answering by selector, with calldata read as words written out by hand. Its tokens, owners
     * and URIs are plain maps; a token's default URI is bare JSON naming it.
     */
    private static final class FakeCollection implements Function<String, Erc721Test.Reply> {
        final String name;
        final String symbol;
        boolean enumerable;
        boolean soulbound;
        BigInteger worlds;
        BigInteger balance = BigInteger.ZERO;
        final List<BigInteger> byIndex = new ArrayList<>();
        final Map<BigInteger, EvmAddress> owners = new HashMap<>();
        final Map<BigInteger, Erc721Test.Reply> uris = new HashMap<>();
        final Map<String, Erc721Test.Reply> overrides = new HashMap<>();

        FakeCollection(String name, String symbol) {
            this.name = name;
            this.symbol = symbol;
        }

        @Override
        public Erc721Test.Reply apply(String data) {
            if(overrides.containsKey(data)) {
                return overrides.get(data);
            }
            return switch(data.substring(0, 8)) {
                case "01ffc9a7" -> Erc721Test.Reply.result(word(switch(data.substring(8, 16)) {
                    case "01ffc9a7", "80ac58cd", "5b5e139f" -> 1;
                    case "780e9d63" -> enumerable ? 1 : 0;
                    case "b45a3c0e" -> soulbound ? 1 : 0;
                    default -> 0;
                }));
                case "06fdde03" -> Erc721Test.Reply.result(string(name));
                case "95d89b41" -> Erc721Test.Reply.result(string(symbol));
                case "23c4f6e3" -> worlds == null ? Erc721Test.Reply.revert() : Erc721Test.Reply.result(word(worlds));
                case "70a08231" -> data.equals("70a08231" + "000000000000000000000000" + hex(HOLDER)) ? Erc721Test.Reply.result(word(balance))
                        : Erc721Test.Reply.result(word(0));
                case "2f745c59" -> {
                    BigInteger index = argument(data, 1);
                    boolean holder = data.substring(8, 72).equals("000000000000000000000000" + hex(HOLDER));
                    yield holder && index.compareTo(BigInteger.valueOf(byIndex.size())) < 0 ? Erc721Test.Reply.result(word(byIndex.get(index.intValue())))
                            : Erc721Test.Reply.revert();
                }
                case "6352211e" -> {
                    EvmAddress owner = owners.get(argument(data, 0));
                    yield owner == null ? Erc721Test.Reply.revert() : Erc721Test.Reply.result("000000000000000000000000" + hex(owner));
                }
                case "c87b56dd" -> {
                    BigInteger id = argument(data, 0);
                    if(uris.containsKey(id)) {
                        yield uris.get(id);
                    }
                    yield owners.containsKey(id) ? Erc721Test.Reply.result(string("{\"name\":\"Token " + id + "\"}")) : Erc721Test.Reply.revert();
                }
                default -> Erc721Test.Reply.revert();
            };
        }

        private static BigInteger argument(String data, int index) {
            int start = 8 + 64 * index;
            return new BigInteger(data.substring(start, start + 64), 16);
        }
    }
}
