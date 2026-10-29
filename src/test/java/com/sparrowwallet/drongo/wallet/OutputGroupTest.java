package com.sparrowwallet.drongo.wallet;

import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class OutputGroupTest {
    private static BlockTransactionHashIndex utxo(int index) {
        return new BlockTransactionHashIndex(Sha256Hash.ZERO_HASH, 100, null, null, index, 1000000);
    }

    /**
     * Group by address put every coin at an address into one group, so 1,292 mined BELLS coins at one address built a
     * transaction over the 400,000 weight-unit standard limit. A group now stops at half that.
     */
    @Test
    public void aGroupStopsTakingInputsAtHalfTheStandardTransactionWeight() {
        long p2pkhInputWeight = 592;
        OutputGroup group = new OutputGroup(ScriptType.P2PKH, 200, p2pkhInputWeight, 1000, 1000);
        int added = 0;
        while(group.canAdd()) {
            group.add(utxo(added++), true, false);
        }
        Assertions.assertEquals(OutputGroup.MAX_INPUT_WEIGHT / p2pkhInputWeight, added);
        Assertions.assertTrue(added * p2pkhInputWeight <= OutputGroup.MAX_INPUT_WEIGHT);
        Assertions.assertEquals(200_000, OutputGroup.MAX_INPUT_WEIGHT);
    }
}
