package com.sparrowwallet.drongo.wallet;

import com.sparrowwallet.drongo.ordinals.RuneName;
import com.sparrowwallet.drongo.ordinals.Runestone;
import com.sparrowwallet.drongo.protocol.Sha256Hash;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A mint whose commit is on its way but whose reveal is not yet broadcast. It holds only what is needed to rebuild the
 * reveal from the seed and the original content, none of it secret: the taproot key's derivation path, the SHA-256 of
 * the reveal script (to check the content given to finish it), the commit outpoint and value, the content type, and
 * for an etch the etching itself. It never holds a key or a signed reveal.
 */
public class PendingMint extends Persistable {
    public enum Kind { INSCRIPTION, ETCH }

    private final Kind kind;
    private final String keyPath;
    private final byte[] scriptHash;
    private final Sha256Hash commitTxid;
    private final int commitVout;
    private final long commitValue;
    private final String contentType;
    private final Runestone.Etching etching;

    public PendingMint(Kind kind, String keyPath, byte[] scriptHash, Sha256Hash commitTxid, int commitVout, long commitValue, String contentType, Runestone.Etching etching) {
        if((kind == Kind.ETCH) != (etching != null)) {
            throw new IllegalArgumentException("An etch, and only an etch, has an etching");
        }
        this.kind = kind;
        this.keyPath = keyPath;
        this.scriptHash = scriptHash.clone();
        this.commitTxid = commitTxid;
        this.commitVout = commitVout;
        this.commitValue = commitValue;
        this.contentType = contentType;
        this.etching = etching;
    }

    public Kind getKind() {
        return kind;
    }

    public String getKeyPath() {
        return keyPath;
    }

    public byte[] getScriptHash() {
        return scriptHash.clone();
    }

    public Sha256Hash getCommitTxid() {
        return commitTxid;
    }

    public int getCommitVout() {
        return commitVout;
    }

    public long getCommitValue() {
        return commitValue;
    }

    public String getContentType() {
        return contentType;
    }

    public Runestone.Etching getEtching() {
        return etching;
    }

    public PendingMint copy() {
        PendingMint copy = new PendingMint(kind, keyPath, scriptHash, commitTxid, commitVout, commitValue, contentType, etching);
        copy.setId(getId());
        return copy;
    }

    /** The etching as stored in the wallet file: key=value pairs separated by ';', with the spaced rune name. */
    public static String encodeEtching(Runestone.Etching etching) {
        if(etching == null) {
            return null;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", etching.name());
        fields.put("divisibility", etching.divisibility());
        fields.put("symbol", etching.symbol());
        fields.put("premine", etching.premine());
        Runestone.Terms terms = etching.terms();
        if(terms != null) {
            fields.put("terms", true);
            fields.put("amount", terms.amount());
            fields.put("cap", terms.cap());
            fields.put("heightStart", terms.heightStart());
            fields.put("heightEnd", terms.heightEnd());
            fields.put("offsetStart", terms.offsetStart());
            fields.put("offsetEnd", terms.offsetEnd());
        }
        fields.put("turbo", etching.turbo());
        StringBuilder encoded = new StringBuilder();
        fields.forEach((key, value) -> {
            if(value != null) {
                encoded.append(encoded.isEmpty() ? "" : ";").append(key).append('=').append(value);
            }
        });
        return encoded.toString();
    }

    public static Runestone.Etching decodeEtching(String encoded) {
        if(encoded == null) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for(String field : encoded.split(";")) {
            int equals = field.indexOf('=');
            fields.put(field.substring(0, equals), field.substring(equals + 1));
        }
        Runestone.Terms terms = null;
        if(fields.containsKey("terms")) {
            terms = new Runestone.Terms(bigInteger(fields.get("amount")), bigInteger(fields.get("cap")), longValue(fields.get("heightStart")),
                    longValue(fields.get("heightEnd")), longValue(fields.get("offsetStart")), longValue(fields.get("offsetEnd")));
        }
        return new Runestone.Etching(RuneName.parse(fields.get("name")), Integer.parseInt(fields.get("divisibility")),
                fields.containsKey("symbol") ? Integer.valueOf(fields.get("symbol")) : null, bigInteger(fields.get("premine")), terms,
                Boolean.parseBoolean(fields.get("turbo")));
    }

    private static BigInteger bigInteger(String value) {
        return value == null ? null : new BigInteger(value);
    }

    private static Long longValue(String value) {
        return value == null ? null : Long.valueOf(value);
    }
}
