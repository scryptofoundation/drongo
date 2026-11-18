package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;

/**
 * Read-only access to an ERC-721 (NFT) contract. Nothing here signs or sends.
 *
 * Every answer comes from a contract anyone can deploy, so each is checked and bounded. The contract's own outcomes
 * (a revert, an answer that is malformed or too large) give an empty or false result; a failure of the network, the
 * HTTP endpoint or the node throws IOException, so a broken connection is never mistaken for "not a collection".
 */
public final class Erc721 {
    /** The largest tokenURI result read, in bytes of call result. */
    public static final int MAX_TOKEN_URI_BYTES = LitVmRpcClient.MAX_BOUNDED_CALL_RESULT_BYTES;
    //Every other call here returns a word or a short string
    private static final int MAX_RESULT_BYTES = 8_192;

    private static final byte[] ERC165 = {0x01, (byte)0xff, (byte)0xc9, (byte)0xa7};
    private static final byte[] NOT_AN_INTERFACE = {(byte)0xff, (byte)0xff, (byte)0xff, (byte)0xff};
    private static final byte[] ERC721 = {(byte)0x80, (byte)0xac, 0x58, (byte)0xcd};
    private static final byte[] ERC721_ENUMERABLE = {0x78, 0x0e, (byte)0x9d, 0x63};
    private static final byte[] EIP5192_SOULBOUND = {(byte)0xb4, 0x5a, 0x3c, 0x0e};

    private Erc721() {}

    private enum Answer {YES, NO, FAILED}

    /**
     * The collection at this address, or empty when it is not an ERC-721 contract. ERC-165 detection is done as the
     * standard describes: supportsInterface(0x01ffc9a7) answers true, supportsInterface(0xffffffff) answers false,
     * and supportsInterface(0x80ac58cd) answers true. A contract that answers true to everything, or reverts or
     * answers nonsense on any of these, is not a collection.
     */
    public static Optional<Erc721Collection> read(LitVmRpcClient client, EvmAddress contract) throws IOException {
        if(!hasCode(client, contract)) {
            return Optional.empty();
        }
        if(supports(client, contract, ERC165) != Answer.YES || supports(client, contract, NOT_AN_INTERFACE) != Answer.NO
                || supports(client, contract, ERC721) != Answer.YES) {
            return Optional.empty();
        }
        boolean enumerable = supports(client, contract, ERC721_ENUMERABLE) == Answer.YES;
        boolean soulbound = supports(client, contract, EIP5192_SOULBOUND) == Answer.YES;
        //Name and symbol belong to the optional metadata extension: absent or nonsense leaves them blank
        String name = optionalString(client, contract, "name()");
        String symbol = optionalString(client, contract, "symbol()");
        return Optional.of(new Erc721Collection(contract, name, symbol, enumerable, soulbound));
    }

    /** How many tokens the owner holds. A revert or a malformed answer throws: a collection must answer this. */
    public static BigInteger balanceOf(LitVmRpcClient client, EvmAddress contract, EvmAddress owner) throws IOException {
        return uint(client, contract, Abi.encodeCall("balanceOf(address)", owner), "balanceOf");
    }

    /**
     * The token's owner, or empty when the contract reverts (no such token) or names the zero address. An answer that
     * cannot be read as an address (over the call's cap, empty, short, or with bits above the address) says nothing
     * about the owner, so it throws IOException: a failure to read, never "not held".
     */
    public static Optional<EvmAddress> ownerOf(LitVmRpcClient client, EvmAddress contract, BigInteger tokenId) throws IOException {
        byte[] result;
        try {
            result = client.ethCall(contract, Abi.encodeCall("ownerOf(uint256)", tokenId), MAX_RESULT_BYTES);
        } catch(LitVmRpcClient.LitVmRpcException e) {
            if(e.isRevert()) {
                return Optional.empty();
            }
            throw e;
        }
        try {
            EvmAddress owner = Abi.decodeAddress(result, 0);
            return Arrays.equals(owner.getBytes(), new byte[20]) ? Optional.empty() : Optional.of(owner);
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid ownerOf result from " + contract);
        }
    }

    /** The token's metadata URI as the contract returns it, or empty on a revert, a malformed answer or one over {@link #MAX_TOKEN_URI_BYTES}. */
    public static Optional<String> tokenUri(LitVmRpcClient client, EvmAddress contract, BigInteger tokenId) throws IOException {
        Optional<byte[]> result = call(client, contract, Abi.encodeCall("tokenURI(uint256)", tokenId), MAX_TOKEN_URI_BYTES);
        if(result.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Abi.decodeString(result.get(), MAX_TOKEN_URI_BYTES));
        } catch(IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The id of the owner's token at this index (ERC-721 Enumerable). A revert or a malformed answer throws. */
    public static BigInteger tokenOfOwnerByIndex(LitVmRpcClient client, EvmAddress contract, EvmAddress owner, BigInteger index) throws IOException {
        return uint(client, contract, Abi.encodeCall("tokenOfOwnerByIndex(address,uint256)", owner, index), "tokenOfOwnerByIndex");
    }

    private static Answer supports(LitVmRpcClient client, EvmAddress contract, byte[] interfaceId) throws IOException {
        //bytes4 is left-aligned in its word
        byte[] word = new byte[32];
        System.arraycopy(interfaceId, 0, word, 0, 4);
        Optional<byte[]> result = call(client, contract, Abi.encodeCall("supportsInterface(bytes4)", (Object)word), MAX_RESULT_BYTES);
        if(result.isEmpty()) {
            return Answer.FAILED;
        }
        try {
            return Abi.decodeBool(result.get(), 0) ? Answer.YES : Answer.NO;
        } catch(IllegalArgumentException e) {
            return Answer.FAILED;
        }
    }

    private static String optionalString(LitVmRpcClient client, EvmAddress contract, String signature) throws IOException {
        Optional<byte[]> result = call(client, contract, Abi.selector(signature), MAX_RESULT_BYTES);
        if(result.isEmpty()) {
            return "";
        }
        try {
            return Abi.decodeString(result.get());
        } catch(IllegalArgumentException e) {
            return "";
        }
    }

    private static BigInteger uint(LitVmRpcClient client, EvmAddress contract, byte[] data, String function) throws IOException {
        byte[] result = client.ethCall(contract, data, MAX_RESULT_BYTES);
        try {
            return Abi.decodeUint(result, 0);
        } catch(IllegalArgumentException e) {
            throw new IOException("Invalid " + function + " result from " + contract);
        }
    }

    /**
     * The call's return data, or empty when the contract reverted or answered more than maxBytes. Anything else
     * (the network, HTTP, a malformed response, a node error that is not a revert) throws.
     */
    private static Optional<byte[]> call(LitVmRpcClient client, EvmAddress contract, byte[] data, int maxBytes) throws IOException {
        try {
            return Optional.of(client.ethCall(contract, data, maxBytes));
        } catch(LitVmRpcClient.LitVmRpcException e) {
            if(e.isRevert()) {
                return Optional.empty();
            }
            throw e;
        } catch(LitVmRpcClient.OversizedResultException e) {
            return Optional.empty();
        }
    }

    private static boolean hasCode(LitVmRpcClient client, EvmAddress contract) throws IOException {
        try {
            return client.hasCode(contract);
        } catch(IllegalArgumentException e) {
            throw new IOException("eth_getCode returned an invalid response", e);
        }
    }
}
