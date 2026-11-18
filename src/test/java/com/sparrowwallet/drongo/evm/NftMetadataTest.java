package com.sparrowwallet.drongo.evm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

public class NftMetadataTest {
    private static final String VAULT_BADGE_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\" shape-rendering=\"crispEdges\">"
            + "<rect width=\"64\" height=\"64\" fill=\"#0E1E38\"/><rect x=\"2\" y=\"2\" width=\"60\" height=\"60\" fill=\"#C8D0DC\"/>"
            + "<rect x=\"4\" y=\"4\" width=\"56\" height=\"56\" fill=\"#345D9D\"/><circle cx=\"32\" cy=\"26\" r=\"15\" fill=\"#3A4458\"/>"
            + "<circle cx=\"32\" cy=\"26\" r=\"13\" fill=\"#C8D0DC\"/>"
            + "<path d=\"M30 16h5v9l4-2v4l-4 2v4h7v4H27v-6l-3 1v-4l3-1V16z\" fill=\"#345D9D\"/>"
            + "<text x=\"32\" y=\"54\" font-family=\"monospace\" font-size=\"9\" font-weight=\"bold\" text-anchor=\"middle\" fill=\"#FFFFFF\">WORLD 1</text></svg>";

    private static NftMetadata parsed(String tokenUri) {
        return NftMetadata.parse(tokenUri).orElseThrow(() -> new AssertionError("unreadable: " + tokenUri));
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonUri(String json) {
        return "data:application/json;base64," + base64(json);
    }

    private static NftMetadata.Image imageOf(String image) {
        return parsed("{\"name\":\"x\",\"image\":\"" + image + "\"}").image();
    }

    @Test
    public void parsesTheVaultBadgeUri() {
        //The tokenURI LiteForge returns for World 1, recorded once; it equals what the contract source builds by hand
        NftMetadata metadata = parsed(Erc721Test.resource("vault-badge-world-1-token-uri.txt"));
        Assertions.assertEquals("Super Litecoin Vault Badge: World 1", metadata.name());
        Assertions.assertEquals("Commemorative badge for clearing the World 1 vault in Super Litecoin. Non-transferable, no value.", metadata.description());
        Assertions.assertEquals(List.of(new NftMetadata.Attribute("World", "1")), metadata.attributes());
        NftMetadata.Image.Svg svg = Assertions.assertInstanceOf(NftMetadata.Image.Svg.class, metadata.image());
        Assertions.assertTrue(svg.source().startsWith("<svg"));
        Assertions.assertEquals(VAULT_BADGE_SVG, svg.source());
    }

    @Test
    public void bareJsonAndPlainDataUris() {
        Assertions.assertEquals("A", parsed("{\"name\":\"A\"}").name());
        Assertions.assertEquals("A", parsed("  \n{\"name\":\"A\"} ").name());
        Assertions.assertEquals("B", parsed("data:application/json,{\"name\":\"B\"}").name());
        Assertions.assertEquals("C+D", parsed("data:application/json,%7B%22name%22%3A%22C+D%22%7D").name(), "a plus sign is not a space");
        Assertions.assertEquals("C D \u00e9", parsed("data:application/json,%7B%22name%22%3A%22C%20D%20%C3%A9%22%7D").name());
        Assertions.assertEquals("E", parsed("data:application/json;charset=utf-8,{\"name\":\"E\"}").name());
        Assertions.assertEquals("F", parsed("DATA:Application/JSON;utf8,{\"name\":\"F\"}").name());
        Assertions.assertEquals("G", parsed("data:application/json;charset=UTF-8;base64," + base64("{\"name\":\"G\"}")).name());

        //A malformed escape or an escape that is not UTF-8 is unreadable, never guessed at
        for(String bad : List.of("data:application/json,%7B%22name%22:%22H%22%7", "data:application/json,{\"name\":\"50%\"}",
                "data:application/json,{\"name\":\"%zz\"}", "data:application/json,{\"name\":\"%\u0663\u0663\"}",
                "data:application/json,{\"name\":\"%C3\"}", "data:application/json;charset=iso-8859-1,{\"name\":\"I\"}",
                "data:application/json;base64;charset=utf-8," + base64("{\"name\":\"J\"}"))) {
            Assertions.assertTrue(NftMetadata.parse(bad).isEmpty(), bad);
        }

        NftMetadata.Image plainSvg = imageOf("data:image/svg+xml,%3Csvg viewBox='0 0 1 1'%3E%3C/svg%3E");
        Assertions.assertEquals(new NftMetadata.Image.Svg("<svg viewBox='0 0 1 1'></svg>"), plainSvg);
        Assertions.assertEquals(new NftMetadata.Image.Svg(VAULT_BADGE_SVG), imageOf("data:image/svg+xml;base64," + base64(VAULT_BADGE_SVG)));
    }

    @Test
    public void anHttpsTokenUriIsNotRead() {
        for(String uri : List.of("https://example.com/1.json", "http://example.com/1.json", "ipfs://QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG/1",
                "ar://abc", "data:text/html,<h1>hi</h1>", "data:,{\"name\":\"x\"}", "data:application/json;base64", "javascript:alert(1)", "", " ",
                "[{\"name\":\"array\"}]", "\"name\"", "null")) {
            Assertions.assertTrue(NftMetadata.parse(uri).isEmpty(), uri);
        }
        Assertions.assertTrue(NftMetadata.parse(null).isEmpty());
    }

    @Test
    public void hostedImagesAreNeverFetched() throws IOException {
        Assertions.assertEquals(new NftMetadata.Image.Hosted("img.example.com"), imageOf("https://img.example.com/a.png"));
        Assertions.assertEquals(new NftMetadata.Image.Hosted("example.com"), imageOf("HTTP://user:pw@Example.COM:8080/x?y#z"));
        Assertions.assertEquals(new NftMetadata.Image.Hosted("IPFS"), imageOf("ipfs://QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG"));
        Assertions.assertEquals(new NftMetadata.Image.Hosted("Arweave"), imageOf("ar://abc"));
        //The host shown is the one a browser would contact, not the decoy in front of it
        Assertions.assertEquals(new NftMetadata.Image.Hosted("evil.example"), imageOf("https://good.example@evil.example/x"));
        Assertions.assertEquals(new NftMetadata.Image.Hosted("evil.example"), imageOf("https://evil.example\\\\@good.example/x"));
        //A host that is not plain ASCII letters, digits, dots and hyphens is not named at all (the Kelvin sign lower-cases to k)
        for(String odd : List.of("https:///x", "https://ex%61mple.com/", "https://\u212Aelvin.example/", "https://\u0435xample.com/",
                "https://[::1]/x", "https://example.com:80a/", "https://ex ample.com/")) {
            Assertions.assertEquals(new NftMetadata.Image.None(), imageOf(odd), odd);
        }

        //No class of NftMetadata refers to anything that could reach the network
        for(Class<?> member : NftMetadata.class.getNestMembers()) {
            String classFile = member.getName().replace('.', '/') + ".class";
            try(InputStream in = member.getClassLoader().getResourceAsStream(classFile)) {
                String constants = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                for(String forbidden : List.of("java/net/", "java/nio/channels/", "java/nio/file/", "java/io/File", "LitVmRpcClient", "BlockscoutClient",
                        "SourcifyClient", "HttpClient", "Socket", "URL")) {
                    Assertions.assertFalse(constants.contains(forbidden), member.getName() + " refers to " + forbidden);
                }
            }
        }
    }

    @Test
    public void oversizedJsonIsUnreadable() {
        String head = "{\"name\":\"";
        String fits = head + "a".repeat(20_000 - head.length() - 2) + "\"}";
        Assertions.assertEquals(20_000, fits.length());
        Assertions.assertEquals(64, parsed(fits).name().length());
        Assertions.assertEquals(64, parsed(jsonUri(fits)).name().length());
        String over = head + "a".repeat(20_001 - head.length() - 2) + "\"}";
        Assertions.assertTrue(NftMetadata.parse(over).isEmpty());
        Assertions.assertTrue(NftMetadata.parse(jsonUri(over)).isEmpty());
        Assertions.assertTrue(NftMetadata.parse("data:application/json," + over).isEmpty());
        //Too long to be a tokenURI at all
        Assertions.assertTrue(NftMetadata.parse(jsonUri(head + "a".repeat(80_000) + "\"}")).isEmpty());

        //What MiniJson refuses is unreadable: deep nesting, huge numbers, broken text
        Assertions.assertTrue(NftMetadata.parse("{\"a\":" + "[".repeat(100) + "]".repeat(100) + "}").isEmpty());
        Assertions.assertTrue(NftMetadata.parse("{\"name\":\"x\",\"n\":1e999999999}").isEmpty());
        Assertions.assertTrue(NftMetadata.parse("{\"name\":\"x\"").isEmpty());
        Assertions.assertTrue(NftMetadata.parse("{\"name\":\"x\"}}").isEmpty());
        Assertions.assertTrue(NftMetadata.parse("{\"name\":\"\\uZZZZ\"}").isEmpty());
    }

    @Test
    public void wrongTypesReadAsAbsent() {
        NftMetadata metadata = parsed("{\"name\":5,\"description\":{\"a\":1},\"attributes\":\"many\",\"image\":7}");
        Assertions.assertEquals("", metadata.name());
        Assertions.assertEquals("", metadata.description());
        Assertions.assertEquals(List.of(), metadata.attributes());
        Assertions.assertEquals(new NftMetadata.Image.None(), metadata.image());
        Assertions.assertEquals(new NftMetadata.Image.None(), parsed("{}").image());
        Assertions.assertEquals(new NftMetadata.Image.None(), parsed("{\"image\":null}").image());

        NftMetadata attributes = parsed("{\"attributes\":[1,\"a\",null,[],{\"trait_type\":5,\"value\":\"v\"},{\"trait_type\":\"T\",\"value\":{}},"
                + "{\"trait_type\":\"U\"},{\"trait_type\":\"B\",\"value\":true},{\"trait_type\":\"N\",\"value\":1.50},{\"value\":\"only\"}]}");
        Assertions.assertEquals(List.of(new NftMetadata.Attribute("", "v"), new NftMetadata.Attribute("B", "true"),
                new NftMetadata.Attribute("N", "1.50"), new NftMetadata.Attribute("", "only")), attributes.attributes());
    }

    @Test
    public void aNumberTooLongToShowIsDropped() {
        //Cut at 64 characters, 1e99 or an 80-digit number would read as another number
        String sixtyFour = "9".repeat(64);
        NftMetadata metadata = parsed("{\"attributes\":[{\"trait_type\":\"A\",\"value\":1e99},{\"trait_type\":\"B\",\"value\":" + "8".repeat(80)
                + "},{\"trait_type\":\"C\",\"value\":" + sixtyFour + "},{\"trait_type\":\"D\",\"value\":\"" + "7".repeat(80) + "\"}]}");
        Assertions.assertEquals(List.of(new NftMetadata.Attribute("C", sixtyFour), new NftMetadata.Attribute("D", "7".repeat(64))), metadata.attributes());
    }

    @Test
    public void aParagraphSeparatorIsStripped() {
        Assertions.assertEquals("onetwo", parsed("{\"name\":\"one" + (char)0x2029 + "two\"}").name());
        Assertions.assertEquals("onetwo", parsed("{\"name\":\"one\\u2029two\"}").name());
    }

    @Test
    public void aHostedHostIsAtMost253Characters() {
        String host = "a".repeat(249) + ".com";
        Assertions.assertEquals(253, host.length());
        Assertions.assertEquals(new NftMetadata.Image.Hosted(host), imageOf("https://" + host + "/x.png"));
        Assertions.assertEquals(new NftMetadata.Image.None(), imageOf("https://a" + host + "/x.png"));
    }

    @Test
    public void anOversizedBase64PayloadIsRefusedBeforeDecoding() {
        //402,000 bytes by its length alone: refused without reaching the decoder
        String tooLarge = "AAAA".repeat(134_000);
        int[] decodes = {0};
        java.util.function.Function<String, byte[]> counting = text -> {
            decodes[0]++;
            return java.util.Base64.getDecoder().decode(text);
        };
        Assertions.assertNull(NftMetadata.base64(tooLarge, NftMetadata.MAX_RASTER_BYTES, counting));
        //A JSON payload whose length proves more than 3 bytes for each of 20,000 characters
        Assertions.assertNull(NftMetadata.base64("AAAA".repeat(20_001), NftMetadata.MAX_JSON_CHARS * 3, counting));
        Assertions.assertEquals(0, decodes[0]);
        //One that fits does reach it
        Assertions.assertNotNull(NftMetadata.base64("AAAA".repeat(133_333), NftMetadata.MAX_RASTER_BYTES, counting));
        Assertions.assertEquals(1, decodes[0]);
        //The paths that read images and JSON go through the same check
        Assertions.assertEquals(new NftMetadata.Image.None(), NftMetadata.image("data:image/png;base64," + tooLarge));
        Assertions.assertTrue(NftMetadata.parse("data:application/json;base64," + "AAAA".repeat(20_001)).isEmpty());
    }

    @Test
    public void duplicateKeysTakeTheLastValue() {
        //MiniJson keeps the last value for a repeated key, as JSON.parse does; that is pinned here so it never varies
        Assertions.assertEquals("Second", parsed("{\"name\":\"First\",\"name\":\"Second\"}").name());
        Assertions.assertEquals("", parsed("{\"name\":\"First\",\"name\":7}").name());
    }

    @Test
    public void tooManyAttributesAreCut() {
        StringBuilder json = new StringBuilder("{\"attributes\":[");
        for(int i = 0; i < 25; i++) {
            json.append(i == 0 ? "" : ",").append("{\"trait_type\":\"T").append(i).append("\",\"value\":").append(i).append("}");
        }
        List<NftMetadata.Attribute> attributes = parsed(json.append("]}").toString()).attributes();
        Assertions.assertEquals(NftMetadata.MAX_ATTRIBUTES, attributes.size());
        Assertions.assertEquals(new NftMetadata.Attribute("T0", "0"), attributes.get(0));
        Assertions.assertEquals(new NftMetadata.Attribute("T19", "19"), attributes.get(19));
    }

    @Test
    public void textIsSanitisedAndCapped() {
        //A right-to-left override, zero-width and isolate characters, a line separator, a bell and a newline, then 5,000 characters.
        //The newline separates words: a space in a name or attribute, a line feed in a description. The bell is escaped: strict
        //JSON refuses a raw control character in a string, so the whole document would be unreadable
        Assertions.assertTrue(NftMetadata.parse("{\"name\":\"a\u0007b\"}").isEmpty());
        String hostile = "\u202eRight\u200bto\u2066left\u2028\\u0007\\n" + "x".repeat(5_000);
        NftMetadata named = parsed("{\"name\":\"" + hostile + "\",\"description\":\"short\"}");
        Assertions.assertEquals(64, named.name().length());
        Assertions.assertTrue(named.name().startsWith("Righttoleft xxx"), named.name());
        NftMetadata described = parsed("{\"description\":\"" + hostile + "\"}");
        Assertions.assertEquals(500, described.description().length());
        Assertions.assertTrue(described.description().startsWith("Righttoleft\nxxx"));
        NftMetadata.Attribute trait = parsed("{\"attributes\":[{\"trait_type\":\"" + hostile + "\",\"value\":\"" + hostile + "\"}]}").attributes().get(0);
        Assertions.assertEquals(64, trait.name().length());
        Assertions.assertEquals(64, trait.value().length());
        Assertions.assertTrue(trait.name().startsWith("Righttoleft xxx") && trait.value().startsWith("Righttoleft xxx"), trait.toString());
        for(String text : List.of(named.name(), described.description(), trait.name(), trait.value())) {
            Assertions.assertTrue(text.codePoints().allMatch(c -> c == '\n' || (!TextRules.stripped(c) && !TextRules.loneSurrogate(c))), text);
            Assertions.assertFalse(text.startsWith(" ") || text.endsWith(" ") || text.contains("  "), text);
        }

        //An escaped unpaired surrogate is dropped, and the cap counts code points and never splits a pair
        Assertions.assertEquals("ab", parsed("{\"name\":\"a\\ud800b\"}").name());
        Assertions.assertEquals("ab", parsed("{\"name\":\"a\\udc00b\"}").name());
        String emoji = "\uD83D\uDE00";
        Assertions.assertEquals("x".repeat(63) + emoji, parsed("{\"name\":\"" + "x".repeat(63) + emoji + "\"}").name());
        Assertions.assertEquals("x".repeat(64), parsed("{\"name\":\"" + "x".repeat(64) + emoji + "\"}").name());
        Assertions.assertEquals("x".repeat(62) + emoji + "y", parsed("{\"name\":\"" + "x".repeat(62) + emoji + "y\"}").name());
        //No space is left at the cut
        Assertions.assertEquals("x".repeat(63), parsed("{\"name\":\"" + "x".repeat(63) + " y\"}").name());
    }

    @Test
    public void textIsCleanedByTheFixedTableNotThePlatformsUnicodeData() {
        //U+2065 (unassigned), the variation selectors and the supplementary variation selectors are in the table
        Assertions.assertEquals("abcd", parsed("{\"name\":\"a\u2065b\ufe0fc\\udb40\\udd00d\"}").name());
        Assertions.assertEquals("abcd", parsed("{\"description\":\"a\u2065b\ufe00c\\udb40\\uddefd\"}").description());
        NftMetadata.Attribute trait = parsed("{\"attributes\":[{\"trait_type\":\"T\u2065\",\"value\":\"\u2764\ufe0f\"}]}").attributes().get(0);
        Assertions.assertEquals(new NftMetadata.Attribute("T", "\u2764"), trait);
    }

    @Test
    public void namesAreOneLineAndDescriptionsKeepTheirLines() {
        NftMetadata metadata = parsed("{\"name\":\"  Vault\\tBadge\\r\\n World 1 \",\"description\":\"\\nFirst line.\\r\\n\\r\\n  Second line. \\n\"}");
        Assertions.assertEquals("Vault Badge World 1", metadata.name());
        Assertions.assertEquals("First line.\nSecond line.", metadata.description());
        StringBuilder lines = new StringBuilder();
        for(int i = 1; i <= 25; i++) {
            lines.append("L").append(i).append("\\n");
        }
        String description = parsed("{\"description\":\"" + lines + "\"}").description();
        Assertions.assertEquals(20, description.split("\n", -1).length);
        Assertions.assertTrue(description.endsWith("\nL20 L21 L22 L23 L24 L25"), description);
    }

    @Test
    public void onlyAsciiWhitespaceAndAsciiCaseCount() {
        //Around the URI and the JSON: space, tab, line feed and carriage return only
        Assertions.assertTrue(NftMetadata.parse("\u2003{\"name\":\"A\"}").isEmpty());
        Assertions.assertTrue(NftMetadata.parse("{\u2003\"name\":\"A\"}").isEmpty());
        Assertions.assertEquals("A", parsed("\t\r\n {\"name\":\"A\"}\t\r\n ").name());
        //A scheme is matched in ASCII case only: the long s upper-cases to S on the JVM
        Assertions.assertEquals(new NftMetadata.Image.None(), imageOf("http\u017f://example.com/a.png"));
        Assertions.assertEquals(new NftMetadata.Image.None(), imageOf("ipf\u017f://x"));
        Assertions.assertEquals(new NftMetadata.Image.Hosted("example.com"), imageOf("HtTpS://example.com/a.png"));
        //A blank SVG is blank in ASCII terms: an em space is text, so the picture stays (and SvgSubset refuses it later)
        Assertions.assertEquals(new NftMetadata.Image.None(), parsed("{\"image_data\":\" \\t\\n\"}").image());
        Assertions.assertEquals(new NftMetadata.Image.Svg("\u2003"), NftMetadata.image("data:image/svg+xml,%E2%80%83"));
    }

    @Test
    public void badBase64IsUnreadable() {
        String good = base64("{\"name\":\"ok\"}");
        Assertions.assertEquals("eyJuYW1lIjoib2sifQ==", good);
        Assertions.assertEquals("ok", parsed("data:application/json;base64," + good).name());
        //e30= is {}; e31= decodes to {} as well through a stray bit, which only a strict decoder refuses
        Assertions.assertTrue(NftMetadata.parse("data:application/json;base64,e30=").isPresent());
        //Unpadded base64 is refused: on-chain encoders always pad. So are other characters, URL-safe ones included,
        //whitespace, padding in the middle and a wrong length
        for(String bad : List.of("eyJuYW1lIjoib2sifQ", "eyJuYW1lIjoib2sifQ=", good.substring(1), good + "=", good + "====", good.replace('e', '!'),
                " " + good, good.substring(0, 4) + "\n" + good.substring(4), good.substring(0, 4) + "-" + good.substring(5),
                good.substring(0, 4) + "_" + good.substring(5), "eyJuYW1lIjoib2sifQ==eyJu", "e31=", "e3==", "=e30")) {
            Assertions.assertTrue(NftMetadata.parse("data:application/json;base64," + bad).isEmpty(), bad);
        }

        //A bad image encoding loses the picture, not the metadata
        NftMetadata metadata = parsed("{\"name\":\"n\",\"image\":\"data:image/svg+xml;base64,PHN2Zz4!\"}");
        Assertions.assertEquals("n", metadata.name());
        Assertions.assertEquals(new NftMetadata.Image.None(), metadata.image());
        Assertions.assertEquals(new NftMetadata.Image.None(), imageOf("data:image/png;base64,iVBORw0KGgo"));
    }

    @Test
    public void rasterDataUriGivesBytes() {
        byte[] png = {(byte)0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 1, 2};
        String encoded = Base64.getEncoder().encodeToString(png);
        for(String type : List.of("image/png", "image/jpeg", "image/gif", "image/webp")) {
            NftMetadata.Image.Raster raster = Assertions.assertInstanceOf(NftMetadata.Image.Raster.class, imageOf("data:" + type + ";base64," + encoded));
            Assertions.assertEquals(type, raster.mediaType());
            Assertions.assertArrayEquals(png, raster.bytes());
            Assertions.assertEquals(new NftMetadata.Image.Raster(type, png), raster);
        }
        //A raster must be base64, of a listed type
        Assertions.assertEquals(new NftMetadata.Image.None(), imageOf("data:image/png,%89PNG"));
        Assertions.assertEquals(new NftMetadata.Image.None(), imageOf("data:image/bmp;base64," + encoded));

        //Size caps, checked before decoding where the encoded length already proves the result too large
        String fits = Base64.getEncoder().encodeToString(new byte[NftMetadata.MAX_RASTER_BYTES]);
        Assertions.assertInstanceOf(NftMetadata.Image.Raster.class, NftMetadata.image("data:image/png;base64," + fits));
        String over = Base64.getEncoder().encodeToString(new byte[NftMetadata.MAX_RASTER_BYTES + 1]);
        Assertions.assertEquals(new NftMetadata.Image.None(), NftMetadata.image("data:image/png;base64," + over));
        String svgFits = "<svg>" + "a".repeat(NftMetadata.MAX_SVG_CHARS - 11) + "</svg>";
        Assertions.assertEquals(new NftMetadata.Image.Svg(svgFits), NftMetadata.image("data:image/svg+xml," + svgFits));
        Assertions.assertEquals(new NftMetadata.Image.None(), NftMetadata.image("data:image/svg+xml," + "<svg>" + "a".repeat(NftMetadata.MAX_SVG_CHARS) + "</svg>"));
        Assertions.assertEquals(new NftMetadata.Image.None(), NftMetadata.image("data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(("<svg>" + "a".repeat(NftMetadata.MAX_SVG_CHARS) + "</svg>").getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void anUnknownImageSchemeIsNone() {
        for(String image : List.of("javascript:alert(1)", "file:///etc/passwd", "blob:https://example.com/0f1e", "data:text/html,<script>alert(1)</script>",
                "data:text/html;base64,PHNjcmlwdD4=", "//example.com/a.png", "a.png", "ftp://example.com/a.png", "data:image/svg+xml;base64,", " ")) {
            Assertions.assertEquals(new NftMetadata.Image.None(), imageOf(image.replace("\"", "\\\"")), image);
        }
    }

    @Test
    public void imageDataHoldsRawSvg() {
        String json = "{\"name\":\"x\",\"image_data\":\"<svg viewBox='0 0 1 1'></svg>\"}";
        Assertions.assertEquals(new NftMetadata.Image.Svg("<svg viewBox='0 0 1 1'></svg>"), parsed(json).image());
        //image wins when it can be shown
        Assertions.assertEquals(new NftMetadata.Image.Hosted("example.com"),
                parsed("{\"image\":\"https://example.com/a.png\",\"image_data\":\"<svg></svg>\"}").image());
        Assertions.assertEquals(new NftMetadata.Image.Svg("<svg></svg>"), parsed("{\"image\":\"javascript:x\",\"image_data\":\"<svg></svg>\"}").image());
    }

    @Test
    public void nothingThrows() {
        List<String> vectors = new ArrayList<>(List.of(Erc721Test.resource("vault-badge-world-1-token-uri.txt"),
                new String(Base64.getDecoder().decode(Erc721Test.resource("vault-badge-world-1-token-uri.txt").substring(29)), StandardCharsets.UTF_8),
                "data:application/json,%7B%22name%22%3A%22C%20D%22%2C%22image%22%3A%22https%3A%2F%2Fa.example%2Fx%22%7D",
                "{\"image\":\"data:image/png;base64,iVBORw0KGgo=\",\"attributes\":[{\"trait_type\":\"a\",\"value\":\"\\ud83d\\ude00\"}]}"));
        for(String vector : vectors) {
            for(int i = 0; i <= vector.length(); i++) {
                String prefix = vector.substring(0, i);
                Optional<NftMetadata> result = Assertions.assertDoesNotThrow(() -> NftMetadata.parse(prefix), prefix);
                Assertions.assertNotNull(result);
            }
        }
    }
}
