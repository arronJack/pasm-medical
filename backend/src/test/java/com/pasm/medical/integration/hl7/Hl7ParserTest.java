package com.pasm.medical.integration.hl7;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HL7 解析与 ORU 映射的单元测试（不启动 Spring，不连库）。
 *
 * <p>覆盖点：MLLP 帧、自定义分隔符、转义、时区、区间/标记归一化，
 * 以及<b>反例</b> —— 非 ORU、缺患者、缺 OBX、非法 ref 都必须抛异常。
 * 反例比正例更重要：解析器"悄悄少解析一条"不会报错，只会让医师少看到一条结果。
 */
class Hl7ParserTest {

    private static final String ORU = String.join("\r",
            "MSH|^~\\&|LIS|HOSP|EMR|HOSP|20261104120000+0800||ORU^R01|MSG-20261104-001|P|2.5.1",
            "PID|1||P001^^^HOSP^MR",
            "OBR|1|ORD001|SPEC001|CBC^血常规^L|||20261104115500",
            "OBX|1|NM|WBC^白细胞计数^L||6.2|10*9/L|3.5-9.5|N|||F|||20261104115800",
            "OBX|2|NM|HGB^血红蛋白^L||98|g/L|130-175|L|||F|||20261104115800",
            "OBX|3|ST|HBSAG^乙肝表面抗原^L||阴性|||||F");

    @Test
    void parsesOruWithPanelAndObservations() {
        OruReport r = Hl7OruMapper.map(Hl7Parser.parse(ORU));

        assertEquals("P001", r.patientRef());
        assertEquals("MSG-20261104-001", r.messageControlId());
        assertEquals("LIS", r.sendingApp());
        assertEquals("CBC", r.panelCode());
        assertEquals("血常规", r.panelName());
        // OBR-7 是 11:55 本地时间且自身不带偏移 → 沿用 MSH-7 的 +0800 = 03:55Z。
        // 若按 UTC 解释会变成 11:55Z，整条结果偏 8 小时。
        assertEquals(Instant.parse("2026-11-04T03:55:00Z"), r.observedAt());
        assertEquals(3, r.observations().size());

        OruReport.Observation wbc = r.observations().get(0);
        assertEquals("WBC", wbc.analyteCode());
        assertEquals("白细胞计数", wbc.analyteName());
        assertEquals(new BigDecimal("6.2"), wbc.valueNum());
        assertEquals("10*9/L", wbc.unit());
        assertEquals(new BigDecimal("3.5"), wbc.refLow());
        assertEquals(new BigDecimal("9.5"), wbc.refHigh());
        assertEquals("normal", wbc.abnormalFlag());
        assertEquals("N", wbc.abnormalFlagRaw());
        assertEquals("F", wbc.status());
        assertEquals(Instant.parse("2026-11-04T03:58:00Z"), wbc.observedAt());

        OruReport.Observation hgb = r.observations().get(1);
        assertEquals("low", hgb.abnormalFlag(), "L 标记应归一化为 low");

        OruReport.Observation hbsag = r.observations().get(2);
        assertEquals("阴性", hbsag.valueText());
        assertNull(hbsag.valueNum(), "文本型结果不能转成数字");
        assertEquals("unknown", hbsag.abnormalFlag(), "无区间无数值时应为 unknown，而不是硬判 normal");
    }

    @Test
    void stripsMllpFrameAndHandlesCustomSeparators() {
        // 分号作字段分隔符、冒号作分量分隔符 —— 部分厂商确实这么发
        String custom = String.join("\r",
                "MSH;:~/&;LIS;HOSP;EMR;HOSP;20261104120000;;ORU:R01;MSG-CS-1;P;2.5.1",
                "PID;1;;P002",
                "OBX;1;NM;GLU:血糖;;5.6;mmol/L;3.9-6.1;H;;;F");
        String framed = "\u000B" + custom + "\u001C\r";

        OruReport r = Hl7OruMapper.map(Hl7Parser.parse(framed));
        assertEquals("P002", r.patientRef());
        assertEquals("GLU", r.observations().get(0).analyteCode());
        assertEquals("血糖", r.observations().get(0).analyteName());
        assertEquals("high", r.observations().get(0).abnormalFlag());
    }

    @Test
    void unescapesSpecialCharacters() {
        String msg = String.join("\r",
                "MSH|^~\\&|LIS|H|E|H|20261104120000||ORU^R01|MSG-ESC-1|P|2.5.1",
                "PID|1||P003",
                "OBX|1|ST|TXT^文本||a\\F\\b\\S\\c\\T\\d\\R\\e\\E\\f|||||||F");

        OruReport r = Hl7OruMapper.map(Hl7Parser.parse(msg));
        // \F\=| \S\=^ \T\=& \R\=~ \E\=\
        assertEquals("a|b^c&d~e\\f", r.observations().get(0).valueText());
    }

    @Test
    void parsesReferenceRangeForms() {
        assertRange("<5", null, "5");
        assertRange(">=1.0", "1.0", null);
        assertRange("3.5-9.5", "3.5", "9.5");
        assertRange("-2.5-2.5", "-2.5", "2.5");
        assertRange("参考范围见报告", null, null);
    }

    private static void assertRange(String text, String low, String high) {
        BigDecimal[] r = Hl7OruMapper.parseRange(text);
        assertEquals(low == null ? null : new BigDecimal(low), r[0], "low of " + text);
        assertEquals(high == null ? null : new BigDecimal(high), r[1], "high of " + text);
    }

    @Test
    void rejectsNonOruMessage() {
        String adt = "MSH|^~\\&|HIS|H|EMR|H|20261104120000||ADT^A01|MSG-ADT-1|P|2.5.1\r"
                + "PID|1||P001";
        Hl7ParseException e = assertThrows(Hl7ParseException.class,
                () -> Hl7OruMapper.map(Hl7Parser.parse(adt)));
        assertEquals("MSH", e.getSegment());
        assertEquals(9, e.getField());
    }

    @Test
    void rejectsMissingPatientIdentifier() {
        String msg = "MSH|^~\\&|LIS|H|E|H|20261104120000||ORU^R01|MSG-NOPID|P|2.5.1\r"
                + "PID|1||\r"
                + "OBX|1|NM|WBC^白细胞|||||||F";
        Hl7ParseException e = assertThrows(Hl7ParseException.class,
                () -> Hl7OruMapper.map(Hl7Parser.parse(msg)));
        assertEquals("PID", e.getSegment());
    }

    @Test
    void rejectsUnsafePatientRef() {
        // 路径穿越 / 注入：会被当成认知层 agent_id 与落盘目录名，必须拒绝
        String msg = "MSH|^~\\&|LIS|H|E|H|20261104120000||ORU^R01|MSG-BADREF|P|2.5.1\r"
                + "PID|1||../../etc/passwd\r"
                + "OBX|1|NM|WBC^白细胞||6.2||||N|||F";
        assertThrows(Hl7ParseException.class, () -> Hl7OruMapper.map(Hl7Parser.parse(msg)));
    }

    @Test
    void rejectsMessageWithoutObservations() {
        String msg = "MSH|^~\\&|LIS|H|E|H|20261104120000||ORU^R01|MSG-NOOBX|P|2.5.1\r"
                + "PID|1||P001\r"
                + "OBR|1|O|S|CBC^血常规";
        Hl7ParseException e = assertThrows(Hl7ParseException.class,
                () -> Hl7OruMapper.map(Hl7Parser.parse(msg)));
        assertEquals("OBX", e.getSegment());
    }

    @Test
    void rejectsObservationWithoutValue() {
        String msg = "MSH|^~\\&|LIS|H|E|H|20261104120000||ORU^R01|MSG-NOVAL|P|2.5.1\r"
                + "PID|1||P001\r"
                + "OBX|1|NM|WBC^白细胞||||||N|||F";
        Hl7ParseException e = assertThrows(Hl7ParseException.class,
                () -> Hl7OruMapper.map(Hl7Parser.parse(msg)));
        assertEquals("OBX", e.getSegment());
        assertEquals(5, e.getField());
    }

    @Test
    void missingSeparatorsIsRejectedNotGuessed() {
        assertThrows(Hl7ParseException.class, () -> Hl7Parser.parse("PID|1||P001"));
        assertThrows(Hl7ParseException.class, () -> Hl7Parser.parse(""));
        assertTrue(true);
    }
}
