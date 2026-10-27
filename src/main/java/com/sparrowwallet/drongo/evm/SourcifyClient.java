package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public SourcifyClient(LitVmNetwork network) {
        if(!supports(network)) {
            throw new IllegalArgumentException("Sourcify does not support " + network.getDisplayName());
        }
        this.network = network;
    }

    /** LiteForge is not one of Sourcify's chains. */
    public static boolean supports(LitVmNetwork network) {
        return network == LitVmNetwork.SEPOLIA || network == LitVmNetwork.BASE_SEPOLIA;
    }

    /** Submits the input and returns the job id to follow with {@link #job(String)}. */
    public String submit(EvmAddress contract, String standardJsonInput, String compilerVersion, String contractIdentifier) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(SERVER + "/v2/verify/" + network.getChainId() + "/" + contract))
                .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(standardJsonInput, compilerVersion, contractIdentifier))).build();
        Object response = parse(send(request), "verification");
        if(response instanceof Map<?, ?> map && map.get("verificationId") instanceof String id && id.matches("[0-9a-fA-F-]{8,64}")) {
            return id;
        }
        throw new IOException("Sourcify did not start a verification" + message(response));
    }

    public Status job(String verificationId) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(SERVER + "/v2/verify/" + verificationId)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").GET().build();
        return status(parse(send(request), "job"));
    }

    /** Whether Sourcify holds source that exactly matches the contract. */
    public boolean isVerified(EvmAddress contract) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(SERVER + "/v2/contract/" + network.getChainId() + "/" + contract)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> response = send(request);
        if(response.statusCode() == 404) {
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

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted talking to Sourcify", e);
        }
    }

    private static Object parse(HttpResponse<String> response, String what) throws IOException {
        if(response.body().length() > MAX_RESPONSE_CHARS) {
            throw new IOException("Sourcify " + what + " response too large");
        }
        Object parsed;
        try {
            parsed = MiniJson.parse(response.body());
        } catch(IllegalArgumentException e) {
            throw new IOException("Sourcify returned HTTP " + response.statusCode());
        }
        if(response.statusCode() / 100 != 2) {
            throw new IOException("Sourcify refused the " + what + " (HTTP " + response.statusCode() + ")" + message(parsed));
        }
        return parsed;
    }
}
