package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.Utils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Reading ERC-721 contracts against a fake JSON-RPC node: a local HTTP server that LitVmRpcClient reaches through its
 * ordinary (network, rpcUrl) constructor, so the real request, response and error paths are exercised.
 */
public class Erc721Test {
    private static final EvmAddress BADGE = EvmAddress.fromString("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3");
    private static final EvmAddress HOLDER = EvmAddress.fromString("0x840e41D4484041A43f9307a90389edeB3d111fb3");
    private static final EvmAddress OTHER = EvmAddress.fromString("0x000000000000000000000000000000000000dEaD");
    //tokenIdOf(HOLDER, 1) = (uint160(HOLDER) << 8) | 1
    private static final BigInteger WORLD_1 = new BigInteger("840e41d4484041a43f9307a90389edeb3d111fb301", 16);

    //Calldata written out by hand, so an encoding mistake in Abi cannot agree with itself
    private static final String PAD = "00000000000000000000000000000000000000000000000000000000";
    private static final String IF_ERC165 = "01ffc9a7" + "01ffc9a7" + PAD;
    private static final String IF_INVALID = "01ffc9a7" + "ffffffff" + PAD;
    private static final String IF_ERC721 = "01ffc9a7" + "80ac58cd" + PAD;
    private static final String IF_METADATA = "01ffc9a7" + "5b5e139f" + PAD;
    private static final String IF_ENUMERABLE = "01ffc9a7" + "780e9d63" + PAD;
    private static final String IF_EIP5192 = "01ffc9a7" + "b45a3c0e" + PAD;
    private static final String NAME = "06fdde03";
    private static final String SYMBOL = "95d89b41";
    private static final String OWNER_OF_WORLD_1 = "6352211e" + "0000000000000000000000840e41d4484041a43f9307a90389edeb3d111fb301";
    private static final String TOKEN_URI_WORLD_1 = "c87b56dd" + "0000000000000000000000840e41d4484041a43f9307a90389edeb3d111fb301";
    private static final String BALANCE_OF_HOLDER = "70a08231" + "000000000000000000000000840e41d4484041a43f9307a90389edeb3d111fb3";

    private FakeNode node;

    @BeforeEach
    public void startNode() throws IOException {
        node = new FakeNode();
    }

    @AfterEach
    public void stopNode() {
        node.close();
    }

    @Test
    public void readsAVaultBadgeLikeContract() throws IOException {
        node.contract(BADGE, vaultBadge());
        LitVmRpcClient client = node.client();

        Erc721Collection collection = Erc721.read(client, BADGE).orElseThrow();
        Assertions.assertEquals(BADGE, collection.contract());
        Assertions.assertEquals("Super Litecoin Vault Badge", collection.name());
        Assertions.assertEquals("SLVB", collection.symbol());
        Assertions.assertFalse(collection.enumerable());
        Assertions.assertTrue(collection.soulbound());

        Assertions.assertEquals(Optional.of(HOLDER), Erc721.ownerOf(client, BADGE, WORLD_1));
        Assertions.assertEquals(BigInteger.ONE, Erc721.balanceOf(client, BADGE, HOLDER));
        //The tokenURI answer is the one LiteForge gave for World 1, read once and recorded
        Assertions.assertEquals(Optional.of(resource("vault-badge-world-1-token-uri.txt")), Erc721.tokenUri(client, BADGE, WORLD_1));
    }

    @Test
    public void anErc20IsNotACollection() throws IOException {
        //An ERC-20 has no supportsInterface: the call falls through and reverts
        node.contract(OTHER, data -> switch(data) {
            case NAME -> Reply.result(string("Test Token"));
            case SYMBOL -> Reply.result(string("TT"));
            case "313ce567" -> Reply.result(word(18));
            default -> Reply.revert();
        });
        Assertions.assertTrue(Erc721.read(node.client(), OTHER).isEmpty());

        //One that does answer ERC-165, but not for ERC-721
        node.contract(OTHER, data -> switch(data) {
            case IF_ERC165 -> Reply.result(word(1));
            case NAME -> Reply.result(string("Test Token"));
            default -> data.startsWith("01ffc9a7") ? Reply.result(word(0)) : Reply.revert();
        });
        Assertions.assertTrue(Erc721.read(node.client(), OTHER).isEmpty());
    }

    @Test
    public void aContractThatAnswersTrueToEverythingIsRefused() throws IOException {
        node.contract(OTHER, data -> Reply.result(word(1)));
        Assertions.assertTrue(Erc721.read(node.client(), OTHER).isEmpty());

        //ERC-165 says a failed call on 0xffffffff also means the contract does not implement it
        node.contract(OTHER, data -> data.equals(IF_INVALID) ? Reply.revert() : Reply.result(word(1)));
        Assertions.assertTrue(Erc721.read(node.client(), OTHER).isEmpty());

        //A word that is not a bool is a malformed answer, not a yes
        node.contract(OTHER, withAnswer(vaultBadge(), IF_ERC721, Reply.result(word(2))));
        Assertions.assertTrue(Erc721.read(node.client(), OTHER).isEmpty());
        node.contract(OTHER, withAnswer(vaultBadge(), IF_ERC721, Reply.result("")));
        Assertions.assertTrue(Erc721.read(node.client(), OTHER).isEmpty());

        //ERC-165 itself must be claimed
        node.contract(OTHER, withAnswer(vaultBadge(), IF_ERC165, Reply.result(word(0))));
        Assertions.assertTrue(Erc721.read(node.client(), OTHER).isEmpty());
    }

    @Test
    public void aRevertOnSupportsInterfaceIsNotACollection() throws IOException {
        for(String query : List.of(IF_ERC165, IF_ERC721)) {
            node.contract(OTHER, withAnswer(vaultBadge(), query, Reply.revert()));
            Assertions.assertTrue(Erc721.read(node.client(), OTHER).isEmpty());
            node.contract(OTHER, withAnswer(vaultBadge(), query, Reply.nitroRevert()));
            Assertions.assertTrue(Erc721.read(node.client(), OTHER).isEmpty());
        }
        //A revert on an optional interface only means the contract lacks it
        node.contract(OTHER, withAnswer(withAnswer(vaultBadge(), IF_EIP5192, Reply.revert()), IF_ENUMERABLE, Reply.revert()));
        Erc721Collection collection = Erc721.read(node.client(), OTHER).orElseThrow();
        Assertions.assertFalse(collection.enumerable());
        Assertions.assertFalse(collection.soulbound());
    }

    @Test
    public void anAddressWithoutCodeIsNotACollection() throws IOException {
        Assertions.assertTrue(Erc721.read(node.client(), HOLDER).isEmpty());
        Assertions.assertTrue(node.calls("eth_call").isEmpty(), "an account without code is not called");
    }

    @Test
    public void tokenUriOverTheCapIsEmpty() throws IOException {
        LitVmRpcClient client = node.client();
        //One byte more than fits: an ABI string of 99,937 bytes needs a 100,064-byte result
        node.contract(BADGE, withAnswer(vaultBadge(), TOKEN_URI_WORLD_1, Reply.result(string("a".repeat(99_937)))));
        Assertions.assertTrue(Erc721.tokenUri(client, BADGE, WORLD_1).isEmpty());

        //Exactly the cap: a 99,936-byte string is a 100,000-byte result
        node.contract(BADGE, withAnswer(vaultBadge(), TOKEN_URI_WORLD_1, Reply.result(string("a".repeat(99_936)))));
        Assertions.assertEquals(99_936, Erc721.tokenUri(client, BADGE, WORLD_1).orElseThrow().length());

        //A whole response over the client's cap is the transport's failure, not the contract's answer: it throws
        node.contract(BADGE, withAnswer(vaultBadge(), TOKEN_URI_WORLD_1, Reply.result(string("a".repeat(600_000)))));
        Assertions.assertThrows(IOException.class, () -> Erc721.tokenUri(client, BADGE, WORLD_1));

        //A length word that claims more than the data holds is refused before anything is allocated for it
        node.contract(BADGE, withAnswer(vaultBadge(), TOKEN_URI_WORLD_1, Reply.result(word(32) + "000000000000000000000000000000000000000000000000000000007fffffff" + "61".repeat(32))));
        Assertions.assertTrue(Erc721.tokenUri(client, BADGE, WORLD_1).isEmpty());
        node.contract(BADGE, withAnswer(vaultBadge(), TOKEN_URI_WORLD_1, Reply.result("ff".repeat(32) + word(4) + "61".repeat(32))));
        Assertions.assertTrue(Erc721.tokenUri(client, BADGE, WORLD_1).isEmpty());
    }

    @Test
    public void tokenUriRevertIsEmpty() throws IOException {
        node.contract(BADGE, vaultBadge());
        //The badge reverts NoSuchToken() for a token nobody holds
        Assertions.assertTrue(Erc721.tokenUri(node.client(), BADGE, WORLD_1.add(BigInteger.ONE)).isEmpty());
        node.contract(BADGE, withAnswer(vaultBadge(), TOKEN_URI_WORLD_1, Reply.nitroRevert()));
        Assertions.assertTrue(Erc721.tokenUri(node.client(), BADGE, WORLD_1).isEmpty());
    }

    @Test
    public void ownerOfRevertIsEmpty() throws IOException {
        node.contract(BADGE, vaultBadge());
        Assertions.assertTrue(Erc721.ownerOf(node.client(), BADGE, WORLD_1.add(BigInteger.ONE)).isEmpty());
        node.contract(BADGE, withAnswer(vaultBadge(), OWNER_OF_WORLD_1, Reply.nitroRevert()));
        Assertions.assertTrue(Erc721.ownerOf(node.client(), BADGE, WORLD_1).isEmpty());
        //The zero address owns nothing: a well-formed answer, read as no owner
        node.contract(BADGE, withAnswer(vaultBadge(), OWNER_OF_WORLD_1, Reply.result(word(0))));
        Assertions.assertTrue(Erc721.ownerOf(node.client(), BADGE, WORLD_1).isEmpty());
    }

    @Test
    public void anOwnerOfAnswerThatCannotBeReadIsAFailure() throws IOException {
        //A word with bits above the address, an empty answer, a short one and one over the call's own cap say nothing about
        //the owner: they are failures to read, never "not held"
        for(String answer : List.of("01" + "00".repeat(11) + hex(HOLDER), "", "00".repeat(31), "ab".repeat(8_193))) {
            node.contract(BADGE, withAnswer(vaultBadge(), OWNER_OF_WORLD_1, Reply.result(answer)));
            assertThrowsIo(() -> Erc721.ownerOf(node.client(), BADGE, WORLD_1));
        }
    }

    @Test
    public void aRateLimitIsToldApart() throws IOException {
        node.contract(BADGE, vaultBadge());
        node.reply(Reply.http(429));
        IOException limited = Assertions.assertThrows(LitVmRpcClient.RateLimitedException.class, () -> Erc721.ownerOf(node.client(), BADGE, WORLD_1));
        //The message is the one every other HTTP failure gives
        Assertions.assertEquals("eth_call failed with HTTP 429", limited.getMessage());
        Assertions.assertThrows(LitVmRpcClient.RateLimitedException.class, () -> Erc721.read(node.client(), BADGE));
        //Any other HTTP failure stays a plain IOException
        node.reply(Reply.http(503));
        IOException other = Assertions.assertThrows(IOException.class, () -> Erc721.ownerOf(node.client(), BADGE, WORLD_1));
        Assertions.assertFalse(other instanceof LitVmRpcClient.RateLimitedException);
        Assertions.assertFalse(other instanceof LitVmRpcClient.LitVmRpcException);
    }

    @Test
    public void namesAreSanitised() throws IOException {
        node.contract(BADGE, withAnswer(withAnswer(vaultBadge(), NAME, Reply.result(string("\u0007Vault\u202e\u200b Badge\n" + "x".repeat(500)))),
                SYMBOL, Reply.result(string("SL\u2066VB" + "y".repeat(500)))));
        Erc721Collection collection = Erc721.read(node.client(), BADGE).orElseThrow();
        Assertions.assertEquals(64, collection.name().length());
        //The line feed separates words, as in every single-line field
        Assertions.assertTrue(collection.name().startsWith("Vault Badge xxx"), collection.name());
        Assertions.assertEquals(16, collection.symbol().length());
        Assertions.assertTrue(collection.symbol().startsWith("SLVByyy"), collection.symbol());

        //Name and symbol are optional in ERC-721: a revert or nonsense leaves them blank
        node.contract(BADGE, withAnswer(withAnswer(vaultBadge(), NAME, Reply.revert()), SYMBOL, Reply.result(word(32) + word(1_000_000))));
        Erc721Collection unnamed = Erc721.read(node.client(), BADGE).orElseThrow();
        Assertions.assertEquals("", unnamed.name());
        Assertions.assertEquals("", unnamed.symbol());

        //Line and paragraph separators go too, and the cap counts code points and never leaves half of a surrogate pair
        String emoji = new String(Character.toChars(0x1F600));
        node.contract(BADGE, withAnswer(withAnswer(vaultBadge(), NAME, Reply.result(string("x".repeat(64) + emoji))),
                SYMBOL, Reply.result(string("SL" + (char)0x2028 + "V" + (char)0x2029 + "B" + "\u2065\ufe0f"))));
        Erc721Collection capped = Erc721.read(node.client(), BADGE).orElseThrow();
        Assertions.assertEquals("x".repeat(64), capped.name());
        Assertions.assertEquals("SLVB", capped.symbol());
        Assertions.assertEquals("x".repeat(63) + emoji, new Erc721Collection(BADGE, "x".repeat(63) + emoji, "", false, false).name());
    }

    @Test
    public void aMalformedResultIsAnIoFailure() throws IOException {
        LitVmRpcClient client = node.client();
        //Not hex, an odd number of digits, no 0x, not a string
        for(String result : List.of("\"0xzz\"", "\"0x123\"", "\"1234\"", "12", "null")) {
            Reply malformed = new Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}");
            node.contract(BADGE, withAnswer(withAnswer(withAnswer(withAnswer(vaultBadge(), IF_ERC721, malformed), OWNER_OF_WORLD_1, malformed),
                    TOKEN_URI_WORLD_1, malformed), BALANCE_OF_HOLDER, malformed));
            assertThrowsIo(() -> Erc721.read(client, BADGE));
            assertThrowsIo(() -> Erc721.ownerOf(client, BADGE, WORLD_1));
            assertThrowsIo(() -> Erc721.tokenUri(client, BADGE, WORLD_1));
            assertThrowsIo(() -> Erc721.balanceOf(client, BADGE, HOLDER));
        }
    }

    @Test
    public void tokenOfOwnerByIndexSendsTheOwnerAndTheIndex() throws IOException {
        //tokenOfOwnerByIndex(address,uint256): selector, then the owner and the index, each a full word
        String exact = "2f745c59" + "000000000000000000000000840e41d4484041a43f9307a90389edeb3d111fb3"
                + "0000000000000000000000000000000000000000000000000000000000000007";
        node.contract(BADGE, data -> data.equals(exact) ? Reply.result(word(42)) : Reply.revert());
        Assertions.assertEquals(BigInteger.valueOf(42), Erc721.tokenOfOwnerByIndex(node.client(), BADGE, HOLDER, BigInteger.valueOf(7)));
        LitVmRpcClient.LitVmRpcException revert = Assertions.assertThrows(LitVmRpcClient.LitVmRpcException.class,
                () -> Erc721.tokenOfOwnerByIndex(node.client(), BADGE, HOLDER, BigInteger.valueOf(8)));
        Assertions.assertTrue(revert.isRevert());
    }

    @Test
    public void balanceOfThrowsOnARevert() throws IOException {
        node.contract(BADGE, withAnswer(vaultBadge(), BALANCE_OF_HOLDER, Reply.revert()));
        LitVmRpcClient.LitVmRpcException revert = Assertions.assertThrows(LitVmRpcClient.LitVmRpcException.class,
                () -> Erc721.balanceOf(node.client(), BADGE, HOLDER));
        Assertions.assertTrue(revert.isRevert());
        node.contract(BADGE, withAnswer(vaultBadge(), BALANCE_OF_HOLDER, Reply.result("")));
        assertThrowsIo(() -> Erc721.balanceOf(node.client(), BADGE, HOLDER));
    }

    @Test
    public void aRevertWithAReasonStringIsARevert() throws IOException {
        //Error(string) "No such token", as require(..., "No such token") reverts
        String reason = "08c379a0" + string("No such token");
        Reply withReason = new Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":3,\"message\":\"execution reverted: No such token\",\"data\":\"0x"
                + reason + "\"}}");
        node.contract(BADGE, withAnswer(withAnswer(vaultBadge(), OWNER_OF_WORLD_1, withReason), TOKEN_URI_WORLD_1, withReason));
        Assertions.assertTrue(Erc721.ownerOf(node.client(), BADGE, WORLD_1).isEmpty());
        Assertions.assertTrue(Erc721.tokenUri(node.client(), BADGE, WORLD_1).isEmpty());
        LitVmRpcClient.LitVmRpcException revert = Assertions.assertThrows(LitVmRpcClient.LitVmRpcException.class,
                () -> node.client().ethCall(BADGE, Utils.hexToBytes(OWNER_OF_WORLD_1)));
        Assertions.assertTrue(revert.isRevert());
        Assertions.assertEquals("execution reverted: No such token", revert.getMessage());
    }

    @Test
    public void aTransportFailureThrows() throws IOException {
        node.contract(BADGE, vaultBadge());
        LitVmRpcClient client = node.client();

        node.reply(Reply.http(502));
        assertThrowsIo(() -> Erc721.read(client, BADGE));
        assertThrowsIo(() -> Erc721.ownerOf(client, BADGE, WORLD_1));
        assertThrowsIo(() -> Erc721.tokenUri(client, BADGE, WORLD_1));

        //A body that is not JSON-RPC at all is the endpoint's fault, not the contract's
        node.reply(new Reply(200, "<html>bad gateway</html>"));
        assertThrowsIo(() -> Erc721.read(client, BADGE));
        assertThrowsIo(() -> Erc721.tokenUri(client, BADGE, WORLD_1));

        //A node error that is not a revert (a rate limit) must not read as "not a collection"
        node.reply(null);
        node.contract(BADGE, withAnswer(vaultBadge(), IF_ERC721, Reply.nodeError()));
        assertThrowsIo(() -> Erc721.read(client, BADGE));
        node.contract(BADGE, withAnswer(vaultBadge(), OWNER_OF_WORLD_1, Reply.nodeError()));
        assertThrowsIo(() -> Erc721.ownerOf(client, BADGE, WORLD_1));
        node.contract(BADGE, withAnswer(vaultBadge(), TOKEN_URI_WORLD_1, Reply.nodeError()));
        assertThrowsIo(() -> Erc721.tokenUri(client, BADGE, WORLD_1));

        //Nobody listening
        int closedPort;
        try(ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        LitVmRpcClient unreachable = new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET, "http://127.0.0.1:" + closedPort + "/", EvmTransport.direct());
        assertThrowsIo(() -> Erc721.read(unreachable, BADGE));
        assertThrowsIo(() -> Erc721.tokenUri(unreachable, BADGE, WORLD_1));
    }

    @Test
    public void aRevertIsToldFromANodeErrorWithoutChangingTheMessage() throws IOException {
        LitVmRpcClient client = node.client();
        node.contract(BADGE, withAnswer(vaultBadge(), NAME, Reply.revert()));
        LitVmRpcClient.LitVmRpcException revert = Assertions.assertThrows(LitVmRpcClient.LitVmRpcException.class,
                () -> client.ethCall(BADGE, Utils.hexToBytes(NAME)));
        Assertions.assertTrue(revert.isRevert());
        Assertions.assertEquals("execution reverted", revert.getMessage());

        node.contract(BADGE, withAnswer(vaultBadge(), NAME, Reply.nitroRevert()));
        Assertions.assertTrue(Assertions.assertThrows(LitVmRpcClient.LitVmRpcException.class, () -> client.ethCall(BADGE, Utils.hexToBytes(NAME))).isRevert());

        node.contract(BADGE, withAnswer(vaultBadge(), NAME, Reply.nodeError()));
        LitVmRpcClient.LitVmRpcException limited = Assertions.assertThrows(LitVmRpcClient.LitVmRpcException.class,
                () -> client.ethCall(BADGE, Utils.hexToBytes(NAME)));
        Assertions.assertFalse(limited.isRevert());
        Assertions.assertEquals("request limit exceeded", limited.getMessage());
        Assertions.assertFalse(new LitVmRpcClient.LitVmRpcException("x").isRevert());
    }

    @Test
    public void ethCallKeepsItsCapAndTheBoundedCallNeverExceedsOneHundredThousand() throws IOException {
        LitVmRpcClient client = node.client();
        Assertions.assertThrows(IllegalArgumentException.class, () -> client.ethCall(BADGE, Utils.hexToBytes(NAME), 100_001));
        Assertions.assertThrows(IllegalArgumentException.class, () -> client.ethCall(BADGE, Utils.hexToBytes(NAME), -1));
        Assertions.assertTrue(node.calls("eth_call").isEmpty(), "a cap above the limit is refused before any request");

        node.contract(BADGE, data -> Reply.result("ab".repeat(8_193)));
        IOException oversized = Assertions.assertThrows(IOException.class, () -> client.ethCall(BADGE, Utils.hexToBytes(NAME)));
        Assertions.assertEquals("eth_call returned an invalid result", oversized.getMessage());
        node.contract(BADGE, data -> Reply.result("ab".repeat(8_192)));
        Assertions.assertEquals(8_192, client.ethCall(BADGE, Utils.hexToBytes(NAME)).length);

        node.contract(BADGE, data -> Reply.result("ab".repeat(100_000)));
        Assertions.assertEquals(100_000, client.ethCall(BADGE, Utils.hexToBytes(NAME), 100_000).length);
        Assertions.assertThrows(LitVmRpcClient.OversizedResultException.class, () -> client.ethCall(BADGE, Utils.hexToBytes(NAME), 99_999));
        node.contract(BADGE, data -> Reply.result("ab".repeat(100_001)));
        Assertions.assertThrows(LitVmRpcClient.OversizedResultException.class, () -> client.ethCall(BADGE, Utils.hexToBytes(NAME), 100_000));
    }

    @Test
    public void decodeStringHonoursTheCallersLimit() {
        byte[] eleven = Utils.hexToBytes(string("hello world"));
        Assertions.assertEquals("hello world", Abi.decodeString(eleven, 11));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Abi.decodeString(eleven, 10));
        //The default limit for existing callers is unchanged
        byte[] long4097 = Utils.hexToBytes(string("z".repeat(4_097)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Abi.decodeString(long4097));
        Assertions.assertEquals(4_097, Abi.decodeString(long4097, Erc721.MAX_TOKEN_URI_BYTES).length());
        //Offsets and lengths are checked against the data actually present
        Assertions.assertThrows(IllegalArgumentException.class, () -> Abi.decodeString(Utils.hexToBytes(word(32) + word(33) + "61".repeat(32)), 100_000));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Abi.decodeString(Utils.hexToBytes(word(64) + word(0)), 100_000));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Abi.decodeString(Utils.hexToBytes(word(32) + "ff".repeat(32)), 100_000));
    }

    //A contract that answers like the deployed VaultBadge, for World 1 held by HOLDER
    private static Function<String, Reply> vaultBadge() {
        return data -> switch(data) {
            case IF_ERC165, IF_ERC721, IF_METADATA, IF_EIP5192 -> Reply.result(word(1));
            case NAME -> Reply.result(string("Super Litecoin Vault Badge"));
            case SYMBOL -> Reply.result(string("SLVB"));
            case OWNER_OF_WORLD_1 -> Reply.result("00".repeat(12) + hex(HOLDER));
            case BALANCE_OF_HOLDER -> Reply.result(word(1));
            case TOKEN_URI_WORLD_1 -> Reply.result(resource("vault-badge-world-1-eth-call-result.txt").substring(2));
            default -> data.startsWith("01ffc9a7") ? Reply.result(word(0)) : Reply.revert();
        };
    }

    private static Function<String, Reply> withAnswer(Function<String, Reply> contract, String data, Reply reply) {
        return call -> call.equals(data) ? reply : contract.apply(call);
    }

    private interface IoCall {
        Object run() throws IOException;
    }

    private static void assertThrowsIo(IoCall call) {
        IOException e = Assertions.assertThrows(IOException.class, call::run);
        Assertions.assertFalse(e instanceof LitVmRpcClient.LitVmRpcException rpc && rpc.isRevert(), "a failure is not a revert");
    }

    private static String word(long value) {
        return String.format(Locale.ROOT, "%064x", value);
    }

    private static String string(String text) {
        return Utils.bytesToHex(Abi.encodeArguments(text));
    }

    private static String hex(EvmAddress address) {
        return Utils.bytesToHex(address.getBytes()).toLowerCase(Locale.ROOT);
    }

    static String resource(String name) {
        try(InputStream in = Erc721Test.class.getResourceAsStream("/com/sparrowwallet/drongo/evm/nft/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch(IOException e) {
            throw new IllegalStateException(e);
        }
    }

    record Reply(int status, String body) {
        static Reply result(String hexWithout0x) {
            return new Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" + hexWithout0x + "\"}");
        }

        //geth and Nitro report a revert with data as code 3
        static Reply revert() {
            return new Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":3,\"message\":\"execution reverted\",\"data\":\"0x2a81c9a6\"}}");
        }

        //A revert without data comes back as a generic server error with the same message
        static Reply nitroRevert() {
            return new Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"execution reverted\"}}");
        }

        static Reply nodeError() {
            return new Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32005,\"message\":\"request limit exceeded\"}}");
        }

        static Reply http(int status) {
            return new Reply(status, "busy");
        }
    }

    /** A minimal JSON-RPC node: eth_getCode and eth_call against fake contracts, each a function of its calldata hex. */
    static final class FakeNode implements AutoCloseable {
        private final HttpServer server;
        private final Map<EvmAddress, Function<String, Reply>> contracts = new ConcurrentHashMap<>();
        private final List<String> methods = Collections.synchronizedList(new ArrayList<>());
        private volatile Reply override;

        FakeNode() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        LitVmRpcClient client() {
            return new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET, "http://127.0.0.1:" + server.getAddress().getPort() + "/", EvmTransport.direct());
        }

        void contract(EvmAddress address, Function<String, Reply> answers) {
            contracts.put(address, answers);
        }

        /** Every request gets this reply instead, until reset with null. */
        void reply(Reply reply) {
            override = reply;
        }

        List<String> calls(String method) {
            synchronized(methods) {
                return methods.stream().filter(method::equals).toList();
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            Reply reply;
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                reply = override != null ? override : answer(body);
            } catch(RuntimeException e) {
                reply = new Reply(500, e.toString());
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try(OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        private Reply answer(String body) {
            Map<?, ?> request = (Map<?, ?>)MiniJson.parse(body);
            String method = (String)request.get("method");
            methods.add(method);
            List<?> params = (List<?>)request.get("params");
            return switch(method) {
                case "eth_getCode" -> contracts.containsKey(EvmAddress.fromString((String)params.get(0))) ? Reply.result("6080604052") : Reply.result("");
                case "eth_call" -> {
                    Map<?, ?> call = (Map<?, ?>)params.get(0);
                    Function<String, Reply> contract = contracts.get(EvmAddress.fromString((String)call.get("to")));
                    String data = ((String)call.get("data")).substring(2).toLowerCase(Locale.ROOT);
                    yield contract == null ? Reply.result("") : contract.apply(data);
                }
                default -> new Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"method not found\"}}");
            };
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
