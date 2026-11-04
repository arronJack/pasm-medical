package com.pasm.medical.integration.hl7;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ORU^R01 → {@link OruReport} 的语义映射。
 *
 * <p>三个刻意的严格点：
 * <ol>
 *   <li><b>只收 ORU</b>：别的消息类型（ADT/ORM）走这条通道会被拒，不会"尽量解析"。
 *       用错通道还成功，等于让 LIS 以为对接好了。</li>
 *   <li><b>患者 ref 白名单字符</b>：这个值会被用作认知层的 agent_id / 落盘目录名。
 *       允许任意字符等于允许路径穿越与注入。</li>
 *   <li><b>OBX 必须带项目编码与结果值</b>：缺 OBX-3 无法判断测的是什么，
 *       缺 OBX-5 就是一条空结果 —— 都不许入库存成"未知"。缺了整条消息退回。</li>
 * </ol>
 */
public final class Hl7OruMapper {

    private Hl7OruMapper() {
    }

    /** 患者 ref 允许的字符（与认知层 agent_id 的约束对齐）。 */
    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern NUMERIC = Pattern.compile("[+-]?\\d+(\\.\\d+)?");

    public static OruReport map(Hl7Message msg) {
        Hl7Message.Segment msh = msg.first("MSH");
        if (msh == null) {
            throw new Hl7ParseException("缺少 MSH 段", "MSH", 0);
        }

        String type = msh.field(9);
        String trigger = msh.component(9, 2);
        if (!"ORU".equalsIgnoreCase(msh.firstComponent(9)) || !"R01".equalsIgnoreCase(trigger)) {
            throw new Hl7ParseException(
                    "不支持的消息类型：" + type + "（本通道只接收 ORU^R01 检验结果）", "MSH", 9);
        }

        String controlId = msh.field(10).trim();
        if (controlId.isEmpty()) {
            throw new Hl7ParseException("MSH-10 消息控制号缺失（幂等键必需）", "MSH", 10);
        }
        if (controlId.length() > 64) {
            throw new Hl7ParseException("MSH-10 超长（>64）", "MSH", 10);
        }

        Hl7Message.Segment pid = msg.first("PID");
        if (pid == null) {
            throw new Hl7ParseException("缺少 PID 段（无法确定患者）", "PID", 0);
        }
        String patientRef = pid.firstComponent(3).trim();
        if (patientRef.isEmpty()) {
            throw new Hl7ParseException("PID-3 患者标识缺失", "PID", 3);
        }
        if (!SAFE_REF.matcher(patientRef).matches()) {
            throw new Hl7ParseException(
                    "PID-3 含非法字符（只允许字母数字._:- 且 ≤64）：" + patientRef, "PID", 3);
        }

        Hl7Message.Segment obr = msg.first("OBR");
        String panelCode = obr == null ? "" : obr.firstComponent(4).trim();
        String panelName = obr == null ? "" : obr.secondComponent(4).trim();
        // 消息级时区偏移：MSH-7 通常带 +0800。OBR/OBX 里的时间经常不带偏移，
        // 若按 UTC 解释会整体偏移 8 小时（检验时间落在"未来"或"昨天"）。
        // 所以缺偏移的时间沿用 MSH-7 的偏移；连 MSH-7 都没有才退回 UTC。
        ZoneOffset messageOffset = offsetOf(msh.field(7));
        Instant panelTime = obr == null ? null : ts(obr.field(7), messageOffset);
        if (panelTime == null) {
            // OBR-7 缺失时用 MSH-7（消息时间）兜底，而不是用"服务器当前时间"：
            // 后者会把补传的历史结果记成今天，时间轴上直接错位。
            panelTime = ts(msh.field(7), messageOffset);
        }

        List<Hl7Message.Segment> obxList = msg.all("OBX");
        if (obxList.isEmpty()) {
            throw new Hl7ParseException("缺少 OBX 段（没有检验项）", "OBX", 0);
        }

        List<OruReport.Observation> out = new ArrayList<>(obxList.size());
        int seq = 0;
        for (Hl7Message.Segment obx : obxList) {
            seq++;
            String code = obx.firstComponent(3).trim();
            if (code.isEmpty()) {
                throw new Hl7ParseException(
                        "第 " + seq + " 个 OBX 缺少项目编码（OBX-3）", "OBX", 3);
            }
            String valueType = obx.field(2).trim();
            String valueText = obx.field(5).trim();
            if (valueText.isEmpty()) {
                throw new Hl7ParseException(
                        "第 " + seq + " 个 OBX 缺少结果值（OBX-5）", "OBX", 5);
            }

            String unit = obx.firstComponent(6).trim();
            String rangeText = obx.field(7).trim();
            BigDecimal[] range = parseRange(rangeText);
            BigDecimal valueNum = parseNumber(valueType, valueText);
            String rawFlag = obx.field(8).trim();
            if (rawFlag.indexOf('~') >= 0) {          // 多重复时取第一个
                rawFlag = rawFlag.substring(0, rawFlag.indexOf('~'));
            }
            String status = obx.field(11).trim();
            if (status.isEmpty()) {
                status = "F";
            }
            Instant obsTime = ts(obx.field(14), messageOffset);
            if (obsTime == null) {
                obsTime = panelTime;
            }

            out.add(new OruReport.Observation(
                    seq,
                    code,
                    obx.secondComponent(3).trim(),
                    valueText,
                    valueNum,
                    unit,
                    range[0],
                    range[1],
                    rangeText,
                    normalizeFlag(rawFlag, valueNum, range[0], range[1]),
                    rawFlag,
                    status,
                    obsTime));
        }

        return new OruReport(patientRef, controlId, msh.field(3).trim(),
                panelCode, panelName, panelTime, out);
    }

    // ------------------------------------------------------------ 字段解析

    /** 数值型判定：OBX-2 为 NM，或值本身是纯数字（部分仪器不填 OBX-2）。 */
    static BigDecimal parseNumber(String valueType, String valueText) {
        String v = valueText.trim();
        if ("NM".equalsIgnoreCase(valueType) || NUMERIC.matcher(v).matches()) {
            try {
                return new BigDecimal(v);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 参考区间解析：{@code 3.5-9.5} / {@code <5} / {@code >=1.0}。
     *
     * <p>解析不出来时返回 {null,null} 且保留原文 —— <b>不猜</b>。
     * 猜区间会让"高/低"判读出错，而判读错误会直接出现在医师看到的界面上。
     */
    static BigDecimal[] parseRange(String text) {
        if (text == null || text.isBlank()) {
            return new BigDecimal[]{null, null};
        }
        String t = text.trim().replace(" ", "");
        try {
            if (t.startsWith("<=")) {
                return new BigDecimal[]{null, new BigDecimal(t.substring(2))};
            }
            if (t.startsWith("<")) {
                return new BigDecimal[]{null, new BigDecimal(t.substring(1))};
            }
            if (t.startsWith(">=")) {
                return new BigDecimal[]{new BigDecimal(t.substring(2)), null};
            }
            if (t.startsWith(">")) {
                return new BigDecimal[]{new BigDecimal(t.substring(1)), null};
            }
            int dash = t.indexOf('-', 1);            // 从 1 开始：跳过负号
            if (dash > 0) {
                return new BigDecimal[]{
                        new BigDecimal(t.substring(0, dash)),
                        new BigDecimal(t.substring(dash + 1))};
            }
        } catch (NumberFormatException ignored) {
            // 落到"不猜"
        }
        return new BigDecimal[]{null, null};
    }

    /**
     * 归一化异常标记。
     *
     * <p>优先用 LIS 给的标记（仪器侧的判读通常已按本机构区间校准过）；
     * 只有标记为空时才用数值与区间自行比较 —— 自行比较的结果可能与仪器不一致，
     * 所以那种情况标 {@code unknown} 而不是硬判 high/low？<b>不</b>：
     * 区间是这张单子印出来的，比较是确定的，标 high/low 是诚实的；
     * 缺区间且无标记才是 unknown。
     */
    static String normalizeFlag(String raw, BigDecimal value, BigDecimal low, BigDecimal high) {
        String f = raw == null ? "" : raw.trim().toUpperCase();
        switch (f) {
            case "H", "HH", ">", "HU":
                return "high";
            case "L", "LL", "<", "LU":
                return "low";
            case "N":
                return "normal";
            case "A", "AA":
                return "abnormal";
            default:
                break;
        }
        if (value == null) {
            return "unknown";
        }
        if (low != null && value.compareTo(low) < 0) {
            return "low";
        }
        if (high != null && value.compareTo(high) > 0) {
            return "high";
        }
        return (low != null || high != null) ? "normal" : "unknown";
    }

    /**
     * HL7 TS → Instant：{@code yyyyMMddHHmmss[.S][+/-ZZZZ]}。
     *
     * @param fallback 字符串自身没带偏移时沿用的时区（一般取 MSH-7 的偏移）；
     *                 null 表示退回 UTC。
     */
    static Instant ts(String s, ZoneOffset fallback) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        try {
            ZoneOffset parsed = offsetOf(t);
            ZoneOffset offset = parsed != null ? parsed
                    : (fallback != null ? fallback : ZoneOffset.UTC);
            if (parsed != null) {
                t = t.substring(0, t.length() - offsetSuffixLength(t));
            }
            String digitsOnly = t.replace(".", "");
            if (digitsOnly.length() < 4) {
                return null;
            }
            int year = Integer.parseInt(digitsOnly.substring(0, 4));
            int month = digitsOnly.length() >= 6 ? Integer.parseInt(digitsOnly.substring(4, 6)) : 1;
            int day = digitsOnly.length() >= 8 ? Integer.parseInt(digitsOnly.substring(6, 8)) : 1;
            int hour = digitsOnly.length() >= 10 ? Integer.parseInt(digitsOnly.substring(8, 10)) : 0;
            int minute = digitsOnly.length() >= 12 ? Integer.parseInt(digitsOnly.substring(10, 12)) : 0;
            int second = digitsOnly.length() >= 14 ? Integer.parseInt(digitsOnly.substring(12, 14)) : 0;
            return LocalDateTime.of(year, month, day, hour, minute, second).toInstant(offset);
        } catch (NumberFormatException | DateTimeException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    /** 取 TS 自带时区偏移；没有（或不合法）返回 null。 */
    static ZoneOffset offsetOf(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        int idx = -1;
        for (int i = 4; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '+' || c == '-') {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return null;
        }
        String off = t.substring(idx);
        String digits = off.substring(1).replace(":", "");
        if (digits.length() < 4) {
            return null;
        }
        try {
            int h = Integer.parseInt(digits.substring(0, 2));
            int m = Integer.parseInt(digits.substring(2, 4));
            int total = h * 3600 + m * 60;
            return ZoneOffset.ofTotalSeconds("+".equals(off.substring(0, 1)) ? total : -total);
        } catch (NumberFormatException | DateTimeException e) {
            return null;
        }
    }

    private static int offsetSuffixLength(String t) {
        int idx = -1;
        for (int i = 4; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '+' || c == '-') {
                idx = i;
                break;
            }
        }
        return idx < 0 ? 0 : t.length() - idx;
    }
}
