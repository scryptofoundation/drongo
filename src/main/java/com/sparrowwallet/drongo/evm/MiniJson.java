package com.sparrowwallet.drongo.evm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small strict JSON reader for JSON-RPC responses, so drongo does not take a JSON library dependency.
 * Objects become LinkedHashMap, arrays ArrayList, numbers BigDecimal; strings, booleans and null as usual.
 */
final class MiniJson {
    private final String s;
    private int pos;

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
        switch(c) {
            case '{': return object();
            case '[': return array();
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
                        sb.append((char)Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("Bad escape");
                }
            } else {
                sb.append(c);
            }
        }
        throw error("Unterminated string");
    }

    private BigDecimal number() {
        int start = pos;
        while(pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
            pos++;
        }
        if(start == pos) {
            throw error("Unexpected character");
        }
        return new BigDecimal(s.substring(start, pos));
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

    private void skipWhitespace() {
        while(pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at " + pos + " in JSON-RPC response");
    }
}
