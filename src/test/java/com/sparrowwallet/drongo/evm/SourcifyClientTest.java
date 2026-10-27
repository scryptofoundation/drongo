package com.sparrowwallet.drongo.evm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class SourcifyClientTest {
    @Test
    @SuppressWarnings("unchecked")
    public void requestCarriesTheInputAsAnObject() {
        String body = SourcifyClient.requestBody("{\"language\":\"Solidity\",\"sources\":{}}", "v0.8.36+commit.8a079791", "Token.sol:Token");
        Map<String, Object> parsed = (Map<String, Object>)MiniJson.parse(body);
        Assertions.assertEquals("0.8.36+commit.8a079791", parsed.get("compilerVersion"));
        Assertions.assertEquals("Token.sol:Token", parsed.get("contractIdentifier"));
        Assertions.assertEquals("Solidity", ((Map<String, Object>)parsed.get("stdJsonInput")).get("language"));
    }

    @Test
    public void onlySupportedNetworks() {
        Assertions.assertTrue(SourcifyClient.supports(LitVmNetwork.SEPOLIA));
        Assertions.assertTrue(SourcifyClient.supports(LitVmNetwork.BASE_SEPOLIA));
        Assertions.assertFalse(SourcifyClient.supports(LitVmNetwork.LITEFORGE_TESTNET));
    }

    @Test
    public void jobStatusIsRead() {
        Object done = MiniJson.parse("{\"isJobCompleted\":true,\"contract\":{\"match\":\"exact_match\",\"creationMatch\":\"exact_match\",\"runtimeMatch\":\"exact_match\"}}");
        Assertions.assertEquals(SourcifyClient.Status.EXACT, SourcifyClient.status(done));
        Object partial = MiniJson.parse("{\"isJobCompleted\":true,\"contract\":{\"match\":\"match\"}}");
        Assertions.assertEquals(SourcifyClient.Status.PARTIAL, SourcifyClient.status(partial));
        Object failed = MiniJson.parse("{\"isJobCompleted\":true,\"error\":{\"customCode\":\"no_match\",\"message\":\"The deployed and recompiled bytecode don't match.\"}}");
        Assertions.assertEquals(SourcifyClient.Status.FAILED, SourcifyClient.status(failed));
        Object pending = MiniJson.parse("{\"isJobCompleted\":false}");
        Assertions.assertEquals(SourcifyClient.Status.PENDING, SourcifyClient.status(pending));
    }

    @Test
    public void explorerCountsOnlyAnExactMatchOfOurSource() {
        Assertions.assertTrue(BlockscoutClient.verifiedExactly(MiniJson.parse("{\"is_verified\":true,\"is_partially_verified\":false}")));
        //A partial match the explorer found in its database, from someone else's source, is not ours
        Assertions.assertFalse(BlockscoutClient.verifiedExactly(MiniJson.parse("{\"is_verified\":true,\"is_partially_verified\":true}")));
        Assertions.assertFalse(BlockscoutClient.verifiedExactly(MiniJson.parse("{\"creation_bytecode\":\"0x00\"}")));
    }
}
