package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.sparrowwallet.drongo.Utils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
        return prepareCall(from, to, value, null);
    }

    /** A ready-to-sign ERC-20 transfer: a call to the token contract, carrying no native value. */
    public Eip1559Transaction prepareTokenTransfer(EvmAddress from, Erc20Token token, EvmAddress to, BigInteger amount) throws IOException {
        return prepareCall(from, token.contract(), BigInteger.ZERO, Abi.encodeCall("transfer(address,uint256)", to, amount));
    }

    /** eth_call against the latest block: the raw return data of a read-only contract call. */
    public byte[] ethCall(EvmAddress to, byte[] data) throws IOException {
        Object result = call("eth_call", "[{\"to\":\"" + to + "\",\"data\":\"0x" + com.sparrowwallet.drongo.Utils.bytesToHex(data) + "\"},\"latest\"]");
        if(!(result instanceof String hex) || !hex.startsWith("0x") || hex.length() % 2 != 0 || hex.length() > MAX_CALL_RESULT_HEX) {
            throw new IOException("eth_call returned an invalid result");
        }
        return com.sparrowwallet.drongo.Utils.hexToBytes(hex.substring(2));
    }

    /** Reads an ERC-20 contract's metadata; fails if the address holds no contract or does not answer decimals(). */
    public Erc20Token readToken(EvmAddress contract) throws IOException {
        Object code = call("eth_getCode", "[\"" + contract + "\",\"latest\"]");
        if(!(code instanceof String hex) || hex.length() <= 2) {
            throw new IOException("No contract at " + contract);
        }
        try {
            BigInteger decimals = Abi.decodeUint(ethCall(contract, Abi.selector("decimals()")), 0);
            if(decimals.compareTo(BigInteger.valueOf(Erc20Token.MAX_DECIMALS)) > 0) {
                throw new IOException("Token decimals out of range: " + decimals);
            }
            String symbol = optionalString(contract, "symbol()");
            String name = optionalString(contract, "name()");
            return new Erc20Token(contract, symbol, name, decimals.intValue());
        } catch(IllegalArgumentException e) {
            throw new IOException(contract + " is not an ERC-20 token: " + e.getMessage());
        }
    }

    private String optionalString(EvmAddress contract, String signature) {
        try {
            return Abi.decodeString(ethCall(contract, Abi.selector(signature)));
        } catch(IOException | IllegalArgumentException e) {
            return "";
        }
    }

    public BigInteger getTokenBalance(Erc20Token token, EvmAddress owner) throws IOException {
        try {
            return Abi.decodeUint(ethCall(token.contract(), Abi.encodeCall("balanceOf(address)", owner)), 0);
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid balanceOf result from " + token.contract());
        }
    }

    /** A ready-to-sign call (plain transfer when data is null) with fees taken from the chain and bounded. */
    public Eip1559Transaction prepareCall(EvmAddress from, EvmAddress to, BigInteger value, byte[] data) throws IOException {
        return prepareCall(from, to, value, data, null, null);
    }

    /**
     * As {@link #prepareCall(EvmAddress, EvmAddress, BigInteger, byte[])}, but with an explicit nonce and gas limit
     * when not null. That lets a call that depends on an earlier one (a bridge deposit after its token approval) be
     * signed up front, when its gas cannot be estimated yet because the approval is not mined.
     */
    public Eip1559Transaction prepareCall(EvmAddress from, EvmAddress to, BigInteger value, byte[] data, BigInteger nonce, BigInteger fixedGasLimit) throws IOException {
        if(nonce == null) {
            nonce = getPendingNonce(from);
        }
        //A tip above a few gwei is never needed on these chains; clamp what the node suggests rather than overpay
        BigInteger priority = getMaxPriorityFee().min(MAX_PRIORITY_FEE);
        BigInteger maxFee = getBaseFee().multiply(BigInteger.TWO).add(priority);
        BigInteger gas = fixedGasLimit != null ? fixedGasLimit : estimateGas(from, to, value, data);
        //A margin on the estimate; unused gas is not charged.
        BigInteger gasLimit = fixedGasLimit != null ? fixedGasLimit : gas.multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
        //The fee inputs come from the node. Bound them so a faulty or hostile node cannot set an absurd fee for the
        //user to sign. Not 21000: on an Arbitrum Nitro chain like LitVM the estimate includes the L1 data cost.
        if(gasLimit.compareTo(MAX_TRANSFER_GAS) > 0) {
            throw new IOException("Node estimated " + gas + " gas, above the " + MAX_TRANSFER_GAS + " limit");
        }
        if(maxFee.compareTo(MAX_FEE_PER_GAS) > 0 || priority.compareTo(maxFee) > 0) {
            throw new IOException("Node reported an implausible fee (max " + maxFee + " wei/gas, priority " + priority + ")");
        }
        return new Eip1559Transaction(network.getChainId(), nonce, priority, maxFee, gasLimit, to, value, data);
    }

    /** Broadcasts a signed transaction and returns its hash. Requires a prior successful {@link #verifyChain()}. */
    public String sendRawTransaction(Eip1559Transaction.Signed signed) throws IOException {
        if(!chainVerified) {
            verifyChain();
        }
        Object result = call("eth_sendRawTransaction", "[\"" + signed.rawHex() + "\"]");
        //The hash is ours to compute: a node reporting a different one is wrong or lying, and its value would then be
        //what the user tracks. Return the local hash, and refuse a mismatch rather than trusting either silently.
        String local = signed.hashHex();
        if(!(result instanceof String reported) || !reported.equalsIgnoreCase(local)) {
            throw new IOException("Node returned transaction hash " + result + " but the transaction hashes to " + local);
        }
        return local;
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

    /** eth_call from a given sender carrying a value: simulates a transaction without sending it. */
    public byte[] simulate(EvmAddress from, EvmAddress to, BigInteger value, byte[] data) throws IOException {
        Object result = call("eth_call", "[{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"value\":\"" + toQuantity(value)
                + "\",\"data\":\"0x" + Utils.bytesToHex(data) + "\"},\"latest\"]");
        if(!(result instanceof String hex) || !hex.startsWith("0x") || hex.length() % 2 != 0 || hex.length() > MAX_CALL_RESULT_HEX) {
            throw new IOException("eth_call returned an invalid result");
        }
        return Utils.hexToBytes(hex.substring(2));
    }

    private static final EvmAddress GAS_PRICE_ORACLE = EvmAddress.fromString("0x420000000000000000000000000000000000000F");
    //An L1 data fee is a small fraction of a cent on these testnets; a reading far above this is not a real answer
    private static final BigInteger MAX_L1_DATA_FEE = BigInteger.TEN.pow(16);

    /**
     * The L1 data fee an OP Stack chain adds on top of gas, as an upper bound from its GasPriceOracle (Fjord and
     * later); zero on other chains.
     */
    public BigInteger l1DataFee(Eip1559Transaction tx) throws IOException {
        if(!network.isOpStack()) {
            return BigInteger.ZERO;
        }
        try {
            BigInteger fee = Abi.decodeUint(ethCall(GAS_PRICE_ORACLE, Abi.encodeCall("getL1FeeUpperBound(uint256)", BigInteger.valueOf(tx.unsignedSize()))), 0);
            if(fee.compareTo(MAX_L1_DATA_FEE) > 0) {
                throw new IOException("Implausible L1 data fee " + fee);
            }
            return fee;
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid L1 data fee response");
        }
    }

    public record Log(EvmAddress address, List<byte[]> topics, byte[] data) {}

    /** The event logs of a mined transaction; empty while it is pending. */
    public List<Log> getTransactionLogs(String txHash) throws IOException {
        Object result = call("eth_getTransactionReceipt", "[\"" + txHash + "\"]");
        if(!(result instanceof Map<?, ?> map)) {
            return List.of();
        }
        return parseLogs(map.get("logs"));
    }

    /** Logs one contract emitted in one block, filtered on topic0 and optionally topic1. */
    public List<Log> getLogs(EvmAddress address, BigInteger block, byte[] topic0, byte[] topic1) throws IOException {
        String blockHex = toQuantity(block);
        String topics = "[\"0x" + Utils.bytesToHex(topic0) + "\"" + (topic1 == null ? "" : ",\"0x" + Utils.bytesToHex(topic1) + "\"") + "]";
        return parseLogs(call("eth_getLogs", "[{\"address\":\"" + address + "\",\"fromBlock\":\"" + blockHex + "\",\"toBlock\":\"" + blockHex + "\",\"topics\":" + topics + "}]"));
    }

    private static List<Log> parseLogs(Object logs) throws IOException {
        if(!(logs instanceof List<?> list)) {
            throw new IOException("Invalid logs in response");
        }
        List<Log> parsed = new ArrayList<>();
        for(Object item : list) {
            if(!(item instanceof Map<?, ?> log) || !(log.get("address") instanceof String address) || !(log.get("topics") instanceof List<?> topics)
                    || !(log.get("data") instanceof String data) || !data.startsWith("0x") || data.length() % 2 != 0) {
                throw new IOException("Invalid log in response");
            }
            List<byte[]> topicBytes = new ArrayList<>();
            for(Object topic : topics) {
                if(!(topic instanceof String hex) || !hex.matches("0x[0-9a-fA-F]{64}")) {
                    throw new IOException("Invalid log topic in response");
                }
                topicBytes.add(Utils.hexToBytes(hex.substring(2)));
            }
            parsed.add(new Log(EvmAddress.fromString(address), topicBytes, Utils.hexToBytes(data.substring(2))));
        }
        return parsed;
    }

    public record ArbBlock(BigInteger number, BigInteger sendCount, byte[] sendRoot) {}

    /** An Arbitrum Nitro block's outbox state: how many L2-to-L1 messages exist as of it, and their Merkle root. */
    public ArbBlock getArbBlock(byte[] blockHash) throws IOException {
        Object result = call("eth_getBlockByHash", "[\"0x" + Utils.bytesToHex(blockHash) + "\",false]");
        if(!(result instanceof Map<?, ?> map) || !(map.get("sendRoot") instanceof String root) || !root.matches("0x[0-9a-fA-F]{64}")) {
            throw new IOException("Block " + Utils.bytesToHex(blockHash) + " not found or has no outbox state");
        }
        return new ArbBlock(quantity(map.get("number")), quantity(map.get("sendCount")), Utils.hexToBytes(root.substring(2)));
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
        //None of the calls made here return more than a receipt; anything far larger is not a real answer
        if(response.body().length() > MAX_RESPONSE_CHARS) {
            throw new IOException(method + " returned an oversized response");
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

    private static final int MAX_RESPONSE_CHARS = 1_000_000;
    //Upper bounds for a plain value transfer; LiteForge measured ~1.1 gwei base fee and well under 1M gas
    private static final BigInteger MAX_TRANSFER_GAS = BigInteger.valueOf(2_000_000);
    //A token metadata or balance result is a few words; anything far larger is not a real answer
    private static final int MAX_CALL_RESULT_HEX = 2 + 2 * 8192;
    private static final BigInteger MAX_PRIORITY_FEE = BigInteger.valueOf(3).multiply(BigInteger.TEN.pow(9)); //3 gwei
    private static final BigInteger MAX_FEE_PER_GAS =BigInteger.valueOf(1_000L).multiply(BigInteger.TEN.pow(9)); //1000 gwei
    //A 256-bit quantity is at most 64 hex digits
    private static final int MAX_QUANTITY_HEX_DIGITS = 64;

    /** A JSON-RPC quantity: 0x-prefixed hex, as the spec requires, of at most 256 bits. */
    static BigInteger quantity(Object value) {
        if(value instanceof String hex && hex.startsWith("0x") && hex.length() - 2 <= MAX_QUANTITY_HEX_DIGITS) {
            return hex.length() == 2 ? BigInteger.ZERO : new BigInteger(hex.substring(2), 16);
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
