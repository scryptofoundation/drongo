package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An EIP-1559 (type 2) EVM transaction: 0x02 || rlp([chainId, nonce, maxPriorityFeePerGas, maxFeePerGas,
 * gasLimit, to, value, data, accessList, yParity, r, s]). The chain id is part of what gets signed, so a
 * transaction signed for LiteForge cannot be replayed on any other chain.
 */
public final class Eip1559Transaction {
    private static final byte TYPE = 0x02;

    private final long chainId;
    private final BigInteger nonce;
    private final BigInteger maxPriorityFeePerGas;
    private final BigInteger maxFeePerGas;
    private final BigInteger gasLimit;
    private final EvmAddress to;
    private final BigInteger value;
    private final byte[] data;

    public Eip1559Transaction(long chainId, BigInteger nonce, BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas,
                              BigInteger gasLimit, EvmAddress to, BigInteger value, byte[] data) {
        if(to == null) {
            throw new IllegalArgumentException("A recipient is required (contract creation is not supported)");
        }
        if(maxPriorityFeePerGas.compareTo(maxFeePerGas) > 0) {
            throw new IllegalArgumentException("maxPriorityFeePerGas cannot exceed maxFeePerGas");
        }
        this.chainId = chainId;
        this.nonce = nonce;
        this.maxPriorityFeePerGas = maxPriorityFeePerGas;
        this.maxFeePerGas = maxFeePerGas;
        this.gasLimit = gasLimit;
        this.to = to;
        this.value = value;
        this.data = data == null ? new byte[0] : data.clone();
    }

    private List<Object> unsignedFields() {
        List<Object> fields = new ArrayList<>();
        fields.add(BigInteger.valueOf(chainId));
        fields.add(nonce);
        fields.add(maxPriorityFeePerGas);
        fields.add(maxFeePerGas);
        fields.add(gasLimit);
        fields.add(to.getBytes());
        fields.add(value);
        fields.add(data);
        fields.add(List.of());
        return fields;
    }

    public byte[] signingHash() {
        return Keccak.keccak256(Rlp.concat(new byte[] {TYPE}, Rlp.encode(unsignedFields())));
    }

    /** The most this transaction can cost the sender: value plus gasLimit at the full maxFeePerGas. */
    public BigInteger maxCost() {
        return value.add(gasLimit.multiply(maxFeePerGas));
    }

    public Signed sign(ECKey key) {
        byte[] hash = signingHash();
        EvmSigner.Signature signature = EvmSigner.sign(hash, key);
        if(signature.recoveryId() > 1) {
            //Recovery ids 2 and 3 need r >= n, which happens with negligible probability; type 2 cannot express them.
            throw new IllegalStateException("Unsupported recovery id " + signature.recoveryId());
        }
        List<Object> fields = unsignedFields();
        fields.add(BigInteger.valueOf(signature.recoveryId()));
        fields.add(signature.r());
        fields.add(signature.s());
        byte[] raw = Rlp.concat(new byte[] {TYPE}, Rlp.encode(fields));
        return new Signed(raw, Keccak.keccak256(raw), EvmSigner.recoverSigner(hash, signature));
    }

    public long getChainId() { return chainId; }
    public BigInteger getNonce() { return nonce; }
    public BigInteger getMaxPriorityFeePerGas() { return maxPriorityFeePerGas; }
    public BigInteger getMaxFeePerGas() { return maxFeePerGas; }
    public BigInteger getGasLimit() { return gasLimit; }
    public EvmAddress getTo() { return to; }
    public BigInteger getValue() { return value; }
    public byte[] getData() { return data == null ? null : data.clone(); }

    /** A signed transaction ready to broadcast, with its hash and the sender recovered from the signature. */
    public record Signed(byte[] raw, byte[] hash, EvmAddress from) {
        public String rawHex() {
            return "0x" + Utils.bytesToHex(raw).toLowerCase(Locale.ROOT);
        }

        public String hashHex() {
            return "0x" + Utils.bytesToHex(hash).toLowerCase(Locale.ROOT);
        }
    }
}
