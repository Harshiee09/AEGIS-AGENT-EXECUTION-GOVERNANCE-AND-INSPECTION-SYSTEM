package aegis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON codec for the JDK-only HTTP backend. */
public final class Json {
    private Json() { }

    public static Object parse(String source) {
        Parser parser = new Parser(source);
        Object value = parser.value();
        parser.space();
        if (parser.at != source.length()) throw new IllegalArgumentException("Invalid JSON after value");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String source) {
        Object value = parse(source);
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("JSON object required");
        return (Map<String, Object>) value;
    }

    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) out.append("null");
        else if (value instanceof String text) {
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 32) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            out.append('"');
        } else if (value instanceof Number || value instanceof Boolean) out.append(value);
        else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                write(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> values) {
            out.append('[');
            boolean first = true;
            for (Object item : values) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
        } else throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }

    private static final class Parser {
        private final String source;
        private int at;
        private int depth;
        Parser(String source) { this.source = source; }
        void space() { while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++; }
        Object value() {
            space();
            if (++depth > 32) throw new IllegalArgumentException("JSON nesting too deep");
            try {
                if (at == source.length()) throw new IllegalArgumentException("Unexpected end of JSON");
                char c = source.charAt(at);
                if (c == '"') return string();
                if (c == '{') return object();
                if (c == '[') return array();
                if (source.startsWith("true", at)) { at += 4; return true; }
                if (source.startsWith("false", at)) { at += 5; return false; }
                if (source.startsWith("null", at)) { at += 4; return null; }
                return number();
            } finally { depth--; }
        }
        Map<String, Object> object() {
            at++;
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (take('}')) return result;
            do {
                space();
                if (at == source.length() || source.charAt(at) != '"') throw new IllegalArgumentException("Object key required");
                String key = string();
                space();
                expect(':');
                if (result.containsKey(key)) throw new IllegalArgumentException("Duplicate JSON key");
                result.put(key, value());
                space();
                if (take('}')) return result;
                expect(',');
            } while (true);
        }
        List<Object> array() {
            at++;
            List<Object> result = new ArrayList<>();
            space();
            if (take(']')) return result;
            do {
                result.add(value());
                space();
                if (take(']')) return result;
                expect(',');
            } while (true);
        }
        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            boolean closed = false;
            while (at < source.length()) {
                char c = source.charAt(at++);
                if (c == '"') { closed = true; break; }
                if (c == '\\') {
                    if (at == source.length()) throw new IllegalArgumentException("Bad JSON escape");
                    c = source.charAt(at++);
                    c = switch (c) {
                        case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/';
                        case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n';
                        case 'r' -> '\r'; case 't' -> '\t';
                        case 'u' -> unicode();
                        default -> throw new IllegalArgumentException("Bad JSON escape");
                    };
                } else if (c < 32) throw new IllegalArgumentException("Control character in JSON string");
                out.append(c);
            }
            if (!closed) throw new IllegalArgumentException("Unterminated JSON string");
            return out.toString();
        }
        char unicode() {
            if (at + 4 > source.length()) throw new IllegalArgumentException("Bad unicode escape");
            try { char c = (char) Integer.parseInt(source.substring(at, at + 4), 16); at += 4; return c; }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Bad unicode escape"); }
        }
        Number number() {
            int start = at;
            if (take('-')) { /* sign */ }
            if (at == source.length()) throw new IllegalArgumentException("Bad JSON number");
            if (take('0')) { /* zero */ }
            else {
                if (!digit()) throw new IllegalArgumentException("Bad JSON number");
                while (digit()) at++;
            }
            if (take('.')) { if (!digit()) throw new IllegalArgumentException("Bad JSON number"); while (digit()) at++; }
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                if (!digit()) throw new IllegalArgumentException("Bad JSON number");
                while (digit()) at++;
            }
            String token = source.substring(start, at);
            try { return token.contains(".") || token.contains("e") || token.contains("E")
                    ? Double.valueOf(token) : Long.valueOf(token); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Bad JSON number"); }
        }
        boolean digit() { return at < source.length() && source.charAt(at) >= '0' && source.charAt(at) <= '9'; }
        boolean take(char c) { if (at < source.length() && source.charAt(at) == c) { at++; return true; } return false; }
        void expect(char c) { if (!take(c)) throw new IllegalArgumentException("Expected '" + c + "' in JSON"); }
    }
}
