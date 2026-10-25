package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Funded LitVM round trip on the LiteForge testnet, as a Litecoin wallet's same-seed LitVM link does it: the account
 * is the link's (m/44'/60'/0'/0/0 of the wallet seed), a transfer is prepared, signed and broadcast, the receipt
 * must report success, and the recipient (account index 1 of the same seed) must have received the value.
 *
 * Throwaway TESTNET seed only. Opt-in: LITVM_E2E_SEED names a file holding BIP39 words (the Litecoin testnet
 * end-to-end wallet's seed works, so the account is that wallet's LitVM link). Fund the printed address with
 * zkLTC from the LiteForge faucet on Caldera Hub.
 */
public class LitVmEndToEndLiveTest {
    private static final BigInteger SEND = Wei.gwei(100_000); //0.0001 zkLTC

    @Test
    public void sameSeedLinkSendsAndReceives() throws Exception {
        String seedPath = System.getenv("LITVM_E2E_SEED");
        Assumptions.assumeTrue(seedPath != null && !seedPath.isEmpty(), "set LITVM_E2E_SEED to run the funded LitVM test");
        List<String> words = Arrays.asList(Files.readString(Path.of(seedPath), StandardCharsets.UTF_8).trim().split(" "));
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(words), "", 0, DeterministicSeed.Type.BIP39);

        //The account a same-seed LitVM link uses, and the recipient one index along
        LitVmLink link = LitVmLink.fromWalletSeed(seed, 0);
        EvmAccount sender = link.getAccount(seed);
        Assertions.assertEquals(link.getAddress(), sender.getAddress());
        EvmAccount recipient = EvmAccount.fromSeed(seed, 1);

        LitVmRpcClient client = new LitVmRpcClient(LitVmNetwork.LITEFORGE_TESTNET);
        client.verifyChain();
        BigInteger balance = client.getBalance(sender.getAddress());
        System.out.println("LitVM link address " + sender.getAddress() + " balance " + Wei.format(balance) + " zkLTC");
        Assumptions.assumeTrue(balance.compareTo(SEND.multiply(BigInteger.valueOf(5))) > 0,
                "not funded yet: claim zkLTC on the LiteForge faucet to " + sender.getAddress());

        BigInteger recipientBefore = client.getBalance(recipient.getAddress());
        Eip1559Transaction tx = client.prepareTransfer(sender.getAddress(), recipient.getAddress(), SEND);
        Assertions.assertTrue(tx.maxCost().compareTo(balance) <= 0, "max cost must fit the balance");
        Eip1559Transaction.Signed signed = sender.sign(tx);
        Assertions.assertEquals(sender.getAddress(), signed.from(), "signature must recover the sender");

        String hash = client.sendRawTransaction(signed);
        System.out.println("sent " + Wei.format(SEND) + " zkLTC, tx " + hash + ", max fee " + Wei.format(tx.maxCost().subtract(SEND)));

        Optional<LitVmRpcClient.Receipt> receipt = Optional.empty();
        for(int i = 0; i < 30 && receipt.isEmpty(); i++) {
            Thread.sleep(2000);
            receipt = client.getReceipt(hash);
        }
        Assertions.assertTrue(receipt.isPresent(), "no receipt for " + hash + " after 60 s");
        Assertions.assertTrue(receipt.get().success(), "transaction reverted");
        System.out.println("mined in block " + receipt.get().blockNumber() + ", gas used " + receipt.get().gasUsed());

        BigInteger recipientAfter = client.getBalance(recipient.getAddress());
        Assertions.assertEquals(recipientBefore.add(SEND), recipientAfter, "recipient must receive exactly the value sent");
        System.out.println("recipient " + recipient.getAddress() + " now holds " + Wei.format(recipientAfter) + " zkLTC");
    }
}
