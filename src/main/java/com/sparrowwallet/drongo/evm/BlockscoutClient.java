package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Address history from a network's Blockscout explorer (v2 API). An EVM node cannot list an address's past
 * transactions, so the LitVM tab shows history from the explorer, labelled as such: it is display data only and
 * never feeds balances, signing or fees, which come from the RPC node.
 */
public final class BlockscoutClient {
    private static final int MAX_RESPONSE_CHARS = 4_000_000;
    //Blockscout lists some native-coin movements (faucet payouts, OP Stack deposits) a second time as transfers of this
    //metadata-less address; the native value is already on the transaction, so these are skipped
    private static final EvmAddress NATIVE_PLACEHOLDER = EvmAddress.fromString("0xfffffffffffffffffffffffffffffffffffffffe");

    private final LitVmNetwork network;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public BlockscoutClient(LitVmNetwork network) {
        this.network = network;
    }

    public LitVmNetwork getNetwork() {
        return network;
    }

    public record TokenTransfer(EvmAddress token, String symbol, int decimals, EvmAddress from, EvmAddress to, BigInteger amount) {
        public String format() {
            return new Erc20Token(token, symbol, symbol, decimals).format(amount);
        }
    }

    /** One transaction touching the address; block is null while pending, to is null for a contract creation. */
    public record Entry(String hash, Instant time, Long block, EvmAddress from, EvmAddress to, BigInteger value, BigInteger fee,
                        boolean failed, String method, List<TokenTransfer> tokenTransfers) {
        /** The native-coin change for the address: received value, minus value and fee when it sent the transaction. */
        public BigInteger nativeChange(EvmAddress address) {
            BigInteger change = BigInteger.ZERO;
            if(address.equals(to) && !failed) {
                change = change.add(value);
            }
            if(address.equals(from)) {
                change = change.subtract(failed ? BigInteger.ZERO : value).subtract(fee);
            }
            return change;
        }
    }

    /** The latest transactions and token transfers for the address (first page of each), newest first. */
    public List<Entry> history(EvmAddress address) throws IOException {
        Map<String, Entry> byHash = new LinkedHashMap<>();
        Object txs = get("/api/v2/addresses/" + address + "/transactions");
        for(Object item : items(txs)) {
            Entry entry = parseTransaction(item);
            if(entry != null) {
                byHash.put(entry.hash(), entry);
            }
        }
        Object transfers = get("/api/v2/addresses/" + address + "/token-transfers");
        for(Object item : items(transfers)) {
            if(!(item instanceof Map<?, ?> map) || !(map.get("transaction_hash") instanceof String hash)) {
                continue;
            }
            TokenTransfer transfer = parseTransfer(map);
            if(transfer == null) {
                continue;
            }
            Entry existing = byHash.get(hash);
            if(existing == null) {
                //A token sent to the address by someone else's transaction
                existing = new Entry(hash, time(map.get("timestamp")), number(map.get("block_number")), transfer.from(), transfer.to(),
                        BigInteger.ZERO, BigInteger.ZERO, false, null, new ArrayList<>());
                byHash.put(hash, existing);
            }
            List<TokenTransfer> list = new ArrayList<>(existing.tokenTransfers());
            list.add(transfer);
            byHash.put(hash, new Entry(existing.hash(), existing.time(), existing.block(), existing.from(), existing.to(), existing.value(),
                    existing.fee(), existing.failed(), existing.method(), list));
        }
        List<Entry> entries = new ArrayList<>(byHash.values());
        entries.sort(Comparator.comparing((Entry e) -> e.time() == null ? Instant.MAX : e.time()).reversed());
        return entries;
    }

    private static List<?> items(Object response) throws IOException {
        if(!(response instanceof Map<?, ?> map) || !(map.get("items") instanceof List<?> list)) {
            throw new IOException("Unexpected explorer response");
        }
        return list;
    }

    private static Entry parseTransaction(Object item) {
        if(!(item instanceof Map<?, ?> map) || !(map.get("hash") instanceof String hash) || !hash.matches("0x[0-9a-fA-F]{64}")) {
            return null;
        }
        EvmAddress from = address(map.get("from"));
        if(from == null) {
            return null;
        }
        BigInteger fee = map.get("fee") instanceof Map<?, ?> feeMap ? amount(feeMap.get("value")) : BigInteger.ZERO;
        boolean failed = "error".equals(map.get("status")) || (map.get("result") instanceof String result && !result.equals("success") && !result.equals("pending"));
        String method = map.get("method") instanceof String m ? Erc20Token.sanitise(m, 40) : null;
        return new Entry(hash.toLowerCase(Locale.ROOT), time(map.get("timestamp")), number(map.get("block_number")), from, address(map.get("to")),
                amount(map.get("value")), fee, failed, method, List.of());
    }

    private static TokenTransfer parseTransfer(Map<?, ?> map) {
        if(!(map.get("token") instanceof Map<?, ?> token) || !(map.get("total") instanceof Map<?, ?> total)) {
            return null;
        }
        EvmAddress contract = address(token.get("address_hash") != null ? Map.of("hash", token.get("address_hash")) : Map.of("hash", String.valueOf(token.get("address"))));
        BigInteger decimals = amount(token.get("decimals"));
        if(contract == null || contract.equals(NATIVE_PLACEHOLDER) || decimals.bitLength() > 7 || decimals.intValue() > Erc20Token.MAX_DECIMALS) {
            return null;
        }
        String text = contract.toString();
        String symbol = token.get("symbol") instanceof String s && !s.isBlank() ? Erc20Token.sanitise(s, 16)
                : "units of " + text.substring(0, 6) + "..." + text.substring(text.length() - 4);
        return new TokenTransfer(contract, symbol, decimals.intValue(), address(map.get("from")), address(map.get("to")), amount(total.get("value")));
    }

    private static EvmAddress address(Object value) {
        if(value instanceof Map<?, ?> map && map.get("hash") instanceof String hash) {
            try {
                return EvmAddress.fromString(hash);
            } catch(IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static BigInteger amount(Object value) {
        if(value instanceof String text && text.matches("\\d{1,78}")) {
            return new BigInteger(text);
        }
        if(value instanceof Number number) {
            return BigInteger.valueOf(number.longValue());
        }
        return BigInteger.ZERO;
    }

    private static Long number(Object value) {
        if(value instanceof Number number) {
            return number.longValue();
        }
        return value instanceof String text && text.matches("\\d{1,18}") ? Long.parseLong(text) : null;
    }

    private static Instant time(Object value) {
        try {
            return value instanceof String text ? Instant.parse(text) : null;
        } catch(RuntimeException e) {
            return null;
        }
    }

    /**
     * Submits a contract's source for verification as solc standard JSON input. The explorer recompiles it and, when
     * the result matches the deployed code, shows the source as verified; that takes from seconds to minutes, so
     * follow with {@link #isVerified(EvmAddress)}. Constructor arguments are read from the creation transaction.
     */
    public void verifyStandardInput(EvmAddress contract, String compilerVersion, String contractName, String standardJsonInput, String license) throws IOException {
        String boundary = "sparrow" + Long.toHexString(new Random().nextLong());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("compiler_version", compilerVersion);
        fields.put("contract_name", contractName);
        fields.put("license_type", license);
        fields.put("autodetect_constructor_args", "true");
        byte[] body = multipart(boundary, fields, "files[0]", "input.json", standardJsonInput);
        HttpRequest request = HttpRequest.newBuilder(URI.create(network.getExplorerUrl() + "/api/v2/smart-contracts/" + contract + "/verification/via/standard-input"))
                .timeout(Duration.ofSeconds(60)).header("Accept", "application/json").header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<String> response = send(request);
        if(response.statusCode() / 100 != 2) {
            String text = response.body().length() > 300 ? response.body().substring(0, 300) : response.body();
            throw new IOException("Explorer refused verification (HTTP " + response.statusCode() + "): " + Erc20Token.sanitise(text, 300));
        }
    }

    /**
     * Whether the explorer shows source that exactly matches the contract. A partial match does not count: the
     * explorer also attaches one on its own, from someone else's source with the same code, which is not ours.
     */
    public boolean isVerified(EvmAddress contract) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(network.getExplorerUrl() + "/api/v2/smart-contracts/" + contract)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> response = send(request);
        if(response.statusCode() == 404) {
            return false;
        }
        if(response.statusCode() / 100 != 2 || response.body().length() > MAX_RESPONSE_CHARS) {
            throw new IOException("Explorer returned HTTP " + response.statusCode());
        }
        return verifiedExactly(MiniJson.parse(response.body()));
    }

    static boolean verifiedExactly(Object response) {
        return response instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("is_verified")) && !Boolean.TRUE.equals(map.get("is_partially_verified"));
    }

    static byte[] multipart(String boundary, Map<String, String> fields, String fileField, String fileName, String fileContent) {
        StringBuilder sb = new StringBuilder();
        for(Map.Entry<String, String> field : fields.entrySet()) {
            sb.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(field.getKey()).append("\"\r\n\r\n")
                    .append(field.getValue()).append("\r\n");
        }
        sb.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(fileField).append("\"; filename=\"").append(fileName).append("\"\r\n")
                .append("Content-Type: application/json\r\n\r\n")
                .append(fileContent).append("\r\n")
                .append("--").append(boundary).append("--\r\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted talking to the explorer", e);
        }
    }

    private Object get(String path) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(network.getExplorerUrl() + path)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted reading history", e);
        }
        if(response.statusCode() / 100 != 2) {
            throw new IOException("Explorer returned HTTP " + response.statusCode());
        }
        if(response.body().length() > MAX_RESPONSE_CHARS) {
            throw new IOException("Explorer response too large");
        }
        return MiniJson.parse(response.body());
    }
}
