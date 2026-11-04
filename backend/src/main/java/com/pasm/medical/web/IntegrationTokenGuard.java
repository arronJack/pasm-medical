package com.pasm.medical.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 机器对接通道的共享密钥校验（LIS / FHIR 投递共用）。
 *
 * <p>抽出来的原因不是"少写两行"，而是<b>安全逻辑只应存在一处</b>：
 * 两个控制器各写一份，早晚会出现"一个改了常量时间比较、另一个还是 equals"
 * 这种不对称 —— 而它不会有任何报错，只会静默退化成可计时爆破。
 *
 * <p>★ 未配置令牌 = <b>关闭通道</b>（fail closed），不是放行。
 */
@Component
public class IntegrationTokenGuard {

    /** 校验结论。 */
    public enum Verdict {
        OK,
        NOT_CONFIGURED,
        MISSING,
        INVALID
    }

    private final String expected;

    public IntegrationTokenGuard(@Value("${medical.lis.token:}") String expected) {
        this.expected = expected == null ? "" : expected;
    }

    public Verdict check(String provided) {
        if (expected.isBlank()) {
            return Verdict.NOT_CONFIGURED;
        }
        if (provided == null || provided.isBlank()) {
            return Verdict.MISSING;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)) ? Verdict.OK : Verdict.INVALID;
    }
}
