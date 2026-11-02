package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.protocol.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides, without an indexer, whether a wallet output may carry an inscription, runes or dunes. Two hints:
 * <ul>
 * <li>The transaction that created it writes one: an ord envelope in a witness (Litecoin, Bellscoin), a doginals-style
 * envelope in a scriptSig (Dogecoin, Pepecoin, Junkcoin, early Bellscoin), a runestone or a dunestone. Every output of
 * such a transaction but its OP_RETURNs is flagged, since a pointer or edict can send to any.</li>
 * <li>Its value is the coin's usual inscription postage. An inscription moved on by others leaves no trace in the
 * transaction that delivers it, so the value is the only local hint left.</li>
 * </ul>
 * Both err towards flagging: a flagged output is only held back from automatic selection. Dingocoin NFTs need no rule:
 * their link outputs pay platform-held asset addresses, so no coin of the user carries one.
 */
public final class CarrierScan {
    private static final byte[] ORD = "ord".getBytes(StandardCharsets.US_ASCII);
    //OP_RETURN, push 1, "D": dunes (ord-with-dunes)
    private static final byte[] DUNESTONE = {0x6a, 0x01, 0x44};

    //ord-litecoin writes 10000; doginals.js and its forks write 100000; Bellscoin's Taproot inscriptions 1000, its
    //earlier P2SH ones 100000
    private static final Map<Coin, Set<Long>> POSTAGE = Map.of(
            Coin.LITECOIN, Set.of(10000L),
            Coin.BELLSCOIN, Set.of(1000L, 100000L),
            Coin.DOGECOIN, Set.of(100000L),
            Coin.PEPECOIN, Set.of(100000L),
            Coin.JUNKCOIN, Set.of(100000L));

    private CarrierScan() {
    }

    /**
     * True if the output may carry something. The creating transaction may be null when it is not at hand, leaving the
     * value as the only hint.
     */
    public static boolean mayCarry(Coin coin, Transaction creatingTx, int index, long value) {
        if(creatingTx != null) {
            if(index < creatingTx.getOutputs().size() && isOpReturn(creatingTx.getOutputs().get(index))) {
                return false;
            }
            if(marksOutputs(creatingTx)) {
                return true;
            }
        }
        return postage(coin).contains(value);
    }

    /** The values this coin's inscription tools put on an inscribed output; empty when it has none. */
    public static Set<Long> postage(Coin coin) {
        return POSTAGE.getOrDefault(coin, Set.of());
    }

    /** True if the transaction writes an inscription, runes or dunes. */
    public static boolean marksOutputs(Transaction tx) {
        return OrdinalsScan.hasEnvelope(tx) || hasScriptSigEnvelope(tx) || OrdinalsScan.hasRunestone(tx) || hasDunestone(tx);
    }

    /**
     * True if an input's scriptSig is a doginals-style envelope: it starts with a push of "ord", or (for the later
     * transactions of a long inscription) it ends with the partial lock script, pubkey CHECKSIGVERIFY, OP_DROPs, OP_TRUE.
     */
    public static boolean hasScriptSigEnvelope(Transaction tx) {
        for(TransactionInput input : tx.getInputs()) {
            List<ScriptChunk> chunks;
            try {
                chunks = input.getScriptSig().getChunks();
            } catch(Exception e) {
                continue;
            }
            if(chunks.isEmpty()) {
                continue;
            }
            byte[] first = chunks.getFirst().getData();
            if(first != null && Arrays.equals(first, ORD)) {
                return true;
            }
            byte[] last = chunks.getLast().getData();
            if(last != null && isPartialLockScript(last)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPartialLockScript(byte[] script) {
        if(script.length < 36 || script[0] != 0x21 || (script[34] & 0xff) != 0xad || script[script.length - 1] != 0x51) {
            return false;
        }
        for(int i = 35; i < script.length - 1; i++) {
            if(script[i] != 0x75) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasDunestone(Transaction tx) {
        for(TransactionOutput output : tx.getOutputs()) {
            byte[] script = output.getScriptBytes();
            if(script.length >= DUNESTONE.length && Arrays.equals(Arrays.copyOf(script, DUNESTONE.length), DUNESTONE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOpReturn(TransactionOutput output) {
        byte[] script = output.getScriptBytes();
        return script.length > 0 && script[0] == 0x6a;
    }
}
