package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.Utils;

import java.io.IOException;
import java.math.BigInteger;

/**
 * The Junkcoin Foundation bridge between Junkcoin testnet (JKC) and wrapped JKC on LiteForge.
 *
 * Deposit: a Junkcoin transaction pays at least {@link #MIN_DEPOSIT_SATS} to the custody address and carries an
 * OP_RETURN holding the 20-byte EVM recipient. After {@link #DEPOSIT_CONFIRMATIONS} confirmations the bridge's
 * signers register a claim, which the recipient then mints with {@code claimDeposit(jkcTxid)}.
 *
 * Withdraw: {@code approve} the bridge for the wJKC, then {@code initiateWithdrawal(amount, jkcAddress, maxFeeBps)};
 * the signers pay the JKC out to that address.
 *
 * The bridge is custodial: the JKC sits under one signing key the bridge operator holds, and the contract is
 * upgradeable by a single owner wallet. Nothing here can verify the payout side. The addresses are pinned so a
 * lookalike site or a changed contract config is refused rather than followed.
 */
public final class JunkcoinBridge {
    public static final LitVmNetwork NETWORK = LitVmNetwork.LITEFORGE_TESTNET;
    public static final EvmAddress BRIDGE = EvmAddress.fromString("0x5E44d547A2541c0E0E5A9231832b7F0085464509");
    public static final Erc20Token WJKC = new Erc20Token(EvmAddress.fromString("0x42A53E32825c993A29823D07600a3E1327F1Ed77"),
            "wtJKC", "Wrapped Testnet Junkcoin", 8);
    /** Junkcoin testnet taproot address holding deposited JKC. */
    public static final String CUSTODY_ADDRESS = "tjc1p2aquhec8rfq62vpukr3fah3k2ytn95adlxy3xmw0pys6mk7j6kese7qx5e";
    public static final long MIN_DEPOSIT_SATS = 200_000_000L;
    public static final int DEPOSIT_CONFIRMATIONS = 6;
    /** The fee the bridge charges today (1%); a withdrawal refuses to pay more than this. */
    public static final int MAX_FEE_BPS = 100;
    public static final String TRUST_WARNING = "This bridge is custodial. Deposited JKC is held under a single signing key controlled by the "
            + "bridge operator, and one owner wallet can upgrade the bridge contract. Use it with test coins only.";

    private JunkcoinBridge() {
    }

    /** The OP_RETURN payload naming the LitVM recipient of a deposit: the raw 20 address bytes. */
    public static byte[] depositData(EvmAddress recipient) {
        return recipient.getBytes();
    }

    /** The recipient a deposit's OP_RETURN payload names, or null if the payload is not a deposit marker. */
    public static EvmAddress depositRecipient(byte[] opReturnData) {
        return opReturnData != null && opReturnData.length == 20 ? EvmAddress.fromBytes(opReturnData) : null;
    }

    /** The bytes32 the bridge keys a deposit by: the Junkcoin txid as displayed, not byte-reversed. */
    public static byte[] txidKey(String jkcTxid) {
        String hex = jkcTxid.trim().toLowerCase();
        if(!hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Not a Junkcoin transaction id: " + jkcTxid);
        }
        return Utils.hexToBytes(hex);
    }

    public record PendingClaim(EvmAddress to, BigInteger netAmount, BigInteger fee, BigInteger expiry) {}

    public enum DepositState { NOT_REGISTERED, READY, CLAIMED }

    public record DepositStatus(DepositState state, PendingClaim claim) {}

    /** Whether the signers have registered the deposit, and whether it has already been minted. */
    public static DepositStatus depositStatus(LitVmRpcClient client, String jkcTxid) throws IOException {
        byte[] key = txidKey(jkcTxid);
        try {
            byte[] claim = client.ethCall(BRIDGE, Abi.encodeCall("pendingClaims(bytes32)", (Object)key));
            EvmAddress to = Abi.decodeAddress(claim, 0);
            if(!to.equals(EvmAddress.fromBytes(new byte[20]))) {
                return new DepositStatus(DepositState.READY, new PendingClaim(to, Abi.decodeUint(claim, 1), Abi.decodeUint(claim, 2), Abi.decodeUint(claim, 3)));
            }
            boolean processed = Abi.decodeBool(client.ethCall(BRIDGE, Abi.encodeCall("processedTxids(bytes32)", (Object)key)), 0);
            return new DepositStatus(processed ? DepositState.CLAIMED : DepositState.NOT_REGISTERED, null);
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid bridge response: " + e.getMessage());
        }
    }

    /** Throws unless the deployed bridge still reports the pinned custody address and token. */
    public static void verifyBridge(LitVmRpcClient client) throws IOException {
        if(client.getNetwork() != NETWORK) {
            throw new IOException("The Junkcoin bridge runs on " + NETWORK.getDisplayName());
        }
        try {
            String custody = Abi.decodeString(client.ethCall(BRIDGE, Abi.selector("jkcCustodyAddress()")));
            if(!CUSTODY_ADDRESS.equals(custody)) {
                throw new IOException("Bridge reports custody address " + custody + ", expected " + CUSTODY_ADDRESS);
            }
            EvmAddress token = Abi.decodeAddress(client.ethCall(BRIDGE, Abi.selector("wjkc()")), 0);
            if(!WJKC.contract().equals(token)) {
                throw new IOException("Bridge reports token " + token + ", expected " + WJKC.contract());
            }
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid bridge response: " + e.getMessage());
        }
    }

    public static int feeBps(LitVmRpcClient client) throws IOException {
        try {
            return Abi.decodeUint(client.ethCall(BRIDGE, Abi.selector("feeBps()")), 0).intValueExact();
        } catch(IllegalArgumentException | ArithmeticException e) {
            throw new IOException("Invalid bridge fee response");
        }
    }

    public static BigInteger allowance(LitVmRpcClient client, EvmAddress owner) throws IOException {
        try {
            return Abi.decodeUint(client.ethCall(WJKC.contract(), Abi.encodeCall("allowance(address,address)", owner, BRIDGE)), 0);
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid allowance response");
        }
    }

    public static byte[] claimCall(String jkcTxid) {
        return Abi.encodeCall("claimDeposit(bytes32)", (Object)txidKey(jkcTxid));
    }

    public static byte[] approveCall(BigInteger amount) {
        return Abi.encodeCall("approve(address,uint256)", BRIDGE, amount);
    }

    public static byte[] withdrawCall(BigInteger amount, String jkcAddress, int maxFeeBps) {
        if(jkcAddress == null || !jkcAddress.matches("[A-Za-z0-9]{26,90}")) {
            throw new IllegalArgumentException("Not a Junkcoin address: " + jkcAddress);
        }
        return Abi.encodeCall("initiateWithdrawal(uint256,string,uint256)", amount, jkcAddress, BigInteger.valueOf(maxFeeBps));
    }

    /** The amount a deposit or withdrawal delivers after the bridge fee. */
    public static BigInteger afterFee(BigInteger amount, int feeBps) {
        return amount.subtract(amount.multiply(BigInteger.valueOf(feeBps)).divide(BigInteger.valueOf(10_000)));
    }
}
