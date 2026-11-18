package com.sparrowwallet.drongo.wallet;

import com.sparrowwallet.drongo.ordinals.track.Found;
import com.sparrowwallet.drongo.ordinals.track.Piece;
import com.sparrowwallet.drongo.ordinals.track.UnitRange;
import com.sparrowwallet.drongo.ordinals.track.Verdict;
import com.sparrowwallet.drongo.protocol.Sha256Hash;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether one wallet coin carries an inscription, as last decided: the verdict, who decided it, whether an indexer
 * reports runes on it, the inscriptions found, why it is unchecked, the block the coin was confirmed in when decided
 * (null while unconfirmed), and the pieces an unfinished trace left open.
 */
public record InscriptionCheck(Sha256Hash txid, int vout, Verdict verdict, Source source, boolean runes, List<Found> found, String reason,
                               Sha256Hash blockHash, List<Piece> open) {
    public enum Source {
        OWN, PUBLIC, YOUR_OWN, BUILT_IN
    }

    public InscriptionCheck {
        found = found == null ? List.of() : List.copyOf(found);
        open = open == null ? List.of() : List.copyOf(open);
    }

    public String outpoint() {
        return txid + ":" + vout;
    }

    public InscriptionCheck copy() {
        return new InscriptionCheck(txid, vout, verdict, source, runes, found, reason, blockHash, open);
    }

    /** Entries joined by ';', each id,offset,number (or -),mintTxid (or -),height,time. */
    public static String encodeFound(List<Found> found) {
        List<String> entries = new ArrayList<>();
        for(Found f : found) {
            entries.add(String.join(",", f.inscriptionId(), Long.toString(f.offset()), f.number() == null ? "-" : Long.toString(f.number()),
                    f.mintTxid() == null ? "-" : f.mintTxid().toString(), Integer.toString(f.mintHeight()), Long.toString(f.mintTime())));
        }
        return String.join(";", entries);
    }

    public static List<Found> decodeFound(String encoded) {
        List<Found> found = new ArrayList<>();
        if(encoded == null || encoded.isEmpty()) {
            return found;
        }
        for(String entry : encoded.split(";")) {
            String[] f = entry.split(",");
            found.add(new Found(f[0], Long.parseLong(f[1]), f[2].equals("-") ? null : Long.parseLong(f[2]), f[3].equals("-") ? null : Sha256Hash.wrap(f[3]),
                    Integer.parseInt(f[4]), Long.parseLong(f[5])));
        }
        return found;
    }

    /** Entries joined by ';', each txid:vout:start-end:coinOffset. */
    public static String encodePieces(List<Piece> pieces) {
        List<String> entries = new ArrayList<>();
        for(Piece piece : pieces) {
            entries.add(piece.txid() + ":" + piece.vout() + ":" + piece.range().start() + "-" + piece.range().end() + ":" + piece.coinOffset());
        }
        return String.join(";", entries);
    }

    public static List<Piece> decodePieces(String encoded) {
        List<Piece> pieces = new ArrayList<>();
        if(encoded == null || encoded.isEmpty()) {
            return pieces;
        }
        for(String entry : encoded.split(";")) {
            String[] p = entry.split(":");
            String[] range = p[2].split("-");
            pieces.add(new Piece(Sha256Hash.wrap(p[0]), Integer.parseInt(p[1]), new UnitRange(Long.parseLong(range[0]), Long.parseLong(range[1])),
                    Long.parseLong(p[3])));
        }
        return pieces;
    }
}
