package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An inscription to mint, and the ord envelope that carries it in the reveal's tapscript:
 * {@code <key> OP_CHECKSIG OP_FALSE OP_IF "ord" <tag 1> <content type> [<tag 13> <rune commitment>] OP_0 <body> OP_ENDIF}.
 * Every field is a plain data push, written the way ord-litecoin writes it (tags as one-byte pushes, not OP_1..OP_16),
 * and the body is split into pushes of at most 520 bytes.
 */
public record Inscription(String contentType, byte[] body, byte[] runeCommitment) {
    /** Litecoin Core's standard transaction weight; a heavier reveal is not relayed. */
    public static final int MAX_REVEAL_WEIGHT = 400_000;
    public static final String TEXT = "text/plain;charset=utf-8";
    private static final byte[] PROTOCOL = "ord".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TAG_CONTENT_TYPE = {1};
    private static final byte[] TAG_RUNE = {13};
    private static final int MAX_PUSH = 520;
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg", "gif", "image/gif", "webp", "image/webp",
            "svg", "image/svg+xml", "json", "application/json", "txt", TEXT);

    public Inscription {
        if(contentType == null || contentType.isEmpty()) {
            throw new IllegalArgumentException("An inscription needs a content type");
        }
        if(body == null || body.length == 0) {
            throw new IllegalArgumentException("An inscription cannot be empty");
        }
    }

    public static Inscription text(String text) {
        return new Inscription(TEXT, text == null ? null : text.getBytes(StandardCharsets.UTF_8), null);
    }

    public static String contentTypeFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    public Script revealScript(ECKey xOnlyKey) {
        List<ScriptChunk> chunks = new ArrayList<>();
        chunks.add(push(xOnlyKey.getPubKeyXCoord()));
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_CHECKSIG));
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_FALSE));
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_IF));
        chunks.add(push(PROTOCOL));
        chunks.add(push(TAG_CONTENT_TYPE));
        chunks.add(push(contentType.getBytes(StandardCharsets.UTF_8)));
        if(runeCommitment != null) {
            chunks.add(push(TAG_RUNE));
            chunks.add(push(runeCommitment));
        }
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_0));
        for(int i = 0; i < body.length; i += MAX_PUSH) {
            chunks.add(push(Arrays.copyOfRange(body, i, Math.min(body.length, i + MAX_PUSH))));
        }
        chunks.add(ScriptChunk.fromOpcode(ScriptOpCodes.OP_ENDIF));
        return new Script(chunks);
    }

    /**
     * The weight of the reveal spending the commit by this inscription's script, with a 64-byte signature and the
     * one-leaf control block, paying POSTAGE to each of the given output scripts.
     */
    public int revealWeight(List<Script> outputScripts) {
        ECKey anyKey = ECKey.fromPublicOnly(Utils.hexToBytes("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"));
        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        TransactionWitness witness = new TransactionWitness(transaction, List.of(new byte[64], revealScript(anyKey).getProgram(), new byte[33]));
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]), witness);
        for(Script outputScript : outputScripts) {
            transaction.addOutput(CollectibleTransactions.POSTAGE, outputScript);
        }
        return transaction.getWeightUnits();
    }

    /** The largest body of this content type whose reveal to one taproot output stays within the standard weight. */
    public static int maxBodyBytes(String contentType) {
        List<Script> taproot = List.of(new Script(new byte[34]));
        int low = 1;
        int high = MAX_REVEAL_WEIGHT;
        while(low < high) {
            int mid = (low + high + 1) >>> 1;
            if(new Inscription(contentType, new byte[mid], null).revealWeight(taproot) <= MAX_REVEAL_WEIGHT) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    private static ScriptChunk push(byte[] data) {
        int opcode = data.length <= 75 ? data.length : (data.length <= 0xff ? ScriptOpCodes.OP_PUSHDATA1 : ScriptOpCodes.OP_PUSHDATA2);
        return new ScriptChunk(opcode, data);
    }
}
