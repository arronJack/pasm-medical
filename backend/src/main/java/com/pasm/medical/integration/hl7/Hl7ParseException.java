package com.pasm.medical.integration.hl7;

/**
 * HL7 消息无法解析 / 不符合 ORU^R01 最低要求。
 *
 * <p>★ 这是一条<b>必须抛出</b>的异常，不允许"尽力而为地解析出一部分"：
 * 检验结果少一条、错一位，都会被下游当成真实结果用于诊疗判断。
 * 宁可整条消息退回给 LIS 重发，也不要落一半。
 *
 * <p>消息里带 {@code segment} / {@code field} 便于对方定位（LIS 厂商排障时
 * 只认段名与字段号，不认我们的内部行号）。
 */
public class Hl7ParseException extends RuntimeException {

    private final String segment;
    private final int field;

    public Hl7ParseException(String message, String segment, int field) {
        super(message);
        this.segment = segment;
        this.field = field;
    }

    public Hl7ParseException(String message) {
        this(message, "", 0);
    }

    public String getSegment() {
        return segment;
    }

    public int getField() {
        return field;
    }
}
