package com.pasm.medical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 一次就诊 / 一次预问诊（FHIR Encounter 的最小投影）。
 *
 * <p>认知层把"一次就诊"当成一个 episode 写记忆；这里同时落业务库，
 * 使后台「患者情况 / 时间轴」有真实数据源，而不是前端硬编码。
 */
@Entity
@Table(name = "encounters")
public class Encounter {

    /** AI 预问诊结束：按合规不含医师结论，**不可**作为自学习依据。 */
    public static final String SRC_AI_PRECONSULT = "AI_PRECONSULT";
    /** 人工坐诊结束但医师未填判断/处置：仍不可作为依据。 */
    public static final String SRC_HUMAN_NO_CONCLUSION = "HUMAN_CONSULT_NO_CONCLUSION";
    /** ★ 人工坐诊结束且医师填写了判断或处置 —— 唯一合格的医师确认来源。 */
    public static final String SRC_PHYSICIAN_CONFIRMED = "PHYSICIAN_CONFIRMED";
    /** 演示种子数据：绝不作为依据（演示数据把判断/处置手工填齐了，极易误判）。 */
    public static final String SRC_DEMO = "DEMO";
    /** V5 之前的历史行：保守起见不可采纳。 */
    public static final String SRC_LEGACY = "LEGACY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false)
    private String patientRef;

    @Column
    private Instant occurredAt = Instant.now();

    @Column(length = 64)
    private String department;

    @Column(length = 200)
    private String chiefComplaint;

    @Column(length = 400)
    private String assessment;

    @Column(length = 400)
    private String plan;

    /** emergency / urgent / routine —— 来自预问诊分诊。 */
    @Column(length = 16)
    private String triageUrgency;

    /** 预问诊摘要正文（去标识化后的关键事实）。 */
    @Column(length = 4000)
    private String summaryText;

    /**
     * 就诊来源（Flyway V5）。★ 判据是**显式来源**，不是「assessment 非空」：
     * 演示种子数据把判断/处置填齐了，只看字段会把演示数据误判成医师确认。
     */
    @Column(length = 32, nullable = false)
    private String source = SRC_LEGACY;

    /** 本条就诊是否具备「医师确认后的判断/处置」。 */
    public boolean isPhysicianConfirmed() {
        return SRC_PHYSICIAN_CONFIRMED.equals(source)
                && ((assessment != null && !assessment.isBlank())
                    || (plan != null && !plan.isBlank()));
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPatientRef() { return patientRef; }
    public void setPatientRef(String patientRef) { this.patientRef = patientRef; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getChiefComplaint() { return chiefComplaint; }
    public void setChiefComplaint(String chiefComplaint) { this.chiefComplaint = chiefComplaint; }

    public String getAssessment() { return assessment; }
    public void setAssessment(String assessment) { this.assessment = assessment; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public String getTriageUrgency() { return triageUrgency; }
    public void setTriageUrgency(String triageUrgency) { this.triageUrgency = triageUrgency; }

    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
