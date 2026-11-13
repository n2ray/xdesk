package io.github.n2ray.xdesk.input;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Just enough JSON for the test vectors: objects, arrays, strings, numbers, booleans, null. */
final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        Object v = p.value();
        p.space();
        if (p.i != p.s.length()) throw p.error("trailing text");
        return v;
    }

    private RuntimeException error(String what) {
        return new IllegalArgumentException(what + " at " + i);
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        space();
        if (i >= s.length()) throw error("end of text");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("unexpected " + c);
        return Double.parseDouble(s.substring(start, i));
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        space();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            space();
            String k = string();
            space();
            if (s.charAt(i++) != ':') throw error("expected :");
            m.put(k, value());
            space();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw error("expected , or }");
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        space();
        if (s.charAt(i) == ']') { i++; return a; }
        while (true) {
            a.add(value());
            space();
            char c = s.charAt(i++);
            if (c == ']') return a;
            if (c != ',') throw error("expected , or ]");
        }
    }

    private String string() {
        if (s.charAt(i) != '"') throw error("expected string");
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e); break;
                }
            } else {
                b.append(c);
            }
        }
    }
}
