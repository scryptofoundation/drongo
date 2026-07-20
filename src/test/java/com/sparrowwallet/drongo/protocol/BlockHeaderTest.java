package com.sparrowwallet.drongo.protocol;

import com.sparrowwallet.drongo.Utils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * Verifies that BlockHeader parsing handles merge-mined (AuxPoW) chains such as Dogecoin, Pepecoin and
 * Luckycoin. Their serialised block headers are variable length - an 80-byte base header followed by the
 * parent coinbase, merkle branches and parent header - and Electrum servers return the full form on the
 * subscribe channel regardless of protocol negotiation. A client that assumed a fixed 80 bytes, or that
 * hashed the whole buffer, would misread the header. drongo reads only the base 80 bytes and ignores the
 * rest, so those chains work without special handling.
 *
 * Real on-chain vector: Dogecoin AuxPoW block 371337 (the first AuxPoW block), raw header taken verbatim
 * from the chain. Its version 0x00620102 has the AuxPoW bit (0x100) set, and at byte offset 80 the parent
 * coinbase transaction begins (0x01000000...), confirming the base header is the first 80 bytes.
 */
public class BlockHeaderTest {
    //First 80 bytes of block 371337's raw serialisation, i.e. the base header.
    private static final String BASE_HEADER_HEX =
            "020162000d6f03470d329026cd1fc720c0609cd378ca8691a117bd1aa46f01fb09b1a846" +
            "8a15bf6f0b0e83f2e5036684169eafb9406468d4f075c999fb5b2a78fbb827ee41fb11548441361b00000000";
    //The AuxPoW data that follows the base header on-chain (here, the start of the parent coinbase tx).
    private static final String AUXPOW_TRAILER_HEX = "0100000001000000000000000000000000";

    private static final String BLOCK_HASH = "60323982f9c5ff1b5a954eac9dc1269352835f47c2c5222691d80f0d50dcf053";
    private static final long VERSION = 0x00620102L;
    private static final Sha256Hash PREV_BLOCK = Sha256Hash.wrap("46a8b109fb016fa41abd17a19186ca78d39c60c020c71fcd2690320d47036f0d");
    private static final Sha256Hash MERKLE_ROOT = Sha256Hash.wrap("ee27b8fb782a5bfb99c975f0d4686440b9af9e16846603e5f2830e0b6fbf158a");

    private static Sha256Hash blockHash(byte[] header80) {
        return Sha256Hash.wrapReversed(Sha256Hash.hashTwice(header80, 0, header80.length));
    }

    @Test
    public void testBaseHeaderMatchesRealDogecoinBlock() {
        byte[] base80 = Utils.hexToBytes(BASE_HEADER_HEX);
        Assertions.assertEquals(80, base80.length);

        BlockHeader parsed = new BlockHeader(base80);
        Assertions.assertEquals(80, parsed.getMessageSize());
        //The block hash is the double-SHA256 of the 80 base bytes; matching the published hash confirms
        //every field and byte order was read correctly.
        Assertions.assertEquals(BLOCK_HASH, blockHash(parsed.bitcoinSerialize()).toString());
        Assertions.assertEquals(VERSION, parsed.getVersion());
        Assertions.assertEquals(PREV_BLOCK, parsed.getPrevBlockHash());
        Assertions.assertEquals(MERKLE_ROOT, parsed.getMerkleRoot());
        Assertions.assertEquals(0L, parsed.getNonce());
        //Re-serialisation is byte-identical to the input.
        Assertions.assertArrayEquals(base80, parsed.bitcoinSerialize());
    }

    @Test
    public void testAuxPowTrailingBytesIgnored() {
        byte[] base80 = Utils.hexToBytes(BASE_HEADER_HEX);

        //A real AuxPoW header is the base 80 bytes followed by parent-chain data. Build one from the real
        //trailer plus non-zero padding out to the ~882 bytes Dogecoin servers return, and prove none of it
        //leaks into the parsed header.
        byte[] auxPowHeader = Utils.hexToBytes(BASE_HEADER_HEX + AUXPOW_TRAILER_HEX);
        auxPowHeader = Arrays.copyOf(auxPowHeader, 882);
        for(int i = Utils.hexToBytes(BASE_HEADER_HEX + AUXPOW_TRAILER_HEX).length; i < auxPowHeader.length; i++) {
            auxPowHeader[i] = (byte)(i & 0xff);
        }

        BlockHeader parsed = new BlockHeader(auxPowHeader);
        Assertions.assertEquals(80, parsed.getMessageSize(), "only the 80-byte base header should be consumed");
        Assertions.assertArrayEquals(base80, parsed.bitcoinSerialize(), "re-serialisation must drop AuxPoW trailing data");
        Assertions.assertEquals(BLOCK_HASH, blockHash(parsed.bitcoinSerialize()).toString(), "block hash must be over the base header only");
        Assertions.assertEquals(VERSION, parsed.getVersion());
        Assertions.assertEquals(PREV_BLOCK, parsed.getPrevBlockHash());
    }
}
