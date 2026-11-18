package com.sparrowwallet.drongo.evm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small strict JSON reader for JSON-RPC responses and NFT metadata, so drongo does not take a JSON library dependency.
 * Objects become LinkedHashMap, arrays ArrayList, numbers BigDecimal; strings, booleans and null as usual.
 *
 * Strict JSON (RFC 8259), with no use of the platform's Unicode data, so the phone's port reads exactly the same:
 * whitespace is space, tab, line feed and carriage return only; a \\u escape is exactly four ASCII hex digits; numbers
 * follow the JSON grammar (no leading +, no leading zeros, digits on both sides of a point); a raw character below
 * U+0020 inside a string is refused. A repeated key keeps its last value, in the place of its first.
 */
final class MiniJson {
    //Bounds on untrusted RPC input: nesting deep enough to overflow the stack, or a number token like 1e999999999
    //whose conversion to an integer would exhaust memory, are rejected rather than parsed
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NUMBER_LENGTH = 80;
    private static final int MAX_SCALE = 100;

    private final String s;
    private int pos;
    private int depth;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String json) {
        MiniJson parser = new MiniJson(json);
        Object value = parser.value();
        parser.skipWhitespace();
        if(parser.pos != json.length()) {
            throw parser.error("Trailing characters");
        }
        return value;
    }

    private Object value() {
        skipWhitespace();
        if(pos >= s.length()) {
            throw error("Unexpected end");
        }
        char c = s.charAt(pos);
        if((c == '{' || c == '[') && ++depth > MAX_DEPTH) {
            throw error("Nesting too deep");
        }
        switch(c) {
            case '{': try { return object(); } finally { depth--; }
            case '[': try { return array(); } finally { depth--; }
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if(peek('}')) {
            pos++;
            return map;
        }
        while(true) {
            skipWhitespace();
            String key = string();
            skipWhitespace();
            consume(':');
            map.put(key, value());
            skipWhitespace();
            if(peek(',')) {
                pos++;
                continue;
            }
            consume('}');
            return map;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++;
        skipWhitespace();
        if(peek(']')) {
            pos++;
            return list;
        }
        while(true) {
            list.add(value());
            skipWhitespace();
            if(peek(',')) {
                pos++;
                continue;
            }
            consume(']');
            return list;
        }
    }

    private String string() {
        consume('"');
        StringBuilder sb = new StringBuilder();
        while(pos < s.length()) {
            char c = s.charAt(pos++);
            if(c == '"') {
                return sb.toString();
            }
            if(c == '\\') {
                if(pos >= s.length()) {
                    break;
                }
                char e = s.charAt(pos++);
                switch(e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if(pos + 4 > s.length()) {
                            throw error("Bad unicode escape");
                        }
                        int code = 0;
                        for(int i = 0; i < 4; i++) {
                            int digit = hexDigit(s.charAt(pos + i));
                            if(digit < 0) {
                                throw error("Bad unicode escape");
                            }
                            code = code << 4 | digit;
                        }
                        sb.append((char)code);
                        pos += 4;
                    }
                    default -> throw error("Bad escape");
                }
            } else if(c < 0x20) {
                throw error("Control character in string");
            } else {
                sb.append(c);
            }
        }
        throw error("Unterminated string");
    }

    //-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?
    private BigDecimal number() {
        int start = pos;
        if(peek('-')) {
            pos++;
        }
        if(peek('0')) {
            pos++;
        } else if(pos < s.length() && s.charAt(pos) >= '1' && s.charAt(pos) <= '9') {
            digits();
        } else {
            throw error(start == pos ? "Unexpected character" : "Malformed number");
        }
        if(peek('.')) {
            pos++;
            if(digits() == 0) {
                throw error("Malformed number");
            }
        }
        if(peek('e') || peek('E')) {
            pos++;
            if(peek('+') || peek('-')) {
                pos++;
            }
            if(digits() == 0) {
                throw error("Malformed number");
            }
        }
        String token = s.substring(start, pos);
        if(token.length() > MAX_NUMBER_LENGTH) {
            throw error("Number out of range");
        }
        BigDecimal number;
        try {
            number = new BigDecimal(token);
        } catch(NumberFormatException e) {
            throw error("Malformed number");
        }
        //Explorer APIs write rates and ratios with exponents; only a small exponent is safe to convert later
        if(Math.abs(number.scale()) > MAX_SCALE) {
            throw error("Number out of range");
        }
        return number;
    }

    private void expect(String word) {
        if(!s.startsWith(word, pos)) {
            throw error("Expected " + word);
        }
        pos += word.length();
    }

    private void consume(char c) {
        skipWhitespace();
        if(!peek(c)) {
            throw error("Expected '" + c + "'");
        }
        pos++;
    }

    private boolean peek(char c) {
        return pos < s.length() && s.charAt(pos) == c;
    }

    private int digits() {
        int start = pos;
        while(pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
            pos++;
        }
        return pos - start;
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

    private void skipWhitespace() {
        while(pos < s.length()) {
            char c = s.charAt(pos);
            if(c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at " + pos + " in JSON-RPC response");
    }
}
