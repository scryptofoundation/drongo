package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Optional;

/**
 * Calls for managing an OpenZeppelin ERC-20 the account owns or holds: minting, pausing, burning and ownership, and
 * reading which of them a token has. Whether a function exists is found by calling it, since an ERC-20 says nothing
 * about its extensions.
 */
public final class Erc20Admin {
    private Erc20Admin() {}

    public static byte[] mint(EvmAddress to, BigInteger amount) { return Abi.encodeCall("mint(address,uint256)", to, amount); }
    public static byte[] burn(BigInteger amount) { return Abi.encodeCall("burn(uint256)", amount); }
    public static byte[] pause() { return Abi.selector("pause()"); }
    public static byte[] unpause() { return Abi.selector("unpause()"); }
    public static byte[] transferOwnership(EvmAddress newOwner) { return Abi.encodeCall("transferOwnership(address)", newOwner); }
    public static byte[] renounceOwnership() { return Abi.selector("renounceOwnership()"); }
    public static byte[] owner() { return Abi.selector("owner()"); }
    public static byte[] paused() { return Abi.selector("paused()"); }
    public static byte[] cap() { return Abi.selector("cap()"); }

    /**
     * What the token lets this account do. owner is empty when the token has no owner (or it was renounced), paused
     * and cap are empty when the token has no such function. mintable and burnable come from simulating mint(me, 0)
     * and burn(0), so they are only true when the call would succeed now (not while paused).
     */
    public record Capabilities(Optional<EvmAddress> owner, Optional<Boolean> paused, Optional<BigInteger> cap, boolean mintable, boolean burnable) {
        public boolean ownedBy(EvmAddress account) {
            return owner.isPresent() && owner.get().equals(account);
        }
    }

    private static final EvmAddress ZERO = EvmAddress.fromBytes(new byte[20]);

    public static Capabilities read(LitVmRpcClient client, EvmAddress token, EvmAddress me) throws IOException {
        Optional<EvmAddress> owner = optional(() -> Abi.decodeAddress(client.ethCall(token, owner()), 0)).filter(a -> !a.equals(ZERO));
        Optional<Boolean> paused = optional(() -> Abi.decodeBool(client.ethCall(token, paused()), 0));
        Optional<BigInteger> cap = optional(() -> Abi.decodeUint(client.ethCall(token, cap()), 0));
        boolean mintable = owner.isPresent() && owner.get().equals(me) && succeeds(() -> client.simulate(me, token, BigInteger.ZERO, mint(me, BigInteger.ZERO)));
        boolean burnable = succeeds(() -> client.simulate(me, token, BigInteger.ZERO, burn(BigInteger.ZERO)));
        return new Capabilities(owner, paused, cap, mintable, burnable);
    }

    private interface Read<T> {
        T get() throws IOException;
    }

    private static <T> Optional<T> optional(Read<T> read) {
        try {
            return Optional.ofNullable(read.get());
        } catch(IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean succeeds(Read<?> read) {
        return optional(read).isPresent();
    }
}
