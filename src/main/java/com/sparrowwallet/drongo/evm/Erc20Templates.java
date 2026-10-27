package com.sparrowwallet.drongo.evm;

import com.sparrowwallet.drongo.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The ERC-20 tokens the LitVM tab can deploy: OpenZeppelin Contracts 5.6.1 in the style of the OpenZeppelin Wizard,
 * one compiled template for each combination of supply and features, built by tools/erc20/build.sh with a pinned
 * solc. Each template carries its exact sources, so a deployed token can be verified on the network's explorer.
 */
public final class Erc20Templates {
    private static final String RESOURCE = "/erc20/templates.json";
    private static final BigInteger UINT256_MAX = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);
    //ERC20Votes checkpoints hold uint208 amounts, so it refuses a supply above this
    private static final BigInteger VOTES_MAX = BigInteger.TWO.pow(208).subtract(BigInteger.ONE);
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_SYMBOL_LENGTH = 16;

    private static Erc20Templates instance;

    private final String compilerVersion;
    private final String contractName;
    private final String sourceName;
    private final Map<String, Object> settings;
    private final Map<String, String> sources;
    private final Map<String, Template> templates;

    private record Template(String source, List<String> imports, String bytecode) {}

    public enum Supply {
        /** Everything is minted at creation and no more can ever be. */
        FIXED,
        /** The owner can mint more at any time. */
        MINTABLE,
        /** The owner can mint more, never past a cap set at creation. */
        CAPPED
    }

    /**
     * What a token can do. Burnable: holders can destroy their own tokens. Pausable: the owner can stop all
     * transfers. Permit: approvals by signature (EIP-2612). Votes: delegation and voting power for governance.
     * Flash mint: anyone can borrow newly minted tokens within one transaction (ERC-3156). ERC-1363: transfers
     * and approvals that call the receiving contract.
     */
    public record Options(Supply supply, boolean burnable, boolean pausable, boolean permit, boolean votes, boolean flashMint, boolean erc1363) {
        public Options {
            Objects.requireNonNull(supply);
        }

        public String key() {
            StringBuilder letters = new StringBuilder();
            if(burnable) letters.append('b');
            if(pausable) letters.append('p');
            if(permit) letters.append('e');
            if(votes) letters.append('v');
            if(flashMint) letters.append('f');
            if(erc1363) letters.append('c');
            String base = supply.name().toLowerCase(Locale.ROOT);
            return letters.isEmpty() ? base : base + "_" + letters;
        }

        /** Whether the token has an owner: minting and pausing are the owner's. */
        public boolean ownable() {
            return supply != Supply.FIXED || pausable;
        }

        public static Options fromKey(String key) {
            int underscore = key.indexOf('_');
            Supply supply = Supply.valueOf((underscore < 0 ? key : key.substring(0, underscore)).toUpperCase(Locale.ROOT));
            String letters = underscore < 0 ? "" : key.substring(underscore + 1);
            Options options = new Options(supply, letters.indexOf('b') >= 0, letters.indexOf('p') >= 0, letters.indexOf('e') >= 0,
                    letters.indexOf('v') >= 0, letters.indexOf('f') >= 0, letters.indexOf('c') >= 0);
            if(!options.key().equals(key)) {
                throw new IllegalArgumentException("Not a template key: " + key);
            }
            return options;
        }
    }

    /** What a deployment is given. The owner is used only when the token has one, the cap only when it is capped. */
    public record Params(String name, String symbol, EvmAddress recipient, BigInteger initialSupply, EvmAddress owner, BigInteger cap) {
        public void check(Options options) {
            checkText("Name", name, MAX_NAME_LENGTH, true);
            checkText("Symbol", symbol, MAX_SYMBOL_LENGTH, false);
            if(recipient == null) {
                throw new IllegalArgumentException("The initial supply needs a recipient");
            }
            if(options.ownable() && owner == null) {
                throw new IllegalArgumentException("This token needs an owner");
            }
            if(initialSupply == null || initialSupply.signum() < 0 || initialSupply.compareTo(UINT256_MAX) > 0) {
                throw new IllegalArgumentException("Initial supply out of range");
            }
            if(options.supply() == Supply.FIXED && initialSupply.signum() == 0) {
                throw new IllegalArgumentException("A fixed-supply token needs an initial supply: no more can ever be minted");
            }
            BigInteger limit = options.votes() ? VOTES_MAX : UINT256_MAX;
            if(initialSupply.compareTo(limit) > 0) {
                throw new IllegalArgumentException("A token with votes cannot hold more than 2^208 - 1 units");
            }
            if(options.supply() == Supply.CAPPED) {
                if(cap == null || cap.signum() <= 0) {
                    throw new IllegalArgumentException("A capped token needs a cap above zero");
                }
                if(cap.compareTo(limit) > 0) {
                    throw new IllegalArgumentException("Cap out of range");
                }
                if(initialSupply.compareTo(cap) > 0) {
                    throw new IllegalArgumentException("The initial supply is above the cap");
                }
            }
        }

        private static void checkText(String what, String text, int maxLength, boolean spaces) {
            if(text == null || text.isBlank() || !text.equals(text.strip())) {
                throw new IllegalArgumentException(what + " is required, without leading or trailing spaces");
            }
            if(text.codePointCount(0, text.length()) > maxLength) {
                throw new IllegalArgumentException(what + " is longer than " + maxLength + " characters");
            }
            for(int i = 0; i < text.length(); ) {
                int c = text.codePointAt(i);
                int type = Character.getType(c);
                //Control and format characters (bidi overrides, zero-width joiners) make a name read differently from what it is
                if(type == Character.CONTROL || type == Character.FORMAT || type == Character.UNASSIGNED || type == Character.PRIVATE_USE
                        || type == Character.SURROGATE || (!spaces && Character.isWhitespace(c)) || (Character.isWhitespace(c) && c != ' ')) {
                    throw new IllegalArgumentException(what + " contains a character that is not allowed");
                }
                i += Character.charCount(c);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Erc20Templates(Map<String, Object> json) {
        this.compilerVersion = (String)json.get("compiler");
        this.contractName = (String)json.get("contractName");
        this.sourceName = (String)json.get("sourceName");
        this.settings = (Map<String, Object>)json.get("settings");
        Map<String, String> shared = new HashMap<>();
        for(Map.Entry<String, Object> entry : ((Map<String, Object>)json.get("sources")).entrySet()) {
            shared.put(entry.getKey(), (String)entry.getValue());
        }
        this.sources = Collections.unmodifiableMap(shared);
        Map<String, Template> map = new LinkedHashMap<>();
        for(Map.Entry<String, Object> entry : ((Map<String, Object>)json.get("variants")).entrySet()) {
            Map<String, Object> v = (Map<String, Object>)entry.getValue();
            List<String> imports = new ArrayList<>();
            for(Object name : (List<Object>)v.get("imports")) {
                if(!shared.containsKey((String)name)) {
                    throw new IllegalStateException("Template " + entry.getKey() + " imports a missing source " + name);
                }
                imports.add((String)name);
            }
            String bytecode = (String)v.get("bytecode");
            if(!bytecode.matches("[0-9a-f]+")) {
                throw new IllegalStateException("Template " + entry.getKey() + " has invalid bytecode");
            }
            map.put(entry.getKey(), new Template((String)v.get("source"), List.copyOf(imports), bytecode));
        }
        this.templates = Collections.unmodifiableMap(map);
    }

    @SuppressWarnings("unchecked")
    public static synchronized Erc20Templates get() {
        if(instance == null) {
            try(InputStream in = Erc20Templates.class.getResourceAsStream(RESOURCE)) {
                if(in == null) {
                    throw new IllegalStateException("Missing " + RESOURCE);
                }
                instance = new Erc20Templates((Map<String, Object>)MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            } catch(IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return instance;
    }

    public Set<String> keys() {
        return templates.keySet();
    }

    /** As the explorers name it, such as v0.8.36+commit.8a079791. */
    public String compilerVersion() {
        return compilerVersion;
    }

    /** The contract to verify, as source:contract. */
    public String qualifiedContractName() {
        return sourceName + ":" + contractName;
    }

    private Template template(Options options) {
        Template template = templates.get(options.key());
        if(template == null) {
            throw new IllegalArgumentException("No template for " + options.key());
        }
        return template;
    }

    public String source(Options options) {
        return template(options).source();
    }

    public byte[] bytecode(Options options) {
        return Utils.hexToBytes(template(options).bytecode());
    }

    public byte[] constructorArguments(Options options, Params params) {
        params.check(options);
        List<Object> args = new ArrayList<>(List.of(params.name(), params.symbol(), params.recipient(), params.initialSupply()));
        if(options.ownable()) {
            args.add(params.owner());
        }
        if(options.supply() == Supply.CAPPED) {
            args.add(params.cap());
        }
        return Abi.encodeArguments(args.toArray());
    }

    /** The data of the deploying transaction: the template's creation code followed by its constructor arguments. */
    public byte[] creationCode(Options options, Params params) {
        return Rlp.concat(bytecode(options), constructorArguments(options, params));
    }

    /** The solc standard JSON input the template was compiled from, which an explorer recompiles to verify a deployment. */
    public String verificationInput(Options options) {
        Template template = template(options);
        TreeMap<String, String> used = new TreeMap<>();
        used.put(sourceName, template.source());
        for(String name : template.imports()) {
            used.put(name, sources.get(name));
        }
        StringBuilder json = new StringBuilder("{\"language\":\"Solidity\",\"sources\":{");
        boolean first = true;
        for(Map.Entry<String, String> entry : used.entrySet()) {
            json.append(first ? "" : ",").append(quote(entry.getKey())).append(":{\"content\":").append(quote(entry.getValue())).append('}');
            first = false;
        }
        json.append("},\"settings\":");
        write(json, settings);
        return json.append('}').toString();
    }

    private static void write(StringBuilder json, Object value) {
        if(value instanceof Map<?, ?> map) {
            json.append('{');
            boolean first = true;
            for(Map.Entry<?, ?> entry : map.entrySet()) {
                json.append(first ? "" : ",").append(quote((String)entry.getKey())).append(':');
                write(json, entry.getValue());
                first = false;
            }
            json.append('}');
        } else if(value instanceof List<?> list) {
            json.append('[');
            for(int i = 0; i < list.size(); i++) {
                json.append(i == 0 ? "" : ",");
                write(json, list.get(i));
            }
            json.append(']');
        } else if(value instanceof String text) {
            json.append(quote(text));
        } else if(value instanceof BigDecimal number) {
            json.append(number.toPlainString());
        } else if(value instanceof Boolean || value == null) {
            json.append(value);
        } else {
            throw new IllegalArgumentException("Cannot write " + value.getClass());
        }
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for(int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch(c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if(c < 0x20) {
                        out.append(String.format("\\u%04x", (int)c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
