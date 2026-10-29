package com.sparrowwallet.drongo.protocol;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
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
    public static final String GENESIS_HEADER_HEX = "0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c";
    public static final String BLOCK_800000_HEADER_HEX = "00601d3455bb9fbd966b3ea2dc42d0c22722e4c0c1729fad17210100000000000000000055087fab0c8f3f89f8bcfd4df26c504d81b0a88e04907161838c0c53001af09135edbd64943805175e955e06";

    @Test
    public void testDecodeCompactBits() {
        Assertions.assertEquals(new BigInteger("ffff", 16).shiftLeft(208), Utils.decodeCompactBits(0x1d00ffffL));
        Assertions.assertEquals(new BigInteger("053894", 16).shiftLeft(160), Utils.decodeCompactBits(0x17053894L));
        Assertions.assertEquals(BigInteger.valueOf(0x123456), Utils.decodeCompactBits(0x03123456L));
        Assertions.assertEquals(BigInteger.valueOf(0x1234), Utils.decodeCompactBits(0x02123456L));
        Assertions.assertTrue(Utils.decodeCompactBits(0x1d80ffffL).signum() < 0);
    }

    @Test
    public void testEncodeCompactBits() {
        //Bitcoin Core's arith_uint256 round trip vectors, as lifted by Electrum: the compact form a target encodes back to is not always the one it decoded from
        Assertions.assertEquals(0x00000000L, Utils.encodeCompactBits(Utils.decodeCompactBits(0x00123456L)));
        Assertions.assertEquals(0x00000000L, Utils.encodeCompactBits(Utils.decodeCompactBits(0x01003456L)));
        Assertions.assertEquals(0x00000000L, Utils.encodeCompactBits(Utils.decodeCompactBits(0x02000056L)));
        Assertions.assertEquals(0x00000000L, Utils.encodeCompactBits(Utils.decodeCompactBits(0x03000000L)));
        Assertions.assertEquals(0x00000000L, Utils.encodeCompactBits(Utils.decodeCompactBits(0x04000000L)));
        Assertions.assertEquals(0x01120000L, Utils.encodeCompactBits(Utils.decodeCompactBits(0x01123456L)));
        Assertions.assertEquals(0x02123400L, Utils.encodeCompactBits(Utils.decodeCompactBits(0x02123456L)));
        Assertions.assertEquals(0x03123456L, Utils.encodeCompactBits(Utils.decodeCompactBits(0x03123456L)));
        Assertions.assertEquals(0x04123456L, Utils.encodeCompactBits(Utils.decodeCompactBits(0x04123456L)));
        Assertions.assertEquals(0x05009234L, Utils.encodeCompactBits(Utils.decodeCompactBits(0x05009234L)));
        Assertions.assertEquals(0x20123456L, Utils.encodeCompactBits(Utils.decodeCompactBits(0x20123456L)));
        Assertions.assertEquals(0x05123456L, Utils.encodeCompactBits(new BigInteger("1234560000", 16)));
        Assertions.assertEquals(0x0600c0deL, Utils.encodeCompactBits(new BigInteger("c0de000000", 16)));

        //A mantissa whose top bit is set must not be encoded as the negative flag
        Assertions.assertEquals(0x02008000L, Utils.encodeCompactBits(BigInteger.valueOf(0x80)));
        Assertions.assertEquals(0x00000000L, Utils.encodeCompactBits(BigInteger.ZERO));

        //decodeCompactBits returns a negative value for the compact forms that set the sign bit, which are not encodable difficulty targets
        Assertions.assertThrows(IllegalArgumentException.class, () -> Utils.encodeCompactBits(Utils.decodeCompactBits(0x1d80ffffL)));

        //The consensus targets of every network Sparrow supports round trip unchanged
        Assertions.assertEquals(0x1d00ffffL, Utils.encodeCompactBits(Utils.decodeCompactBits(0x1d00ffffL)));
        Assertions.assertEquals(0x207fffffL, Utils.encodeCompactBits(Utils.decodeCompactBits(0x207fffffL)));
        Assertions.assertEquals(0x1e0377aeL, Utils.encodeCompactBits(Utils.decodeCompactBits(0x1e0377aeL)));
        Assertions.assertEquals(0x17053894L, Utils.encodeCompactBits(Utils.decodeCompactBits(0x17053894L)));
    }

    @Test
    public void testGenesisHeader() {
        Network.set(Network.MAINNET);

        BlockHeader blockHeader = new BlockHeader(Utils.hexToBytes(GENESIS_HEADER_HEX));
        Assertions.assertEquals("000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", blockHeader.getHash().toString());
        Assertions.assertEquals(0x1d00ffffL, blockHeader.getDifficultyTarget());
        Assertions.assertTrue(blockHeader.verifyProofOfWork());
    }

    @Test
    public void testBlock800000Header() {
        Network.set(Network.MAINNET);

        BlockHeader blockHeader = new BlockHeader(Utils.hexToBytes(BLOCK_800000_HEADER_HEX));
        Assertions.assertEquals("00000000000000000002a7c4c1e48d76c5a37902165a270156b7a8d72728a054", blockHeader.getHash().toString());
        Assertions.assertEquals(0x17053894L, blockHeader.getDifficultyTarget());
        Assertions.assertTrue(blockHeader.verifyProofOfWork());
    }

    @Test
    public void testTamperedHeaderFailsProofOfWork() {
        Network.set(Network.MAINNET);

        byte[] tampered = Utils.hexToBytes(BLOCK_800000_HEADER_HEX);
        tampered[79] ^= 0x01;
        BlockHeader blockHeader = new BlockHeader(tampered);
        Assertions.assertFalse(blockHeader.verifyProofOfWork());
    }

    @Test
    public void testTargetAboveProofOfWorkLimitFails() {
        Network.set(Network.MAINNET);

        //An absurdly easy target above the mainnet powLimit must fail regardless of the header hash
        BlockHeader genesis = new BlockHeader(Utils.hexToBytes(GENESIS_HEADER_HEX));
        BlockHeader blockHeader = new BlockHeader(genesis.getVersion(), genesis.getPrevBlockHash(), genesis.getMerkleRoot(), null, genesis.getTime(), 0x2100ffffL, genesis.getNonce());
        Assertions.assertFalse(blockHeader.verifyProofOfWork());
    }

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

    @AfterEach
    public void tearDown() throws Exception {
        Network.set(null);
    }
}
