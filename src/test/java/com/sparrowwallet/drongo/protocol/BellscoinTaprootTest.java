package com.sparrowwallet.drongo.protocol;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.SchnorrSignature;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Bellscoin mainnet runs Taproot as Bitcoin does: drongo's BIP341 signature hash verifies the Schnorr signatures of a
 * real Bellscoin mainnet key-path spend (tx a81aa83b...4ea3, block 1252057, read from api.nintondo.io), so wallets
 * built here sign Bellscoin Taproot inputs the way its nodes check them.
 */
public class BellscoinTaprootTest {
    private static final String RAW =
            "020000000001047b2ee853d440302db4a27719ceb84565a480f666be9043b126d3b7f071a925620400000000ffffffff7b2e" +
            "e853d440302db4a27719ceb84565a480f666be9043b126d3b7f071a925620500000000ffffffff1d91a72f0916d531992be5" +
            "501134692bf232d5fc692d3042fdcbd3e560da08c00000000000fffffffff96a889020ce6dba1f5cf065cb21c40666077abb" +
            "b5fbad9bcb54fd5d259c243e0000000000ffffffff07d007000000000000225120a066bb7b97a348a3ec0854bee453cc7b51" +
            "77affcb179c7adee5aa3256c659bdde803000000000000225120a066bb7b97a348a3ec0854bee453cc7b5177affcb179c7ad" +
            "ee5aa3256c659bdd80b2ef4b000000001600146c068e55f537c15a3c52004a24d3d009ef37a9c500751903000000001976a9" +
            "14b9c6d0cd855028851de6b3c138ed27aeb7d3d48788ace803000000000000225120a066bb7b97a348a3ec0854bee453cc7b" +
            "5177affcb179c7adee5aa3256c659bdde803000000000000225120a066bb7b97a348a3ec0854bee453cc7b5177affcb179c7" +
            "adee5aa3256c659bdd4080b5c32a000000225120a066bb7b97a348a3ec0854bee453cc7b5177affcb179c7adee5aa3256c65" +
            "9bdd0140b1c7449908249036bfe73e9e47fa849cac23877cd3a7f8e7dff324adf6e2e44625aef7957293fce08d73e962803f" +
            "03cc510a2990e47db817c2bd886138df57a60140a9d2ade7920bfddece1e3e33a4a43f9ddf933e92cca91fc0aa1406afd808" +
            "a5a6b0386743346a203ada54434c2f63dd7919cdda7563e09f9dd95de2c50a2c80d40247304402200e64e733924ff55dd1c1" +
            "4d9af36182c329058dd7bc654a44b1a5fe106573bdab022074578e53ad666d5d90b68bea52f54cab0861969a6c5983772987" +
            "015bbcacc40f83210260dc26e2487d420916e9e98f8fe35cbc9b9806da83ceaee44e7e0e73cd8162c701402db8978ee82518" +
            "327a803cf65a141dfca47fad044223bf0f76862dd33d1cfffbe1fa9015854281ff8a89b1c126cb4a85c645eed6e30c95ff99" +
            "9ddc7232a3d43d00000000";

    @Test
    public void mainnetKeyPathSignaturesVerify() {
        Transaction transaction = new Transaction(Utils.hexToBytes(RAW));
        Assertions.assertEquals("a81aa83bfb7fe6d716593d6d7af9cef0531a71b6d62e1717b1518d7878ba4ea3", transaction.getTxId().toString());
        List<TransactionOutput> spent = List.of(
                new TransactionOutput(null, 1000L, Utils.hexToBytes("5120a066bb7b97a348a3ec0854bee453cc7b5177affcb179c7adee5aa3256c659bdd")),
                new TransactionOutput(null, 1000L, Utils.hexToBytes("5120a066bb7b97a348a3ec0854bee453cc7b5177affcb179c7adee5aa3256c659bdd")),
                new TransactionOutput(null, 1000L, Utils.hexToBytes("00146c068e55f537c15a3c52004a24d3d009ef37a9c5")),
                new TransactionOutput(null, 184998248400L, Utils.hexToBytes("5120a066bb7b97a348a3ec0854bee453cc7b5177affcb179c7adee5aa3256c659bdd")));

        int taprootInputs = 0;
        for(int i = 0; i < spent.size(); i++) {
            byte[] script = spent.get(i).getScriptBytes();
            if(script.length != 34 || script[0] != 0x51) {
                continue; //the Native Segwit input
            }
            byte[] witness = transaction.getInputs().get(i).getWitness().getPushes().getFirst();
            byte sigHashType = witness.length == 65 ? witness[64] : SigHash.DEFAULT.byteValue();
            Sha256Hash hash = transaction.hashForTaprootSignature(spent, i, false, null, sigHashType, null);
            SchnorrSignature signature = SchnorrSignature.decode(Arrays.copyOf(witness, 64));
            byte[] outputKey = Arrays.copyOfRange(script, 2, 34);
            Assertions.assertTrue(signature.verify(hash.getBytes(), outputKey), "input " + i);
            //and the hash is bound to the spent amounts: a changed value no longer verifies
            List<TransactionOutput> altered = new java.util.ArrayList<>(spent);
            altered.set(i, new TransactionOutput(null, spent.get(i).getValue() + 1, script));
            Assertions.assertFalse(signature.verify(transaction.hashForTaprootSignature(altered, i, false, null, sigHashType, null).getBytes(), outputKey));
            taprootInputs++;
        }
        Assertions.assertEquals(3, taprootInputs);
    }
}
