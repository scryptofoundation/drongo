package com.sparrowwallet.drongo.protocol;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class TapScriptTest {
    static Properties fixture() throws IOException {
        Properties properties = new Properties();
        try(InputStream in = TapScriptTest.class.getResourceAsStream("ord-reveal-regtest.properties")) {
            properties.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        return properties;
    }

    static ECKey xOnly(byte[] x) {
        return ECKey.fromPublicOnly(Utils.concat(new byte[] {0x02}, x));
    }

    @Test
    public void leafHashAndTweakMatchOrdReveal() throws IOException {
        Properties f = fixture();
        for(String reveal : List.of("png", "etch")) {
            byte[] controlBlock = Utils.hexToBytes(f.getProperty(reveal + ".reveal.controlBlock"));
            ECKey internalKey = xOnly(Arrays.copyOfRange(controlBlock, 1, 33));
            Script script = new Script(Utils.hexToBytes(f.getProperty(reveal + ".reveal.script")));
            byte[] root = TapScript.leafHash(script);

            ECKey outputKey = TapScript.outputKey(internalKey, root);
            byte[] commitScript = Utils.hexToBytes(f.getProperty(reveal + ".commit.output.script"));
            Assertions.assertArrayEquals(Arrays.copyOfRange(commitScript, 2, 34), outputKey.getPubKeyXCoord(), reveal);
            Assertions.assertEquals(controlBlock[0] & 1, outputKey.hasOddYCoord() ? 1 : 0, reveal);
        }
    }

    @Test
    public void controlBlockMatchesOrdReveal() throws IOException {
        Properties f = fixture();
        for(String reveal : List.of("png", "etch")) {
            byte[] controlBlock = Utils.hexToBytes(f.getProperty(reveal + ".reveal.controlBlock"));
            ECKey internalKey = xOnly(Arrays.copyOfRange(controlBlock, 1, 33));
            Script script = new Script(Utils.hexToBytes(f.getProperty(reveal + ".reveal.script")));
            Assertions.assertArrayEquals(controlBlock, TapScript.controlBlock(internalKey, TapScript.leafHash(script)), reveal);
        }
    }

    @Test
    public void ordRevealSignatureVerifies() throws IOException {
        Properties f = fixture();
        Transaction reveal = new Transaction(Utils.hexToBytes(f.getProperty("png.reveal.tx")));
        Script script = new Script(Utils.hexToBytes(f.getProperty("png.reveal.script")));
        TransactionOutput spent = new TransactionOutput(null, Long.parseLong(f.getProperty("png.commit.output.value")),
                Utils.hexToBytes(f.getProperty("png.commit.output.script")));
        Sha256Hash hash = reveal.hashForTaprootSignature(List.of(spent), 0, true, script, SigHash.DEFAULT, null);
        byte[] signature = reveal.getInputs().getFirst().getWitness().getPushes().getFirst();
        ECKey scriptKey = xOnly(Arrays.copyOfRange(script.getProgram(), 1, 33));
        Assertions.assertTrue(scriptKey.verify(hash, TransactionSignature.decodeFromBitcoin(TransactionSignature.Type.SCHNORR, signature, false)));
    }

    @Test
    public void scriptPathSignatureVerifies() {
        ECKey key = ECKey.fromPrivate(Utils.hexToBytes("0101010101010101010101010101010101010101010101010101010101010101"));
        Script script = new Script(Utils.concat(Utils.concat(new byte[] {0x20}, key.getPubKeyXCoord()), new byte[] {(byte)ScriptOpCodes.OP_CHECKSIG}));
        ECKey outputKey = TapScript.outputKey(key, TapScript.leafHash(script));
        TransactionOutput spent = new TransactionOutput(null, 20_000, ScriptType.P2TR.getOutputScript(outputKey.getPubKeyXCoord()));

        Transaction spend = new Transaction();
        spend.setVersion(2);
        spend.addInput(Sha256Hash.wrap("52b559ee298a9e57b1b6e743a53e68c9d9a7e75da6b090cfdd8e2bb260f3636e"), 0, new Script(new byte[0]));
        spend.addOutput(10_000, ScriptType.P2TR.getOutputScript(key.getTweakedOutputKey()));

        TransactionSignature signature = TapScript.signScriptPath(spend, 0, List.of(spent), script, key);
        Sha256Hash hash = spend.hashForTaprootSignature(List.of(spent), 0, true, script, SigHash.DEFAULT, null);
        Assertions.assertEquals(64, signature.encodeToBitcoin().length);
        Assertions.assertTrue(ECKey.fromPublicOnly(key).verify(hash, signature));
    }
}
