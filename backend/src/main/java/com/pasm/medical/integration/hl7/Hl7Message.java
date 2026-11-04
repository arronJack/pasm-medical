package com.pasm.medical.integration.hl7;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条已切分的 HL7 v2 消息（段 + 字段 + 分量）。
 *
 * <p>字段编号遵循 HL7 惯例（<b>1 起</b>）。两个容易写错的地方在这里一次说清：
 * <ul>
 *   <li>{@code MSH} 段没有"段名之后的分隔符"这一位：{@code MSH|^~\&|A|...} 里
 *       第 4 个字符就是 MSH-1（字段分隔符本身），{@code ^~\&} 是 MSH-2。
 *       所以 MSH 的字段下标要整体前移一位。</li>
 *   <li>其它段 {@code PID|1||P001} 中 {@code PID-1} 是 {@code 1}，正常下标。</li>
 * </ul>
 */
public final class Hl7Message {

    /** 一个段。 */
    public static final class Segment {

        private final String name;
        private final List<String> fields;
        private final char componentSep;

        Segment(String name, List<String> fields, char componentSep) {
            this.name = name;
            this.fields = Collections.unmodifiableList(fields);
            this.componentSep = componentSep;
        }

        public String name() {
            return name;
        }

        /** 取字段（1 起）；越界返回空串。 */
        public String field(int n) {
            if (n <= 0) {
                return "";
            }
            int idx = "MSH".equals(name) ? n - 1 : n;
            return idx < fields.size() ? fields.get(idx) : "";
        }

        /** 取字段的第 c 个分量（1 起）。 */
        public String component(int n, int c) {
            String f = field(n);
            if (f.isEmpty()) {
                return "";
            }
            String[] parts = f.split(java.util.regex.Pattern.quote(String.valueOf(componentSep)), -1);
            return c >= 1 && c <= parts.length ? parts[c - 1] : "";
        }

        /** 取字段的第一个分量（最常见：编码^名称 里的编码）。 */
        public String firstComponent(int n) {
            return component(n, 1);
        }

        /** 取字段的第二个分量（通常是名称）。 */
        public String secondComponent(int n) {
            return component(n, 2);
        }
    }

    private final List<Segment> segments;

    Hl7Message(List<Segment> segments) {
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
    }

    public List<Segment> segments() {
        return segments;
    }

    /** 第一个同名段；没有则 null。 */
    public Segment first(String name) {
        for (Segment s : segments) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** 所有同名段（OBX 会多次出现）。 */
    public List<Segment> all(String name) {
        List<Segment> out = new ArrayList<>();
        for (Segment s : segments) {
            if (s.name().equals(name)) {
                out.add(s);
            }
        }
        return out;
    }

    public boolean has(String name) {
        return first(name) != null;
    }
}
