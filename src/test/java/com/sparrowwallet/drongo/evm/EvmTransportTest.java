package com.sparrowwallet.drongo.evm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The EVM clients have no way of their own to a server: every request of every one of them goes through the transport
 * it was made with, and none can be made without one.
 */
public class EvmTransportTest {
    private static final EvmAddress CONTRACT = EvmAddress.fromString("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3");

    /** Records every request, and answers each with the same body. */
    private static final class Recording implements EvmTransport {
        private final List<String> requests = new ArrayList<>();
        private final String answer;

        private Recording(String answer) {
            this.answer = answer;
        }

        @Override
        public Response send(URI uri, Map<String, String> headers, byte[] body, Duration timeout, int maxBytes) {
            requests.add((body == null ? "GET " : "POST ") + uri + " " + timeout.toSeconds() + "s");
            return new Response(200, answer);
        }
    }

    @Test
    public void noClientCanBeMadeWithoutATransport() {
        for(Class<?> client : List.of(LitVmRpcClient.class, BlockscoutClient.class, SourcifyClient.class)) {
            Constructor<?>[] constructors = Arrays.stream(client.getDeclaredConstructors()).filter(c -> !Modifier.isPrivate(c.getModifiers())).toArray(Constructor<?>[]::new);
            Assertions.assertTrue(constructors.length > 0, client.getSimpleName());
            for(Constructor<?> constructor : constructors) {
                Assertions.assertTrue(Arrays.asList(constructor.getParameterTypes()).contains(EvmTransport.class), constructor + " takes no transport");
            }
            //And none keeps a JDK client of its own
            for(Field field : client.getDeclaredFields()) {
                Assertions.assertFalse(field.getType().getName().startsWith("java.net.http."), client.getSimpleName() + "." + field.getName());
            }
        }
        Assertions.assertThrows(NullPointerException.class, () -> new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET, null));
        Assertions.assertThrows(NullPointerException.class, () -> new BlockscoutClient(LitVmNetwork.LITEFORGE_TESTNET, null));
        Assertions.assertThrows(NullPointerException.class, () -> new SourcifyClient(LitVmNetwork.SEPOLIA, null));
    }

    @Test
    public void everyRpcRequestGoesByTheTransport() throws IOException {
        Recording transport = new Recording("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x1159\"}");
        LitVmRpcClient client = new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET, "https://rpc.example.test/path", transport);
        Assertions.assertEquals(4441, client.chainId());
        client.verifyChain();
        client.getBalance(CONTRACT);
        //A client with a deadline sends through the same transport, with what is left of its time
        client.withDeadline(Duration.ofSeconds(5)).getBalance(CONTRACT);
        Assertions.assertEquals(4, transport.requests.size());
        for(String request : transport.requests) {
            Assertions.assertTrue(request.startsWith("POST https://rpc.example.test/path "), request);
        }
        Assertions.assertTrue(transport.requests.get(0).endsWith(" 30s"), transport.requests.get(0));
        Assertions.assertTrue(transport.requests.get(3).endsWith(" 4s") || transport.requests.get(3).endsWith(" 5s"), transport.requests.get(3));
        //The network's own endpoint when none is given
        Recording other = new Recording("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x1159\"}");
        new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET, other).chainId();
        Assertions.assertEquals(List.of("POST " + LitVmNetwork.LITEFORGE_TESTNET.getRpcUrl() + " 30s"), other.requests);
        //What the transport throws is what the caller gets
        EvmTransport down = (uri, headers, body, timeout, maxBytes) -> {
            throw new java.net.ConnectException("down");
        };
        Assertions.assertThrows(java.net.ConnectException.class, () -> new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET, down).chainId());
    }

    @Test
    public void everyExplorerRequestGoesByTheTransport() throws IOException {
        Recording transport = new Recording("{\"is_verified\":true,\"items\":[]}");
        BlockscoutClient client = new BlockscoutClient(LitVmNetwork.LITEFORGE_TESTNET, transport);
        Assertions.assertTrue(client.isVerified(CONTRACT));
        client.history(CONTRACT);
        client.verifyStandardInput(CONTRACT, "v0.8.30", "Token.sol:Token", "{}", "mit");
        String explorer = LitVmNetwork.LITEFORGE_TESTNET.getExplorerUrl();
        Assertions.assertTrue(transport.requests.size() >= 4, transport.requests.toString());
        for(String request : transport.requests) {
            Assertions.assertTrue(request.startsWith("GET " + explorer + "/api/v2/") || request.startsWith("POST " + explorer + "/api/v2/"), request);
        }
        Assertions.assertEquals("GET " + explorer + "/api/v2/smart-contracts/" + CONTRACT + " 30s", transport.requests.getFirst());
        Assertions.assertEquals("POST " + explorer + "/api/v2/smart-contracts/" + CONTRACT + "/verification/via/standard-input 60s", transport.requests.getLast());
        EvmTransport down = (uri, headers, body, timeout, maxBytes) -> {
            throw new java.net.ConnectException("down");
        };
        Assertions.assertThrows(java.net.ConnectException.class, () -> new BlockscoutClient(LitVmNetwork.LITEFORGE_TESTNET, down).history(CONTRACT));
    }

    @Test
    public void everySourcifyRequestGoesByTheTransport() throws IOException {
        Recording transport = new Recording("{\"match\":\"exact_match\",\"verificationId\":\"0123456789abcdef\",\"isJobCompleted\":false}");
        SourcifyClient client = new SourcifyClient(LitVmNetwork.SEPOLIA, transport);
        Assertions.assertTrue(client.isVerified(CONTRACT));
        Assertions.assertEquals("0123456789abcdef", client.submit(CONTRACT, "{}", "v0.8.30", "Token.sol:Token"));
        Assertions.assertEquals(SourcifyClient.Status.PENDING, client.job("0123456789abcdef"));
        long chain = LitVmNetwork.SEPOLIA.getChainId();
        Assertions.assertEquals(List.of("GET https://sourcify.dev/server/v2/contract/" + chain + "/" + CONTRACT + " 30s", "POST https://sourcify.dev/server/v2/verify/" + chain + "/" + CONTRACT + " 60s",
                "GET https://sourcify.dev/server/v2/verify/0123456789abcdef 30s"), transport.requests);
        //Naming the repository page asks nothing
        client.repositoryUrl(CONTRACT);
        Assertions.assertEquals(3, transport.requests.size());
    }

    @Test
    public void whatCertainlyDidNotLeaveIsSaidByType() throws IOException {
        //Directly, to a port nothing listens on: the connection was never made
        int closedPort;
        try(java.net.ServerSocket closed = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = closed.getLocalPort();
        }
        IOException refused = Assertions.assertThrows(IOException.class,
                () -> EvmTransport.direct().send(URI.create("http://127.0.0.1:" + closedPort + "/"), Map.of(), null, Duration.ofSeconds(5), 100));
        Assertions.assertInstanceOf(EvmTransport.NotSentException.class, refused);
        Assertions.assertInstanceOf(java.net.ConnectException.class, refused.getCause());

        Eip1559Transaction tx = new Eip1559Transaction(LitVmNetwork.LITEFORGE_TESTNET.getChainId(), java.math.BigInteger.ZERO, java.math.BigInteger.ONE, java.math.BigInteger.TEN,
                java.math.BigInteger.valueOf(21000), CONTRACT, java.math.BigInteger.ONE, null);
        Eip1559Transaction.Signed signed = EvmAccount.fromPrivateKey(new com.sparrowwallet.drongo.crypto.ECKey()).sign(tx);

        //A broadcast whose chain check fails first: the transaction was handed to no one
        Recording otherChain = new Recording("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x1\"}");
        IOException unverified = Assertions.assertThrows(IOException.class, () -> new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET, otherChain).sendRawTransaction(signed));
        Assertions.assertInstanceOf(EvmTransport.NotSentException.class, unverified);
        Assertions.assertEquals(1, otherChain.requests.size());

        //A broadcast whose time is spent before the request: none is made
        Recording node = new Recording("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x1159\"}");
        LitVmRpcClient client = new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET, node);
        client.verifyChain();
        IOException late = Assertions.assertThrows(IOException.class, () -> client.withDeadline(Duration.ZERO).sendRawTransaction(signed));
        Assertions.assertInstanceOf(EvmTransport.NotSentException.class, late);
        Assertions.assertInstanceOf(LitVmRpcClient.DeadlineException.class, late.getCause());
        Assertions.assertEquals(1, node.requests.size());

        //Once the request is made, whatever comes back is not "not sent": here an answer that is no hash
        IOException answered = Assertions.assertThrows(IOException.class, () -> client.sendRawTransaction(signed));
        Assertions.assertFalse(answered instanceof EvmTransport.NotSentException);
        Assertions.assertEquals(2, node.requests.size());
        //Nor is a transport's own failure after it took the request
        EvmTransport lost = (uri, headers, body, timeout, maxBytes) -> {
            if(new String(body, java.nio.charset.StandardCharsets.UTF_8).contains("eth_sendRawTransaction")) {
                throw new java.net.ConnectException("lost");
            }
            return new EvmTransport.Response(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x1159\"}");
        };
        IOException maybe = Assertions.assertThrows(IOException.class, () -> new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET, lost).sendRawTransaction(signed));
        Assertions.assertFalse(maybe instanceof EvmTransport.NotSentException);
    }

    @Test
    public void theTransportsCapIsNeverBelowAClientsOwn() throws IOException {
        //A body over the transport's cap is refused there; one within it reaches the client's own check
        Assertions.assertEquals("abc", EvmTransport.read(new java.io.ByteArrayInputStream("abc".getBytes()), 3));
        Assertions.assertThrows(IOException.class, () -> EvmTransport.read(new java.io.ByteArrayInputStream("abcd".getBytes()), 3));
        int[] caps = new int[1];
        EvmTransport big = (uri, headers, body, timeout, maxBytes) -> {
            caps[0] = maxBytes;
            return new EvmTransport.Response(200, "x".repeat(1_000_001));
        };
        Assertions.assertThrows(IOException.class, () -> new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET, big).chainId());
        Assertions.assertEquals(4_000_000, caps[0]);
    }
}
