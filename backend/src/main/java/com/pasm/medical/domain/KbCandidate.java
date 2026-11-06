package com.pasm.medical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 自学习候选知识（PLAN §8 候选池）。
 *
 * <p>每日批处理从近期就诊抽取「可沉淀为资料的经验候选」写入本表，<b>不直接生效</b>。
 * 审核台（human-in-the-loop）审核通过后，才 upsert 进 {@link KnowledgeDoc} 并 bump 知识池版本。
 *
 * <p>★ 铁律：候选永远只有「过审才生效」一条出路。本表是「建议池」，
 * 不是「知识库」—— 任何写进来的内容都还没经过临床复核，绝不能当成回答依据。
 */
@Entity
@Table(name = "kb_candidates")
public class KbCandidate {

    /** 待审。 */
    public static final String PENDING = "PENDING";
    /** 已采纳入库（已 upsert 进 knowledge_docs）。 */
    public static final String APPROVED = "APPROVED";
    /** 已驳回（不入库）。 */
    public static final String REJECTED = "REJECTED";
    /** 与现库冲突（同标题、结论相反），挂起待人工裁定。 */
    public static final String CONFLICT = "CONFLICT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 目标资料键：非空 = 更新既有资料；空 = 新增。 */
    @Column(length = 64)
    private String docKey;

    @Column(length = 200, nullable = false)
    private String title;

    @Column(length = 8000)
    private String content;

    @Column(length = 400)
    private String tags;

    /** 建议归属科室（空 = 全院通用）；审核时作为授权范围（科室管理员只能审本科室）。 */
    @Column(length = 64)
    private String department;

    @Column(length = 32, nullable = false)
    private String source = "daily-batch";

    @Column(length = 16, nullable = false)
    private String status = PENDING;

    /** 抽取完整度启发式评分（0~1）—— 仅用于辅助排序，<b>不是</b>临床确定性。 */
    @Column(nullable = false)
    private double confidence = 0.0;

    /** 冲突时指向现库资料键。 */
    @Column(length = 64)
    private String conflictWith;

    /** 生成依据（来源就诊 ref 列表 / 命中规则），供审核者判断。 */
    @Column(length = 2000)
    private String evidence;

    /** 归一化(title+content) 的 SHA-256，用于去重（同一经验不重复入池）。 */
    @Column(length = 64)
    private String fingerprint;

    /**
     * ★ 医师确认判据（Flyway V5）：候选是否源自「医师确认后的判断/处置」。
     *   false = 预问诊采集 / 演示数据 / 历史行，**approve() 必须硬拒** ——
     *   内容护栏只是给人看的文字，能被一次点击绕过；这里是结构性闸门。
     */
    @Column(nullable = false)
    private boolean physicianConfirmed = false;

    /** 审核通过时打上的「知识池版本号」，回滚据此定位。 */
    @Column(length = 32)
    private String poolVersion;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column
    private Instant reviewedAt;

    @Column(length = 64)
    private String reviewedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getDocKey() { return docKey; }
    public void setDocKey(String docKey) { this.docKey = docKey; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getConflictWith() { return conflictWith; }
    public void setConflictWith(String conflictWith) { this.conflictWith = conflictWith; }

    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public String getPoolVersion() { return poolVersion; }
    public void setPoolVersion(String poolVersion) { this.poolVersion = poolVersion; }

    public boolean isPhysicianConfirmed() { return physicianConfirmed; }
    public void setPhysicianConfirmed(boolean v) { this.physicianConfirmed = v; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
}
