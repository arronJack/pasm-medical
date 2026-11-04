package com.pasm.medical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一条检验结果（项目级）。
 *
 * <p>对应 FHIR R4 的 {@code Observation}（{@code category=laboratory}）。
 * 一次 LIS 消息（ORU^R01）会落成多行 —— 每个 OBX 一行。
 *
 * <p>★ 这里只做"落库"，<b>不写认知记忆</b>。<br>
 * 原因：LIS 结果虽来自仪器、比 OCR 可信，但"进记忆"意味着它会被当成长期事实
 * 参与后续问答；而结果更正（OBX-11=C）、复检、单位差异都可能让旧值失效。
 * 记忆写入仍走医师确认那条路（{@code pasm_medical.lab} 的 confirmed 闸门）。
 *
 * <p>★ 同时保留 {@code valueText} 与原样异常标记：检验值不全是数字
 * （"阴性""未见生长"），把它们强转成数值会静默丢掉信息。
 */
@Entity
@Table(name = "lab_results")
public class LabResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_ref", length = 64, nullable = false)
    private String patientRef;

    /** MSH-10，消息控制号：与 observationSeq 一起构成幂等键。 */
    @Column(name = "message_control_id", length = 64, nullable = false)
    private String messageControlId;

    /** 消息内第几个 OBX（1 起）。 */
    @Column(name = "observation_seq", nullable = false)
    private Integer observationSeq;

    @Column(name = "panel_code", length = 64)
    private String panelCode;

    @Column(name = "panel_name", length = 128)
    private String panelName;

    @Column(name = "analyte_code", length = 64, nullable = false)
    private String analyteCode;

    @Column(name = "analyte_name", length = 128)
    private String analyteName;

    @Column(name = "value_text", length = 128)
    private String valueText;

    /** 数值型结果；文本型结果（"阴性"）为 null。 */
    @Column(name = "value_num", precision = 18, scale = 6)
    private BigDecimal valueNum;

    @Column(length = 32)
    private String unit;

    @Column(name = "ref_low", precision = 18, scale = 6)
    private BigDecimal refLow;

    @Column(name = "ref_high", precision = 18, scale = 6)
    private BigDecimal refHigh;

    @Column(name = "ref_range_text", length = 64)
    private String refRangeText;

    /** 归一化标记：high / low / normal / abnormal / unknown。 */
    @Column(name = "abnormal_flag", length = 16)
    private String abnormalFlag;

    /** LIS 原始标记（H/L/HH/LL/N/A）。 */
    @Column(name = "abnormal_flag_raw", length = 8)
    private String abnormalFlagRaw;

    /** OBX-11：F 最终 / P 初步 / C 更正。 */
    @Column(name = "result_status", length = 8)
    private String resultStatus;

    @Column(name = "observed_at")
    private Instant observedAt;

    @Column(length = 16, nullable = false)
    private String source = "hl7";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }

    public String getPatientRef() { return patientRef; }
    public void setPatientRef(String patientRef) { this.patientRef = patientRef; }

    public String getMessageControlId() { return messageControlId; }
    public void setMessageControlId(String messageControlId) { this.messageControlId = messageControlId; }

    public Integer getObservationSeq() { return observationSeq; }
    public void setObservationSeq(Integer observationSeq) { this.observationSeq = observationSeq; }

    public String getPanelCode() { return panelCode; }
    public void setPanelCode(String panelCode) { this.panelCode = panelCode; }

    public String getPanelName() { return panelName; }
    public void setPanelName(String panelName) { this.panelName = panelName; }

    public String getAnalyteCode() { return analyteCode; }
    public void setAnalyteCode(String analyteCode) { this.analyteCode = analyteCode; }

    public String getAnalyteName() { return analyteName; }
    public void setAnalyteName(String analyteName) { this.analyteName = analyteName; }

    public String getValueText() { return valueText; }
    public void setValueText(String valueText) { this.valueText = valueText; }

    public BigDecimal getValueNum() { return valueNum; }
    public void setValueNum(BigDecimal valueNum) { this.valueNum = valueNum; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public BigDecimal getRefLow() { return refLow; }
    public void setRefLow(BigDecimal refLow) { this.refLow = refLow; }

    public BigDecimal getRefHigh() { return refHigh; }
    public void setRefHigh(BigDecimal refHigh) { this.refHigh = refHigh; }

    public String getRefRangeText() { return refRangeText; }
    public void setRefRangeText(String refRangeText) { this.refRangeText = refRangeText; }

    public String getAbnormalFlag() { return abnormalFlag; }
    public void setAbnormalFlag(String abnormalFlag) { this.abnormalFlag = abnormalFlag; }

    public String getAbnormalFlagRaw() { return abnormalFlagRaw; }
    public void setAbnormalFlagRaw(String abnormalFlagRaw) { this.abnormalFlagRaw = abnormalFlagRaw; }

    public String getResultStatus() { return resultStatus; }
    public void setResultStatus(String resultStatus) { this.resultStatus = resultStatus; }

    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
