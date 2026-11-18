package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.crypto.DeterministicKey;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.crypto.HDKeyDerivation;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;

/**
 * Writes the vectors the phone's read-only LitVM port is held to (mobile vectors/evm.json): Keccak, the account address
 * of public test seeds, EIP-55, every ABI call the phone makes and the decoding of good and malformed results, NFT
 * metadata, the two text-cleaning rules and the Vault Badge token ids. Every expected value comes from drongo's own
 * classes; nothing expected is typed by hand. The output is deterministic (insertion-ordered keys, ASCII only with
 * everything else escaped as \\uXXXX per UTF-16 unit, LF, a trailing newline).
 *
 * Opt-in: skipped unless EVM_VECTORS_OUT names the file to write.
 * Run:    from the app checkout (drongo is its subproject),
 *         EVM_VECTORS_OUT=<mobile>/vectors/evm.json ./gradlew --no-daemon :drongo:test --rerun --tests '*EvmVectorGeneratorTest*'
 * Output: EVM_VECTORS_OUT
 */
public class EvmVectorGeneratorTest {
    //An input longer than this is written as parts (see BUILT), with its length and hash
    private static final int LITERAL_MAX = 4_096;
    private static final int RAW_LIMIT = 100_000;

    private static final EvmAddress HOLDER = EvmAddress.fromString("0x840e41D4484041A43f9307a90389edeB3d111fb3");
    private static final EvmAddress DEAD = EvmAddress.fromString("0x000000000000000000000000000000000000dEaD");
    private static final EvmAddress ALL_ONES = EvmAddress.fromBytes(HexFormat.of().parseHex("ff".repeat(20)));

    private static final String BUILT = "An input given as \"<key>Parts\" instead of \"<key>\" is the concatenation of its parts: a string is itself; "
            + "{\"repeat\": s, \"times\": n} is s repeated n times; {\"base64\": [parts]} is the standard padded base64 of the UTF-8 bytes of its "
            + "parts. \"<key>Length\" is the built text's length in UTF-16 units and \"<key>Sha256\" the SHA-256 of its UTF-8 bytes. ABI results "
            + "are hex text. A long decoded value is given as valueLength (UTF-16 units) and valueSha256 (of its UTF-8 bytes). "
            + "A json case's \"canonical\" is the parsed value written compactly: objects in the reader's key order, numbers as "
            + "BigDecimal.toPlainString, strings with \\\" \\\\ \\n \\t \\r and \\uXXXX (lower-case hex) for every other UTF-16 unit "
            + "outside U+0020..U+007E.";

    @Test
    public void generate() throws Exception {
        String out = System.getenv("EVM_VECTORS_OUT");
        Assumptions.assumeTrue(out != null && !out.isBlank(), "EVM_VECTORS_OUT names the file to write");

        Map<String, Object> root = map(
                "description", "EVM vectors written by drongo EvmVectorGeneratorTest for the phone's read-only LitVM port. Do not edit by hand.",
                "built", BUILT,
                "constants", constants(),
                "keccak", keccak(),
                "addresses", addresses(),
                "eip55", eip55(),
                "abi", abi(),
                "json", json(),
                "metadata", metadata(),
                "clean", clean(),
                "tokenIds", tokenIds());
        Files.write(Path.of(out), (json(root, "") + "\n").getBytes(StandardCharsets.UTF_8));
    }

    // ---------------- Sections ----------------

    private static Map<String, Object> constants() {
        return map("nftMaxUriChars", NftMetadata.MAX_URI_CHARS, "nftMaxJsonChars", NftMetadata.MAX_JSON_CHARS,
                "nftMaxNameLength", NftMetadata.MAX_NAME_LENGTH, "nftMaxDescriptionLength", NftMetadata.MAX_DESCRIPTION_LENGTH,
                "nftMaxAttributeLength", NftMetadata.MAX_ATTRIBUTE_LENGTH, "nftMaxAttributes", NftMetadata.MAX_ATTRIBUTES,
                "nftMaxSvgChars", NftMetadata.MAX_SVG_CHARS, "nftMaxRasterBytes", NftMetadata.MAX_RASTER_BYTES,
                "erc721MaxTokenUriBytes", Erc721.MAX_TOKEN_URI_BYTES, "collectionMaxNameLength", Erc721Collection.MAX_NAME_LENGTH,
                "collectionMaxSymbolLength", Erc721Collection.MAX_SYMBOL_LENGTH, "svgMaxText", SvgSubset.MAX_TEXT,
                "vaultBadge", KnownCollections.VAULT_BADGE.toString(), "vaultBadgeMaxWorlds", KnownCollections.MAX_VAULT_BADGE_WORLDS,
                "listerMaxTokens", NftLister.MAX_TOKENS, "evmDerivationPrefix", EvmAccount.DERIVATION_PREFIX);
    }

    private static List<Object> keccak() {
        List<Object> cases = new ArrayList<>();
        cases.add(keccakCase("empty", new byte[0]));
        cases.add(keccakCase("abc", "abc".getBytes(StandardCharsets.US_ASCII)));
        for(int length : new int[] {1, 32, 135, 136, 137, 200, 271, 272, 273}) {
            byte[] data = new byte[length];
            for(int i = 0; i < length; i++) {
                data[i] = (byte)i;
            }
            cases.add(keccakCase(length + " bytes, byte i = i mod 256", data));
        }
        return cases;
    }

    private static Map<String, Object> keccakCase(String label, byte[] data) {
        return map("label", label, "length", data.length, "input", hex(data), "digest", hex(Keccak.keccak256(data)));
    }

    private record SeedCase(String name, String mnemonic, String passphrase, DeterministicSeed.Type type) {}

    private static List<Object> addresses() throws Exception {
        List<SeedCase> seeds = List.of(
                new SeedCase("bip39-standard-vector", "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "",
                        DeterministicSeed.Type.BIP39),
                new SeedCase("drongo-test-seed", "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor", "", DeterministicSeed.Type.BIP39),
                new SeedCase("drongo-test-seed-passphrase", "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor", "pp",
                        DeterministicSeed.Type.BIP39),
                new SeedCase("electrum-seed-vector", "coach fan denial rifle frost rival join install one wasp cool antique", "", DeterministicSeed.Type.ELECTRUM),
                new SeedCase("evm-development-mnemonic", "test test test test test test test test test test test junk", "", DeterministicSeed.Type.BIP39));
        List<Object> cases = new ArrayList<>();
        for(SeedCase seedCase : seeds) {
            DeterministicSeed seed = new DeterministicSeed(seedCase.mnemonic(), seedCase.passphrase(), 0, seedCase.type());
            byte[] seedBytes = seed.getSeedBytes();
            List<Object> accounts = new ArrayList<>();
            for(int index = 0; index < 2; index++) {
                //The same-seed LitVM link, as the desktop makes it
                EvmAddress address = LitVmLink.fromWalletSeed(seed, index).getAddress();
                Assertions.assertEquals(EvmAccount.fromSeed(seed, index).getAddress(), address);
                //The same steps as EvmAccount.fromSeed, to give the public key between them
                DeterministicKey current = HDKeyDerivation.createMasterPrivateKey(seedBytes);
                for(ChildNumber child : KeyDerivation.parsePath(EvmAccount.DERIVATION_PREFIX + index)) {
                    current = HDKeyDerivation.deriveChildKey(current, child);
                }
                ECKey key = ECKey.fromPrivate(current.getPrivKeyBytes());
                Assertions.assertEquals(address, EvmAddress.fromPublicKey(key));
                accounts.add(map("index", index, "path", EvmAccount.DERIVATION_PREFIX + index, "publicKey", hex(key.getPubKey(false)),
                        "address", address.toString()));
            }
            cases.add(map("name", seedCase.name(), "mnemonic", seedCase.mnemonic(), "passphrase", seedCase.passphrase(),
                    "seedType", seedCase.type().name(), "seed", hex(seedBytes), "accounts", accounts));
        }
        return cases;
    }

    private static List<Object> eip55() {
        //The examples in EIP-55 itself: all caps, all lower, then normal
        List<String> examples = List.of("0x52908400098527886E0F7030069857D2E4169EE7", "0x8617E340B3D01FA5F11F306F4090FD50E238070D",
                "0xde709f2102306220921060314715629080e2fb77", "0x27b1fdb04752bbc536007a920d24acb045561c26",
                "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
                "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb");
        Map<String, String> inputs = new LinkedHashMap<>();
        for(String example : examples) {
            String digits = example.substring(2);
            inputs.putIfAbsent(example, "as in EIP-55");
            inputs.putIfAbsent("0x" + digits.toLowerCase(Locale.ROOT), "all lower case");
            inputs.putIfAbsent("0x" + digits.toUpperCase(Locale.ROOT), "all upper case");
            inputs.putIfAbsent(flipLastLetter(example), "one letter's case flipped");
            inputs.putIfAbsent(digits, "no 0x prefix");
            inputs.putIfAbsent("0X" + digits, "0X prefix");
        }
        String normal = examples.get(4);
        inputs.putIfAbsent(" " + normal + " ", "spaces around");
        inputs.putIfAbsent("\t" + normal + "\n", "tab before, line feed after");
        inputs.putIfAbsent("\u00a0" + normal, "no-break space before");
        inputs.putIfAbsent(normal.substring(0, 41), "39 digits");
        inputs.putIfAbsent(normal + "a", "41 digits");
        inputs.putIfAbsent("0x" + "g" + normal.substring(3), "a non-hex digit");
        inputs.putIfAbsent("0x0x" + normal.substring(4), "prefix twice");
        inputs.putIfAbsent("", "empty");
        inputs.putIfAbsent("0x", "prefix only");
        inputs.putIfAbsent("0x1234", "too short");
        inputs.putIfAbsent("ltc1qxyz", "a Litecoin address");
        inputs.putIfAbsent("0x000000000000000000000000000000000000dEaD", "the dead address, checksummed");
        inputs.putIfAbsent("0x000000000000000000000000000000000000dead", "the dead address, lower case");
        inputs.putIfAbsent("0x000000000000000000000000000000000000DEAD", "the dead address, upper case");
        inputs.putIfAbsent("0x000000000000000000000000000000000000DeAD", "the dead address, wrong mixed case");
        inputs.putIfAbsent("0x" + "\uff10".repeat(40), "fullwidth digits");

        List<Object> cases = new ArrayList<>();
        for(Map.Entry<String, String> input : inputs.entrySet()) {
            boolean valid = EvmAddress.isValid(input.getKey());
            //The phone accepts only "0x" and 40 hex digits (mixed case passing EIP-55): on that shape it must agree with the
            //desktop; any other form the desktop accepts is its leniency, which the phone refuses
            String kind = canonicalShape(input.getKey()) ? "canonical" : valid ? "desktopLeniency" : "notAnAddress";
            Map<String, Object> entry = map("label", input.getValue(), "input", input.getKey(), "kind", kind, "valid", valid,
                    "phoneValid", valid && kind.equals("canonical"));
            if(valid) {
                entry.put("address", EvmAddress.fromString(input.getKey()).toString());
            }
            cases.add(entry);
        }
        return cases;
    }

    private static boolean canonicalShape(String text) {
        if(text.length() != 42 || !text.startsWith("0x")) {
            return false;
        }
        for(int i = 2; i < text.length(); i++) {
            char c = text.charAt(i);
            if(!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    /** What MiniJson, the strict reader metadata goes through, accepts (written back canonically) and refuses. */
    private static List<Object> json() {
        List<Object[]> inputs = new ArrayList<>();
        //Whitespace
        inputs.add(new Object[] {"space, tab, line feed and carriage return everywhere", " \t\r\n{ \t\r\n\"a\" \t\r\n: \t\r\n1 \t\r\n} \t\r\n"});
        for(String space : List.of("\u2003", "\u00a0", "\u000b", "\u000c", "\u001c", "\u0085", "\u3000", "\ufeff")) {
            String code = String.format(Locale.ROOT, "U+%04X", (int)space.charAt(0));
            inputs.add(new Object[] {code + " inside", "{" + space + "\"a\":1}"});
            inputs.add(new Object[] {code + " before", space + "{\"a\":1}"});
        }
        //Escapes
        inputs.add(new Object[] {"every short escape", "\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t\""});
        inputs.add(new Object[] {"unicode escapes, both cases", "\"\\u0041\\u004a\\u00e9\\u00E9\""});
        inputs.add(new Object[] {"an escaped pair", "\"\\ud83d\\ude00\""});
        inputs.add(new Object[] {"an escaped lone surrogate", "\"a\\ud800b\""});
        for(String escape : List.of("\\u+041", "\\u-041", "\\u\u0660\u0660\u0664\u0661", "\\u\uff10\uff10\uff14\uff21", "\\u041\"", "\\u004g", "\\uZZZZ", "\\x41",
                "\\u00")) {
            inputs.add(new Object[] {"escape " + escape, "\"" + escape + "\""});
        }
        //Numbers
        for(String number : List.of("0", "-0", "5", "-5", "0.5", "-0.5", "1.50", "1e5", "1E+5", "1e-5", "1.25e2", "-0.0", "1e-100", "1e100", "1e-101", "1e101",
                "+5", ".5", "5.", "007", "-01", "01.5", "-.5", "1e", "1e+", "1.e5", "-", "--1", "1.2.3", "0x10", "1ee5", "Infinity", "NaN", "1".repeat(80),
                "1".repeat(81))) {
            inputs.add(new Object[] {"number " + (number.length() > 20 ? number.length() + " digits" : number), "[" + number + "]"});
        }
        //Strings
        for(int c : new int[] {0x00, 0x01, 0x09, 0x0A, 0x0D, 0x1F, 0x7F, 0x85}) {
            inputs.add(new Object[] {String.format(Locale.ROOT, "raw U+%04X in a string", c), "\"a" + (char)c + "b\""});
        }
        inputs.add(new Object[] {"unterminated string", "\"abc"});
        inputs.add(new Object[] {"raw non-ASCII", "\"\u00e9\u20ac\ud83d\ude00\""});
        //Structure
        inputs.add(new Object[] {"duplicate keys keep the last value in the first place", "{\"a\":1,\"b\":2,\"a\":3}"});
        inputs.add(new Object[] {"literals", "[true,false,null]"});
        inputs.add(new Object[] {"a word that only starts like true", "[trueish]"});
        inputs.add(new Object[] {"trailing comma", "[1,]"});
        inputs.add(new Object[] {"trailing characters", "{} x"});
        inputs.add(new Object[] {"empty input", ""});
        inputs.add(new Object[] {"a bare string at the top", "\"x\""});
        inputs.add(new Object[] {"nested 64 deep", "[".repeat(64) + "]".repeat(64)});
        inputs.add(new Object[] {"nested 65 deep", "[".repeat(65) + "]".repeat(65)});
        inputs.add(new Object[] {"objects count toward the depth", "{\"a\":" + "[".repeat(63) + "]".repeat(63) + "}"});
        inputs.add(new Object[] {"objects count toward the depth, one too many", "{\"a\":" + "[".repeat(64) + "]".repeat(64) + "}"});
        inputs.add(new Object[] {"an unquoted key", "{a:1}"});
        inputs.add(new Object[] {"single quotes", "['a']"});

        List<Object> cases = new ArrayList<>();
        for(Object[] input : inputs) {
            Map<String, Object> entry = map("label", input[0], "input", input[1]);
            try {
                entry.put("canonical", canonical(MiniJson.parse((String)input[1])));
            } catch(IllegalArgumentException e) {
                entry.put("refused", true);
            }
            cases.add(entry);
        }
        return cases;
    }

    /**
     * A parsed value written back compactly: objects in their map's order, strings quoted as this file quotes them,
     * numbers as BigDecimal.toPlainString.
     */
    private static String canonical(Object value) {
        if(value == null) {
            return "null";
        }
        if(value instanceof String text) {
            return quote(text);
        }
        if(value instanceof Boolean flag) {
            return flag.toString();
        }
        if(value instanceof java.math.BigDecimal number) {
            return number.toPlainString();
        }
        if(value instanceof Map<?, ?> map) {
            List<String> fields = new ArrayList<>();
            for(Map.Entry<?, ?> field : map.entrySet()) {
                fields.add(quote((String)field.getKey()) + ":" + canonical(field.getValue()));
            }
            return "{" + String.join(",", fields) + "}";
        }
        List<String> items = new ArrayList<>();
        for(Object item : (List<?>)value) {
            items.add(canonical(item));
        }
        return "[" + String.join(",", items) + "]";
    }

    private static String flipLastLetter(String address) {
        char[] chars = address.toCharArray();
        for(int i = chars.length - 1; i >= 2; i--) {
            if(Character.isLetter(chars[i])) {
                chars[i] = Character.isUpperCase(chars[i]) ? Character.toLowerCase(chars[i]) : Character.toUpperCase(chars[i]);
                break;
            }
        }
        return new String(chars);
    }

    private static Map<String, Object> abi() {
        List<String> signatures = List.of("supportsInterface(bytes4)", "name()", "symbol()", "balanceOf(address)", "ownerOf(uint256)", "tokenURI(uint256)",
                "tokenOfOwnerByIndex(address,uint256)", "worlds()");
        List<Object> selectors = new ArrayList<>();
        for(String signature : signatures) {
            selectors.add(map("signature", signature, "selector", hex(Abi.selector(signature))));
        }

        BigInteger world1 = KnownCollections.vaultBadgeTokenId(HOLDER, 1);
        BigInteger max = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);
        List<Object> calls = new ArrayList<>();
        for(String id : List.of("01ffc9a7", "80ac58cd", "ffffffff", "780e9d63", "b45a3c0e")) {
            //As Erc721 asks: the bytes4 left-aligned in its word
            byte[] word = new byte[32];
            System.arraycopy(HexFormat.of().parseHex(id), 0, word, 0, 4);
            calls.add(call("supportsInterface(bytes4)", List.of(map("bytes4", id)), Abi.encodeCall("supportsInterface(bytes4)", (Object)word)));
        }
        //Erc721 and KnownCollections send these as the bare selector
        for(String signature : List.of("name()", "symbol()", "worlds()")) {
            calls.add(call(signature, List.of(), Abi.selector(signature)));
        }
        for(EvmAddress owner : List.of(HOLDER, DEAD)) {
            calls.add(call("balanceOf(address)", List.of(map("address", owner.toString())), Abi.encodeCall("balanceOf(address)", owner)));
        }
        for(String signature : List.of("ownerOf(uint256)", "tokenURI(uint256)")) {
            for(BigInteger id : List.of(world1, BigInteger.ZERO, max)) {
                calls.add(call(signature, List.of(map("uint256", id.toString())), Abi.encodeCall(signature, id)));
            }
        }
        for(Object[] args : List.of(new Object[] {HOLDER, BigInteger.ZERO}, new Object[] {HOLDER, BigInteger.valueOf(7)}, new Object[] {DEAD, max})) {
            calls.add(call("tokenOfOwnerByIndex(address,uint256)", List.of(map("address", args[0].toString()), map("uint256", args[1].toString())),
                    Abi.encodeCall("tokenOfOwnerByIndex(address,uint256)", args)));
        }

        return map("selectors", selectors, "calls", calls, "bools", bools(), "uints", uints(), "addresses", addressResults(), "strings", strings());
    }

    private static Map<String, Object> call(String signature, List<Object> args, byte[] data) {
        return map("signature", signature, "args", args, "data", hex(data));
    }

    private static List<Object> bools() {
        List<Object> cases = new ArrayList<>();
        List<Object[]> results = List.of(new Object[] {"0", word(0)}, new Object[] {"1", word(1)}, new Object[] {"2", word(2)},
                new Object[] {"a huge word", "ff".repeat(32)}, new Object[] {"high bit only", "80" + "00".repeat(31)},
                new Object[] {"1 then another word", word(1) + word(5)}, new Object[] {"1 then one more byte", word(1) + "00"},
                new Object[] {"31 bytes", "00".repeat(30) + "01"}, new Object[] {"empty", ""});
        for(Object[] result : results) {
            Map<String, Object> entry = map("label", result[0], "result", result[1]);
            try {
                entry.put("value", Abi.decodeBool(unhex((String)result[1]), 0));
            } catch(IllegalArgumentException e) {
                entry.put("refused", true);
            }
            cases.add(entry);
        }
        return cases;
    }

    private static List<Object> uints() {
        List<Object> cases = new ArrayList<>();
        List<Object[]> results = List.of(new Object[] {"0", word(0)}, new Object[] {"1", word(1)}, new Object[] {"4441", word(4441)},
                new Object[] {"2^256 - 1", "ff".repeat(32)}, new Object[] {"2^255", "80" + "00".repeat(31)},
                new Object[] {"7 then another word", word(7) + word(9)}, new Object[] {"7 then one more byte", word(7) + "ab"},
                new Object[] {"31 bytes", "00".repeat(30) + "07"}, new Object[] {"empty", ""});
        for(Object[] result : results) {
            Map<String, Object> entry = map("label", result[0], "result", result[1]);
            try {
                entry.put("value", Abi.decodeUint(unhex((String)result[1]), 0).toString());
            } catch(IllegalArgumentException e) {
                entry.put("refused", true);
            }
            cases.add(entry);
        }
        return cases;
    }

    private static List<Object> addressResults() {
        String holder = hex(HOLDER.getBytes());
        List<Object> cases = new ArrayList<>();
        List<Object[]> results = List.of(new Object[] {"an address", "00".repeat(12) + holder}, new Object[] {"the zero address", word(0)},
                new Object[] {"dirty first byte", "01" + "00".repeat(11) + holder}, new Object[] {"dirty twelfth byte", "00".repeat(11) + "01" + holder},
                new Object[] {"all ones", "ff".repeat(32)}, new Object[] {"an address then another word", "00".repeat(12) + holder + word(1)},
                new Object[] {"20 bytes only", holder}, new Object[] {"empty", ""});
        for(Object[] result : results) {
            Map<String, Object> entry = map("label", result[0], "result", result[1]);
            try {
                entry.put("value", Abi.decodeAddress(unhex((String)result[1]), 0).toString());
            } catch(IllegalArgumentException e) {
                entry.put("refused", true);
            }
            cases.add(entry);
        }
        return cases;
    }

    private static List<Object> strings() {
        List<Object> cases = new ArrayList<>();
        stringCase(cases, "empty string", hex(Abi.encodeArguments("")));
        stringCase(cases, "short", hex(Abi.encodeArguments("hello world")));
        stringCase(cases, "the badge's symbol", hex(Abi.encodeArguments("SLVB")));
        for(int length : new int[] {4_096, 4_097, 99_936, 99_937, RAW_LIMIT, RAW_LIMIT + 1}) {
            String label = switch(length) {
                case 99_936 -> "99,936 bytes: the longest string whose result fits the 100,000-byte call cap";
                case 99_937 -> "99,937 bytes: decode only, the call cap refuses its 100,064-byte result first";
                case RAW_LIMIT, RAW_LIMIT + 1 -> String.format(Locale.ROOT, "%,d bytes: decode only, the call cap refuses this result first", length);
                default -> String.format(Locale.ROOT, "%,d bytes", length);
            };
            int padding = (32 - length % 32) % 32;
            List<Object> parts = List.of(word(32) + word(length), rep("61", length), "00".repeat(padding));
            Assertions.assertEquals(hex(Abi.encodeArguments("a".repeat(length))), build(parts));
            stringCase(cases, label, parts);
        }
        //The limit counts UTF-8 bytes, not characters
        List<Object> twoByte = List.of(word(32) + word(4_096), rep("c3a9", 2_048));
        Assertions.assertEquals(hex(Abi.encodeArguments("\u00e9".repeat(2_048))), build(twoByte));
        stringCase(cases, "2,048 two-byte characters (4,096 bytes)", twoByte);
        List<Object> twoByteAndOne = List.of(word(32) + word(4_097), rep("c3a9", 2_048), "61" + "00".repeat(31));
        Assertions.assertEquals(hex(Abi.encodeArguments("\u00e9".repeat(2_048) + "a")), build(twoByteAndOne));
        stringCase(cases, "2,048 two-byte characters and one more byte (4,097 bytes)", twoByteAndOne);

        String abc = word(3) + "616263" + "00".repeat(29);
        stringCase(cases, "offset past the end", word(64) + word(0));
        stringCase(cases, "offset 64, not 32", word(64) + word(0) + abc);
        stringCase(cases, "offset 0", word(0) + word(0));
        stringCase(cases, "64 zero bytes", "00".repeat(64));
        stringCase(cases, "offset 1, unaligned", word(1) + "00".repeat(32) + "61".repeat(300));
        stringCase(cases, "offset with high bits set", "ff".repeat(32) + abc);
        stringCase(cases, "offset 2^31", "00".repeat(28) + "80000000" + abc);
        stringCase(cases, "offset 2^31 - 1", "00".repeat(28) + "7fffffff" + abc);
        stringCase(cases, "length larger than the remaining data", word(32) + word(33) + "61".repeat(32));
        stringCase(cases, "length 2^31 - 1", word(32) + "00".repeat(28) + "7fffffff" + "61".repeat(32));
        stringCase(cases, "length 2^31", word(32) + "00".repeat(28) + "80000000" + "61".repeat(32));
        stringCase(cases, "length with every bit set", word(32) + "ff".repeat(32));
        stringCase(cases, "length 2^255", word(32) + "80" + "00".repeat(31) + "61".repeat(32));
        stringCase(cases, "no padding after the text", word(32) + word(3) + "616263");
        stringCase(cases, "padding not zero", word(32) + word(3) + "616263" + "ff".repeat(29));
        stringCase(cases, "more data after the padding", word(32) + abc + word(5));
        stringCase(cases, "63 bytes", word(32) + "00".repeat(31));
        stringCase(cases, "33 bytes", word(32) + "00");
        stringCase(cases, "31 bytes", "00".repeat(31));
        stringCase(cases, "empty result", "");

        //Bytes that are not UTF-8 are decoded leniently here (unlike metadata, which refuses them)
        for(String bytes : List.of("c328", "ff", "f09f98", "f09f9880", "eda080", "eda080edb080", "c080", "e282" + "61", "f4908080", "80", "fe", "61ff62")) {
            stringCase(cases, "bytes " + bytes, hex(Abi.encodeArguments(new Abi.Dynamic(unhex(bytes)))));
        }

        //A 32-byte result is read as a bytes32 name or symbol, trimmed of trailing zero bytes
        stringCase(cases, "bytes32 MKR", hex("MKR".getBytes(StandardCharsets.US_ASCII)) + "00".repeat(29));
        stringCase(cases, "bytes32 all zero", "00".repeat(32));
        stringCase(cases, "bytes32 that is the word 32", word(32));
        stringCase(cases, "bytes32 with a zero inside", "410042" + "00".repeat(29));
        stringCase(cases, "bytes32 with no zero", "61".repeat(32));
        stringCase(cases, "bytes32 not UTF-8", "ff" + "00".repeat(31));
        stringCase(cases, "bytes32 with leading zeros", "0000" + "6162" + "00".repeat(28));
        return cases;
    }

    private static void stringCase(List<Object> cases, String label, Object result) {
        Map<String, Object> entry = map("label", label);
        String text = put(entry, "result", result);
        byte[] bytes = unhex(text);
        entry.put("limit4096", decoded(() -> Abi.decodeString(bytes)));
        entry.put("limit100000", decoded(() -> Abi.decodeString(bytes, RAW_LIMIT)));
        cases.add(entry);
    }

    private interface Decode {
        String run();
    }

    private static Map<String, Object> decoded(Decode decode) {
        try {
            return textValue("value", decode.run());
        } catch(IllegalArgumentException e) {
            return map("refused", true);
        }
    }

    private static Map<String, Object> textValue(String key, String value) {
        Map<String, Object> entry = new LinkedHashMap<>();
        if(value.length() <= LITERAL_MAX) {
            entry.put(key, value);
        } else {
            entry.put(key + "Length", value.length());
            entry.put(key + "Sha256", sha256(value));
        }
        return entry;
    }

    private static Map<String, Object> metadata() throws Exception {
        List<Object> uris = new ArrayList<>();
        String world1 = resource("vault-badge-world-1-token-uri.txt");
        Assertions.assertEquals(world1, vaultBadgeUri(1), "the fixture is what the contract builds");
        meta(uris, "Vault Badge World 1, as LiteForge returned it", world1);
        meta(uris, "Vault Badge World 2, built as the contract builds it", vaultBadgeUri(2));
        meta(uris, "Vault Badge World 1 JSON, bare", new String(Base64.getDecoder().decode(world1.substring("data:application/json;base64,".length())),
                StandardCharsets.UTF_8));

        //Bare JSON and plain data URIs
        meta(uris, "bare", "{\"name\":\"A\"}");
        meta(uris, "bare with whitespace around", "  \n{\"name\":\"A\"} ");
        meta(uris, "plain data URI", "data:application/json,{\"name\":\"B\"}");
        meta(uris, "percent-encoded, a plus sign stays a plus", "data:application/json,%7B%22name%22%3A%22C+D%22%7D");
        meta(uris, "percent-encoded UTF-8", "data:application/json,%7B%22name%22%3A%22C%20D%20%C3%A9%22%7D");
        meta(uris, "charset utf-8", "data:application/json;charset=utf-8,{\"name\":\"E\"}");
        meta(uris, "upper-case scheme and type, a bare utf8 parameter", "DATA:Application/JSON;utf8,{\"name\":\"F\"}");
        meta(uris, "charset then base64", "data:application/json;charset=UTF-8;base64," + base64("{\"name\":\"G\"}"));
        meta(uris, "charset us-ascii", "data:application/json;charset=us-ascii,{\"name\":\"H\"}");
        meta(uris, "charset utf8", "data:application/json;charset=utf8,{\"name\":\"H\"}");
        meta(uris, "spaces around the media type and parameters", "data: application/json ; base64 ," + base64("{\"name\":\"S\"}"));
        meta(uris, "truncated escape", "data:application/json,%7B%22name%22:%22H%22%7");
        meta(uris, "a percent sign not followed by two hex digits", "data:application/json,{\"name\":\"50%\"}");
        meta(uris, "escape with non-hex digits", "data:application/json,{\"name\":\"%zz\"}");
        meta(uris, "escape with Arabic-Indic digits", "data:application/json,{\"name\":\"%\u0663\u0663\"}");
        meta(uris, "escape that is not UTF-8", "data:application/json,{\"name\":\"%C3\"}");
        meta(uris, "charset iso-8859-1", "data:application/json;charset=iso-8859-1,{\"name\":\"I\"}");
        meta(uris, "base64 not last", "data:application/json;base64;charset=utf-8," + base64("{\"name\":\"J\"}"));
        meta(uris, "an unpaired surrogate in a plain data URI", "data:application/json,{\"name\":\"a\ud800b\"}");

        //Token URIs that are not read
        for(String uri : List.of("https://example.com/1.json", "http://example.com/1.json", "ipfs://QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG/1",
                "ar://abc", "data:text/html,<h1>hi</h1>", "data:,{\"name\":\"x\"}", "data:application/json;base64", "javascript:alert(1)", "", " ",
                "[{\"name\":\"array\"}]", "\"name\"", "null")) {
            meta(uris, "not read", uri);
        }

        //Images, inside JSON as the test reads them
        meta(uris, "plain SVG data URI", imageJson("data:image/svg+xml,%3Csvg viewBox='0 0 1 1'%3E%3C/svg%3E"));
        meta(uris, "base64 SVG data URI", imageJson("data:image/svg+xml;base64," + base64(vaultBadgeSvg("1"))));
        meta(uris, "hosted", imageJson("https://img.example.com/a.png"));
        meta(uris, "hosted, user info, port, query and fragment", imageJson("HTTP://user:pw@Example.COM:8080/x?y#z"));
        meta(uris, "IPFS", imageJson("ipfs://QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG"));
        meta(uris, "IPFS, upper case", imageJson("IPFS://x"));
        meta(uris, "Arweave", imageJson("ar://abc"));
        meta(uris, "decoy before @", imageJson("https://good.example@evil.example/x"));
        meta(uris, "backslashes before @", imageJson("https://evil.example\\\\@good.example/x"));
        meta(uris, "empty port", imageJson("https://example.com:/x"));
        meta(uris, "empty user info", imageJson("https://@example.com/x"));
        meta(uris, "nothing after @", imageJson("https://user@/x"));
        meta(uris, "trailing dot", imageJson("https://example.com./x"));
        meta(uris, "upper-case https", imageJson("HTTPS://Example.com"));
        for(String odd : List.of("https:///x", "https://ex%61mple.com/", "https://\u212Aelvin.example/", "https://\u0435xample.com/", "https://[::1]/x",
                "https://example.com:80a/", "https://ex ample.com/", "ipfs:/x")) {
            meta(uris, "odd host or scheme", imageJson(odd));
        }
        String host253 = "a".repeat(249) + ".com";
        meta(uris, "a 253-character host", imageJson("https://" + host253 + "/x.png"));
        meta(uris, "a 254-character host", imageJson("https://a" + host253 + "/x.png"));
        for(String image : List.of("javascript:alert(1)", "file:///etc/passwd", "blob:https://example.com/0f1e", "data:text/html,<script>alert(1)</script>",
                "data:text/html;base64,PHNjcmlwdD4=", "//example.com/a.png", "a.png", "ftp://example.com/a.png", "data:image/svg+xml;base64,", " ")) {
            meta(uris, "unknown image scheme", imageJson(image.replace("\"", "\\\"")));
        }
        byte[] png = {(byte)0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 1, 2};
        for(String type : List.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")) {
            meta(uris, "raster " + type, imageJson("data:" + type + ";base64," + Base64.getEncoder().encodeToString(png)));
        }
        meta(uris, "raster not base64", imageJson("data:image/png,%89PNG"));
        meta(uris, "raster unpadded", imageJson("data:image/png;base64,iVBORw0KGgo"));
        meta(uris, "bad image base64 loses the picture only", "{\"name\":\"n\",\"image\":\"data:image/svg+xml;base64,PHN2Zz4!\"}");
        meta(uris, "image_data raw SVG", "{\"name\":\"x\",\"image_data\":\"<svg viewBox='0 0 1 1'></svg>\"}");
        meta(uris, "image_data raw SVG with whitespace before", "{\"name\":\"x\",\"image_data\":\"  \\n<svg></svg> \"}");
        meta(uris, "image_data as a data URI", "{\"image_data\":\"data:image/svg+xml,<svg></svg>\"}");
        meta(uris, "image_data blank", "{\"image_data\":\"   \"}");
        meta(uris, "image wins when it can be shown", "{\"image\":\"https://example.com/a.png\",\"image_data\":\"<svg></svg>\"}");
        meta(uris, "image_data when image cannot be shown", "{\"image\":\"javascript:x\",\"image_data\":\"<svg></svg>\"}");
        meta(uris, "image_data when image is not text", "{\"image\":5,\"image_data\":\"<svg></svg>\"}");

        //Sizes
        String head = "{\"name\":\"";
        List<Object> fits = List.of(head, rep("a", 20_000 - head.length() - 2), "\"}");
        List<Object> over = List.of(head, rep("a", 20_001 - head.length() - 2), "\"}");
        meta(uris, "JSON of exactly 20,000 characters", fits);
        meta(uris, "JSON of exactly 20,000 characters, base64", concat("data:application/json;base64,", b64(fits)));
        meta(uris, "JSON of 20,001 characters", over);
        meta(uris, "JSON of 20,001 characters, base64", concat("data:application/json;base64,", b64(over)));
        meta(uris, "JSON of 20,001 characters, plain data URI", concat("data:application/json,", over));
        meta(uris, "too long to be a token URI", List.of("data:application/json;base64,", b64(head, rep("a", 80_000), "\"}")));
        meta(uris, "base64 too long for 20,000 characters", List.of("data:application/json;base64,", rep("AAAA", 20_001)));
        //A URI of 100,000 characters whose JSON is well under its limit: 9 characters of URI per character of JSON, then spaces
        String euroHead = "data:application/json,%7B%22name%22%3A%22";
        int euros = 11_105;
        int spaces = 100_000 - euroHead.length() - 9 * euros - "%22%7D".length();
        meta(uris, "a token URI of exactly 100,000 characters", List.of(euroHead, rep("%E2%82%AC", euros), "%22%7D", rep(" ", spaces)));
        meta(uris, "a token URI of 100,001 characters", List.of(euroHead, rep("%E2%82%AC", euros), "%22%7D", rep(" ", spaces + 1)));

        //What MiniJson reads and refuses
        meta(uris, "nested 100 deep", "{\"a\":" + "[".repeat(100) + "]".repeat(100) + "}");
        meta(uris, "nested 64 deep", "{\"name\":\"x\",\"a\":" + "[".repeat(63) + "]".repeat(63) + "}");
        meta(uris, "nested 65 deep", "{\"name\":\"x\",\"a\":" + "[".repeat(64) + "]".repeat(64) + "}");
        meta(uris, "a huge exponent", "{\"name\":\"x\",\"n\":1e999999999}");
        meta(uris, "unterminated", "{\"name\":\"x\"");
        meta(uris, "trailing brace", "{\"name\":\"x\"}}");
        meta(uris, "bad unicode escape", "{\"name\":\"\\uZZZZ\"}");
        meta(uris, "short unicode escape at the end", "{\"name\":\"\\u41");
        meta(uris, "unicode escape with a plus sign", "{\"name\":\"\\u+041\"}");
        meta(uris, "unicode escape with a minus sign", "{\"name\":\"\\u-041\"}");
        meta(uris, "unicode escape with Arabic-Indic digits", "{\"name\":\"\\u\u0660\u0660\u0664\u0661\"}");
        meta(uris, "unicode escape with fullwidth digits", "{\"name\":\"\\u\uff10\uff10\uff14\uff21\"}");
        meta(uris, "escaped solidus and controls", "{\"name\":\"a\\/b\\tc\\nd\\be\\ff\\rg\"}");
        meta(uris, "unknown escape", "{\"name\":\"\\x41\"}");
        meta(uris, "raw control character in a string", "{\"name\":\"a\u0001b\"}");
        meta(uris, "raw tab and line feed in a string", "{\"name\":\"a\tb\nc\"}");
        meta(uris, "em space between tokens", "{\u2003\"name\"\u2003:\u2003\"A\"\u2003}");
        meta(uris, "no-break space between tokens", "{\u00a0\"name\":\"A\"}");
        meta(uris, "em space before the brace", "\u2003{\"name\":\"A\"}");
        meta(uris, "information separator before the brace", "\u001c{\"name\":\"A\"}");
        meta(uris, "byte order mark before the brace", "\ufeff{\"name\":\"A\"}");
        meta(uris, "word that only starts like true", "{\"name\":\"x\",\"b\":trueish}");
        meta(uris, "empty object", "{}");
        meta(uris, "image null", "{\"image\":null}");
        meta(uris, "wrong types read as absent", "{\"name\":5,\"description\":{\"a\":1},\"attributes\":\"many\",\"image\":7}");
        meta(uris, "attributes of every kind", "{\"attributes\":[1,\"a\",null,[],{\"trait_type\":5,\"value\":\"v\"},{\"trait_type\":\"T\",\"value\":{}},"
                + "{\"trait_type\":\"U\"},{\"trait_type\":\"B\",\"value\":true},{\"trait_type\":\"N\",\"value\":1.50},{\"value\":\"only\"}]}");
        meta(uris, "numbers too long to show are dropped", "{\"attributes\":[{\"trait_type\":\"A\",\"value\":1e99},{\"trait_type\":\"B\",\"value\":"
                + "8".repeat(80) + "},{\"trait_type\":\"C\",\"value\":" + "9".repeat(64) + "},{\"trait_type\":\"D\",\"value\":\"" + "7".repeat(80) + "\"}]}");
        meta(uris, "a number of 81 characters", "{\"name\":\"x\",\"attributes\":[{\"trait_type\":\"N\",\"value\":" + "1".repeat(81) + "}]}");
        StringBuilder numbers = new StringBuilder("{\"name\":\"x\",\"attributes\":[");
        List<String> forms = List.of("+5", ".5", "5.", "007", "-0", "-0.0", "1e2", "1E+2", "1.0e-2", "1e-100", "1e100", "-1.25", "0.000", "12345678901234567890");
        for(int i = 0; i < forms.size(); i++) {
            numbers.append(i == 0 ? "" : ",").append("{\"trait_type\":\"").append(forms.get(i)).append("\",\"value\":").append(forms.get(i)).append("}");
        }
        meta(uris, "number forms", numbers.append("]}").toString());
        meta(uris, "scale 101", "{\"name\":\"x\",\"n\":1e-101}");
        meta(uris, "scale -101", "{\"name\":\"x\",\"n\":1e101}");
        meta(uris, "malformed number", "{\"name\":\"x\",\"n\":1.2.3}");
        meta(uris, "a paragraph separator, raw", "{\"name\":\"one\u2029two\"}");
        meta(uris, "a paragraph separator, escaped", "{\"name\":\"one\\u2029two\"}");
        meta(uris, "duplicate keys keep the last", "{\"name\":\"First\",\"name\":\"Second\"}");
        meta(uris, "duplicate keys, last of the wrong type", "{\"name\":\"First\",\"name\":7}");
        meta(uris, "duplicate image keys", "{\"image\":\"https://a.example/x\",\"image\":\"ipfs://y\"}");
        StringBuilder many = new StringBuilder("{\"attributes\":[");
        for(int i = 0; i < 25; i++) {
            many.append(i == 0 ? "" : ",").append("{\"trait_type\":\"T").append(i).append("\",\"value\":").append(i).append("}");
        }
        meta(uris, "25 attributes", many.append("]}").toString());
        StringBuilder skipped = new StringBuilder("{\"attributes\":[");
        for(int i = 0; i < 22; i++) {
            skipped.append(i == 0 ? "" : ",").append(i % 2 == 0 ? "{\"trait_type\":\"T" + i + "\"}" : "{\"trait_type\":\"T" + i + "\",\"value\":" + i + "}");
        }
        meta(uris, "attributes without a value do not count", skipped.append("]}").toString());
        StringBuilder twentyOne = new StringBuilder("{\"attributes\":[");
        for(int i = 0; i < 21; i++) {
            twentyOne.append(i == 0 ? "" : ",").append("{\"trait_type\":\"T").append(i).append("\",\"value\":\"v").append(i).append("\"}");
        }
        meta(uris, "21 attributes", twentyOne.append("]}").toString());

        //Text is cleaned and capped
        meta(uris, "a raw bell in a string", "{\"name\":\"a\u0007b\"}");
        String hostile = "\u202eRight\u200bto\u2066left\u2028\\u0007\\n";
        meta(uris, "hostile name", List.of("{\"name\":\"" + hostile, rep("x", 5_000), "\",\"description\":\"short\"}"));
        meta(uris, "hostile description", List.of("{\"description\":\"" + hostile, rep("x", 5_000), "\"}"));
        meta(uris, "hostile attribute", List.of("{\"attributes\":[{\"trait_type\":\"" + hostile, rep("x", 5_000), "\",\"value\":\"" + hostile,
                rep("x", 5_000), "\"}]}"));
        meta(uris, "an escaped unpaired surrogate", "{\"name\":\"a\\ud800b\"}");
        meta(uris, "an escaped surrogate pair", "{\"name\":\"a\\ud83d\\ude00b\"}");
        meta(uris, "a pair that would be split at the cap", "{\"name\":\"" + "x".repeat(63) + "\ud83d\ude00\"}");
        meta(uris, "a pair that fits the cap", "{\"name\":\"" + "x".repeat(62) + "\ud83d\ude00y\"}");
        meta(uris, "a space left at the cap", "{\"name\":\"" + "x".repeat(63) + " y\"}");
        meta(uris, "spaces around the name", "{\"name\":\"  padded  \"}");
        meta(uris, "variation selector and a tag", "{\"name\":\"\u2764\ufe0f\udb40\udc61\"}");
        meta(uris, "the table, not the platform: U+2065, U+FE0F and U+E0100", "{\"name\":\"a\u2065b\ufe0fc\\udb40\\udd00d\",\"description\":\"a\u2065b\ufe00c\\udb40\\uddefd\","
                + "\"attributes\":[{\"trait_type\":\"T\u2065\",\"value\":\"\u2764\ufe0f\"}]}");
        meta(uris, "an escaped lone low surrogate", "{\"name\":\"a\\udc00b\"}");
        meta(uris, "a name of 64 code points with a pair at the end", "{\"name\":\"" + "x".repeat(63) + "\ud83d\ude00\"}");
        meta(uris, "a pair cut off at 64 code points", "{\"name\":\"" + "x".repeat(64) + "\ud83d\ude00\"}");
        meta(uris, "one line: tab, line feed and carriage return separate words",
                "{\"name\":\"  Vault\\tBadge\\r\\n World 1 \",\"attributes\":[{\"trait_type\":\" a\\n b \",\"value\":\"c\\t\\td\"}]}");
        meta(uris, "a description keeps single line breaks", "{\"description\":\"\\nFirst line.\\r\\n\\r\\n  Second line. \\n\\rThird\\rFourth\"}");
        StringBuilder lines = new StringBuilder("{\"description\":\"");
        for(int i = 1; i <= 25; i++) {
            lines.append("L").append(i).append("\\n");
        }
        meta(uris, "a description of 25 lines", lines.append("\"}").toString());
        meta(uris, "a description cut at a line break", "{\"description\":\"" + "x".repeat(499) + "\\ny\"}");
        meta(uris, "a description of 500 code points with a pair at the end", "{\"description\":\"" + "x".repeat(499) + "\ud83d\ude00\"}");
        meta(uris, "a scheme with a long s", imageJson("http\u017f://example.com/a.png"));
        meta(uris, "IPFS with a long s", imageJson("ipf\u017f://x"));
        meta(uris, "mixed-case scheme", imageJson("HtTpS://example.com/a.png"));
        meta(uris, "image_data of ASCII whitespace", "{\"image_data\":\" \\t\\n\"}");
        meta(uris, "image_data of an em space", "{\"image_data\":\"\u2003\"}");
        //The two inputs NftMetadataTest.nothingThrows adds to the fixtures
        meta(uris, "nothingThrows: percent-encoded with a hosted image",
                "data:application/json,%7B%22name%22%3A%22C%20D%22%2C%22image%22%3A%22https%3A%2F%2Fa.example%2Fx%22%7D");
        meta(uris, "nothingThrows: a short PNG and an escaped pair in an attribute",
                "{\"image\":\"data:image/png;base64,iVBORw0KGgo=\",\"attributes\":[{\"trait_type\":\"a\",\"value\":\"\\ud83d\\ude00\"}]}");

        //Base64
        String good = base64("{\"name\":\"ok\"}");
        meta(uris, "good base64", "data:application/json;base64," + good);
        meta(uris, "base64 of {}", "data:application/json;base64,e30=");
        for(String bad : List.of("eyJuYW1lIjoib2sifQ", "eyJuYW1lIjoib2sifQ=", good.substring(1), good + "=", good + "====", good.replace('e', '!'),
                " " + good, good.substring(0, 4) + "\n" + good.substring(4), good.substring(0, 4) + "-" + good.substring(5),
                good.substring(0, 4) + "_" + good.substring(5), "eyJuYW1lIjoib2sifQ==eyJu", "e31=", "e3==", "=e30")) {
            meta(uris, "bad base64", "data:application/json;base64," + bad);
        }

        //Images read directly, at their own limits (a token URI is too short to carry these)
        List<Object> images = new ArrayList<>();
        image(images, "a raster of exactly 400,000 bytes", List.of("data:image/png;base64,", b64(rep("\u0000", NftMetadata.MAX_RASTER_BYTES))));
        image(images, "a raster of 400,001 bytes", List.of("data:image/png;base64,", b64(rep("\u0000", NftMetadata.MAX_RASTER_BYTES + 1))));
        image(images, "base64 whose length alone proves it too large", List.of("data:image/png;base64,", rep("AAAA", 134_000)));
        image(images, "an SVG of exactly 100,000 characters", List.of("data:image/svg+xml,<svg>", rep("a", NftMetadata.MAX_SVG_CHARS - 11), "</svg>"));
        image(images, "an SVG of 100,011 characters", List.of("data:image/svg+xml,<svg>", rep("a", NftMetadata.MAX_SVG_CHARS), "</svg>"));
        image(images, "an SVG of 100,011 characters, base64", List.of("data:image/svg+xml;base64,", b64("<svg>", rep("a", NftMetadata.MAX_SVG_CHARS), "</svg>")));
        image(images, "an SVG of 100,000 two-byte characters, base64", List.of("data:image/svg+xml;base64,", b64("<svg>", rep("\u00e9", NftMetadata.MAX_SVG_CHARS - 11), "</svg>")));
        image(images, "an SVG of 33,333 three-byte characters, plain", List.of("data:image/svg+xml,<svg>", rep("%E2%82%AC", 33_333), "</svg>"));
        image(images, "a blank SVG", "data:image/svg+xml,%20%20");
        image(images, "an SVG of an em space", "data:image/svg+xml,%E2%80%83");
        image(images, "an SVG of ASCII whitespace", "data:image/svg+xml,%20%09%0A%0D");

        return map("tokenUris", uris, "images", images);
    }

    private static String imageJson(String image) {
        return "{\"name\":\"x\",\"image\":\"" + image + "\"}";
    }

    private static void meta(List<Object> cases, String label, Object input) {
        Map<String, Object> entry = map("label", label);
        String text = put(entry, "input", input);
        Optional<NftMetadata> parsed = NftMetadata.parse(text);
        if(parsed.isEmpty()) {
            entry.put("refused", true);
        } else {
            NftMetadata metadata = parsed.get();
            List<Object> attributes = new ArrayList<>();
            for(NftMetadata.Attribute attribute : metadata.attributes()) {
                attributes.add(map("name", attribute.name(), "value", attribute.value()));
            }
            entry.put("metadata", map("name", metadata.name(), "description", metadata.description(), "attributes", attributes,
                    "image", image(metadata.image())));
        }
        cases.add(entry);
    }

    private static void image(List<Object> cases, String label, Object input) {
        Map<String, Object> entry = map("label", label);
        String text = put(entry, "input", input);
        entry.put("image", image(NftMetadata.image(text)));
        cases.add(entry);
    }

    private static Map<String, Object> image(NftMetadata.Image image) {
        if(image instanceof NftMetadata.Image.Svg svg) {
            Map<String, Object> entry = map("kind", "svg", "sourceLength", svg.source().length(), "sourceSha256", sha256(svg.source()));
            if(svg.source().length() <= LITERAL_MAX) {
                entry.put("source", svg.source());
            }
            return entry;
        }
        if(image instanceof NftMetadata.Image.Raster raster) {
            return map("kind", "raster", "mediaType", raster.mediaType(), "byteLength", raster.bytes().length, "sha256", sha256(raster.bytes()));
        }
        if(image instanceof NftMetadata.Image.Hosted hosted) {
            return map("kind", "hosted", "host", hosted.host());
        }
        return map("kind", "none");
    }

    private static Map<String, Object> clean() {
        //The fixed table as ranges, read from TextRules itself
        List<Object> tableJson = new ArrayList<>();
        Set<Integer> boundaries = new TreeSet<>(List.of(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x2003, 0xD7FF, 0xD800, 0xDBFF, 0xDC00, 0xDFFF, 0xE000));
        for(int[] range : ranges(TextRules::stripped)) {
            tableJson.add(List.of(range[0], range[1]));
            for(int c : new int[] {range[0] - 1, range[0], range[1], range[1] + 1}) {
                if(c >= 0 && c <= Character.MAX_CODE_POINT) {
                    boundaries.add(c);
                }
            }
        }
        //Each code point between two letters, through every rule
        List<Object> codePoints = new ArrayList<>();
        for(int c : boundaries) {
            String text = "a" + new String(Character.toChars(c)) + "b";
            Map<String, Object> entry = map("codePoint", c, "inTable", TextRules.stripped(c), "singleLine", TextRules.singleLine(text, 64),
                    "description", TextRules.description(text, 500));
            try {
                entry.put("svg", SvgSubset.clean(text));
            } catch(SvgSubset.Refused e) {
                throw new IllegalStateException(e);
            }
            codePoints.add(entry);
        }

        List<Object> texts = new ArrayList<>();
        String emoji = "\ud83d\ude00";
        cleanCase(texts, "plain", "Vault Badge");
        cleanCase(texts, "line breaks of every kind", "\r\n line one \r\n\r\n \t line two  \n\rthree\rfour\n");
        StringBuilder lines = new StringBuilder();
        for(int i = 1; i <= 25; i++) {
            lines.append("L").append(i).append('\n');
        }
        cleanCase(texts, "25 lines", lines.toString());
        cleanCase(texts, "15 then a line break then more", "x".repeat(15) + "\ny");
        cleanCase(texts, "63 then a line break then more", "x".repeat(63) + "\ny");
        cleanCase(texts, "499 then a line break then more", "x".repeat(499) + "\ny");
        cleanCase(texts, "499 then a pair", "x".repeat(499) + emoji);
        cleanCase(texts, "64 then a pair", "x".repeat(64) + emoji);
        cleanCase(texts, "stripped characters do not count", "\u200b".repeat(50) + "x".repeat(16));
        cleanCase(texts, "spaces around and inside", "  two  words  ");
        cleanCase(texts, "tab, line feed and carriage return", "a\tb\nc\rd");
        cleanCase(texts, "whitespace runs", "a \t\n\r b");
        cleanCase(texts, "only whitespace", " \t\n ");
        cleanCase(texts, "a stripped character between spaces", "a \u200b b");
        cleanCase(texts, "only stripped characters", "\u200b\u200e\u00ad");
        cleanCase(texts, "the hostile text from the tests", "\u202eRight\u200bto\u2066left\u2028\u0007\n" + "x".repeat(70));
        cleanCase(texts, "C0, DEL and C1", "a\u0000b\u001fc\u007fd\u0080e\u009ff");
        cleanCase(texts, "no-break, em and ideographic spaces", "a\u00a0b\u2003c\u3000d");
        cleanCase(texts, "next line", "a\u0085b");
        cleanCase(texts, "variation selectors", "\u2764\ufe0f \u8fbb\udb40\udd00");
        cleanCase(texts, "tags", "\ud83c\udff4\udb40\udc67\udb40\udc62\udb40\udc7f");
        cleanCase(texts, "an unpaired high surrogate", "a\ud800b");
        cleanCase(texts, "an unpaired low surrogate", "a\udc00b");
        cleanCase(texts, "noncharacters", "a\ufffe\uffffb");
        cleanCase(texts, "combining marks", "e\u0301\u0302");
        cleanCase(texts, "15 then a pair", "x".repeat(15) + emoji);
        cleanCase(texts, "63 then a pair", "x".repeat(63) + emoji);
        cleanCase(texts, "62 then a pair then one more", "x".repeat(62) + emoji + "y");
        cleanCase(texts, "63 then a space then one more", "x".repeat(63) + " y");
        cleanCase(texts, "200 code points", "x".repeat(200));
        cleanCase(texts, "201 code points", "x".repeat(201));
        cleanCase(texts, "199 then a pair: 200 code points, 201 units", "x".repeat(199) + emoji);
        cleanCase(texts, "200 pairs", emoji.repeat(200));
        cleanCase(texts, "201 with one stripped", "x".repeat(200) + "\u200b");
        cleanCase(texts, "101 one-letter words: 201 code points, the spaces counted", "x ".repeat(100) + "x");
        cleanCase(texts, "500 then more", "y".repeat(501));

        return map("rule", "One rule for all contract and metadata text (TextRules): the fixed table below is stripped, as are lone surrogates; "
                        + "tab, line feed, carriage return and space separate words. singleLine: a run of separators between kept characters is one "
                        + "space, none at the ends. description: a run holding a line feed or carriage return is one line feed, at most "
                        + TextRules.MAX_LINES + " lines (later runs are a space). Caps count code points after cleaning and never leave a "
                        + "separator at the end. svg: SvgSubset's text, which collapses every separator to a space and refuses more than "
                        + SvgSubset.MAX_TEXT + " code points.",
                "unicodeVersion", TextRules.UNICODE_VERSION, "maxLines", TextRules.MAX_LINES, "table", tableJson, "codePoints", codePoints,
                "texts", texts);
    }

    private static void cleanCase(List<Object> cases, String label, String text) {
        Map<String, Object> entry = map("label", label);
        put(entry, "input", text);
        entry.put("singleLine16", TextRules.singleLine(text, 16));
        entry.put("singleLine64", TextRules.singleLine(text, 64));
        entry.put("description500", TextRules.description(text, 500));
        try {
            entry.put("svg", map("value", SvgSubset.clean(text)));
        } catch(SvgSubset.Refused e) {
            entry.put("svg", map("refused", true));
        }
        cases.add(entry);
    }

    private static List<int[]> ranges(IntPredicate in) {
        List<int[]> ranges = new ArrayList<>();
        int start = -1;
        for(int c = 0; c <= Character.MAX_CODE_POINT + 1; c++) {
            boolean member = c <= Character.MAX_CODE_POINT && in.test(c);
            if(member && start < 0) {
                start = c;
            } else if(!member && start >= 0) {
                ranges.add(new int[] {start, c - 1});
                start = -1;
            }
        }
        return ranges;
    }

    private static List<Object> tokenIds() {
        List<Object> cases = new ArrayList<>();
        for(EvmAddress owner : List.of(HOLDER, DEAD, ALL_ONES)) {
            for(int world : new int[] {1, 2, 3, 4, KnownCollections.MAX_VAULT_BADGE_WORLDS}) {
                BigInteger id = KnownCollections.vaultBadgeTokenId(owner, world);
                cases.add(map("owner", owner.toString(), "world", world, "decimal", id.toString(), "hex", id.toString(16), "word", hex(Abi.uint(id))));
            }
        }
        return cases;
    }

    // ---------------- The Vault Badge's token URI, as VaultBadge.sol builds it ----------------

    private static String vaultBadgeSvg(String world) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\" shape-rendering=\"crispEdges\">"
                + "<rect width=\"64\" height=\"64\" fill=\"#0E1E38\"/><rect x=\"2\" y=\"2\" width=\"60\" height=\"60\" fill=\"#C8D0DC\"/>"
                + "<rect x=\"4\" y=\"4\" width=\"56\" height=\"56\" fill=\"#345D9D\"/><circle cx=\"32\" cy=\"26\" r=\"15\" fill=\"#3A4458\"/>"
                + "<circle cx=\"32\" cy=\"26\" r=\"13\" fill=\"#C8D0DC\"/>"
                + "<path d=\"M30 16h5v9l4-2v4l-4 2v4h7v4H27v-6l-3 1v-4l3-1V16z\" fill=\"#345D9D\"/>"
                + "<text x=\"32\" y=\"54\" font-family=\"monospace\" font-size=\"9\" font-weight=\"bold\" text-anchor=\"middle\" fill=\"#FFFFFF\">WORLD "
                + world + "</text></svg>";
    }

    private static String vaultBadgeUri(int world) {
        String w = Integer.toString(world);
        String json = "{\"name\":\"Super Litecoin Vault Badge: World " + w + "\",\"description\":\"Commemorative badge for clearing the World " + w
                + " vault in Super Litecoin. Non-transferable, no value.\",\"attributes\":[{\"trait_type\":\"World\",\"value\":" + w
                + "}],\"image\":\"data:image/svg+xml;base64," + base64(vaultBadgeSvg(w)) + "\"}";
        return "data:application/json;base64," + base64(json);
    }

    private static String resource(String name) throws Exception {
        try(var in = EvmVectorGeneratorTest.class.getResourceAsStream("/com/sparrowwallet/drongo/evm/nft/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---------------- Built inputs ----------------

    private static Map<String, Object> rep(String text, int times) {
        return map("repeat", text, "times", times);
    }

    private static Map<String, Object> b64(Object... parts) {
        return map("base64", List.of(parts));
    }

    private static Map<String, Object> b64(List<Object> parts) {
        return map("base64", parts);
    }

    //A flat list of parts: a list among the arguments is spliced in
    private static List<Object> concat(Object... items) {
        List<Object> parts = new ArrayList<>();
        for(Object item : items) {
            if(item instanceof List<?> list) {
                parts.addAll(list);
            } else {
                parts.add(item);
            }
        }
        return parts;
    }

    private static String build(Object input) {
        if(input instanceof String text) {
            return text;
        }
        if(input instanceof List<?> parts) {
            StringBuilder built = new StringBuilder();
            for(Object part : parts) {
                built.append(build(part));
            }
            return built.toString();
        }
        Map<?, ?> part = (Map<?, ?>)input;
        if(part.containsKey("repeat")) {
            return ((String)part.get("repeat")).repeat((Integer)part.get("times"));
        }
        return base64(build(part.get("base64")));
    }

    /** Puts a literal input under key, or one built from parts under keyParts with its length and hash; returns the text. */
    private static String put(Map<String, Object> entry, String key, Object input) {
        String text = build(input);
        if(input instanceof String) {
            Assertions.assertTrue(text.length() <= 2 * LITERAL_MAX, "write a long input as parts");
            entry.put(key, text);
        } else {
            entry.put(key + "Parts", input);
            entry.put(key + "Length", text.length());
            entry.put(key + "Sha256", sha256(text));
        }
        return text;
    }

    // ---------------- Small helpers ----------------

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for(int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String)keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static String word(long value) {
        return hex(Abi.uint(BigInteger.valueOf(value)));
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static byte[] unhex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- JSON ----------------

    private static String json(Object value, String indent) {
        if(value == null) {
            return "null";
        }
        if(value instanceof String text) {
            return quote(text);
        }
        if(value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof BigInteger) {
            return value.toString();
        }
        String inner = indent + "  ";
        if(value instanceof Map<?, ?> map) {
            if(map.isEmpty()) {
                return "{}";
            }
            List<String> fields = new ArrayList<>();
            for(Map.Entry<?, ?> field : map.entrySet()) {
                fields.add(inner + quote((String)field.getKey()) + ": " + json(field.getValue(), inner));
            }
            return "{\n" + String.join(",\n", fields) + "\n" + indent + "}";
        }
        if(value instanceof List<?> list) {
            if(list.stream().noneMatch(item -> item instanceof Map || item instanceof List)) {
                List<String> items = new ArrayList<>();
                for(Object item : list) {
                    items.add(json(item, inner));
                }
                return "[" + String.join(", ", items) + "]";
            }
            List<String> items = new ArrayList<>();
            for(Object item : list) {
                items.add(inner + json(item, inner));
            }
            return "[\n" + String.join(",\n", items) + "\n" + indent + "]";
        }
        throw new IllegalArgumentException("Not a JSON value: " + value.getClass());
    }

    private static String quote(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for(int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if(c == '"' || c == '\\') {
                quoted.append('\\').append(c);
            } else if(c == '\n') {
                quoted.append("\\n");
            } else if(c == '\t') {
                quoted.append("\\t");
            } else if(c == '\r') {
                quoted.append("\\r");
            } else if(c < 0x20 || c > 0x7e) {
                quoted.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
            } else {
                quoted.append(c);
            }
        }
        return quoted.append('"').toString();
    }
}
