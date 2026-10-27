package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Deploying token templates on the testnets. The first test is read-only (LITVM_LIVE=1): each network runs the
 * creation code of a few templates in eth_call and returns contract code, so the bytecode and the EVM version suit
 * every chain. The second (LITVM_E2E_SEED, a throwaway TESTNET seed file) deploys a template (ERC20_KEY, default the
 * one with every feature) on a network (ERC20_NETWORK, default LITEFORGE_TESTNET), checks it as a token, mints, burns,
 * pauses and unpauses as its owner when it can, and has the explorer verify its source.
 */
public class Erc20DeployLiveTest {
    private static final EvmAddress PROBE = EvmAddress.fromString("0x840e41D4484041A43f9307a90389edeB3d111fb3");

    @Test
    public void creationCodeRunsOnEveryNetwork() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("LITVM_LIVE")));
        Erc20Templates templates = Erc20Templates.get();
        for(LitVmNetwork network : LitVmNetwork.values()) {
            LitVmRpcClient client = new LitVmRpcClient(network);
            client.verifyChain();
            for(String key : List.of("fixed", "mintable_v", "capped_bpevfc")) {
                Erc20Templates.Options options = Erc20Templates.Options.fromKey(key);
                Erc20Templates.Params params = new Erc20Templates.Params("Probe Token", "PROBE", PROBE, BigInteger.TEN.pow(21), PROBE,
                        options.supply() == Erc20Templates.Supply.CAPPED ? BigInteger.TEN.pow(24) : null);
                byte[] code = templates.creationCode(options, params);
                byte[] runtime = client.simulate(PROBE, null, BigInteger.ZERO, code);
                BigInteger gas = client.estimateGas(PROBE, null, BigInteger.ZERO, code);
                System.out.println(network.getDisplayName() + " " + key + ": runtime " + runtime.length + " bytes, gas " + gas);
                Assertions.assertTrue(runtime.length > 1000, network + " " + key);
            }
        }
    }

    @Test
    public void deployManageAndVerify() throws Exception {
        String seedPath = System.getenv("LITVM_E2E_SEED");
        Assumptions.assumeTrue(seedPath != null && !seedPath.isEmpty(), "set LITVM_E2E_SEED to deploy a test token");
        List<String> words = Arrays.asList(Files.readString(Path.of(seedPath), StandardCharsets.UTF_8).trim().split(" "));
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(words), "", 0, DeterministicSeed.Type.BIP39);
        EvmAccount account = LitVmLink.fromWalletSeed(seed, 0).getAccount(seed);
        EvmAddress me = account.getAddress();

        LitVmNetwork network = LitVmNetwork.valueOf(Objects.requireNonNullElse(System.getenv("ERC20_NETWORK"), "LITEFORGE_TESTNET"));
        LitVmRpcClient client = new LitVmRpcClient(network);
        client.verifyChain();
        Erc20Templates templates = Erc20Templates.get();
        Erc20Templates.Options options = Erc20Templates.Options.fromKey(Objects.requireNonNullElse(System.getenv("ERC20_KEY"), "capped_bpevfc"));
        BigInteger supply = BigInteger.valueOf(1000).multiply(BigInteger.TEN.pow(18));
        BigInteger cap = options.supply() == Erc20Templates.Supply.CAPPED ? supply.multiply(BigInteger.TEN) : null;
        Erc20Templates.Params params = new Erc20Templates.Params("Sparrow Test Token", "SPTT", me, supply, me, cap);
        byte[] code = templates.creationCode(options, params);

        Eip1559Transaction tx = client.prepareCall(me, null, BigInteger.ZERO, code);
        EvmAddress contract = tx.createdContract(me);
        String hash = mined(client, account, tx);
        System.out.println("deployed " + options.key() + " on " + network.getDisplayName() + " at " + contract + " in " + hash);
        Assertions.assertTrue(client.hasCode(contract));

        Erc20Token token = client.readToken(contract);
        Assertions.assertEquals("SPTT", token.symbol());
        Assertions.assertEquals("Sparrow Test Token", token.name());
        Assertions.assertEquals(18, token.decimals());
        Assertions.assertEquals(supply, client.getTokenBalance(token, me));

        Erc20Admin.Capabilities caps = Erc20Admin.read(client, contract, me);
        Assertions.assertEquals(options.ownable(), caps.ownedBy(me));
        Assertions.assertEquals(options.pausable() ? Optional.of(false) : Optional.empty(), caps.paused());
        Assertions.assertEquals(Optional.ofNullable(cap), caps.cap());
        Assertions.assertEquals(options.supply() != Erc20Templates.Supply.FIXED, caps.mintable());
        Assertions.assertEquals(options.burnable(), caps.burnable());
        if(options.key().equals("capped_bpevfc")) {
            manage(client, account, contract, token, supply);
        }
        verify(new BlockscoutClient(network), templates, options, contract, network);
    }

    private static void manage(LitVmRpcClient client, EvmAccount account, EvmAddress contract, Erc20Token token, BigInteger supply) throws Exception {
        EvmAddress me = account.getAddress();
        mined(client, account, client.prepareCall(me, contract, BigInteger.ZERO, Erc20Admin.mint(me, BigInteger.TEN.pow(18))));
        mined(client, account, client.prepareCall(me, contract, BigInteger.ZERO, Erc20Admin.burn(BigInteger.TEN.pow(17))));
        Assertions.assertEquals(supply.add(BigInteger.TEN.pow(18)).subtract(BigInteger.TEN.pow(17)), client.getTokenBalance(token, me));
        mined(client, account, client.prepareCall(me, contract, BigInteger.ZERO, Erc20Admin.pause()));
        Assertions.assertEquals(Optional.of(true), Erc20Admin.read(client, contract, me).paused());
        mined(client, account, client.prepareCall(me, contract, BigInteger.ZERO, Erc20Admin.unpause()));
        Assertions.assertEquals(Optional.of(false), Erc20Admin.read(client, contract, me).paused());
    }

    private static void verify(BlockscoutClient explorer, Erc20Templates templates, Erc20Templates.Options options, EvmAddress contract, LitVmNetwork network) throws Exception {
        if(SourcifyClient.supports(network)) {
            //Sourcify proves the source exactly; in October 2026 the Blockscout explorers could not reach their verifier service
            SourcifyClient sourcify = new SourcifyClient(network);
            SourcifyClient.Status status = SourcifyClient.Status.PENDING;
            for(int attempt = 0; attempt < 6 && status != SourcifyClient.Status.EXACT; attempt++) {
                try {
                    String id = sourcify.submit(contract, templates.verificationInput(options), templates.compilerVersion(), templates.qualifiedContractName());
                    for(int i = 0; i < 30 && (status = sourcify.job(id)) == SourcifyClient.Status.PENDING; i++) {
                        Thread.sleep(4000);
                    }
                } catch(IOException e) {
                    System.out.println("sourcify: " + e.getMessage());
                    Thread.sleep(15000);
                }
            }
            Assertions.assertEquals(SourcifyClient.Status.EXACT, status);
            Assertions.assertTrue(sourcify.isVerified(contract));
            System.out.println("verified on Sourcify " + sourcify.repositoryUrl(contract));
            return;
        }
        //The explorer indexes the new contract a little after it is mined
        IOException last = null;
        //LiteForge's explorer has taken 15 minutes to index a new contract
        for(int i = 0; i < 150; i++) {
            try {
                explorer.verifyStandardInput(contract, templates.compilerVersion(), templates.qualifiedContractName(), templates.verificationInput(options), "mit");
                last = null;
                break;
            } catch(IOException e) {
                last = e;
                System.out.println("verify: " + e.getMessage());
                Thread.sleep(10000);
            }
        }
        if(last != null) {
            throw last;
        }
        boolean verified = false;
        for(int i = 0; i < 60 && !verified; i++) {
            Thread.sleep(5000);
            try {
                verified = explorer.isVerified(contract);
            } catch(IOException e) {
                //the explorer answers 500 for a while after indexing a new contract
            }
        }
        Assertions.assertTrue(verified, "explorer did not verify " + network.explorerAddressUrl(contract));
        System.out.println("verified " + network.explorerAddressUrl(contract));
    }

    private static String mined(LitVmRpcClient client, EvmAccount account, Eip1559Transaction tx) throws Exception {
        String hash = client.sendRawTransaction(account.sign(tx));
        Optional<LitVmRpcClient.Receipt> receipt = Optional.empty();
        for(int i = 0; i < 90 && receipt.isEmpty(); i++) {
            Thread.sleep(2000);
            receipt = client.getReceipt(hash);
        }
        Assertions.assertTrue(receipt.isPresent(), "no receipt for " + hash);
        Assertions.assertTrue(receipt.get().success(), hash + " reverted");
        return hash;
    }
}
