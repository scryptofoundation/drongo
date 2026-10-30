package com.sparrowwallet.drongo.wallet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WalletModelTest {
    @Test
    public void sparrowMobileIncludesPreviousTransactionsInQr() {
        // the phone refuses multi-input PSBTs without them, so a two-session lie about amounts cannot hide a fee
        Assertions.assertTrue(WalletModel.SPARROW_MOBILE.includeNonWitnessUtxoForQR());
        Assertions.assertFalse(WalletModel.PASSPORT.includeNonWitnessUtxoForQR());
    }

    @Test
    public void sparrowMobileIsAppendedLast() {
        // keystores store the model's ordinal, so existing values must keep theirs
        Assertions.assertEquals(45, WalletModel.ERA_WALLET.ordinal());
        Assertions.assertEquals(46, WalletModel.SPARROW_MOBILE.ordinal());
        Assertions.assertEquals(47, WalletModel.values().length);
    }

    @Test
    public void sparrowMobileDisplayAndIcon() {
        Assertions.assertEquals("Sparrow Mobile", WalletModel.SPARROW_MOBILE.toDisplayString());
        Assertions.assertEquals("sparrow", WalletModel.SPARROW_MOBILE.getType());
        Assertions.assertEquals(WalletModel.SPARROW, WalletModel.fromType("sparrow"));
    }
}
