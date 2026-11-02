package com.sparrowwallet.drongo.crypto;

import com.sparrowwallet.drongo.Utils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DumpedPrivateKeyTest {
    //The BIP38 vectors' keys (L44B5gGE..., 5KN7MzqK...) under Litecoin's WIF byte 0xb0
    private static final String COMPRESSED_WIF = "T9tSXRZREDCpCGno32vycoaTgTpa6w46P9MCFsHkDuhLD4FVuQVZ";
    private static final String UNCOMPRESSED_WIF = "6vfqq8NqzNLtvkuWv2Lw4afPp1Bn7L3WmSTKs3LCc6bWjDkycfE";

    @Test
    public void testCompressedRoundTrip() {
        DumpedPrivateKey parsed = DumpedPrivateKey.fromBase58(COMPRESSED_WIF);
        Assertions.assertTrue(parsed.getKey().isCompressed());
        Assertions.assertEquals(COMPRESSED_WIF, parsed.toBase58());
        Assertions.assertEquals(COMPRESSED_WIF, parsed.getKey().getPrivateKeyEncoded().toBase58());
    }

    @Test
    public void testUncompressedRoundTrip() {
        DumpedPrivateKey parsed = DumpedPrivateKey.fromBase58(UNCOMPRESSED_WIF);
        Assertions.assertFalse(parsed.getKey().isCompressed());
        Assertions.assertEquals(UNCOMPRESSED_WIF, parsed.toBase58());
        Assertions.assertEquals(UNCOMPRESSED_WIF, parsed.getKey().getPrivateKeyEncoded().toBase58());
    }

    @Test
    public void testKeyMatchesRegardlessOfConstruction() {
        for(String wif : new String[] {COMPRESSED_WIF, UNCOMPRESSED_WIF}) {
            DumpedPrivateKey parsed = DumpedPrivateKey.fromBase58(wif);
            DumpedPrivateKey encoded = parsed.getKey().getPrivateKeyEncoded();
            Assertions.assertEquals(Utils.bytesToHex(parsed.getKey().getPubKey()), Utils.bytesToHex(encoded.getKey().getPubKey()));
            Assertions.assertEquals(parsed, encoded);
            Assertions.assertEquals(parsed.hashCode(), encoded.hashCode());
        }
    }

    @Test
    public void testCompressedAndUncompressedDifferDespiteSharedScalar() {
        //These two BIP38 vectors are the same 32 byte scalar, so only the compression marker separates them
        ECKey compressed = DumpedPrivateKey.fromBase58(COMPRESSED_WIF).getKey();
        ECKey uncompressed = DumpedPrivateKey.fromBase58(UNCOMPRESSED_WIF).getKey();
        Assertions.assertEquals(compressed.getPrivKey(), uncompressed.getPrivKey());
        Assertions.assertNotEquals(Utils.bytesToHex(compressed.getPubKey()), Utils.bytesToHex(uncompressed.getPubKey()));
    }
}
