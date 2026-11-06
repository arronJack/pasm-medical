package com.pasm.medical.integration.hl7;

import java.nio.charset.StandardCharsets;

/**
 * MLLP（Minimal Lower Layer Protocol）帧编解码 —— <b>纯字节层，不解释 HL7</b>。
 *
 * <p>帧格式：{@code <VT=0x0B> HL7 消息 <FS=0x1C> <CR=0x0D>}。
 * 之所以单独抽出来，是因为 HL7 消息体里可能出现 {@code \r} 作段分隔符，
 * 但绝不会出现 0x0B / 0x1C —— 用它们做边界才不会把多段消息读成半条。
 *
 * <p>★ 边界判定按 <b>byte</b> 而非 char：UTF-8 中文每个字节都 ≥ 0x80，
 * 不会误撞 0x0B / 0x1C；用 char 判断则会在多字节序列上做错切分。
 *
 * <p>★ 上限 1 MiB 与 HTTP 投递端点一致：正常 ORU 只有几 KB，
 * 超限多半是发错对象或攻击，不能让它把内存吃满。
 */
public final class MllpFrame {

    public static final byte VT = 0x0B;
    public static final byte FS = 0x1C;
    public static final byte CR = 0x0D;

    /** 单帧最大字节数（含首尾封装字节）。 */
    public static final int MAX_FRAME_BYTES = 1024 * 1024 + 3;

    private MllpFrame() {
    }

    /** 把 HL7 文本包成 MLLP 帧。 */
    public static byte[] wrap(String hl7) {
        byte[] body = hl7.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = VT;
        System.arraycopy(body, 0, out, 1, body.length);
        out[out.length - 2] = FS;
        out[out.length - 1] = CR;
        return out;
    }

    /**
     * 从完整帧字节里取出 HL7 文本。
     *
     * @throws MllpFrameException 帧不完整、超长或首尾字节错误
     */
    public static String unwrap(byte[] frame) {
        if (frame == null || frame.length < 3) {
            throw new MllpFrameException("empty_frame", "MLLP 帧为空或过短");
        }
        if (frame.length > MAX_FRAME_BYTES) {
            throw new MllpFrameException("frame_too_large",
                    "MLLP 帧超过 1 MiB 上限：" + frame.length);
        }
        if (frame[0] != VT) {
            throw new MllpFrameException("bad_start", "缺少 0x0B 起始字节");
        }
        if (frame[frame.length - 2] != FS || frame[frame.length - 1] != CR) {
            throw new MllpFrameException("bad_end", "缺少 0x1C 0x0D 结束字节");
        }
        return new String(frame, 1, frame.length - 3, StandardCharsets.UTF_8);
    }

    /** 帧层错误（与 HL7 解析错误分开：前者连消息都没读完，不该回 400 语义）。 */
    public static final class MllpFrameException extends RuntimeException {
        private final String code;

        public MllpFrameException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
