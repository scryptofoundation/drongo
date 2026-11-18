package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Source verification on Sourcify, the open verification repository, for the networks it supports. Sourcify
 * recompiles the submitted solc input and reports an exact match only when both the creation and the deployed code,
 * metadata hash included, are what the input produces, so a match proves the published source is the deployed one.
 */
public final class SourcifyClient {
    private static final String SERVER = "https://sourcify.dev/server";
    private static final int MAX_RESPONSE_CHARS = 1_000_000;

    public enum Status { PENDING, EXACT, PARTIAL, FAILED }

    private final LitVmNetwork network;
    private final EvmTransport transport;

    /** Every request goes through this transport: the client has no way of its own to Sourcify. */
    public SourcifyClient(LitVmNetwork network, EvmTransport transport) {
        if(!supports(network)) {
            throw new IllegalArgumentException("Sourcify does not support " + network.getDisplayName());
        }
        this.network = network;
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
    }

    /** LiteForge is not one of Sourcify's chains. */
    public static boolean supports(LitVmNetwork network) {
        return network == LitVmNetwork.SEPOLIA || network == LitVmNetwork.BASE_SEPOLIA;
    }

    /** Submits the input and returns the job id to follow with {@link #job(String)}. */
    public String submit(EvmAddress contract, String standardJsonInput, String compilerVersion, String contractIdentifier) throws IOException {
        Object response = parse(send("/v2/verify/" + network.getChainId() + "/" + contract, Map.of("Content-Type", "application/json", "Accept", "application/json"),
                requestBody(standardJsonInput, compilerVersion, contractIdentifier).getBytes(java.nio.charset.StandardCharsets.UTF_8), Duration.ofSeconds(60)), "verification");
        if(response instanceof Map<?, ?> map && map.get("verificationId") instanceof String id && id.matches("[0-9a-fA-F-]{8,64}")) {
            return id;
        }
        throw new IOException("Sourcify did not start a verification" + message(response));
    }

    public Status job(String verificationId) throws IOException {
        return status(parse(send("/v2/verify/" + verificationId, Map.of("Accept", "application/json"), null, Duration.ofSeconds(30)), "job"));
    }

    /** Whether Sourcify holds source that exactly matches the contract. */
    public boolean isVerified(EvmAddress contract) throws IOException {
        EvmTransport.Response response = send("/v2/contract/" + network.getChainId() + "/" + contract, Map.of("Accept", "application/json"), null, Duration.ofSeconds(30));
        if(response.status() == 404) {
            return false;
        }
        Object parsed = parse(response, "lookup");
        return parsed instanceof Map<?, ?> map && "exact_match".equals(map.get("match"));
    }

    /** Where the verified source can be read. */
    public String repositoryUrl(EvmAddress contract) {
        return "https://repo.sourcify.dev/" + network.getChainId() + "/" + contract;
    }

    static String requestBody(String standardJsonInput, String compilerVersion, String contractIdentifier) {
        String version = compilerVersion.startsWith("v") ? compilerVersion.substring(1) : compilerVersion;
        return "{\"stdJsonInput\":" + standardJsonInput + ",\"compilerVersion\":" + Erc20Templates.quote(version)
                + ",\"contractIdentifier\":" + Erc20Templates.quote(contractIdentifier) + "}";
    }

    static Status status(Object response) {
        if(!(response instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("isJobCompleted"))) {
            return Status.PENDING;
        }
        if(map.get("contract") instanceof Map<?, ?> contract && contract.get("match") instanceof String match) {
            return match.equals("exact_match") ? Status.EXACT : match.equals("match") ? Status.PARTIAL : Status.FAILED;
        }
        return Status.FAILED;
    }

    private static String message(Object response) {
        if(response instanceof Map<?, ?> map && map.get("message") instanceof String text) {
            return ": " + Erc20Token.sanitise(text, 200);
        }
        return "";
    }

    /** One request to Sourcify, by the client's transport. The cap is in bytes, at most four to a character. */
    private EvmTransport.Response send(String path, Map<String, String> headers, byte[] body, Duration timeout) throws IOException {
        return transport.send(URI.create(SERVER + path), headers, body, timeout, 4 * MAX_RESPONSE_CHARS);
    }

    private static Object parse(EvmTransport.Response response, String what) throws IOException {
        if(response.body().length() > MAX_RESPONSE_CHARS) {
            throw new IOException("Sourcify " + what + " response too large");
        }
        Object parsed;
        try {
            parsed = MiniJson.parse(response.body());
        } catch(IllegalArgumentException e) {
            throw new IOException("Sourcify returned HTTP " + response.status());
        }
        if(response.status() / 100 != 2) {
            throw new IOException("Sourcify refused the " + what + " (HTTP " + response.status() + ")" + message(parsed));
        }
        return parsed;
    }
}
