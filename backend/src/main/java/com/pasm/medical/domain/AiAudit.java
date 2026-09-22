package com.pasm.medical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * AI 审计日志 —— **只增不改**。
 *
 * <p>PLAN.md 红线圈 #2：每一次 AI 输出必须可追溯
 * （输入快照 + 召回条目 + 模型版本 + 操作者 + 时间）。
 * 这个实体是落地：它只提供写入入口，服务层**不暴露** update / delete。
 *
 * <p>生产建议：落到<b>独立审计库</b>且只授予 INSERT（WORM），与业务库物理隔离；
 * 当前在开发档用同一 H2 实例，靠"服务层不提供改删方法"维持不可变语义。
 */
@Entity
@Table(name = "ai_audit")
public class AiAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    /** 患者引用（登录类事件可空）。 */
    @Column(length = 64)
    private String patientRef;

    /** 操作者（登录用户名）。 */
    @Column(length = 64)
    private String actor;

    /** 动作：ask / consult-start / consult-finish / consult-redflag /
     *        lab-parse / lab-confirm / critical-fact / feedback / login。 */
    @Column(length = 32, nullable = false)
    private String action;

    /** 召回依据条数（可追溯）。 */
    @Column
    private Integer evidenceCount;

    /** 模型名与版本（如 cognition-recall / rule-engine）。 */
    @Column(length = 64)
    private String modelVersion;

    /** 输入快照（可审计）：本次请求的关键输入。 */
    @Column(length = 4000)
    private String inputSnapshot;

    /** 是否拒答（无依据）。 */
    @Column
    private Boolean refused;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public String getPatientRef() { return patientRef; }
    public void setPatientRef(String patientRef) { this.patientRef = patientRef; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public Integer getEvidenceCount() { return evidenceCount; }
    public void setEvidenceCount(Integer evidenceCount) { this.evidenceCount = evidenceCount; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    public String getInputSnapshot() { return inputSnapshot; }
    public void setInputSnapshot(String inputSnapshot) { this.inputSnapshot = inputSnapshot; }

    public Boolean getRefused() { return refused; }
    public void setRefused(Boolean refused) { this.refused = refused; }
}
