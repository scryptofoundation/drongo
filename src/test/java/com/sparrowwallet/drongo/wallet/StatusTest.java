package com.sparrowwallet.drongo.wallet;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;

public class StatusTest {
    private static BlockTransactionHashIndex txo(Status status) {
        BlockTransactionHashIndex txo = new BlockTransactionHashIndex(Sha256Hash.ZERO_HASH, 100, new Date(), 0L, 0, 10000);
        txo.setStatus(status);
        return txo;
    }

    /** The wallet database stores status as the enum ordinal, so existing values keep their positions. */
    @Test
    public void ordinalsArePersistenceStable() {
        Assertions.assertEquals(0, Status.FROZEN.ordinal());
        Assertions.assertEquals(1, Status.PROTECTED.ordinal());
        Assertions.assertEquals(2, Status.RELEASED.ordinal());
    }

    @Test
    public void onlyNullAndReleasedAreSpendable() {
        Assertions.assertTrue(Status.isSpendable(null));
        Assertions.assertTrue(Status.isSpendable(Status.RELEASED));
        Assertions.assertFalse(Status.isSpendable(Status.FROZEN));
        Assertions.assertFalse(Status.isSpendable(Status.PROTECTED));
    }

    @Test
    public void frozenFilterSkipsProtected() {
        FrozenTxoFilter filter = new FrozenTxoFilter();
        Assertions.assertTrue(filter.isEligible(txo(null)));
        Assertions.assertTrue(filter.isEligible(txo(Status.RELEASED)));
        Assertions.assertFalse(filter.isEligible(txo(Status.FROZEN)));
        Assertions.assertFalse(filter.isEligible(txo(Status.PROTECTED)));
    }

    @Test
    public void copyKeepsStatus() {
        Assertions.assertEquals(Status.PROTECTED, txo(Status.PROTECTED).copy().getStatus());
        Assertions.assertNull(txo(null).copy().getStatus());
    }
}
