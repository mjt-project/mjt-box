package com.mjt.core.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bộ phân tích JSON đệ quy thuần Java (Zero-Dependency).
 * Hỗ trợ đầy đủ JSON chuẩn RFC 8259 (giữ nguyên thứ tự key bằng LinkedHashMap),
 * đồng thời bỏ qua comment (//, /* * /) và dấu phẩy thừa nếu người dùng sửa tay.
 */
public class SimpleJsonParser {

    private final String src;
    private int pos;

    private SimpleJsonParser(String src) {
        this.src = src != null ? src : "";
        this.pos = 0;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object val = new SimpleJsonParser(json).parseValue();
        if (val instanceof Map) {
            return (Map<String, Object>) val;
        }
        return new LinkedHashMap<>();
    }

    private Object parseValue() {
        skipWhitespaceAndComments();
        if (pos >= src.length()) return null;

        char c = src.charAt(pos);
        if (c == '{') return parseMap();
        if (c == '[') return parseList();
        if (c == '"') return parseString();
        if (c == 't' || c == 'f') return parseBooleanOrWord();
        if (c == 'n') return parseNullOrWord();
        if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
        // Cho phép từ khóa không có ngoặc kép (vd: "listen_port": auto)
        if (Character.isLetter(c)) return parseBareWord();
        throw new IllegalArgumentException("JSON không hợp lệ tại vị trí " + pos + ": '" + c + "'");
    }

    private Map<String, Object> parseMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // '{'
        while (pos < src.length()) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) break;
            if (src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            if (src.charAt(pos) == ',' || src.charAt(pos) == ';') {
                pos++;
                continue;
            }

            String key = (src.charAt(pos) == '"') ? parseString() : parseBareWord();
            skipWhitespaceAndComments();
            if (pos < src.length() && src.charAt(pos) == ':') {
                pos++;
            }
            Object val = parseValue();
            map.put(key, val);

            skipWhitespaceAndComments();
            if (pos < src.length() && (src.charAt(pos) == ',' || src.charAt(pos) == ';')) {
                pos++;
            }
        }
        return map;
    }

    private List<Object> parseList() {
        List<Object> list = new ArrayList<>();
        pos++; // '['
        while (pos < src.length()) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) break;
            if (src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            if (src.charAt(pos) == ',') {
                pos++;
                continue;
            }
            list.add(parseValue());
            skipWhitespaceAndComments();
            if (pos < src.length() && src.charAt(pos) == ',') {
                pos++;
            }
        }
        return list;
    }

    private String parseString() {
        pos++; // '"'
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\' && pos < src.length()) {
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 <= src.length()) {
                            String hex = src.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        break;
                    default: sb.append(esc); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object parseNumber() {
        int start = pos;
        if (src.charAt(pos) == '-') pos++;
        boolean isFloat = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                isFloat = true;
                pos++;
            } else {
                break;
            }
        }
        String numStr = src.substring(start, pos);
        if (isFloat) {
            return Double.parseDouble(numStr);
        }
        return Long.parseLong(numStr);
    }

    private Object parseBooleanOrWord() {
        if (src.startsWith("true", pos) && isWordBoundary(pos + 4)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos) && isWordBoundary(pos + 5)) {
            pos += 5;
            return Boolean.FALSE;
        }
        return parseBareWord();
    }

    private Object parseNullOrWord() {
        if (src.startsWith("null", pos) && isWordBoundary(pos + 4)) {
            pos += 4;
            return null;
        }
        return parseBareWord();
    }

    private String parseBareWord() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                pos++;
            } else {
                break;
            }
        }
        return src.substring(start, pos);
    }

    private boolean isWordBoundary(int idx) {
        if (idx >= src.length()) return true;
        char c = src.charAt(idx);
        return !Character.isLetterOrDigit(c) && c != '_';
    }

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                pos += 2;
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                pos += 2;
                while (pos + 1 < src.length()
                        && !(src.charAt(pos) == '*' && src.charAt(pos + 1) == '/')) {
                    pos++;
                }
                pos = Math.min(pos + 2, src.length());
            } else {
                break;
            }
        }
    }
}
