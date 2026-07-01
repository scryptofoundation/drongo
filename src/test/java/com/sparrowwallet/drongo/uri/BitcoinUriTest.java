package com.sparrowwallet.drongo.uri;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Locale;

public class BitcoinUriTest {
    @Test
    public void testSamourai() throws BitcoinURIParseException {
        String uri = "litecoin:ltc1qt4nrm47695ywdg9n30n68jarmxrjnkfm8dqpa9?amount=0,001";
        BitcoinURI bitcoinURI = new BitcoinURI(uri);

        Assertions.assertEquals("ltc1qt4nrm47695ywdg9n30n68jarmxrjnkfm8dqpa9".toLowerCase(Locale.ROOT), bitcoinURI.getAddress().toString());
        Assertions.assertEquals(Long.valueOf(100000), bitcoinURI.getAmount());
    }
}
