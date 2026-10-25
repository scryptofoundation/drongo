package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JSON-RPC client for a LitVM network, covering what a simple wallet needs: balance, nonce, fees, gas
 * estimation, broadcasting a signed transaction and reading its receipt.
 *
 * Fail-closed on the chain: {@link #verifyChain()} must pass before anything is broadcast, so a mistyped or
 * hijacked RPC URL serving another chain is refused rather than trusted.
 */
public final class LitVmRpcClient {
    private final LitVmNetwork network;
    private final URI endpoint;
    private final HttpClient http;
    private final AtomicLong ids = new AtomicLong();
    private volatile boolean chainVerified;

    public LitVmRpcClient(LitVmNetwork network) {
        this(network, network.getRpcUrl());
    }

    public LitVmRpcClient(LitVmNetwork network, String rpcUrl) {
        this.network = network;
        this.endpoint = URI.create(rpcUrl);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public LitVmNetwork getNetwork() {
        return network;
    }

    public long chainId() throws IOException {
        return quantity(call("eth_chainId", "[]")).longValueExact();
    }

    /** Throws unless the endpoint reports this network's chain id. */
    public void verifyChain() throws IOException {
        long reported = chainId();
        if(reported != network.getChainId()) {
            throw new LitVmRpcException("RPC endpoint reports chain " + reported + ", expected " + network.getChainId() + " (" + network.getDisplayName() + ")");
        }
        chainVerified = true;
    }

    public BigInteger getBalance(EvmAddress address) throws IOException {
        return quantity(call("eth_getBalance", "[\"" + address + "\",\"latest\"]"));
    }

    /** The next nonce, counting transactions still in the mempool. */
    public BigInteger getPendingNonce(EvmAddress address) throws IOException {
        return quantity(call("eth_getTransactionCount", "[\"" + address + "\",\"pending\"]"));
    }

    /** The latest block's base fee, or the legacy gas price on a node that does not report one. */
    @SuppressWarnings("unchecked")
    public BigInteger getBaseFee() throws IOException {
        Object block = call("eth_getBlockByNumber", "[\"latest\",false]");
        if(block instanceof Map<?, ?> map && map.get("baseFeePerGas") != null) {
            return quantity(map.get("baseFeePerGas"));
        }
        return quantity(call("eth_gasPrice", "[]"));
    }

    /** The suggested priority fee; LitVM, like other Nitro rollups, generally needs none, so errors mean zero. */
    public BigInteger getMaxPriorityFee() {
        try {
            return quantity(call("eth_maxPriorityFeePerGas", "[]"));
        } catch(IOException | RuntimeException e) {
            return BigInteger.ZERO;
        }
    }

    public BigInteger estimateGas(EvmAddress from, EvmAddress to, BigInteger value, byte[] data) throws IOException {
        StringBuilder tx = new StringBuilder("{\"from\":\"").append(from).append("\",\"to\":\"").append(to)
                .append("\",\"value\":\"").append(toQuantity(value)).append("\"");
        if(data != null && data.length > 0) {
            tx.append(",\"data\":\"0x").append(com.sparrowwallet.drongo.Utils.bytesToHex(data)).append("\"");
        }
        tx.append("}");
        return quantity(call("eth_estimateGas", "[" + tx + "]"));
    }

    /** A ready-to-sign plain transfer with fees taken from the chain: maxFee = 2 x baseFee + priority fee. */
    public Eip1559Transaction prepareTransfer(EvmAddress from, EvmAddress to, BigInteger value) throws IOException {
        BigInteger nonce = getPendingNonce(from);
        BigInteger priority = getMaxPriorityFee();
        BigInteger maxFee = getBaseFee().multiply(BigInteger.TWO).add(priority);
        BigInteger gas = estimateGas(from, to, value, null);
        //A margin on the estimate; unused gas is not charged.
        BigInteger gasLimit = gas.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
        return new Eip1559Transaction(network.getChainId(), nonce, priority, maxFee, gasLimit, to, value, null);
    }

    /** Broadcasts a signed transaction and returns its hash. Requires a prior successful {@link #verifyChain()}. */
    public String sendRawTransaction(Eip1559Transaction.Signed signed) throws IOException {
        if(!chainVerified) {
            verifyChain();
        }
        Object result = call("eth_sendRawTransaction", "[\"" + signed.rawHex() + "\"]");
        return String.valueOf(result);
    }

    public record Receipt(boolean success, BigInteger blockNumber, BigInteger gasUsed) {}

    /** The receipt once the transaction is mined; empty while it is pending. */
    public Optional<Receipt> getReceipt(String txHash) throws IOException {
        Object result = call("eth_getTransactionReceipt", "[\"" + txHash + "\"]");
        if(!(result instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        boolean success = "0x1".equals(map.get("status"));
        return Optional.of(new Receipt(success, quantity(map.get("blockNumber")), quantity(map.get("gasUsed"))));
    }

    private Object call(String method, String paramsJson) throws IOException {
        long id = ids.incrementAndGet();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted calling " + method, e);
        }
        if(response.statusCode() / 100 != 2) {
            throw new IOException(method + " failed with HTTP " + response.statusCode());
        }
        Object parsed = MiniJson.parse(response.body());
        if(!(parsed instanceof Map<?, ?> map)) {
            throw new IOException(method + " returned a non-object response");
        }
        if(map.get("error") instanceof Map<?, ?> error) {
            throw new LitVmRpcException(String.valueOf(error.get("message")));
        }
        return map.get("result");
    }

    static BigInteger quantity(Object value) {
        if(value instanceof String hex && hex.startsWith("0x")) {
            return hex.length() == 2 ? BigInteger.ZERO : new BigInteger(hex.substring(2), 16);
        }
        if(value instanceof BigDecimal number) {
            return number.toBigIntegerExact();
        }
        throw new IllegalArgumentException("Not a JSON-RPC quantity: " + value);
    }

    static String toQuantity(BigInteger value) {
        return "0x" + value.toString(16);
    }

    /** An error returned by the node, such as "insufficient funds" or "nonce too low". */
    public static final class LitVmRpcException extends IOException {
        public LitVmRpcException(String message) {
            super(message);
        }
    }
}
