package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.Utils;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * The native zkLTC bridge between Ethereum Sepolia and LiteForge: LiteForge is an Arbitrum Nitro rollup on Sepolia
 * whose gas token is the zkLTC ERC-20 there. This is the Ethereum-to-LitVM leg of LitVM's planned Litecoin route
 * (Litecoin to zkLTC on Ethereum, then this bridge); the Litecoin leg has no published protocol yet.
 *
 * Deposit (Sepolia to LiteForge): {@code approve(Inbox)} then {@code Inbox.depositERC20(amount)}, credited to the
 * same address on LiteForge within minutes.
 *
 * Withdraw (LiteForge to Sepolia): {@code ArbSys.withdrawEth(dest)} carrying the zkLTC as value. Once the rollup
 * confirms a node covering it (typically hours), anyone can execute it on the Sepolia Outbox with a Merkle proof
 * built by LiteForge's NodeInterface.
 *
 * Trust: the rollup runs permissioned validators, an AnyTrust data committee and an owner who can upgrade it.
 */
public final class ZkLtcBridge {
    public static final LitVmNetwork L1 = LitVmNetwork.SEPOLIA;
    public static final LitVmNetwork L2 = LitVmNetwork.LITEFORGE_TESTNET;
    public static final Erc20Token ZKLTC_L1 = new Erc20Token(EvmAddress.fromString("0xaE9190aEca45F50dCDa0483c0223E191E6811ad2"), "zkLTC", "zkLTC", 18);
    public static final EvmAddress INBOX = EvmAddress.fromString("0x8A381f8822E512E50dd4679E678271E9a83226E6");
    public static final EvmAddress BRIDGE = EvmAddress.fromString("0x9bc68f8b2fea572edc0813f76c186a31e4150f6f");
    public static final EvmAddress OUTBOX = EvmAddress.fromString("0xD015239D9E25DDF00C7F855660f25A9360904E15");
    public static final EvmAddress ROLLUP = EvmAddress.fromString("0xd8c594652b205fa4c8047608fac3ab102b6e968d");
    public static final EvmAddress ARB_SYS = EvmAddress.fromString("0x0000000000000000000000000000000000000064");
    public static final EvmAddress NODE_INTERFACE = EvmAddress.fromString("0x00000000000000000000000000000000000000C8");
    public static final String TRUST_WARNING = "LiteForge is a testnet rollup run by permissioned validators, and its owner can upgrade the "
            + "bridge contracts. Withdrawals to Sepolia wait for the rollup to confirm, typically 1 to 8 hours, then need a claim "
            + "transaction on Sepolia paid in Sepolia ETH.";

    static final byte[] L2_TO_L1_TX_TOPIC = Keccak.keccak256("L2ToL1Tx(address,address,uint256,uint256,uint256,uint256,uint256,uint256,bytes)".getBytes(StandardCharsets.US_ASCII));
    //Legacy (pre-BoLD) rollup NodeCreated event, as emitted by this rollup; its data holds the assertion's after state
    static final byte[] NODE_CREATED_TOPIC = Utils.hexToBytes("4f4caa9e67fb994e349dd35d1ad0ce23053d4323f83ce11dc817b5435031d096");
    private static final int NODE_CREATED_AFTER_BLOCK_HASH_WORD = 6;
    private static final int NODE_CREATED_AFTER_SEND_ROOT_WORD = 7;
    private static final int NODE_CREATED_AT_BLOCK_WORD = 10;

    private ZkLtcBridge() {
    }

    /** Throws unless the Sepolia inbox still points at the pinned bridge, and the bridge at the pinned zkLTC token. */
    public static void verifyBridge(LitVmRpcClient l1) throws IOException {
        if(l1.getNetwork() != L1) {
            throw new IOException("The zkLTC bridge deposits from " + L1.getDisplayName());
        }
        try {
            EvmAddress bridge = Abi.decodeAddress(l1.ethCall(INBOX, Abi.selector("bridge()")), 0);
            if(!BRIDGE.equals(bridge)) {
                throw new IOException("Inbox reports bridge " + bridge + ", expected " + BRIDGE);
            }
            EvmAddress token = Abi.decodeAddress(l1.ethCall(BRIDGE, Abi.selector("nativeToken()")), 0);
            if(!ZKLTC_L1.contract().equals(token)) {
                throw new IOException("Bridge reports native token " + token + ", expected " + ZKLTC_L1.contract());
            }
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid bridge response: " + e.getMessage());
        }
    }

    public static BigInteger depositAllowance(LitVmRpcClient l1, EvmAddress owner) throws IOException {
        try {
            return Abi.decodeUint(l1.ethCall(ZKLTC_L1.contract(), Abi.encodeCall("allowance(address,address)", owner, INBOX)), 0);
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid allowance response");
        }
    }

    public static byte[] approveCall(BigInteger amount) {
        return Abi.encodeCall("approve(address,uint256)", INBOX, amount);
    }

    /** Deposits to the sender's own address on LiteForge. */
    public static byte[] depositCall(BigInteger amount) {
        return Abi.encodeCall("depositERC20(uint256)", amount);
    }

    /** Sent to {@link #ARB_SYS} on LiteForge with the zkLTC to withdraw as the transaction value. */
    public static byte[] withdrawCall(EvmAddress destination) {
        return Abi.encodeCall("withdrawEth(address)", destination);
    }

    /** An L2-to-L1 message, as logged by ArbSys when a withdrawal is made. */
    public record Withdrawal(EvmAddress caller, EvmAddress destination, BigInteger position, BigInteger arbBlockNum,
                             BigInteger ethBlockNum, BigInteger timestamp, BigInteger callValue, byte[] data) {}

    /** The withdrawal a LiteForge transaction made, or null if it is pending or made none. */
    public static Withdrawal findWithdrawal(LitVmRpcClient l2, String l2TxHash) throws IOException {
        for(LitVmRpcClient.Log log : l2.getTransactionLogs(l2TxHash)) {
            if(log.address().equals(ARB_SYS) && log.topics().size() == 4 && Arrays.equals(log.topics().get(0), L2_TO_L1_TX_TOPIC)) {
                try {
                    EvmAddress destination = Abi.decodeAddress(log.topics().get(1), 0);
                    BigInteger position = new BigInteger(1, log.topics().get(3));
                    return new Withdrawal(Abi.decodeAddress(log.data(), 0), destination, position, Abi.decodeUint(log.data(), 1),
                            Abi.decodeUint(log.data(), 2), Abi.decodeUint(log.data(), 3), Abi.decodeUint(log.data(), 4), Abi.decodeBytes(log.data(), 5));
                } catch(IllegalArgumentException e) {
                    throw new IOException("Invalid withdrawal log: " + e.getMessage());
                }
            }
        }
        return null;
    }

    public record Confirmed(BigInteger sendCount, byte[] sendRoot) {}

    /**
     * The L2-to-L1 messages covered by the rollup's latest confirmed node, as a count and Merkle root: a withdrawal at
     * a position below the count can be executed. Read from the node's creation event, and cross-checked against the
     * Outbox's root.
     */
    public static Confirmed confirmedState(LitVmRpcClient l1, LitVmRpcClient l2) throws IOException {
        try {
            BigInteger nodeNum = Abi.decodeUint(l1.ethCall(ROLLUP, Abi.selector("latestConfirmed()")), 0);
            byte[] node = l1.ethCall(ROLLUP, Abi.encodeCall("getNode(uint64)", nodeNum));
            BigInteger createdAt = Abi.decodeUint(node, NODE_CREATED_AT_BLOCK_WORD);
            List<LitVmRpcClient.Log> logs = l1.getLogs(ROLLUP, createdAt, NODE_CREATED_TOPIC, Abi.uint(nodeNum));
            if(logs.size() != 1) {
                throw new IOException("Expected one creation event for rollup node " + nodeNum + ", found " + logs.size());
            }
            byte[] data = logs.getFirst().data();
            byte[] blockHash = Arrays.copyOfRange(data, NODE_CREATED_AFTER_BLOCK_HASH_WORD * 32, NODE_CREATED_AFTER_BLOCK_HASH_WORD * 32 + 32);
            byte[] sendRoot = Arrays.copyOfRange(data, NODE_CREATED_AFTER_SEND_ROOT_WORD * 32, NODE_CREATED_AFTER_SEND_ROOT_WORD * 32 + 32);
            byte[] outboxBlock = l1.ethCall(OUTBOX, Abi.encodeCall("roots(bytes32)", (Object)sendRoot));
            if(!Arrays.equals(outboxBlock, blockHash)) {
                throw new IOException("Outbox has no root for rollup node " + nodeNum);
            }
            LitVmRpcClient.ArbBlock block = l2.getArbBlock(blockHash);
            if(!Arrays.equals(block.sendRoot(), sendRoot)) {
                throw new IOException("LiteForge block " + block.number() + " does not match the confirmed send root");
            }
            return new Confirmed(block.sendCount(), sendRoot);
        } catch(IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            throw new IOException("Invalid rollup response: " + e.getMessage());
        }
    }

    public static boolean isClaimed(LitVmRpcClient l1, Withdrawal withdrawal) throws IOException {
        try {
            return Abi.decodeBool(l1.ethCall(OUTBOX, Abi.encodeCall("isSpent(uint256)", withdrawal.position())), 0);
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid outbox response");
        }
    }

    /** The Sepolia Outbox call that releases a confirmed withdrawal, with its proof against the confirmed send count. */
    public static byte[] claimCall(LitVmRpcClient l2, Withdrawal withdrawal, BigInteger sendCount) throws IOException {
        if(withdrawal.position().compareTo(sendCount) >= 0) {
            throw new IOException("Withdrawal is not confirmed yet");
        }
        List<byte[]> proof;
        try {
            byte[] result = l2.ethCall(NODE_INTERFACE, Abi.encodeCall("constructOutboxProof(uint64,uint64)", sendCount, withdrawal.position()));
            proof = Abi.decodeBytes32Array(result, 2);
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid outbox proof: " + e.getMessage());
        }
        return Abi.encodeCall("executeTransaction(bytes32[],uint256,address,address,uint256,uint256,uint256,uint256,bytes)",
                new Abi.Bytes32Array(proof), withdrawal.position(), withdrawal.caller(), withdrawal.destination(), withdrawal.arbBlockNum(),
                withdrawal.ethBlockNum(), withdrawal.timestamp(), withdrawal.callValue(), new Abi.Dynamic(withdrawal.data()));
    }
}
