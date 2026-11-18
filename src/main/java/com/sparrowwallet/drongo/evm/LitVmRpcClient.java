package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import com.sparrowwallet.drongo.Utils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * JSON-RPC client for a LitVM network, covering what a simple wallet needs: balance, nonce, fees, gas
 * estimation, broadcasting a signed transaction and reading its receipt.
 *
 * Fail-closed on the chain: {@link #verifyChain()} must pass before anything is broadcast, so a mistyped or
 * hijacked RPC URL serving another chain is refused rather than trusted.
 *
 * Every request goes through the {@link EvmTransport} the client is made with: it has no way of its own to a server.
 */
public final class LitVmRpcClient {
    private final LitVmNetwork network;
    private final URI endpoint;
    private final EvmTransport transport;
    private final AtomicLong ids = new AtomicLong();
    private volatile boolean chainVerified;
    //A client with a deadline sends through its parent; the plain client has neither
    private final LitVmRpcClient parent;
    private final long deadline;
    private final LongSupplier clock;

    /** The longest one call waits for its response, after the connection (at most {@link #CONNECT_TIMEOUT}) is made. */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration CONNECT_TIMEOUT = EvmTransport.CONNECT_TIMEOUT;

    public LitVmRpcClient(LitVmNetwork network, EvmTransport transport) {
        this(network, network.getRpcUrl(), transport);
    }

    public LitVmRpcClient(LitVmNetwork network, String rpcUrl, EvmTransport transport) {
        this.network = network;
        this.endpoint = URI.create(rpcUrl);
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.parent = null;
        this.deadline = 0;
        this.clock = null;
    }

    private LitVmRpcClient(LitVmRpcClient parent, long deadline, LongSupplier clock) {
        this.network = parent.network;
        this.endpoint = parent.endpoint;
        this.transport = parent.transport;
        this.chainVerified = parent.chainVerified;
        this.parent = parent;
        this.deadline = deadline;
        this.clock = clock;
    }

    /**
     * This client with a time budget: no call starts once the budget is spent (it throws {@link DeadlineException}),
     * and a call's timeout is cut to the time left. The JDK client applies a request's timeout to connecting as well
     * (a call with nanoseconds left fails to connect), so a call ends by the deadline; at worst, on a client that did
     * not, it would overrun by one {@link #CONNECT_TIMEOUT}. A budget inside another keeps the earlier deadline.
     */
    public LitVmRpcClient withDeadline(Duration budget) {
        return until(System.nanoTime() + budget.toNanos(), System::nanoTime);
    }

    /** As {@link #withDeadline}, against the given clock: no call starts once clock reads deadline or later. */
    LitVmRpcClient until(long deadline, LongSupplier clock) {
        return new LitVmRpcClient(this, deadline, clock);
    }

    /** The response timeout for a call with this many nanoseconds left. */
    static Duration timeoutWithin(long nanosLeft) {
        return nanosLeft >= REQUEST_TIMEOUT.toNanos() ? REQUEST_TIMEOUT : Duration.ofNanos(nanosLeft);
    }

    public LitVmNetwork getNetwork() {
        return network;
    }

    public long chainId() throws IOException {
        try {
            return answer("eth_chainId", call("eth_chainId", "[]")).longValueExact();
        } catch(ArithmeticException e) {
            throw new MalformedAnswerException("eth_chainId returned an invalid answer", e);
        }
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
        return answer("eth_getBalance", call("eth_getBalance", "[\"" + address + "\",\"latest\"]"));
    }

    /** The next nonce, counting transactions still in the mempool. */
    public BigInteger getPendingNonce(EvmAddress address) throws IOException {
        return answer("eth_getTransactionCount", call("eth_getTransactionCount", "[\"" + address + "\",\"pending\"]"));
    }

    /** The latest block's base fee, or the legacy gas price on a node that does not report one. */
    @SuppressWarnings("unchecked")
    public BigInteger getBaseFee() throws IOException {
        Object block = call("eth_getBlockByNumber", "[\"latest\",false]");
        if(block instanceof Map<?, ?> map && map.get("baseFeePerGas") != null) {
            return answer("eth_getBlockByNumber", map.get("baseFeePerGas"));
        }
        return answer("eth_gasPrice", call("eth_gasPrice", "[]"));
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
        StringBuilder tx = new StringBuilder("{\"from\":\"").append(from).append("\"");
        if(to != null) {
            tx.append(",\"to\":\"").append(to).append("\"");
        }
        tx.append(",\"value\":\"").append(toQuantity(value)).append("\"");
        if(data != null && data.length > 0) {
            tx.append(",\"data\":\"0x").append(com.sparrowwallet.drongo.Utils.bytesToHex(data)).append("\"");
        }
        tx.append("}");
        return answer("eth_estimateGas", call("eth_estimateGas", "[" + tx + "]"));
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
        return ethCallHex(to, data, MAX_CALL_RESULT_HEX);
    }

    /**
     * As {@link #ethCall(EvmAddress, byte[])}, with a caller-chosen cap on the result of at most
     * {@link #MAX_BOUNDED_CALL_RESULT_BYTES}, for a call whose answer can be larger than a few words (an NFT's
     * tokenURI). The contract chooses its answer, so the outcomes are kept apart: a revert is a
     * {@link LitVmRpcException} whose {@link LitVmRpcException#isRevert()} is true, an answer over the cap is an
     * {@link OversizedResultException}, and anything else (the network, HTTP, a malformed response, another node
     * error) is a plain IOException or a LitVmRpcException that is not a revert.
     */
    public byte[] ethCall(EvmAddress to, byte[] data, int maxResultBytes) throws IOException {
        if(maxResultBytes < 0 || maxResultBytes > MAX_BOUNDED_CALL_RESULT_BYTES) {
            throw new IllegalArgumentException("eth_call result cap out of range: " + maxResultBytes);
        }
        try {
            return ethCallHex(to, data, 2 + 2 * maxResultBytes);
        } catch(IllegalArgumentException | com.sparrowwallet.drongo.protocol.ProtocolException e) {
            //A response that is not JSON-RPC (MiniJson), or a result that is not hex (Utils.hexToBytes), is the endpoint's fault
            throw new IOException("eth_call returned an invalid response", e);
        }
    }

    private byte[] ethCallHex(EvmAddress to, byte[] data, int maxHex) throws IOException {
        Object result = call("eth_call", "[{\"to\":\"" + to + "\",\"data\":\"0x" + com.sparrowwallet.drongo.Utils.bytesToHex(data) + "\"},\"latest\"]");
        if(!(result instanceof String hex) || !hex.startsWith("0x") || hex.length() % 2 != 0) {
            throw new IOException("eth_call returned an invalid result");
        }
        if(hex.length() > maxHex) {
            throw new OversizedResultException("eth_call returned an invalid result");
        }
        return hexAnswer("eth_call", hex);
    }

    /** Whether the address holds contract code at the latest block. */
    public boolean hasCode(EvmAddress address) throws IOException {
        Object code = call("eth_getCode", "[\"" + address + "\",\"latest\"]");
        return code instanceof String hex && hex.startsWith("0x") && hex.length() > 2;
    }

    /** Reads an ERC-20 contract's metadata; fails if the address holds no contract or does not answer decimals(). */
    public Erc20Token readToken(EvmAddress contract) throws IOException {
        if(!hasCode(contract)) {
            throw new LitVmCheckException(LitVmCheckException.Reason.NO_CONTRACT, "No contract at " + contract);
        }
        try {
            BigInteger decimals = Abi.decodeUint(ethCall(contract, Abi.selector("decimals()")), 0);
            if(decimals.compareTo(BigInteger.valueOf(Erc20Token.MAX_DECIMALS)) > 0) {
                throw new LitVmCheckException(LitVmCheckException.Reason.TOKEN_DECIMALS, "Token decimals out of range: " + decimals);
            }
            String symbol = optionalString(contract, "symbol()");
            String name = optionalString(contract, "name()");
            return new Erc20Token(contract, symbol, name, decimals.intValue());
        } catch(IllegalArgumentException e) {
            throw new LitVmCheckException(LitVmCheckException.Reason.NOT_A_TOKEN, contract + " is not an ERC-20 token: " + e.getMessage());
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
        BigInteger maxGas = to == null ? MAX_CREATION_GAS : MAX_TRANSFER_GAS;
        if(to == null && fixedGasLimit == null && gas.compareTo(maxGas) <= 0) {
            //A creation close to the cap keeps what margin is left below it
            gasLimit = gasLimit.min(maxGas);
        }
        if(gasLimit.compareTo(maxGas) > 0) {
            if(to == null) {
                throw new LitVmCheckException(LitVmCheckException.Reason.CREATION_OVER_GAS_CAP, "Deploying this needs " + gas + " gas on " + network.getDisplayName()
                        + ", above the " + maxGas + " a transaction can use. Choose fewer features, or another network.");
            }
            throw new IOException("Node estimated " + gas + " gas, above the " + maxGas + " limit");
        }
        if(maxFee.compareTo(MAX_FEE_PER_GAS) > 0 || priority.compareTo(maxFee) > 0) {
            throw new IOException("Node reported an implausible fee (max " + maxFee + " wei/gas, priority " + priority + ")");
        }
        return new Eip1559Transaction(network.getChainId(), nonce, priority, maxFee, gasLimit, to, value, data);
    }

    /** Broadcasts a signed transaction and returns its hash. Requires a prior successful {@link #verifyChain()}. */
    public String sendRawTransaction(Eip1559Transaction.Signed signed) throws IOException {
        if(!chainVerified) {
            try {
                verifyChain();
            } catch(IOException e) {
                //The chain check comes first, and it failed: the transaction was not handed to anyone
                throw new EvmTransport.NotSentException("The chain could not be verified, so the transaction was not sent", e);
            }
        }
        Object result;
        try {
            result = call("eth_sendRawTransaction", "[\"" + signed.rawHex() + "\"]");
        } catch(DeadlineException e) {
            //Thrown only before a request is made: the time was spent, so none was
            throw new EvmTransport.NotSentException("Out of time before the transaction was sent", e);
        }
        //The hash is ours to compute: a node reporting a different one is wrong or lying, and its value would then be
        //what the user tracks. Return the local hash, and refuse a mismatch rather than trusting either silently.
        String local = signed.hashHex();
        if(!(result instanceof String reported) || !reported.equalsIgnoreCase(local)) {
            throw new IOException("Node returned transaction hash " + result + " but the transaction hashes to " + local);
        }
        return local;
    }

    public record Receipt(boolean success, BigInteger blockNumber, BigInteger gasUsed) {}

    /**
     * The receipt once the transaction is mined; empty while it is pending (the node answers null). A receipt is taken
     * only when it is for the transaction asked about (its transactionHash), says exactly 0x1 (succeeded) or 0x0
     * (failed) and names its block; anything else is a {@link MalformedAnswerException}, never a failed transaction.
     * A node can still lie in a well-formed receipt: what a caller does on one is its own to weigh.
     */
    public Optional<Receipt> getReceipt(String txHash) throws IOException {
        Object result = call("eth_getTransactionReceipt", "[\"" + txHash + "\"]");
        if(result == null) {
            return Optional.empty();
        }
        if(!(result instanceof Map<?, ?> map)) {
            throw new MalformedAnswerException("eth_getTransactionReceipt returned an invalid answer", null);
        }
        if(!(map.get("transactionHash") instanceof String reported) || !reported.equalsIgnoreCase(txHash)) {
            throw new MalformedAnswerException("eth_getTransactionReceipt returned a receipt for another transaction", null);
        }
        boolean success;
        if("0x1".equals(map.get("status"))) {
            success = true;
        } else if("0x0".equals(map.get("status"))) {
            success = false;
        } else {
            throw new MalformedAnswerException("eth_getTransactionReceipt returned a receipt with no valid status", null);
        }
        return Optional.of(new Receipt(success, answer("eth_getTransactionReceipt", map.get("blockNumber")), answer("eth_getTransactionReceipt", map.get("gasUsed"))));
    }

    /** eth_call from a given sender carrying a value: simulates a transaction without sending it. */
    public byte[] simulate(EvmAddress from, EvmAddress to, BigInteger value, byte[] data) throws IOException {
        //With no recipient this runs creation code, returning the code the contract would get
        Object result = call("eth_call", "[{\"from\":\"" + from + "\"" + (to == null ? "" : ",\"to\":\"" + to + "\"") + ",\"value\":\"" + toQuantity(value)
                + "\",\"data\":\"0x" + Utils.bytesToHex(data) + "\"},\"latest\"]");
        int max = to == null ? MAX_CREATED_CODE_HEX : MAX_CALL_RESULT_HEX;
        if(!(result instanceof String hex) || !hex.startsWith("0x") || hex.length() % 2 != 0 || hex.length() > max) {
            throw new IOException("eth_call returned an invalid result");
        }
        return hexAnswer("eth_call", hex);
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
        try {
            return parseLogsUnchecked(logs);
        } catch(IllegalArgumentException | com.sparrowwallet.drongo.protocol.ProtocolException e) {
            throw new MalformedAnswerException("Invalid log in response", e);
        }
    }

    private static List<Log> parseLogsUnchecked(Object logs) throws IOException {
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
        return new ArbBlock(answer("eth_getBlockByHash", map.get("number")), answer("eth_getBlockByHash", map.get("sendCount")), Utils.hexToBytes(root.substring(2)));
    }

    private Object call(String method, String paramsJson) throws IOException {
        return call(method, paramsJson, REQUEST_TIMEOUT);
    }

    private Object call(String method, String paramsJson, Duration timeout) throws IOException {
        if(parent != null) {
            long left = deadline - clock.getAsLong();
            if(left <= 0) {
                throw new DeadlineException(method + " not sent: out of time");
            }
            Duration within = timeoutWithin(left);
            return parent.call(method, paramsJson, within.compareTo(timeout) < 0 ? within : timeout);
        }
        long id = ids.incrementAndGet();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
        //The transport's own cap is in bytes: at most four to a character, so it never cuts below the cap that follows
        EvmTransport.Response response = transport.send(endpoint, Map.of("Content-Type", "application/json"), body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                timeout, 4 * MAX_RESPONSE_CHARS);
        if(response.status() == 429) {
            throw new RateLimitedException(method + " failed with HTTP " + response.status());
        }
        if(response.status() / 100 != 2) {
            throw new IOException(method + " failed with HTTP " + response.status());
        }
        //None of the calls made here return more than a receipt or a 100,000-byte eth_call result (200,002 hex
        //characters); a whole response far larger is a failure of the transport, for every method. Only a result over
        //the caller's cap, inside a response of a sane size, is the contract's answer (OversizedResultException)
        if(response.body().length() > MAX_RESPONSE_CHARS) {
            throw new IOException(method + " returned an oversized response");
        }
        Object parsed;
        try {
            parsed = MiniJson.parse(response.body());
        } catch(RuntimeException e) {
            //An answer that is not JSON is the endpoint's fault, never the caller's
            throw new MalformedAnswerException(method + " returned a response that is not JSON", e);
        }
        if(!(parsed instanceof Map<?, ?> map)) {
            throw new IOException(method + " returned a non-object response");
        }
        if(map.get("error") instanceof Map<?, ?> error) {
            String message = String.valueOf(error.get("message"));
            throw new LitVmRpcException(message, isRevert(error.get("code"), message));
        }
        return map.get("result");
    }

    /**
     * Whether a node error is the called contract reverting: geth and Nitro report a revert with data as code 3, and
     * one without data as -32000 "execution reverted". Kept narrow on purpose: any other error (a rate limit, a node
     * fault) must not read as the contract's answer.
     */
    private static boolean isRevert(Object code, String message) {
        return (code instanceof BigDecimal number && number.compareTo(BigDecimal.valueOf(3)) == 0) || message.startsWith("execution reverted");
    }

    private static final int MAX_RESPONSE_CHARS = 1_000_000;
    /** The largest result {@link #ethCall(EvmAddress, byte[], int)} accepts. */
    public static final int MAX_BOUNDED_CALL_RESULT_BYTES = 100_000;
    //Upper bounds for a plain value transfer; LiteForge measured ~1.1 gwei base fee and well under 1M gas
    private static final BigInteger MAX_TRANSFER_GAS = BigInteger.valueOf(2_000_000);
    //Ethereum's per-transaction gas cap (EIP-7825). The largest token template needs about 2.7M on LiteForge and Base
    //Sepolia, but around 18M on Sepolia, whose repriced code deposit charges about 1,500 gas a byte
    private static final BigInteger MAX_CREATION_GAS = BigInteger.valueOf(16_777_216);
    //Simulated creation returns the new contract's code, at most the EIP-170 size
    private static final int MAX_CREATED_CODE_HEX = 2 + 2 * 24576;
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

    /** A quantity in a node's answer: one that is not a quantity is the node's fault, so an IOException. */
    private static BigInteger answer(String method, Object value) throws IOException {
        try {
            return quantity(value);
        } catch(IllegalArgumentException e) {
            throw new MalformedAnswerException(method + " returned an invalid answer", e);
        }
    }

    /** Hex data in a node's answer, after its 0x: characters that are not hex are the node's fault, so an IOException. */
    private static byte[] hexAnswer(String method, String hex) throws IOException {
        try {
            return Utils.hexToBytes(hex.substring(2));
        } catch(IllegalArgumentException | com.sparrowwallet.drongo.protocol.ProtocolException e) {
            throw new MalformedAnswerException(method + " returned an invalid result", e);
        }
    }

    static String toQuantity(BigInteger value) {
        return "0x" + value.toString(16);
    }

    /** An error returned by the node, such as "insufficient funds" or "nonce too low". */
    public static final class LitVmRpcException extends IOException {
        private final boolean revert;

        public LitVmRpcException(String message) {
            this(message, false);
        }

        LitVmRpcException(String message, boolean revert) {
            super(message);
            this.revert = revert;
        }

        /** Whether the node reported that the called contract reverted, rather than failing itself. */
        public boolean isRevert() {
            return revert;
        }
    }

    /**
     * The endpoint answered, but not with what the method returns: text that is not JSON, a number that is not a
     * quantity, data that is not hex. The endpoint's fault, so an IOException like any other failure to get an answer.
     */
    public static final class MalformedAnswerException extends IOException {
        public MalformedAnswerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A call not sent because the client's time budget is spent ({@link #withDeadline}). */
    public static final class DeadlineException extends IOException {
        public DeadlineException(String message) {
            super(message);
        }
    }

    /**
     * The endpoint answered HTTP 429: it is limiting requests. The message is the one any other HTTP failure gives, and
     * a caller that catches IOException sees no difference; one that must stop asking can tell it apart.
     */
    public static final class RateLimitedException extends IOException {
        public RateLimitedException(String message) {
            super(message);
        }
    }

    /** An eth_call result larger than the caller allows: the contract's answer, not a failure of the network or node. */
    public static final class OversizedResultException extends IOException {
        public OversizedResultException(String message) {
            super(message);
        }
    }
}
