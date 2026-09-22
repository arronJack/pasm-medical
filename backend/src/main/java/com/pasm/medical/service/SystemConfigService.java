package com.pasm.medical.service;

import com.pasm.medical.domain.SystemConfig;
import com.pasm.medical.repository.SystemConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 机构级对接设置的读写与**校验**。
 *
 * <p>★ 为什么校验必须在这一层，而不是只在前端下拉框里限制：
 * 前端可以绕过（直接 POST）。而这些值决定了"检验单能不能识别""患者数据会不会出网"——
 * 一个被改成 {@code ocr=none} 的机构会发现检验单功能静默消失，
 * 一个被改成 {@code llm=openai} 的机构会把患者数据送出去。所以枚举值必须在服务端**白名单校验**。
 *
 * <p>★ 为什么不存密钥：这张表会被后台页面整体读出来。任何 API key / LIS 口令
 * 一律走环境变量或密钥管理，不进这里。{@link #save} 也因此只接受白名单内的键，
 * 不会把调用方多传的字段顺手存进去。
 */
@Service
public class SystemConfigService {

    /** 允许的配置项 → 允许的取值（空集表示自由文本，见 {@link #freeText}）。 */
    private static final Map<String, Set<String>> ENUMS = Map.of(
            "ocr", Set.of("none", "vendor", "generic"),
            "lis", Set.of("off", "hl7", "fhir"),
            "llm", Set.of("null", "ollama", "openai"));

    /** 键值表里只允许出现这些键；调用方多传的一律忽略（防止把表当垃圾桶用）。 */
    private static final List<String> KEYS = List.of("ocr", "lis", "llm", "model", "baseUrl");

    private static final Map<String, String> DEFAULTS = Map.of(
            "ocr", "none",
            "lis", "off",
            "llm", "null",
            "model", "",
            "baseUrl", "");

    private final SystemConfigRepository repo;

    public SystemConfigService(SystemConfigRepository repo) {
        this.repo = repo;
    }

    /** 一组对接设置（不可变）。 */
    public record Settings(String ocr, String lis, String llm, String model, String baseUrl) {

        public Map<String, String> asMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("ocr", ocr);
            m.put("lis", lis);
            m.put("llm", llm);
            m.put("model", model);
            m.put("baseUrl", baseUrl);
            return m;
        }
    }

    /** 期望的 LLM 档位在业务侧的取值 → 认知服务进程的 provider 取值。 */
    private static String toCognitionProvider(String llm) {
        return "null".equals(llm) ? "null" : llm;
    }

    /** 读取当前设置；缺失项回落到默认值（新建的库里一行都没有）。 */
    public Settings read() {
        Map<String, String> cur = new LinkedHashMap<>(DEFAULTS);
        for (SystemConfig c : repo.findAll()) {
            if (KEYS.contains(c.getKey()) && c.getValue() != null) {
                cur.put(c.getKey(), c.getValue());
            }
        }
        return new Settings(cur.get("ocr"), cur.get("lis"), cur.get("llm"),
                cur.get("model"), cur.get("baseUrl"));
    }

    /** 最近一次修改时间（没有则 null）。 */
    public Instant lastUpdatedAt() {
        Instant latest = null;
        for (SystemConfig c : repo.findAll()) {
            if (c.getUpdatedAt() != null && (latest == null || c.getUpdatedAt().isAfter(latest))) {
                latest = c.getUpdatedAt();
            }
        }
        return latest;
    }

    /** 最后修改人（没有则空串）。 */
    public String lastUpdatedBy() {
        String who = "";
        Instant latest = null;
        for (SystemConfig c : repo.findAll()) {
            if (c.getUpdatedAt() != null && (latest == null || c.getUpdatedAt().isAfter(latest))) {
                latest = c.getUpdatedAt();
                who = c.getUpdatedBy() == null ? "" : c.getUpdatedBy();
            }
        }
        return who;
    }

    /**
     * 保存设置（幂等 upsert）。
     *
     * @throws IllegalArgumentException 取值不在白名单内 —— 由调用方翻译成 400，
     *                                  **不要**吞掉：静默改成一个"安全"的默认值会让
     *                                  管理员以为改成功了。
     */
    @Transactional
    public Settings save(Settings in, String actor) {
        Map<String, String> vals = new LinkedHashMap<>();
        vals.put("ocr", norm(in.ocr(), "ocr"));
        vals.put("lis", norm(in.lis(), "lis"));
        vals.put("llm", norm(in.llm(), "llm"));
        vals.put("model", freeText(in.model(), "model", 64, "[A-Za-z0-9 ._:/+\\-]"));
        vals.put("baseUrl", url(in.baseUrl()));

        Instant now = Instant.now();
        for (Map.Entry<String, String> e : vals.entrySet()) {
            SystemConfig c = repo.findById(e.getKey()).orElseGet(() -> {
                SystemConfig n = new SystemConfig();
                n.setKey(e.getKey());
                return n;
            });
            c.setValue(e.getValue());
            c.setUpdatedAt(now);
            c.setUpdatedBy(actor == null ? "" : actor);
            repo.save(c);
        }
        return new Settings(vals.get("ocr"), vals.get("lis"), vals.get("llm"),
                vals.get("model"), vals.get("baseUrl"));
    }

    /** 期望 vs 实际是否漂移。实际值取不到（认知服务不可达）时返回 null = 未知，不猜。 */
    public Boolean drift(Settings desired, Map<String, Object> applied) {
        if (applied == null) {
            return null;
        }
        String ap = String.valueOf(applied.getOrDefault("provider", ""));
        String am = String.valueOf(applied.getOrDefault("model", ""));
        return !toCognitionProvider(desired.llm()).equals(ap) || !desired.model().equals(am);
    }

    // ------------------------------------------------------------ 校验

    private static String norm(String v, String key) {
        String s = v == null || v.isBlank() ? DEFAULTS.get(key) : v.trim();
        Set<String> allowed = ENUMS.get(key);
        if (allowed != null && !allowed.contains(s)) {
            throw new IllegalArgumentException(
                    key + " 取值非法：" + s + "（允许：" + String.join("/", allowed) + "）");
        }
        return s;
    }

    /** 自由文本：限长 + 限字符集。超限直接拒绝，**不截断** —— 截断过的模型名会静默换成别的模型。 */
    private static String freeText(String v, String key, int max, String charset) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.length() > max) {
            throw new IllegalArgumentException(key + " 超长（≤" + max + " 字符）");
        }
        if (!s.matches(charset + "+")) {
            throw new IllegalArgumentException(key + " 含非法字符：" + s);
        }
        return s;
    }

    private static String url(String v) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.length() > 200) {
            throw new IllegalArgumentException("baseUrl 超长（≤200 字符）");
        }
        if (!(s.startsWith("http://") || s.startsWith("https://"))) {
            throw new IllegalArgumentException("baseUrl 必须以 http:// 或 https:// 开头：" + s);
        }
        if (s.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("baseUrl 不能含空白字符：" + s);
        }
        return s;
    }
}
