package com.sparrowwallet.drongo.ordinals;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptChunk;
import com.sparrowwallet.drongo.protocol.ScriptOpCodes;
import com.sparrowwallet.drongo.protocol.ScriptType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class InscriptionTest {
    static Properties fixture() throws IOException {
        Properties properties = new Properties();
        try(InputStream in = InscriptionTest.class.getResourceAsStream("/com/sparrowwallet/drongo/protocol/ord-reveal-regtest.properties")) {
            properties.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        return properties;
    }

    static ECKey scriptKey(String scriptHex) {
        return ECKey.fromPublicOnly(Utils.concat(new byte[] {0x02}, Arrays.copyOfRange(Utils.hexToBytes(scriptHex), 1, 33)));
    }

    @Test
    public void revealScriptMatchesOrd() throws IOException {
        Properties f = fixture();
        String png = f.getProperty("png.reveal.script");
        Inscription image = new Inscription(f.getProperty("png.contentType"), Utils.hexToBytes(f.getProperty("png.body")), null);
        Assertions.assertEquals(png, Utils.bytesToHex(image.revealScript(scriptKey(png)).getProgram()));

        //ord's etch envelope also has an empty pointer tag (01 02 00), which a single inscription does not need
        String etch = f.getProperty("etch.reveal.script");
        Inscription text = new Inscription("text/plain;charset=utf-8", f.getProperty("etch.text").getBytes(StandardCharsets.UTF_8),
                Utils.hexToBytes(f.getProperty("etch.commitment")));
        Assertions.assertEquals(etch.replace("010200010d", "010d"), Utils.bytesToHex(text.revealScript(scriptKey(etch)).getProgram()));
    }

    @Test
    public void bodySplitsAt520() {
        ECKey key = ECKey.fromPrivate(Utils.hexToBytes("0101010101010101010101010101010101010101010101010101010101010101"));
        Script script = new Inscription("application/octet-stream", new byte[1200], null).revealScript(key);
        List<ScriptChunk> chunks = script.getChunks();
        int body = chunks.size() - 4;
        Assertions.assertTrue(chunks.get(body - 1).equalsOpCode(ScriptOpCodes.OP_0));
        Assertions.assertEquals(ScriptOpCodes.OP_PUSHDATA2, chunks.get(body).opcode);
        Assertions.assertEquals(520, chunks.get(body).data.length);
        Assertions.assertEquals(520, chunks.get(body + 1).data.length);
        Assertions.assertEquals(ScriptOpCodes.OP_PUSHDATA1, chunks.get(body + 2).opcode);
        Assertions.assertEquals(160, chunks.get(body + 2).data.length);
        Assertions.assertTrue(chunks.getLast().equalsOpCode(ScriptOpCodes.OP_ENDIF));
    }

    @Test
    public void maxBodyKeepsRevealUnder400kWeight() {
        ECKey key = ECKey.fromPrivate(Utils.hexToBytes("0101010101010101010101010101010101010101010101010101010101010101"));
        List<Script> destination = List.of(ScriptType.P2TR.getOutputScript(key.getTweakedOutputKey()));
        int max = Inscription.maxBodyBytes("image/png");
        Assertions.assertTrue(max > 390_000);
        Assertions.assertTrue(new Inscription("image/png", new byte[max], null).revealWeight(destination) <= Inscription.MAX_REVEAL_WEIGHT);
        Assertions.assertTrue(new Inscription("image/png", new byte[max + 1], null).revealWeight(destination) > Inscription.MAX_REVEAL_WEIGHT);
        Assertions.assertEquals(400_000, Inscription.MAX_REVEAL_WEIGHT);
    }

    @Test
    public void contentTypes() {
        Assertions.assertEquals("image/png", Inscription.contentTypeFor("ref.png"));
        Assertions.assertEquals("image/jpeg", Inscription.contentTypeFor("Photo.JPG"));
        Assertions.assertEquals("image/jpeg", Inscription.contentTypeFor("a.jpeg"));
        Assertions.assertEquals("image/gif", Inscription.contentTypeFor("a.gif"));
        Assertions.assertEquals("image/webp", Inscription.contentTypeFor("a.webp"));
        Assertions.assertEquals("image/svg+xml", Inscription.contentTypeFor("a.svg"));
        Assertions.assertEquals("application/json", Inscription.contentTypeFor("a.json"));
        Assertions.assertEquals("text/plain;charset=utf-8", Inscription.contentTypeFor("notes.txt"));
        Assertions.assertEquals("application/octet-stream", Inscription.contentTypeFor("archive.zip"));
        Assertions.assertEquals("application/octet-stream", Inscription.contentTypeFor("noextension"));

        Inscription text = Inscription.text("hello litecoin");
        Assertions.assertEquals("text/plain;charset=utf-8", text.contentType());
        Assertions.assertArrayEquals("hello litecoin".getBytes(StandardCharsets.UTF_8), text.body());
        Assertions.assertNull(text.runeCommitment());
        Assertions.assertThrows(IllegalArgumentException.class, () -> Inscription.text(""));
    }

    @Test
    public void contentTypeFitsTheWalletFileAndOnePush() {
        //Stored in a varchar(255) column and pushed as one script element, so longer types are refused before any commit
        Assertions.assertDoesNotThrow(() -> new Inscription("a".repeat(Inscription.MAX_CONTENT_TYPE_BYTES), new byte[] {1}, null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Inscription("a".repeat(Inscription.MAX_CONTENT_TYPE_BYTES + 1), new byte[] {1}, null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Inscription("é".repeat(128), new byte[] {1}, null));
        Assertions.assertEquals(255, Inscription.MAX_CONTENT_TYPE_BYTES);
    }
}
