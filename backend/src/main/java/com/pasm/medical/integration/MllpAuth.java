package com.pasm.medical.integration;

/**
 * MLLP 通道的 {@code ZAT} 自定义段处理（纯静态，可单测）。
 *
 * <p>TCP 没有 HTTP 头，共享密钥只能放在 HL7 消息里。用 {@code Z} 段是 HL7 v2
 * 允许的扩展方式；{@code ZAT} = <b>Z</b>-<b>A</b>uth-<b>T</b>oken。
 *
 * <p>★ 存在这个类的唯一理由是那条不变量：<b>令牌必须在交给业务层之前被剥掉</b>。
 * 它不是「代码整洁」，而是防止共享密钥落进异常文本、日志、审计记录或
 * {@code evidence} 字段。剥不掉就等于把机器凭证散落在库里，
 * 而这类泄露不会有任何报错 —— 所以必须有一条单测直接断言它。
 */
public final class MllpAuth {

    public static final String SEGMENT = "ZAT";

    private MllpAuth() {
    }

    /**
     * 从 {@code ZAT|<token>} 取令牌；段不存在返回 {@code null}
     * （由 {@code IntegrationTokenGuard} 判为 MISSING，而不是当成空串放行）。
     */
    public static String extract(String raw) {
        if (raw == null) {
            return null;
        }
        for (String line : raw.split("[\r\n]", -1)) {
            if (line.startsWith(SEGMENT)) {
                int sep = line.indexOf('|');
                return sep >= 0 ? line.substring(sep + 1).trim() : "";
            }
        }
        return null;
    }

    /**
     * 剥掉认证段，保留其余段及原始段分隔符风格（{@code \r} 优先）。
     * 入站消息可能被 MLLP 封装，调用方需先 {@code unwrap}。
     */
    public static String strip(String raw) {
        if (raw == null) {
            return "";
        }
        String sep = raw.indexOf('\r') >= 0 ? "\r" : "\n";
        StringBuilder out = new StringBuilder();
        for (String line : raw.split("[\r\n]", -1)) {
            if (line.isEmpty() || line.startsWith(SEGMENT)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(sep);
            }
            out.append(line);
        }
        return out.toString();
    }
}
