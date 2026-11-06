package com.pasm.medical.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投产门禁的<b>启动期自检</b>：把「忘了配就带着上线」这类不可发现的错，变成启动日志里
 * 一条明确、可核对的结论。
 *
 * <p>★ <b>为什么默认只告警、不直接拒绝启动</b>：这个仓的 dev / 嵌入式 PG 测试 /
 *   e2e 都用同一份 {@code application.yml} 默认值（空 JWT 密钥、H2 内存库、
 *   演示账号）。若默认就抛异常，所有一键验证会集体起不来 —— 那不是"更安全"，
 *   是把校验变成了噪声，最终会被人关掉。所以：
 *   <ul>
 *     <li>默认（{@code medical.production-gate.strict=false}）：打印门禁报告，WARN 级；</li>
 *     <li>投产时置 {@code MEDICAL_PRODUCTION_GATE_STRICT=true}：任一 BLOCK 项未过
 *         → <b>抛异常阻止启动</b>，不给"带病上线"留路。</li>
 *   </ul>
 *   开关的默认值同样遵守项目惯例：忘记配置时是"没拦住但看得见"，而<b>不是</b>
 *   "静默放行"或"静默拒绝"。
 *
 * <p>★ <b>哪些是代码能判的，哪些不是</b>：本类只判机器可观测的配置组合
 *   （密钥、开关、绑定地址）。器械分类界定 / NMPA 注册 / 等保测评 / 临床顾问复核 /
 *   回顾性研究 / 临床药师复核等<b>机构流程</b>，代码无法判定，也不假装能判 ——
 *   它们写在 {@code docs/COMPLIANCE-GATE.md} 的人工签核清单里。
 *   把流程问题伪装成一行绿色日志，比没有门禁更危险。
 *
 * <p>★ 这里刻意<b>不读患者数据、不连认知服务</b>：启动自检必须零副作用，
 *   否则"检查本身"会成为新的故障源。
 */
@Component
@Order(100)
public class ProductionGate implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionGate.class);

    /** 严重级别：BLOCK = 投产必须解决；WARN = 需人工确认但不自动拦。 */
    public enum Level { BLOCK, WARN }

    /** 一条门禁结论。 */
    public record Finding(String code, Level level, String status, String detail) {
    }

    private final boolean strict;
    private final boolean devProfile;
    private final boolean demoLoginEnabled;
    private final boolean learningAuto;
    private final boolean mllpEnabled;
    private final String mllpBind;
    private final String jwtSecret;
    private final String lisToken;
    private final String dbUrl;

    public ProductionGate(
            @Value("${medical.production-gate.strict:false}") boolean strict,
            @Value("${spring.profiles.active:}") String activeProfiles,
            @Value("${medical.auth.demo-login-enabled:false}") boolean demoLoginEnabled,
            @Value("${medical.learning.auto-generate-candidates.enabled:false}") boolean learningAuto,
            @Value("${medical.lis.mllp.enabled:false}") boolean mllpEnabled,
            @Value("${medical.lis.mllp.bind:127.0.0.1}") String mllpBind,
            @Value("${medical.auth.jwt-secret:}") String jwtSecret,
            @Value("${medical.lis.token:}") String lisToken,
            @Value("${spring.datasource.url:}") String dbUrl) {
        this.strict = strict;
        this.devProfile = activeProfiles != null && activeProfiles.contains("dev");
        this.demoLoginEnabled = demoLoginEnabled;
        this.learningAuto = learningAuto;
        this.mllpEnabled = mllpEnabled;
        this.mllpBind = mllpBind == null ? "" : mllpBind;
        this.jwtSecret = jwtSecret == null ? "" : jwtSecret;
        this.lisToken = lisToken == null ? "" : lisToken;
        this.dbUrl = dbUrl == null ? "" : dbUrl;
    }

    /**
     * 计算门禁结论（纯函数，无 Spring 运行时依赖 → 可直接单测）。
     * dev 档全部降级为「开发态」标注，不参与投产判定。
     */
    public List<Finding> evaluate() {
        List<Finding> out = new ArrayList<>();
        if (devProfile) {
            out.add(new Finding("profile", Level.WARN, "DEV",
                    "当前为 dev 档（H2 内存库 + 演示账号），本档结论不作为投产依据"));
        }

        out.add(check("demo_login", demoLoginEnabled,
                "演示账号已启用（写死口令 123456）",
                "演示账号已禁用 —— 投产需另接医院统一身份（OIDC/OAuth2 或签名 JWT）"));

        out.add(check("jwt_secret", jwtSecret.isBlank() || jwtSecret.length() < 32,
                "JWT 签名密钥为空或短于 32 字节（重启后旧令牌全失效 / 可预测）",
                "JWT 密钥已注入且长度足够"));

        out.add(check("learning_auto", learningAuto,
                "自学习每日自动批处理已开启（生产默认应关闭，且候选须医师确认）",
                "自动批处理关闭"));

        if (mllpEnabled) {
            out.add(check("mllp_token", lisToken.isBlank(),
                    "MLLP 直连已开启但 MEDICAL_LIS_TOKEN 未配置（通道将拒绝所有消息，属 fail closed，但需明确告知运维）",
                    "MLLP 共享密钥已配置"));
            boolean loopback = mllpBind.equals("127.0.0.1") || mllpBind.equals("::1")
                    || mllpBind.equalsIgnoreCase("localhost");
            out.add(new Finding("mllp_bind", loopback ? Level.WARN : Level.BLOCK,
                    loopback ? "OK" : "BLOCK",
                    loopback
                            ? "MLLP 仅绑回环 " + mllpBind + "；跨机需院方在网络层做白名单/专网隔离"
                            : "MLLP 绑定 " + mllpBind
                                    + "（非回环）：本项目不代替院方做网络准入，投产前须由网络管理员确认白名单"));
        }

        boolean inMemory = dbUrl.contains(":h2:mem:") || dbUrl.contains(":h2:file:");
        out.add(check("database", inMemory,
                "数据源为 H2 内存/文件库（投产必须 PostgreSQL + Flyway 版本化迁移）",
                "数据源非 H2：" + maskDbUrl(dbUrl)));

        return out;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Spring 生命周期入口只做委托：把判定逻辑放在无参 verify() 里，
        // 单测就能直接 new 出来调用，不必为了测一个布尔判断去启整个上下文。
        verify();
    }

    /** 执行门禁判定并按需阻止启动（无 Spring 运行时依赖，可直接单测）。 */
    public void verify() {
        List<Finding> findings = evaluate();
        List<Finding> blocked = findings.stream()
                .filter(f -> "BLOCK".equals(f.status()) && f.level() == Level.BLOCK)
                .toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("strict", strict);
        report.put("devProfile", devProfile);
        for (Finding f : findings) {
            report.put(f.code(), f.status());
        }

        if (blocked.isEmpty()) {
            log.info("投产门禁自检：无 BLOCK 项（strict={}, dev={}）{}",
                    strict, devProfile, devProfile ? "；dev 档结论不作为投产依据" : "");
        } else {
            for (Finding f : blocked) {
                log.warn("投产门禁 BLOCK [{}]：{}", f.code(), f.detail());
            }
            if (strict && !devProfile) {
                throw new IllegalStateException(
                        "投产门禁未通过（strict=true）：" + blocked.size() + " 项 BLOCK —— "
                                + String.join("；", blocked.stream().map(Finding::code).toList())
                                + "。详见 docs/COMPLIANCE-GATE.md；确认无误可临时关闭 strict，"
                                + "但不得在投产环境这样做。");
            }
            log.warn("投产门禁有 {} 项 BLOCK，但 strict=false / dev 档，未阻止启动。"
                    + "投产前必须逐项解决并置 MEDICAL_PRODUCTION_GATE_STRICT=true 复验。",
                    blocked.size());
        }
    }

    private Finding check(String code, boolean bad, String badDetail, String okDetail) {
        return bad
                ? new Finding(code, Level.BLOCK, "BLOCK", badDetail)
                : new Finding(code, Level.WARN, "OK", okDetail);
    }

    /** 数据源 URL 可能含口令，日志里只留协议与库名形态。 */
    private static String maskDbUrl(String url) {
        int q = url.indexOf('?');
        String head = q >= 0 ? url.substring(0, q) : url;
        int at = head.indexOf("://");
        return at >= 0 ? head.substring(0, at + 3) + "…" : "…";
    }
}
