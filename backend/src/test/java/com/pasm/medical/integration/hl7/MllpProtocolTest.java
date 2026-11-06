package com.pasm.medical.integration.hl7;

import com.pasm.medical.integration.MllpAuth;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MLLP 帧 / ACK / 认证段的纯单元测试（不启 Spring、不连库、不开端口）。
 *
 * <p>反例比正例重要：帧少一个字节、ACK 少回控制号、令牌没剥干净，
 * 都不会当场报错，只会让 LIS 无限重发或让共享密钥悄悄落库。
 */
class MllpProtocolTest {

    private static final String ORU = String.join("\r",
            "MSH|^~\\&|LIS|HOSP|EMR|HOSP|20261104120000+0800||ORU^R01|MSG-MLLP-001|P|2.5.1",
            "PID|1||P001^^^HOSP^MR",
            "OBR|1|ORD001|SPEC001|CBC^血常规^L|||20261104115500",
            "OBX|1|NM|WBC^白细胞计数^L||6.2|10*9/L|3.5-9.5|N|||F");

    // ------------------------------------------------------------ 帧

    @Test
    void roundTripKeepsMultiByteContentAndSegmentSeparators() {
        byte[] frame = MllpFrame.wrap(ORU);
        assertEquals(MllpFrame.VT, frame[0]);
        assertEquals(MllpFrame.FS, frame[frame.length - 2]);
        assertEquals(MllpFrame.CR, frame[frame.length - 1]);
        assertEquals(ORU, MllpFrame.unwrap(frame),
                "UTF-8 中文与 \\r 段分隔符必须原样还原");
    }

    @Test
    void rejectsBrokenFrames() {
        byte[] good = MllpFrame.wrap(ORU);
        byte[] badStart = good.clone();
        badStart[0] = 'X';
        assertEquals("bad_start", assertThrows(MllpFrame.MllpFrameException.class,
                () -> MllpFrame.unwrap(badStart)).code());

        byte[] badEnd = good.clone();
        badEnd[badEnd.length - 1] = '\n';
        assertEquals("bad_end", assertThrows(MllpFrame.MllpFrameException.class,
                () -> MllpFrame.unwrap(badEnd)).code());

        assertThrows(MllpFrame.MllpFrameException.class,
                () -> MllpFrame.unwrap(new byte[0]));
        // 超长帧按上限拒绝，不能让它把内存吃满
        byte[] huge = new byte[MllpFrame.MAX_FRAME_BYTES + 1];
        huge[0] = MllpFrame.VT;
        huge[huge.length - 2] = MllpFrame.FS;
        huge[huge.length - 1] = MllpFrame.CR;
        assertEquals("frame_too_large", assertThrows(MllpFrame.MllpFrameException.class,
                () -> MllpFrame.unwrap(huge)).code());
    }

    @Test
    void frameBytesAreUtf8NotPlatformDefault() {
        byte[] frame = MllpFrame.wrap("OBX|1|ST|HBSAG^乙肝表面抗原^L||阴性");
        String decoded = new String(frame, 1, frame.length - 3, StandardCharsets.UTF_8);
        assertTrue(decoded.contains("乙肝表面抗原"));
    }

    // ------------------------------------------------------------ ACK

    @Test
    void ackEchoesControlIdAndUsesThreeDistinctCodes() {
        String aa = Hl7Ack.build("MSG-MLLP-001", Hl7Ack.Code.AA, "inserted=1");
        assertTrue(aa.startsWith("MSH|^~\\&|"), "ACK 必须有合法 MSH 头");
        assertTrue(aa.contains("||ACK^R01|"), "消息类型必须是 ACK^R01");
        assertTrue(aa.contains("MSA|AA|MSG-MLLP-001|"), "MSA-2 必须回显原控制号");

        assertTrue(Hl7Ack.build("X", Hl7Ack.Code.AE, "unknown_patient")
                .contains("MSA|AE|X|unknown_patient"));
        assertTrue(Hl7Ack.build("X", Hl7Ack.Code.AR, "invalid_token")
                .contains("MSA|AR|X|invalid_token"));
    }

    @Test
    void ackTextCannotInjectExtraSegments() {
        // 错误码若含 \r 或 |，会把 ACK 自己切出额外的段 —— 必须被清洗。
        String ack = Hl7Ack.build("MSG\r\n|INJECT", Hl7Ack.Code.AE, "bad|code\rMSA|AA|fake");
        assertFalse(ack.contains("\n"), "ACK 内不得出现换行注入");
        assertEquals(2, ack.split("\r", -1).length - 1, "ACK 应恰好两个段分隔符（MSH + MSA）");
        assertTrue(ack.contains("MSA|AE|"));
    }

    @Test
    void ackOverlongControlIdIsTruncated() {
        String ack = Hl7Ack.build("X".repeat(200), Hl7Ack.Code.AA, "");
        assertTrue(ack.length() < 400, "控制号超长时必须截断，不能撑爆 ACK");
        assertTrue(ack.contains("MSA|AA|"));
    }

    @Test
    void controlIdIsExtractedEvenFromMllpWrappedOrBrokenMessage() {
        assertEquals("MSG-MLLP-001", Hl7Ack.controlIdOf(ORU));
        String wrapped = new String(MllpFrame.wrap(ORU), StandardCharsets.ISO_8859_1);
        assertEquals("MSG-MLLP-001", Hl7Ack.controlIdOf(wrapped),
                "帧异常分支也要尽量拿到控制号给 LIS 对账");
        assertEquals("", Hl7Ack.controlIdOf(null));
        assertEquals("", Hl7Ack.controlIdOf("PID|1||P001"));
    }

    // ------------------------------------------------------------ 认证段

    @Test
    void authSegmentIsExtractedAndNeverLeaksIntoBusinessMessage() {
        String withAuth = ORU + "\rZAT|secret-lis-token";
        assertEquals("secret-lis-token", MllpAuth.extract(withAuth));
        String clean = MllpAuth.strip(withAuth);
        assertEquals(ORU, clean, "剥离后必须与原始业务消息逐字节一致（幂等键不变）");
        assertFalse(clean.contains("ZAT"));
        assertFalse(clean.contains("secret-lis-token"),
                "★ 令牌不得进入交给 ingest() 的消息：否则会落进异常/日志/evidence");
        assertNull(MllpAuth.extract(ORU), "无 ZAT 段必须返回 null，由 guard 判 MISSING");
    }

    @Test
    void stripHandlesLfSeparatorsAndTrailingEmptySegments() {
        String lf = ORU.replace('\r', '\n') + "\nZAT|tok\n";
        assertEquals(ORU.replace('\r', '\n'), MllpAuth.strip(lf));
        assertEquals("", MllpAuth.strip("ZAT|tok"));
    }

    @Test
    void strippedMessageStillParsesAsValidOru() {
        // 端到端不变量：剥认证段不能破坏 HL7 解析，否则「安全」会把通道弄坏。
        String clean = MllpAuth.strip(ORU + "\rZAT|tok");
        OruReport r = Hl7OruMapper.map(Hl7Parser.parse(clean));
        assertEquals("P001", r.patientRef());
        assertEquals("MSG-MLLP-001", r.messageControlId());
        assertEquals(1, r.observations().size());
    }
}
