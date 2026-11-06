package com.pasm.medical.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投产门禁的单测（不启 Spring 上下文，直接 new 出来算结论）。
 *
 * <p>★ 必须包含<b>反例</b>：如果 strict=true 下危险组合也能照常启动，
 * 这个门禁就是一行装饰 —— 和「文字护栏挡不住一次点击」是同一类问题。
 */
class ProductionGateTest {

    /** dev 档 / 默认配置：宽松，但结论必须如实标注为 DEV。 */
    private static ProductionGate dev() {
        return new ProductionGate(false, "dev", true, false, false,
                "127.0.0.1", "", "", "jdbc:h2:mem:pasm_medical;DB_CLOSE_DELAY=-1");
    }

    /** 一套「全部合格」的投产配置。 */
    private static ProductionGate prodOk() {
        return new ProductionGate(true, "prod", false, false, true,
                "127.0.0.1", "x".repeat(48), "lis-token-xyz",
                "jdbc:postgresql://127.0.0.1:5432/pasm_medical");
    }

    private static Map<String, ProductionGate.Finding> byCode(ProductionGate g) {
        return g.evaluate().stream().collect(Collectors.toMap(
                ProductionGate.Finding::code, f -> f, (a, b) -> a, java.util.LinkedHashMap::new));
    }

    @Test
    void devProfileIsLabelledAndNeverUsedAsProductionEvidence() {
        Map<String, ProductionGate.Finding> f = byCode(dev());
        assertEquals("DEV", f.get("profile").status());
        assertEquals(ProductionGate.Level.WARN, f.get("profile").level());
        assertTrue(f.get("profile").detail().contains("不作为投产依据"));
    }

    @Test
    void devDefaultsAreNotSilentlyPassedAsOk() {
        // 演示账号开着、JWT 空、H2 内存库 —— 这些在 dev 是允许的，
        // 但门禁必须如实把它们标成 BLOCK 状态，不能因为「dev」就一律报绿。
        Map<String, ProductionGate.Finding> f = byCode(dev());
        assertEquals("BLOCK", f.get("demo_login").status());
        assertEquals("BLOCK", f.get("jwt_secret").status());
        assertEquals("BLOCK", f.get("database").status());
    }

    @Test
    void strictModeRefusesToStartWithDangerousCombination() {
        // ★ 反例：投产档 + strict，却带着写死口令、空 JWT 密钥、自动学习开启、
        //   MLLP 绑公网、H2 内存库 —— 必须抛异常阻止启动。
        ProductionGate bad = new ProductionGate(true, "prod", true, true, true,
                "0.0.0.0", "", "", "jdbc:h2:mem:pasm_medical");
        IllegalStateException e = assertThrows(IllegalStateException.class, bad::verify);
        assertTrue(e.getMessage().contains("投产门禁未通过"));
        assertTrue(e.getMessage().contains("demo_login"));
        assertTrue(e.getMessage().contains("jwt_secret"));
        assertTrue(e.getMessage().contains("mllp_bind"));
        assertTrue(e.getMessage().contains("database"));
    }

    @Test
    void strictModeAllowsFullyConfiguredProduction() {
        ProductionGate ok = prodOk();
        Map<String, ProductionGate.Finding> f = byCode(ok);
        for (ProductionGate.Finding x : f.values()) {
            assertFalse("BLOCK".equals(x.status()),
                    "合格配置不应有 BLOCK 项，实际：" + x.code() + " " + x.detail());
        }
        ok.verify();   // 不抛异常
    }

    @Test
    void nonStrictModeWarnsButDoesNotBreakDevAndTests() {
        // 关键回归：默认 strict=false 时，即使结论全是 BLOCK 也不能炸掉启动，
        // 否则 dev / 嵌入式 PG 测试 / e2e 会集体起不来。
        ProductionGate bad = new ProductionGate(false, "prod", true, true, false,
                "127.0.0.1", "", "", "jdbc:h2:mem:pasm_medical");
        bad.verify();
    }

    @Test
    void mllpNonLoopbackBindIsBlocked() {
        ProductionGate g = new ProductionGate(true, "prod", false, false, true,
                "0.0.0.0", "x".repeat(48), "tok",
                "jdbc:postgresql://127.0.0.1:5432/pasm_medical");
        assertEquals("BLOCK", byCode(g).get("mllp_bind").status());
        assertEquals(ProductionGate.Level.BLOCK, byCode(g).get("mllp_bind").level());
        assertThrows(IllegalStateException.class, g::verify);
    }

    @Test
    void mllpEnabledWithoutTokenIsReported() {
        ProductionGate g = new ProductionGate(true, "prod", false, false, true,
                "127.0.0.1", "x".repeat(48), "",
                "jdbc:postgresql://127.0.0.1:5432/pasm_medical");
        assertEquals("BLOCK", byCode(g).get("mllp_token").status());
    }

    @Test
    void dbUrlIsMaskedInReports() {
        Map<String, ProductionGate.Finding> f = byCode(prodOk());
        assertFalse(f.get("database").detail().contains("5432"),
                "数据源 URL 可能含口令，结论文本里不得回显主机/端口");
    }
}
