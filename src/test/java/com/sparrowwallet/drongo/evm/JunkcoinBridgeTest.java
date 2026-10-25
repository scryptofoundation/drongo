package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.InvalidAddressException;
import com.sparrowwallet.drongo.protocol.ScriptType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

public class JunkcoinBridgeTest {
    private static final EvmAddress RECIPIENT = EvmAddress.fromString("0x840e41D4484041A43f9307a90389edeB3d111fb3");
    private static final String TXID = "fbbd36190000000000000000000000000000000000000000000000000000abcd";

    @Test
    public void depositMarkerRoundTrips() {
        byte[] data = JunkcoinBridge.depositData(RECIPIENT);
        Assertions.assertEquals("840e41d4484041a43f9307a90389edeb3d111fb3", Utils.bytesToHex(data));
        Assertions.assertEquals(RECIPIENT, JunkcoinBridge.depositRecipient(data));
        Assertions.assertNull(JunkcoinBridge.depositRecipient(new byte[80]));
    }

    @Test
    public void custodyIsATaprootJunkcoinTestnetAddress() throws Exception {
        Coin previous = Coin.beginScope(Coin.JUNKCOIN);
        try {
            Address custody = Address.fromString(Network.TESTNET, JunkcoinBridge.CUSTODY_ADDRESS);
            Assertions.assertEquals(ScriptType.P2TR, custody.getScriptType());
            Assertions.assertEquals(JunkcoinBridge.CUSTODY_ADDRESS, custody.getAddress(Network.TESTNET));
            Assertions.assertThrows(InvalidAddressException.class, () -> Address.fromString(Network.MAINNET, JunkcoinBridge.CUSTODY_ADDRESS));
        } finally {
            Coin.endScope(previous);
        }
    }

    @Test
    public void claimKeysTxidInDisplayOrder() {
        Assertions.assertEquals("5e585889" + TXID, Utils.bytesToHex(JunkcoinBridge.claimCall(TXID.toUpperCase())));
        Assertions.assertThrows(IllegalArgumentException.class, () -> JunkcoinBridge.txidKey("abc"));
    }

    @Test
    public void encodesWithdrawal() {
        String hex = Utils.bytesToHex(JunkcoinBridge.withdrawCall(BigInteger.valueOf(300_000_000L), "mvBsPSCNfg4wPEa6zjtqM4irJDzM9pbVzR", 100));
        Assertions.assertTrue(hex.startsWith("85e54b13"));
        Assertions.assertEquals(BigInteger.valueOf(300_000_000L), new BigInteger(hex.substring(8, 72), 16));
        Assertions.assertEquals(BigInteger.valueOf(0x60), new BigInteger(hex.substring(72, 136), 16), "string offset");
        Assertions.assertEquals(BigInteger.valueOf(100), new BigInteger(hex.substring(136, 200), 16));
        Assertions.assertThrows(IllegalArgumentException.class, () -> JunkcoinBridge.withdrawCall(BigInteger.ONE, "bad address!", 100));
        Assertions.assertEquals(BigInteger.valueOf(297_000_000L), JunkcoinBridge.afterFee(BigInteger.valueOf(300_000_000L), 100));
    }

    /** Read-only check against the live bridge on LiteForge; set LITVM_LIVE=1 to run. */
    @Test
    public void liveBridgeMatchesPinnedConfig() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("LITVM_LIVE")));
        LitVmRpcClient client = new LitVmRpcClient(JunkcoinBridge.NETWORK);
        client.verifyChain();
        JunkcoinBridge.verifyBridge(client);
        Assertions.assertTrue(JunkcoinBridge.feeBps(client) <= JunkcoinBridge.MAX_FEE_BPS);
        Erc20Token token = client.readToken(JunkcoinBridge.WJKC.contract());
        Assertions.assertEquals(8, token.decimals());
        Assertions.assertEquals(JunkcoinBridge.DepositState.NOT_REGISTERED, JunkcoinBridge.depositStatus(client, TXID).state());
    }
}
