package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptChunk;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class RunestoneTest {
    @Test
    public void leb128() {
        Assertions.assertArrayEquals(Utils.hexToBytes("00"), Runestone.leb128(BigInteger.ZERO));
        Assertions.assertArrayEquals(Utils.hexToBytes("7f"), Runestone.leb128(BigInteger.valueOf(127)));
        Assertions.assertArrayEquals(Utils.hexToBytes("8001"), Runestone.leb128(BigInteger.valueOf(128)));
        Assertions.assertArrayEquals(Utils.hexToBytes("f403"), Runestone.leb128(BigInteger.valueOf(500)));
        Assertions.assertEquals(19, Runestone.leb128(BigInteger.TWO.pow(128).subtract(BigInteger.ONE)).length);
        Assertions.assertThrows(IllegalArgumentException.class, () -> Runestone.leb128(BigInteger.TWO.pow(128)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Runestone.leb128(BigInteger.ONE.negate()));
    }

    @Test
    public void pointerThenBodyWithOneEdict() {
        Script script = Runestone.script(List.of(new Runestone.Edict(RuneId.parse("111:1"), BigInteger.valueOf(500), 0)), 1);
        //OP_RETURN OP_13, push of 8 bytes: pointer (16 01), body tag (00), edict 111 1 500 0 (6f 01 f4 03 00)
        Assertions.assertEquals("6a5d081601006f01f40300", Utils.bytesToHex(script.getProgram()));
    }

    @Test
    public void edictsAreSortedAndDeltaEncoded() {
        Script script = Runestone.script(List.of(
                new Runestone.Edict(new RuneId(200, 3), BigInteger.ONE, 2),
                new Runestone.Edict(new RuneId(111, 1), BigInteger.TEN, 0),
                new Runestone.Edict(new RuneId(111, 4), BigInteger.TWO, 1)), null);
        //No pointer; body: 111:1 -> (6f 01), 111:4 -> block delta 0, tx delta 3 (00 03), 200:3 -> block delta 89, tx absolute 3 (59 03)
        Assertions.assertEquals("6a5d0d" + "00" + "6f010a00" + "00030201" + "59030102", Utils.bytesToHex(script.getProgram()));
    }

    @Test
    public void pointerOnlyHasNoBody() {
        Assertions.assertEquals("6a5d021602", Utils.bytesToHex(Runestone.script(List.of(), 2).getProgram()));
    }

    @Test
    public void payloadOverOnePushIsSplitAt520() {
        List<Runestone.Edict> edicts = new ArrayList<>();
        for(int i = 0; i < 200; i++) {
            edicts.add(new Runestone.Edict(new RuneId(1000 + i, i), BigInteger.TWO.pow(100), i % 3));
        }
        Script script = Runestone.script(edicts, 1);
        List<ScriptChunk> chunks = script.getChunks();
        Assertions.assertTrue(chunks.size() > 3);
        int total = 0;
        for(ScriptChunk chunk : chunks.subList(2, chunks.size())) {
            Assertions.assertTrue(chunk.getData().length <= 520);
            total += chunk.getData().length;
        }
        Assertions.assertEquals(Runestone.payload(edicts, 1).length, total);
    }

    @Test
    public void runeIdParse() {
        Assertions.assertEquals(new RuneId(111, 1), RuneId.parse("111:1"));
        Assertions.assertEquals("111:1", new RuneId(111, 1).toString());
        Assertions.assertThrows(IllegalArgumentException.class, () -> RuneId.parse("111"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> RuneId.parse("a:1"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> RuneId.parse("-1:1"));
    }
}
