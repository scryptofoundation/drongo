package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.crypto.ECKey;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigInteger;
import java.util.Locale;

/**
 * Talks to the real LiteForge testnet, so it only runs with LITVM_LIVE=1.
 *
 * Nothing is spent: a transfer is signed by a brand-new throwaway key with no funds, so the node must reject
 * it for insufficient funds. That rejection proves the node decoded our EIP-1559 encoding and recovered the
 * intended sender from our signature.
 */
@EnabledIfEnvironmentVariable(named = "LITVM_LIVE", matches = "1")
public class LitVmLiveTest {
    @Test
    public void liteForgeAcceptsOurEncodingAndSignature() throws Exception {
        LitVmRpcClient client = new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET);
        client.verifyChain();
        Assertions.assertEquals(4441L, client.chainId());

        EvmAccount throwaway = EvmAccount.fromPrivateKey(new ECKey());
        Assertions.assertEquals(BigInteger.ZERO, client.getBalance(throwaway.getAddress()));
        Assertions.assertEquals(BigInteger.ZERO, client.getPendingNonce(throwaway.getAddress()));

        BigInteger baseFee = client.getBaseFee();
        BigInteger priority = client.getMaxPriorityFee();
        System.out.println("LiteForge base fee " + baseFee + " wei, priority fee " + priority + " wei");

        Eip1559Transaction tx = new Eip1559Transaction(4441L, BigInteger.ZERO, priority, baseFee.multiply(BigInteger.TWO).add(priority),
                BigInteger.valueOf(21000), EvmAddress.fromString("0x000000000000000000000000000000000000dEaD"), BigInteger.ONE, null);
        Eip1559Transaction.Signed signed = throwaway.sign(tx);

        LitVmRpcClient.LitVmRpcException rejection = Assertions.assertThrows(LitVmRpcClient.LitVmRpcException.class, () -> client.sendRawTransaction(signed));
        String message = rejection.getMessage().toLowerCase(Locale.ROOT);
        System.out.println("LiteForge rejected the unfunded transfer as expected: " + rejection.getMessage());
        Assertions.assertTrue(message.contains("insufficient funds"), "Expected an insufficient-funds rejection, got: " + rejection.getMessage());
        if(message.contains("0x")) {
            //Nitro names the recovered sender in this error; it must be our address.
            Assertions.assertTrue(message.contains(throwaway.getAddress().toString().toLowerCase(Locale.ROOT)),
                    "Node recovered a different sender: " + rejection.getMessage());
        }
    }
}
