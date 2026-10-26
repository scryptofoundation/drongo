package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.math.BigInteger;

/**
 * Moving Sepolia ETH to Base Sepolia through Base's OptimismPortal on Sepolia: depositTransaction to the sender's
 * own address mints the same ETH on Base Sepolia, typically about 5 minutes later. The way back (a proven and
 * finalised withdrawal) is not supported.
 */
public final class BaseBridge {
    public static final LitVmNetwork L1 = LitVmNetwork.SEPOLIA;
    public static final LitVmNetwork L2 = LitVmNetwork.BASE_SEPOLIA;
    public static final EvmAddress PORTAL = EvmAddress.fromString("0x49f53e41452C74589E85cA1677426Ba426459e85");
    static final EvmAddress SYSTEM_CONFIG = EvmAddress.fromString("0xf272670eb55e895584501d564AfEB048bEd26194");
    //The L2 gas limit for a plain ETH credit to an externally owned account
    private static final BigInteger L2_GAS_LIMIT = BigInteger.valueOf(21_000);
    public static final String NOTE = "Base Sepolia is an OP Stack testnet. Deposits arrive in about 5 minutes; this wallet cannot bring ETH back "
            + "from Base to Sepolia (that needs a proven withdrawal and a waiting period).";

    private BaseBridge() {
    }

    /** Throws unless the portal is live, unpaused, and wired to Base Sepolia's system config. */
    public static void verify(LitVmRpcClient l1) throws IOException {
        if(l1.getNetwork() != L1) {
            throw new IOException("Base deposits are sent on " + L1.getDisplayName());
        }
        try {
            UniswapV3Venue.expect(l1, PORTAL, "systemConfig()", SYSTEM_CONFIG);
            long chainId = Abi.decodeUint(l1.ethCall(SYSTEM_CONFIG, Abi.selector("l2ChainId()")), 0).longValueExact();
            if(chainId != L2.getChainId()) {
                throw new IOException("Portal's system config is for chain " + chainId + ", expected " + L2.getChainId());
            }
            if(Abi.decodeBool(l1.ethCall(PORTAL, Abi.selector("paused()")), 0)) {
                throw new IOException("The Base portal is paused");
            }
        } catch(IllegalArgumentException | ArithmeticException e) {
            throw new IOException("Invalid portal response: " + e.getMessage());
        }
    }

    /** Sent to {@link #PORTAL} with the ETH to move as the transaction value; credits the same address on Base. */
    public static byte[] depositCall(EvmAddress recipient, BigInteger value) {
        return Abi.encodeCall("depositTransaction(address,uint256,uint64,bool,bytes)", recipient, value, L2_GAS_LIMIT, false, new Abi.Dynamic(new byte[0]));
    }
}
