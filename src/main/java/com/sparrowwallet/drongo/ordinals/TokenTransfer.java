package com.sparrowwallet.drongo.ordinals;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds and reads the JSON bodies of -20 token inscriptions (deploy, mint, transfer). drongo carries no JSON library,
 * so parse reads the flat object these protocols use with a small strict scanner.
 */
public final class TokenTransfer {
    public static final String CONTENT_TYPE = "text/plain;charset=utf-8";
    public static final int MAX_CONTENT_BYTES = 1000;
    public static final int MAX_DECIMALS = 18;

    private static final Pattern AMOUNT = Pattern.compile("^[0-9]+(\\.[0-9]+)?$");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private TokenTransfer() {
    }

    public record Parsed(String p, String op, String tick, String amt) {
    }

    public static byte[] body(TokenProtocol p, String tick, String amount) {
        if(tick == null || tick.getBytes(StandardCharsets.UTF_8).length != 4) {
            throw new IllegalArgumentException("A token tick is exactly 4 bytes");
        }
        if(tick.indexOf('"') >= 0 || tick.indexOf('\\') >= 0 || tick.chars().anyMatch(c -> c < 0x20)) {
            throw new IllegalArgumentException("Invalid characters in token tick");
        }
        Optional<String> problem = amountProblem(amount, MAX_DECIMALS, null);
        if(problem.isPresent()) {
            throw new IllegalArgumentException(problem.get());
        }

        return ("{\"p\":\"" + p.p() + "\",\"op\":\"transfer\",\"tick\":\"" + tick + "\",\"amt\":\"" + amount + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    public static Optional<Parsed> parse(byte[] content) {
        if(content == null || content.length == 0 || content.length > MAX_CONTENT_BYTES) {
            return Optional.empty();
        }

        Reader reader;
        Map<String, String> fields;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
            reader = new Reader(text);
            fields = reader.object();
        } catch(IllegalArgumentException | CharacterCodingException e) {
            return Optional.empty();
        }

        for(String key : new String[] {"p", "op", "tick", "amt"}) {
            if(fields.containsKey(key) && !reader.stringKeys.contains(key)) {
                return Optional.empty();
            }
        }

        String p = fields.get("p");
        String op = fields.get("op");
        if(p == null || op == null) {
            return Optional.empty();
        }

        return Optional.of(new Parsed(p, op, fields.get("tick"), fields.get("amt")));
    }

    /**
     * @return empty when the amount is a valid, positive, plain decimal within the allowed decimal places. A null dec
     * uses the decimal places of the balance string, capped at 18.
     */
    public static Optional<String> amountProblem(String amount, Integer dec, String balance) {
        if(amount == null || !AMOUNT.matcher(amount).matches()) {
            return Optional.of("Enter a plain decimal amount, such as 100 or 1.5");
        }
        if(new BigDecimal(amount).signum() <= 0) {
            return Optional.of("The amount must be greater than zero");
        }

        int allowed = dec != null ? Math.min(dec, MAX_DECIMALS) : Math.min(decimalPlaces(balance), MAX_DECIMALS);
        if(decimalPlaces(amount) > allowed) {
            return Optional.of(allowed == 0 ? "This token has no decimal places" : "This token allows at most " + allowed + " decimal places");
        }

        return Optional.empty();
    }

    private static int decimalPlaces(String number) {
        if(number == null) {
            return 0;
        }
        int dot = number.indexOf('.');
        return dot < 0 ? 0 : number.length() - dot - 1;
    }

    /** Reads one flat JSON object; string and number values are kept as text, anything nested is rejected. */
    private static final class Reader {
        private final String s;
        private final Set<String> stringKeys = new HashSet<>();
        private int i;

        Reader(String s) {
            this.s = s;
        }

        Map<String, String> object() {
            Map<String, String> map = new HashMap<>();
            ws();
            expect('{');
            ws();
            if(peek() == '}') {
                i++;
                end();
                return map;
            }
            while(true) {
                ws();
                String key = string();
                ws();
                expect(':');
                ws();
                boolean isString = peek() == '"';
                if(map.put(key, value()) != null) {
                    throw new IllegalArgumentException("Duplicate key");
                }
                if(isString) {
                    stringKeys.add(key);
                }
                ws();
                char c = next();
                if(c == '}') {
                    break;
                }
                if(c != ',') {
                    throw new IllegalArgumentException("Expected , or }");
                }
            }
            end();
            return map;
        }

        private String value() {
            if(peek() == '"') {
                return string();
            }
            int start = i;
            while(i < s.length() && "{}[],: \t\r\n\"".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            String literal = s.substring(start, i);
            if(!(literal.equals("true") || literal.equals("false") || literal.equals("null") || NUMBER.matcher(literal).matches())) {
                throw new IllegalArgumentException("Unsupported value");
            }
            return literal;
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while(true) {
                char c = next();
                if(c == '"') {
                    return sb.toString();
                }
                if(c < 0x20) {
                    throw new IllegalArgumentException("Control character in string");
                }
                if(c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = next();
                switch(e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if(i + 4 > s.length()) {
                            throw new IllegalArgumentException("Bad escape");
                        }
                        int code = 0;
                        for(int j = i; j < i + 4; j++) {
                            int digit = Character.digit(s.charAt(j), 16);
                            if(digit < 0) {
                                throw new IllegalArgumentException("Bad escape");
                            }
                            code = code * 16 + digit;
                        }
                        sb.append((char)code);
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad escape");
                }
            }
        }

        private void ws() {
            while(i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
        }

        private void end() {
            ws();
            if(i != s.length()) {
                throw new IllegalArgumentException("Trailing content");
            }
        }

        private char peek() {
            if(i >= s.length()) {
                throw new IllegalArgumentException("Unexpected end");
            }
            return s.charAt(i);
        }

        private char next() {
            char c = peek();
            i++;
            return c;
        }

        private void expect(char c) {
            if(next() != c) {
                throw new IllegalArgumentException("Expected " + c);
            }
        }
    }
}
