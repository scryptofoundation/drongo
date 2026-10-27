package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * A 20-byte EVM account address, shown with the EIP-55 mixed-case checksum.
 *
 * Kept entirely separate from the UTXO {@code Address} hierarchy: an EVM address has no script type and
 * no network version byte, and must never be parsed or offered where a Litecoin address is expected.
 */
public final class EvmAddress {
    private final byte[] bytes;

    private EvmAddress(byte[] bytes) {
        if(bytes.length != 20) {
            throw new IllegalArgumentException("EVM addresses are 20 bytes, not " + bytes.length);
        }
        this.bytes = bytes.clone();
    }

    public static EvmAddress fromBytes(byte[] bytes) {
        return new EvmAddress(bytes);
    }

    /** The address of a public key: the last 20 bytes of keccak256 of the uncompressed point without its 0x04 prefix. */
    public static EvmAddress fromPublicKey(ECKey key) {
        byte[] pub = key.getPubKey(false);
        byte[] hash = Keccak.keccak256(Arrays.copyOfRange(pub, 1, pub.length));
        return new EvmAddress(Arrays.copyOfRange(hash, 12, 32));
    }

    /** The address a contract created by sender with this nonce gets (CREATE): the last 20 bytes of keccak256(rlp([sender, nonce])). */
    public static EvmAddress contractAddress(EvmAddress sender, java.math.BigInteger nonce) {
        byte[] hash = Keccak.keccak256(Rlp.encode(java.util.List.of(sender.getBytes(), nonce)));
        return new EvmAddress(Arrays.copyOfRange(hash, 12, 32));
    }

    /**
     * Parses a 0x-prefixed (or bare) 40-hex-digit address. All-lowercase and all-uppercase addresses carry no
     * checksum and are accepted as-is; a mixed-case address must match its EIP-55 checksum exactly, because a
     * mismatch almost always means a mistyped address.
     */
    public static EvmAddress fromString(String address) {
        String hex = address.trim();
        if(hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        if(!hex.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("Not an EVM address: " + address);
        }
        EvmAddress parsed = new EvmAddress(Utils.hexToBytes(hex.toLowerCase(Locale.ROOT)));
        boolean mixedCase = !hex.equals(hex.toLowerCase(Locale.ROOT)) && !hex.equals(hex.toUpperCase(Locale.ROOT));
        if(mixedCase && !parsed.toString().substring(2).equals(hex)) {
            throw new IllegalArgumentException("EVM address checksum does not match (EIP-55): " + address);
        }
        return parsed;
    }

    public static boolean isValid(String address) {
        try {
            fromString(address);
            return true;
        } catch(IllegalArgumentException e) {
            return false;
        }
    }

    public byte[] getBytes() {
        return bytes.clone();
    }

    /** The EIP-55 checksummed form, 0x-prefixed. */
    @Override
    public String toString() {
        String lower = Utils.bytesToHex(bytes).toLowerCase(Locale.ROOT);
        byte[] hash = Keccak.keccak256(lower.getBytes(StandardCharsets.US_ASCII));
        StringBuilder sb = new StringBuilder("0x");
        for(int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            int nibble = (hash[i / 2] >> (i % 2 == 0 ? 4 : 0)) & 0x0f;
            sb.append(Character.isLetter(c) && nibble >= 8 ? Character.toUpperCase(c) : c);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EvmAddress other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
