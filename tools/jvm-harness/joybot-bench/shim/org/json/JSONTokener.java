package org.json;

/** Runtime shim of Android's org.json.JSONTokener (lenient the same way: see {@link JSONObject}). */
public class JSONTokener {

    private final String in;
    private int pos;

    public JSONTokener(String in) {
        if (in != null && in.startsWith("﻿")) in = in.substring(1);
        this.in = in;
    }

    public Object nextValue() throws JSONException {
        int c = nextCleanInternal();
        switch (c) {
            case -1: throw syntaxError("End of input");
            case '{': return readObject();
            case '[': return readArray();
            case '\'': case '"': return nextString((char) c);
            default: pos--; return readLiteral();
        }
    }

    private int nextCleanInternal() throws JSONException {
        while (pos < in.length()) {
            int c = in.charAt(pos++);
            switch (c) {
                case '\t': case ' ': case '\n': case '\r': continue;
                case '/':
                    if (pos == in.length()) return c;
                    char peek = in.charAt(pos);
                    if (peek == '*') {
                        pos++;
                        int commentEnd = in.indexOf("*/", pos);
                        if (commentEnd == -1) throw syntaxError("Unterminated comment");
                        pos = commentEnd + 2;
                        continue;
                    } else if (peek == '/') {
                        pos++;
                        skipToEndOfLine();
                        continue;
                    }
                    return c;
                case '#': skipToEndOfLine(); continue;
                default: return c;
            }
        }
        return -1;
    }

    private void skipToEndOfLine() {
        for (; pos < in.length(); pos++) {
            char c = in.charAt(pos);
            if (c == '\r' || c == '\n') { pos++; break; }
        }
    }

    public String nextString(char quote) throws JSONException {
        StringBuilder builder = null;
        int start = pos;
        while (pos < in.length()) {
            int c = in.charAt(pos++);
            if (c == quote) {
                if (builder == null) return in.substring(start, pos - 1);
                builder.append(in, start, pos - 1);
                return builder.toString();
            }
            if (c == '\\') {
                if (pos == in.length()) throw syntaxError("Unterminated escape sequence");
                if (builder == null) builder = new StringBuilder();
                builder.append(in, start, pos - 1);
                builder.append(readEscapeCharacter());
                start = pos;
            }
        }
        throw syntaxError("Unterminated string");
    }

    private char readEscapeCharacter() throws JSONException {
        char escaped = in.charAt(pos++);
        switch (escaped) {
            case 'u':
                if (pos + 4 > in.length()) throw syntaxError("Unterminated escape sequence");
                String hex = in.substring(pos, pos + 4);
                pos += 4;
                try { return (char) Integer.parseInt(hex, 16); }
                catch (NumberFormatException nfe) { throw syntaxError("Invalid escape sequence: " + hex); }
            case 't': return '\t';
            case 'b': return '\b';
            case 'n': return '\n';
            case 'r': return '\r';
            case 'f': return '\f';
            default: return escaped;
        }
    }

    private Object readLiteral() throws JSONException {
        String literal = nextToInternal("{}[]/\\:,=;# \t\f");
        if (literal.length() == 0) throw syntaxError("Expected literal value");
        if ("null".equalsIgnoreCase(literal)) return JSONObject.NULL;
        if ("true".equalsIgnoreCase(literal)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(literal)) return Boolean.FALSE;
        if (literal.indexOf('.') == -1) {
            int base = 10;
            String number = literal;
            if (number.startsWith("0x") || number.startsWith("0X")) { number = number.substring(2); base = 16; }
            else if (number.startsWith("0") && number.length() > 1) { number = number.substring(1); base = 8; }
            try {
                long longValue = Long.parseLong(number, base);
                if (longValue <= Integer.MAX_VALUE && longValue >= Integer.MIN_VALUE) return (int) longValue;
                return longValue;
            } catch (NumberFormatException ignored) { }
        }
        try { return Double.valueOf(literal); } catch (NumberFormatException ignored) { }
        return literal;
    }

    private String nextToInternal(String excluded) {
        int start = pos;
        for (; pos < in.length(); pos++) {
            char c = in.charAt(pos);
            if (c == '\r' || c == '\n' || excluded.indexOf(c) != -1) return in.substring(start, pos);
        }
        return in.substring(start);
    }

    private JSONObject readObject() throws JSONException {
        JSONObject result = new JSONObject();
        int first = nextCleanInternal();
        if (first == '}') return result;
        else if (first != -1) pos--;
        while (true) {
            Object name = nextValue();
            if (!(name instanceof String)) {
                if (name == null) throw syntaxError("Names cannot be null");
                throw syntaxError("Names must be strings, but " + name + " is of type " + name.getClass().getName());
            }
            int separator = nextCleanInternal();
            if (separator != ':' && separator != '=') throw syntaxError("Expected ':' after " + name);
            if (pos < in.length() && in.charAt(pos) == '>') pos++;
            result.put((String) name, nextValue());
            switch (nextCleanInternal()) {
                case '}': return result;
                case ';': case ',': continue;
                default: throw syntaxError("Unterminated object");
            }
        }
    }

    private JSONArray readArray() throws JSONException {
        JSONArray result = new JSONArray();
        boolean hasTrailingSeparator = false;
        while (true) {
            switch (nextCleanInternal()) {
                case -1: throw syntaxError("Unterminated array");
                case ']': if (hasTrailingSeparator) result.put((Object) null); return result;
                case ',': case ';': result.put((Object) null); hasTrailingSeparator = true; continue;
                default: pos--;
            }
            result.put(nextValue());
            switch (nextCleanInternal()) {
                case ']': return result;
                case ',': case ';': hasTrailingSeparator = true; continue;
                default: throw syntaxError("Unterminated array");
            }
        }
    }

    public JSONException syntaxError(String message) { return new JSONException(message + this); }

    @Override public String toString() { return " at character " + pos + " of " + in; }

    public boolean more() { return pos < in.length(); }

    public char next() { return pos < in.length() ? in.charAt(pos++) : '\0'; }

    public char nextClean() throws JSONException { int c = nextCleanInternal(); return c == -1 ? '\0' : (char) c; }
}
