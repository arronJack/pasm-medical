package com.pasm.medical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 机构级对接设置（键值对）—— 后台「对接设置」页的真实落点。
 *
 * <p>为什么用键值表而不是固定列的单行表：
 * <ul>
 *   <li>对接项会随机构增加（不同院区的 OCR / LIS / 模型都不一样），
 *       加一类设置就改一次表结构，在医疗系统里要走变更单，成本过高；</li>
 *   <li>键值表可以用 {@code config_key} 做主键做**幂等 upsert**，
 *       不需要先查"有没有那一行"。</li>
 * </ul>
 *
 * <p>★ 这里存的只是**机构期望值**（"我们想用 ollama + qwen2.5:7b"），
 * 不等于「认知服务进程实际在用什么」。真正跑模型的在 Python 侧，由环境变量
 * （{@code PASM_MEDICAL_LLM*}）决定。两者可能不一致，所以
 * {@code GET /api/admin/config} 会把认知服务的**实际值**一并取回来做对账
 * （见 {@code AdminController#config}）。"配置了但其实没生效"比"没有配置项"更危险。
 *
 * <p>★ 只放**非机密**的对接开关。任何密钥（云 API key、LIS 口令）都必须走
 * 环境变量 / 密钥管理服务，**不得**进这张会被后台页面读出来的表。
 */
@Entity
@Table(name = "system_config")
public class SystemConfig {

    /** 配置项名，如 {@code ocr} / {@code lis} / {@code llm} / {@code model} / {@code baseUrl}。 */
    @Id
    @Column(name = "config_key", length = 64, nullable = false)
    private String key;

    /**
     * 配置值。
     * <p>列名显式写成 {@code config_value}：{@code value} 在若干数据库里是保留字，
     * 用默认命名策略生成的 {@code value} 列在迁移到别的库时会直接建表失败。
     */
    @Column(name = "config_value", length = 500)
    private String value;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    /** 最后修改人（令牌里的 username）。医疗系统里"谁改的"必须可查。 */
    @Column(length = 64)
    private String updatedBy;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
