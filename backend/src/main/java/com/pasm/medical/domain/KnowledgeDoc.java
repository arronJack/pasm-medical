package com.pasm.medical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 机构资料（诊疗指南 / 规则表 / 术语字典 / 科室手册）。
 *
 * <p>★ <b>为什么权威库放这里，而不是直接用框架的 `knowledge_base` 插件</b>：
 * 那个插件只有 {@code ingest}（追加），**没有删除和编辑**；而资料必须能"下架/改版" ——
 * 「下架的资料还被当成依据引用」在医疗场景是事故，不能靠"再追加一版"绕过（旧版永远是依据）。
 * 所以：**业务库（本表）是权威**，带状态 / 版本 / 复核人 / 审计；
 * 认知侧只留一份"当前生效资料"的检索副本，由
 * {@link com.pasm.medical.service.KnowledgeDocService#syncToCognition()} 全量同步。
 */
@Entity
@Table(name = "knowledge_docs")
public class KnowledgeDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 稳定业务键（认知侧与前端都用它定位），创建后不再变。 */
    @Column(length = 64, nullable = false, unique = true)
    private String docKey;

    @Column(length = 200, nullable = false)
    private String title;

    /**
     * 正文。它**不参与**"能不能当依据"的判定 —— 相关性闸门只看标题与标签，
     * 因为正文里的偶然词重合正是"答非所问"的来源（见 pasm_skills.cognition.relevance）。
     */
    @Column(length = 8000)
    private String content;

    /**
     * 标签，逗号分隔。
     *
     * <p>★ 这是**相关性闸门的命中面**，不是装饰：问题词元必须落在标题或标签上才算依据。
     * 所以标签要人工有意维护 —— 想让某类提问能命中这份资料，就得把那个词写进标签。
     * （用逗号分隔的字符串而不是关联表：一份资料的标签是个位数、按整条读写，
     * 拆表只会多一次 join 与一处不一致。）
     */
    @Column(length = 400)
    private String tags;

    /** 归属科室（空 = 全院通用）。预留：将来按科室过滤资料。 */
    @Column(length = 64)
    private String department;

    /** {@code active} / {@code inactive} —— **只有 active 的会同步到认知侧**。 */
    @Column(length = 16, nullable = false)
    private String status = "active";

    /** 版本号（人工维护，如 `2026.09`）。 */
    @Column(length = 32)
    private String version;

    /** 复核人。为空即表示**尚未复核**，界面要如实显示（规则表投产前必须逐条复核）。 */
    @Column(length = 64)
    private String reviewer;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(length = 64)
    private String updatedBy;

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

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getReviewer() { return reviewer; }
    public void setReviewer(String reviewer) { this.reviewer = reviewer; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
