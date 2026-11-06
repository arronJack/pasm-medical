package com.pasm.medical.integration.hl7;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * HL7 v2 {@code ACK^R01} 构造（纯静态，不连库、不依赖 Spring）。
 *
 * <p>ACK 的 MSA-1 三态<b>必须区分</b>，否则 LIS 会做出错误行为：
 * <ul>
 *   <li>{@code AA} 接受：已落库（含幂等重发）。LIS 可丢弃本地副本。</li>
 *   <li>{@code AE} 应用错误：消息能解析但业务上拒收（未知患者、机构未启用、
 *       解析失败）。LIS <b>修正后可重发</b>，直接无限重试是错的。</li>
 *   <li>{@code AR} 拒绝：令牌无效/未配置、非 ORU 报文等配置或鉴权问题，
 *       重发也不会变好，必须 <b>停止重试并报警</b>。</li>
 * </ul>
 * 用 HTTP 状态码已经表达了同样的语义（202 / 409 / 422 / 401），
 * MLLP 只是把它翻译成 HL7 的标准写法，不引入第二套判定逻辑。
 *
 * <p>★ MSA-2 回显原 MSH-10 消息控制号：LIS 靠它把 ACK 和发出去的消息对上。
 * 丢了这个字段，LIS 会认为所有消息都没确认而无限重发。
 *
 * <p>★ ACK 文本里<b>不放异常原文</b>：异常可能含患者 ref / 内部路径，
 * 回给院外系统等于泄露。只放稳定的错误码 + 一句安全说明。
 */
public final class Hl7Ack {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssZ");

    private Hl7Ack() {
    }

    /** ACK 结果码。 */
    public enum Code {
        AA, AE, AR
    }

    /**
     * 构造 ACK 报文（段分隔符 {@code \r}，与入站 ORU 一致）。
     *
     * @param originalControlId 原消息 MSH-10；解析不出来时传空串（LIS 侧会记为无法配对）
     * @param code              AA / AE / AR
     * @param reasonCode        稳定的机器可读码，如 {@code unknown_patient}
     */
    public static String build(String originalControlId, Code code, String reasonCode) {
        String ts = OffsetDateTime.now().format(TS);
        // ★ 先清洗再拼接：MSH-10 来自外部消息，可能带 \r / \n / | 等结构字符。
        //   旧实现只 sanitize 了 MSA-2 和 MSA-3，却把原始控制号直接拼进 MSH-10，
        //   等于给 ACK 留了一个注入口（一条坏消息能把回执切成好几段）。
        String safeRef = sanitize(originalControlId);
        String controlId = "ACK-" + safeRef;
        // 控制号超长会让某些 LIS 截断后对不上账；截断并保留可追溯前缀。
        if (controlId.length() > 64) {
            controlId = controlId.substring(0, 64);
        }
        String safeReason = sanitize(reasonCode);
        // AA 也必须带上 reasonCode（如 inserted=2;skippedDuplicates=0）：
        // LIS 靠它区分「真落库」和「幂等重发」，只回 accepted 等于把对账信息丢了。
        String text = safeReason.isEmpty()
                ? (code == Code.AA ? "accepted" : code.name())
                : safeReason;

        return "MSH|^~\\&|PASM|HOSP|LIS|HOSP|" + ts + "||ACK^R01|"
                + controlId + "|P|2.5.1\r"
                + "MSA|" + code.name() + "|" + safeRef + "|" + text + "\r";
    }

    /** 尽量从（可能不完整的）入站消息里取出 MSH-10，供 ACK 回显。 */
    public static String controlIdOf(String raw) {
        if (raw == null) {
            return "";
        }
        String body = raw;
        int vt = body.indexOf(MllpFrame.VT);
        if (vt >= 0) {
            body = body.substring(vt + 1);
        }
        int end = body.indexOf(MllpFrame.FS);
        if (end >= 0) {
            body = body.substring(0, end);
        }
        for (String line : body.split("[\r\n]", -1)) {
            if (!line.startsWith("MSH")) {
                continue;
            }
            char sep = line.length() > 3 ? line.charAt(3) : '|';
            String[] f = line.split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1);
            // MSH 字段整体前移一位：MSH-10 落在下标 9（见 Hl7Message 注释）。
            return f.length > 9 ? f[9].trim() : "";
        }
        return "";
    }

    /** 去掉 HL7 结构字符，防止错误码把 ACK 自己切坏或注入额外段。 */
    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String out = s.replaceAll("[\\r\\n|\\^~\\\\&]", "_");
        return out.length() > 120 ? out.substring(0, 120) : out;
    }
}
