package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Utils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

public class RuneNameTest {
    @Test
    public void runeNameRoundTrip() {
        RuneName name = RuneName.parse("TESTRUNE•LITECOIN");
        Assertions.assertEquals(128, name.spacers());
        Assertions.assertEquals("9bdfeffcc5efd6f7a2dd1c", Utils.bytesToHex(Runestone.leb128(name.value())));
        Assertions.assertEquals("TESTRUNE•LITECOIN", name.toString());
        Assertions.assertEquals("TESTRUNELITECOIN", name.letters());
        Assertions.assertEquals(name, RuneName.parse("TESTRUNE.LITECOIN"));

        Assertions.assertEquals(BigInteger.ZERO, RuneName.parse("A").value());
        Assertions.assertEquals(BigInteger.valueOf(25), RuneName.parse("Z").value());
        Assertions.assertEquals(BigInteger.valueOf(26), RuneName.parse("AA").value());
        Assertions.assertEquals(BigInteger.valueOf(27), RuneName.parse("AB").value());
        Assertions.assertEquals("AB", new RuneName(BigInteger.valueOf(27), 0).toString());
        Assertions.assertEquals("A•B•C", RuneName.parse("A.B.C").toString());
    }

    @Test
    public void invalidNamesRefused() {
        for(String bad : new String[] {"", "a", "A1", "•A", "A•", "A••B", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> RuneName.parse(bad), bad);
        }
    }

    @Test
    public void commitmentTrimsZeros() {
        Assertions.assertEquals("9bef9b5f7c5befa22e07", Utils.bytesToHex(RuneName.parse("TESTRUNELITECOIN").commitment()));
        Assertions.assertEquals(0, RuneName.parse("A").commitment().length);
        Assertions.assertEquals("01", Utils.bytesToHex(RuneName.parse("B").commitment()));
        Assertions.assertEquals("0001", Utils.bytesToHex(new RuneName(BigInteger.valueOf(256), 0).commitment()));
    }
}
