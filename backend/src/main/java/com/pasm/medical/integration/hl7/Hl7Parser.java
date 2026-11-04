package com.pasm.medical.integration.hl7;

import java.util.ArrayList;
import java.util.List;

/**
 * HL7 v2.x 消息切分器（<b>只做结构解析</b>，不解释业务）。
 *
 * <p>支持三种实际会遇到的输入形态：
 * <ol>
 *   <li>裸文本：{@code MSH|^~\&|...\rPID|...\rOBX|...}；</li>
 *   <li>MLLP 帧：{@code 0x0B ... 0x1C 0x0D}（TCP 传输的常见封装）；</li>
 *   <li>段分隔符为 {@code \n} 而非 {@code \r}（部分中间件/测试工具会改写）。</li>
 * </ol>
 *
 * <p>分隔符<b>不写死</b>：从 MSH-1 / MSH-2 读取。有些厂商会改成分号或其它字符，
 * 写死 {@code |} {@code ^} 的解析器在那种环境里会"解析成功但字段全错" ——
 * 这种错比直接报错更难查。
 */
public final class Hl7Parser {

    private Hl7Parser() {
    }

    /** MLLP 帧起始字节。 */
    private static final char MLLP_START = 0x0B;
    /** MLLP 帧结束字节（后跟 0x0D）。 */
    private static final char MLLP_END = 0x1C;

    public static Hl7Message parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new Hl7ParseException("空消息");
        }
        String body = stripMllp(raw);

        // MSH 必须存在，否则连分隔符都无从得知 —— 不能猜默认值。
        int mshIdx = body.indexOf("MSH");
        if (mshIdx < 0) {
            throw new Hl7ParseException("缺少 MSH 段，无法确定分隔符", "MSH", 0);
        }
        if (mshIdx > 0) {
            body = body.substring(mshIdx);
        }
        if (body.length() < 8) {
            throw new Hl7ParseException("MSH 段过短，无法读取分隔符", "MSH", 1);
        }

        char fieldSep = body.charAt(3);          // MSH-1
        String encoding = readUntilFieldSep(body, 4, fieldSep);
        if (encoding.isEmpty()) {
            throw new Hl7ParseException("MSH-2 编码字符缺失", "MSH", 2);
        }
        char componentSep = encoding.charAt(0);
        char repetitionSep = encoding.length() > 1 ? encoding.charAt(1) : '~';
        char escapeChar = encoding.length() > 2 ? encoding.charAt(2) : '\\';
        char subSep = encoding.length() > 3 ? encoding.charAt(3) : '&';

        List<Hl7Message.Segment> segments = new ArrayList<>();
        for (String line : splitSegments(body)) {
            String seg = line.strip();
            if (seg.isEmpty()) {
                continue;
            }
            String[] fields = seg.split(java.util.regex.Pattern.quote(String.valueOf(fieldSep)), -1);
            if (fields.length == 0 || fields[0].isBlank()) {
                throw new Hl7ParseException("段名缺失：" + preview(seg), "", 0);
            }
            // 反转义逐字段做：转义序列不会跨字段，先切后解是对的。
            List<String> unescaped = new ArrayList<>(fields.length);
            for (String f : fields) {
                unescaped.add(unescape(f, escapeChar, fieldSep, componentSep, subSep, repetitionSep));
            }
            segments.add(new Hl7Message.Segment(unescaped.get(0).trim(), unescaped, componentSep));
        }

        if (segments.isEmpty()) {
            throw new Hl7ParseException("消息不含任何段");
        }
        return new Hl7Message(segments);
    }

    // ------------------------------------------------------------ 内部

    private static String stripMllp(String raw) {
        int start = raw.indexOf(MLLP_START);
        int end = raw.indexOf(MLLP_END);
        if (start >= 0 && end > start) {
            return raw.substring(start + 1, end);
        }
        return raw;
    }

    private static String readUntilFieldSep(String s, int from, char fieldSep) {
        int i = from;
        while (i < s.length() && s.charAt(i) != fieldSep && s.charAt(i) != '\r' && s.charAt(i) != '\n') {
            i++;
        }
        return s.substring(from, Math.min(i, s.length()));
    }

    private static String[] splitSegments(String body) {
        String normalized = body.replace("\r\n", "\r");
        if (normalized.indexOf('\r') >= 0) {
            return normalized.split("\r", -1);
        }
        return normalized.split("\n", -1);
    }

    /**
     * HL7 转义反转义。
     *
     * <p>按规范映射：{@code \F\}=字段分隔符、{@code \S\}=分量、{@code \T\}=子分量、
     * {@code \R\}=重复、{@code \E\}=转义符；{@code \Xdd..\ } 十六进制按字节还原。
     * 未知转义<b>原样保留</b>（不吞字符）—— 丢掉看不见的内容比留一段可疑文本更危险。
     */
    private static String unescape(String s, char esc, char fieldSep, char compSep,
                                   char subSep, char repSep) {
        if (s.indexOf(esc) < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != esc) {
                out.append(c);
                i++;
                continue;
            }
            int close = s.indexOf(esc, i + 1);
            if (close < 0) {
                out.append(s.substring(i));      // 未闭合：原样保留
                break;
            }
            String code = s.substring(i + 1, close);
            switch (code) {
                case "F" -> out.append(fieldSep);
                case "S" -> out.append(compSep);
                case "T" -> out.append(subSep);
                case "R" -> out.append(repSep);
                case "E" -> out.append(esc);
                case ".br" -> out.append('\n');
                default -> {
                    if (code.startsWith("X") && code.length() > 1) {
                        out.append(decodeHex(code.substring(1)));
                    } else {
                        out.append(esc).append(code).append(esc);
                    }
                }
            }
            i = close + 1;
        }
        return out.toString();
    }

    private static String decodeHex(String hex) {
        if (hex.length() % 2 != 0) {
            return "\\X" + hex + "\\";
        }
        StringBuilder sb = new StringBuilder(hex.length() / 2);
        for (int i = 0; i < hex.length(); i += 2) {
            try {
                sb.append((char) Integer.parseInt(hex.substring(i, i + 2), 16));
            } catch (NumberFormatException e) {
                return "\\X" + hex + "\\";
            }
        }
        return sb.toString();
    }

    private static String preview(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }
}
