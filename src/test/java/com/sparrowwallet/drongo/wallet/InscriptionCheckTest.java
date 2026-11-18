package com.sparrowwallet.drongo.wallet;

import com.sparrowwallet.drongo.ordinals.track.Found;
import com.sparrowwallet.drongo.ordinals.track.Piece;
import com.sparrowwallet.drongo.ordinals.track.UnitRange;
import com.sparrowwallet.drongo.ordinals.track.Verdict;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class InscriptionCheckTest {
    private static final Sha256Hash A = Sha256Hash.wrap("52b559ee298a9e57b1b6e743a53e68c9d9a7e75da6b090cfdd8e2bb260f3636e");
    private static final Sha256Hash B = Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000b00");

    @Test
    public void foundRoundTrips() {
        List<Found> found = List.of(new Found(A + "i0", 5_000, 1234L, A, 2_500_000, 1_700_000_000L),
                new Found(B + "i3", -1, null, B, 0, 0));
        Assertions.assertEquals(found, InscriptionCheck.decodeFound(InscriptionCheck.encodeFound(found)));
        Assertions.assertEquals(List.of(), InscriptionCheck.decodeFound(InscriptionCheck.encodeFound(List.of())));
        Assertions.assertEquals(List.of(), InscriptionCheck.decodeFound(null));
    }

    @Test
    public void unknownGenesisIdRoundTrips() {
        List<Found> found = List.of(new Found("?i0", 0, null, A, 7, 8));
        Assertions.assertEquals(found, InscriptionCheck.decodeFound(InscriptionCheck.encodeFound(found)));
    }

    @Test
    public void piecesRoundTrip() {
        List<Piece> pieces = List.of(new Piece(A, 0, new UnitRange(0, 10_000), 0), new Piece(B, Piece.FEE, new UnitRange(500, 1_000), 5_000_000_000L));
        Assertions.assertEquals(pieces, InscriptionCheck.decodePieces(InscriptionCheck.encodePieces(pieces)));
        Assertions.assertEquals(List.of(), InscriptionCheck.decodePieces(InscriptionCheck.encodePieces(List.of())));
    }

    @Test
    public void copyIsDeep() {
        List<Found> found = new ArrayList<>(List.of(new Found(A + "i0", 0, null, A, 1, 2)));
        InscriptionCheck check = new InscriptionCheck(A, 1, Verdict.INSCRIBED, InscriptionCheck.Source.BUILT_IN, false, found, null, B, List.of());
        InscriptionCheck copy = check.copy();
        found.clear();
        Assertions.assertEquals(1, copy.found().size());
        Assertions.assertEquals(A + ":1", copy.outpoint());
    }

    @Test
    public void walletCopiesItsChecks() {
        Wallet wallet = new Wallet("copy", com.sparrowwallet.drongo.policy.PolicyType.SINGLE_HD, com.sparrowwallet.drongo.protocol.ScriptType.P2WPKH);
        wallet.getInscriptionChecks().add(new InscriptionCheck(A, 0, Verdict.CLEAN, InscriptionCheck.Source.PUBLIC, false, List.of(), null, B, List.of()));
        Assertions.assertEquals(wallet.getInscriptionChecks(), wallet.copy().getInscriptionChecks());
    }
}
