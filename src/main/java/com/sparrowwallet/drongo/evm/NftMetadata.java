package com.sparrowwallet.drongo.evm;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * An NFT's name, description, attributes and picture, read from its token URI. Only metadata carried in the URI
 * itself is read: a data: URI holding JSON (base64 or percent-encoded) or bare JSON text. Nothing is ever fetched, and
 * this class refers to no network code: an https, http, ipfs or ar URI is not read, and an image hosted that way is
 * reported by its host only.
 *
 * The URI is chosen by whoever deployed the contract, so every step is bounded and strict and a failure is "unreadable"
 * (empty), never an exception or a best guess. Within readable JSON, a field of the wrong type reads as absent.
 * All text is cleaned by {@link TextRules} (a fixed table, not the platform's Unicode data): the name and attributes as
 * one line, the description with its line breaks; each is capped in code points.
 *
 * Nothing here uses the platform's Unicode data, so the phone's port can match it exactly: whitespace around the URI is
 * space, tab, line feed and carriage return only, and schemes and media types are compared in ASCII case.
 *
 * JSON goes through {@link MiniJson}, which reads strict JSON: an object is required at the top; nesting deeper than 64
 * and oversized numbers are refused; a repeated key keeps its last value (as JSON.parse does). Base64 must be canonical and padded, as
 * on-chain encoders such as OpenZeppelin's Base64 always write it: unpadded or otherwise non-canonical base64 is
 * unreadable.
 */
public record NftMetadata(String name, String description, List<Attribute> attributes, Image image) {
    /** The longest token URI read, as {@link Erc721#MAX_TOKEN_URI_BYTES} allows. */
    public static final int MAX_URI_CHARS = 100_000;
    public static final int MAX_JSON_CHARS = 20_000;
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 500;
    public static final int MAX_ATTRIBUTE_LENGTH = 64;
    public static final int MAX_ATTRIBUTES = 20;
    public static final int MAX_SVG_CHARS = 100_000;
    public static final int MAX_RASTER_BYTES = 400_000;
    //Room for the largest raster in base64 (533,336 characters) and its header. Metadata JSON is far smaller, so this
    //only bounds a direct call
    private static final int MAX_IMAGE_URI_CHARS = 600_000;
    private static final int MAX_HOST_LENGTH = 253;
    //UTF-8 needs at most 3 bytes for each UTF-16 character, so more bytes than this proves too many characters
    private static final int MAX_BYTES_PER_CHAR = 3;
    private static final List<String> RASTER_TYPES = List.of("image/png", "image/jpeg", "image/gif", "image/webp");

    public NftMetadata {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        attributes = List.copyOf(attributes);
        Objects.requireNonNull(image, "image");
    }

    /** One trait, shown as name and value. */
    public record Attribute(String name, String value) {
        public Attribute {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    /** The picture, as far as it can be read without fetching anything. */
    public sealed interface Image permits Image.None, Image.Svg, Image.Raster, Image.Hosted {
        /** No picture that can be shown. */
        record None() implements Image {}

        /** SVG text carried in the metadata: decoded, not yet validated. */
        record Svg(String source) implements Image {
            public Svg {
                Objects.requireNonNull(source, "source");
            }
        }

        /** A PNG, JPEG, GIF or WebP carried in the metadata, by its declared type; its bytes still decide what it is. */
        record Raster(String mediaType, byte[] bytes) implements Image {
            public Raster {
                Objects.requireNonNull(mediaType, "mediaType");
                bytes = bytes.clone();
            }

            @Override
            public byte[] bytes() {
                return bytes.clone();
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof Raster other && mediaType.equals(other.mediaType) && Arrays.equals(bytes, other.bytes);
            }

            @Override
            public int hashCode() {
                return 31 * mediaType.hashCode() + Arrays.hashCode(bytes);
            }

            @Override
            public String toString() {
                return "Raster[mediaType=" + mediaType + ", bytes=" + bytes.length + "]";
            }
        }

        /** A picture hosted elsewhere, named by its host ("IPFS" and "Arweave" for those networks). It is never fetched. */
        record Hosted(String host) implements Image {
            public Hosted {
                Objects.requireNonNull(host, "host");
            }
        }
    }

    private static final Image NONE = new Image.None();

    /** The metadata in this token URI, or empty when it is unreadable or not carried in the URI. Never throws. */
    public static Optional<NftMetadata> parse(String tokenUri) {
        try {
            return Optional.ofNullable(read(tokenUri));
        } catch(RuntimeException e) {
            return Optional.empty();
        }
    }

    private static NftMetadata read(String tokenUri) {
        if(tokenUri == null || tokenUri.length() > MAX_URI_CHARS) {
            return null;
        }
        String uri = asciiStrip(tokenUri);
        String json;
        if(uri.startsWith("{")) {
            json = uri;
        } else if(startsWithIgnoreCase(uri, "data:")) {
            DataUri data = DataUri.parse(uri);
            if(data == null || !data.mediaType().equals("application/json")) {
                return null;
            }
            json = data.text(MAX_JSON_CHARS);
        } else {
            return null;
        }
        if(json == null || json.length() > MAX_JSON_CHARS) {
            return null;
        }
        if(!(MiniJson.parse(json) instanceof Map<?, ?> fields)) {
            return null;
        }
        return new NftMetadata(text(fields.get("name"), MAX_NAME_LENGTH),
                fields.get("description") instanceof String description ? TextRules.description(description, MAX_DESCRIPTION_LENGTH) : "",
                attributes(fields.get("attributes")), image(fields));
    }

    private static List<Attribute> attributes(Object value) {
        List<Attribute> attributes = new ArrayList<>();
        if(!(value instanceof List<?> items)) {
            return attributes;
        }
        for(Object item : items) {
            if(attributes.size() == MAX_ATTRIBUTES) {
                break;
            }
            if(!(item instanceof Map<?, ?> attribute)) {
                continue;
            }
            String shown = scalar(attribute.get("value"));
            if(shown != null) {
                attributes.add(new Attribute(text(attribute.get("trait_type"), MAX_ATTRIBUTE_LENGTH), TextRules.singleLine(shown, MAX_ATTRIBUTE_LENGTH)));
            }
        }
        return attributes;
    }

    //A string, number or boolean as text; anything else has no value to show
    private static String scalar(Object value) {
        if(value instanceof String text) {
            return text;
        }
        if(value instanceof BigDecimal number) {
            //Cutting a number at the cap would show a different number: one too long to show is not shown at all
            String plain = number.toPlainString();
            return plain.length() > MAX_ATTRIBUTE_LENGTH ? null : plain;
        }
        if(value instanceof Boolean flag) {
            return flag.toString();
        }
        return null;
    }

    private static Image image(Map<?, ?> fields) {
        Image image = fields.get("image") instanceof String uri ? image(uri) : NONE;
        if(image instanceof Image.None && fields.get("image_data") instanceof String data) {
            image = asciiStrip(data).startsWith("<") ? svg(data) : image(data);
        }
        return image;
    }

    /** What an image URI can show without fetching anything. */
    static Image image(String uri) {
        try {
            if(uri == null || uri.length() > MAX_IMAGE_URI_CHARS) {
                return NONE;
            }
            String text = asciiStrip(uri);
            if(startsWithIgnoreCase(text, "data:")) {
                return dataImage(text);
            }
            if(startsWithIgnoreCase(text, "https://") || startsWithIgnoreCase(text, "http://")) {
                String host = host(text.substring(text.indexOf("://") + 3));
                return host == null ? NONE : new Image.Hosted(host);
            }
            if(startsWithIgnoreCase(text, "ipfs://")) {
                return new Image.Hosted("IPFS");
            }
            if(startsWithIgnoreCase(text, "ar://")) {
                return new Image.Hosted("Arweave");
            }
            return NONE;
        } catch(RuntimeException e) {
            return NONE;
        }
    }

    private static Image dataImage(String uri) {
        DataUri data = DataUri.parse(uri);
        if(data == null) {
            return NONE;
        }
        if(data.mediaType().equals("image/svg+xml")) {
            String source = data.text(MAX_SVG_CHARS);
            return source == null ? NONE : svg(source);
        }
        if(RASTER_TYPES.contains(data.mediaType()) && data.base64()) {
            byte[] bytes = base64(data.payload(), MAX_RASTER_BYTES);
            return bytes == null ? NONE : new Image.Raster(data.mediaType(), bytes);
        }
        return NONE;
    }

    private static Image svg(String source) {
        return asciiStrip(source).isEmpty() || source.length() > MAX_SVG_CHARS ? NONE : new Image.Svg(source);
    }

    /**
     * The host of an http(s) URI after its scheme: what a browser would contact, so any user-info decoy before an @ is
     * dropped, as is the port. Only plain ASCII letters, digits, dots and hyphens are accepted, checked before lower
     * casing (some non-ASCII letters lower-case to ASCII ones); anything else gives null.
     */
    private static String host(String rest) {
        int end = rest.length();
        for(char stop : new char[] {'/', '?', '#', '\\'}) {
            int index = rest.indexOf(stop);
            if(index >= 0 && index < end) {
                end = index;
            }
        }
        String authority = rest.substring(0, end);
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        int colon = authority.lastIndexOf(':');
        if(colon >= 0) {
            String port = authority.substring(colon + 1);
            if(!port.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return null;
            }
            authority = authority.substring(0, colon);
        }
        if(authority.isEmpty() || authority.length() > MAX_HOST_LENGTH || !authority.chars().allMatch(NftMetadata::isHostCharacter)) {
            return null;
        }
        return asciiLower(authority);
    }

    private static boolean isHostCharacter(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
    }

    /**
     * data:[media type][;parameter]*[;base64],payload. The media type is compared in lower case; a charset other than
     * UTF-8 or US-ASCII is refused, other parameters are ignored, and base64 must be the last parameter.
     */
    private record DataUri(String mediaType, boolean base64, String payload) {
        static DataUri parse(String uri) {
            int comma = uri.indexOf(',');
            if(comma < 0) {
                return null;
            }
            String[] parameters = uri.substring("data:".length(), comma).split(";", -1);
            boolean base64 = false;
            for(int i = 1; i < parameters.length; i++) {
                String parameter = asciiLower(asciiStrip(parameters[i]));
                if(parameter.equals("base64")) {
                    if(i != parameters.length - 1) {
                        return null;
                    }
                    base64 = true;
                } else if(parameter.startsWith("charset=") && !List.of("charset=utf-8", "charset=utf8", "charset=us-ascii").contains(parameter)) {
                    return null;
                }
            }
            return new DataUri(asciiLower(asciiStrip(parameters[0])), base64, uri.substring(comma + 1));
        }

        /** The payload as UTF-8 text of at most maxChars characters, or null when it is not. */
        String text(int maxChars) {
            int maxBytes = maxChars * MAX_BYTES_PER_CHAR;
            byte[] bytes = base64 ? NftMetadata.base64(payload, maxBytes) : percentDecode(payload, maxBytes);
            String text = bytes == null ? null : utf8(bytes);
            return text == null || text.length() > maxChars ? null : text;
        }
    }

    /**
     * Strict base64: the standard alphabet, padded, and canonical (no stray bits), exactly as an encoder writes it.
     * The decoded size is known from the encoded length, so an over-large payload is refused before decoding.
     */
    static byte[] base64(String encoded, int maxBytes) {
        return base64(encoded, maxBytes, Base64.getDecoder()::decode);
    }

    /** As {@link #base64(String, int)} with the given decoder, so a test can see an over-large payload never reaches it. */
    static byte[] base64(String encoded, int maxBytes, Function<String, byte[]> decoder) {
        if(encoded.length() % 4 != 0 || (long)encoded.length() / 4 * 3 - 2 > maxBytes) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = decoder.apply(encoded);
        } catch(IllegalArgumentException e) {
            return null;
        }
        if(bytes.length > maxBytes || !Base64.getEncoder().encodeToString(bytes).equals(encoded)) {
            return null;
        }
        return bytes;
    }

    /**
     * Percent-decoding as a data: URI defines it: %XX (two ASCII hex digits) is a byte, any other character stands for
     * its own UTF-8 bytes, and + is a plus sign. A malformed escape or an unpaired surrogate gives null.
     */
    static byte[] percentDecode(String text, int maxBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while(i < text.length()) {
            char c = text.charAt(i);
            if(c == '%') {
                int high = i + 1 < text.length() ? hexDigit(text.charAt(i + 1)) : -1;
                int low = i + 2 < text.length() ? hexDigit(text.charAt(i + 2)) : -1;
                if(high < 0 || low < 0) {
                    return null;
                }
                out.write(high << 4 | low);
                i += 3;
            } else {
                int codePoint = text.codePointAt(i);
                if(TextRules.loneSurrogate(codePoint)) {
                    return null;
                }
                out.writeBytes(Character.toString(codePoint).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(codePoint);
            }
            if(out.size() > maxBytes) {
                return null;
            }
        }
        return out.toByteArray();
    }

    private static int hexDigit(char c) {
        if(c >= '0' && c <= '9') {
            return c - '0';
        }
        if(c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if(c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    //Bytes that are not valid UTF-8 are unreadable rather than shown with replacement characters
    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch(CharacterCodingException e) {
            return null;
        }
    }

    //One line of text by TextRules, capped in code points
    private static String text(Object value, int maxLength) {
        return value instanceof String text ? TextRules.singleLine(text, maxLength) : "";
    }

    //Space, tab, line feed and carriage return removed from both ends: the JSON whitespace, not the platform's
    private static String asciiStrip(String text) {
        int start = 0;
        int end = text.length();
        while(start < end && TextRules.separator(text.charAt(start))) {
            start++;
        }
        while(end > start && TextRules.separator(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    //A-Z to a-z only: no other character lower-cases to ASCII
    private static String asciiLower(String text) {
        StringBuilder lower = new StringBuilder(text.length());
        for(int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            lower.append(c >= 'A' && c <= 'Z' ? (char)(c + ('a' - 'A')) : c);
        }
        return lower.toString();
    }

    //Compared in ASCII case only, so that no other character (the long s, the Kelvin sign) matches a letter of the prefix
    private static boolean startsWithIgnoreCase(String text, String prefix) {
        return text.length() >= prefix.length() && asciiLower(text.substring(0, prefix.length())).equals(prefix);
    }
}
