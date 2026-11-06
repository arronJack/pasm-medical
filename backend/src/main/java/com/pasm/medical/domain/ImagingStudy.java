package com.pasm.medical.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 影像检查归档记录。
 *
 * <p>★ <b>刻意没有任何结论字段</b>：本系统对影像只做「归档 + 转交」，
 * 不做像素解析、不做 DICOM 标签解读、不出影像所见/印象/结论。
 * 自动读片属高风险诊断行为，需三类医疗器械注册证（见
 * {@code docs/COMPLIANCE-GATE.md} §3）—— 在实体上留一个
 * {@code impression} 字段，就等于给将来「顺手接个模型」留了旁路。
 *
 * <p>状态机两态：{@link #ARCHIVED} 已归档待转交 → {@link #HANDED_OFF} 已转交科室。
 * 不设「已阅片/已出报告」这类状态，因为那不是本系统的职责。
 */
@Entity
@Table(name = "imaging_studies")
public class ImagingStudy {

    public static final String ARCHIVED = "ARCHIVED";
    public static final String HANDED_OFF = "HANDED_OFF";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_ref", length = 128, nullable = false)
    private String patientRef;

    @Column(length = 32, nullable = false)
    private String modality;

    @Column(name = "body_part", length = 128, nullable = false)
    private String bodyPart;

    @Column(name = "study_date", length = 32)
    private String studyDate;

    @Column(name = "accession_no", length = 128)
    private String accessionNo;

    @Column(length = 64, nullable = false)
    private String department = "";

    @Column(name = "file_name", length = 256, nullable = false)
    private String fileName;

    @Column(name = "content_type", length = 128, nullable = false)
    private String contentType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(length = 64, nullable = false)
    private String sha256;

    @Column(name = "storage_key", length = 256, nullable = false)
    private String storageKey;

    @Column(length = 24, nullable = false)
    private String status = ARCHIVED;

    @Column(name = "handoff_department", length = 64)
    private String handoffDepartment;

    @Column(name = "handoff_note", length = 1024)
    private String handoffNote;

    @Column(name = "handoff_by", length = 64)
    private String handoffBy;

    @Column(name = "handoff_at")
    private Instant handoffAt;

    @Column(name = "uploaded_by", length = 64, nullable = false)
    private String uploadedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPatientRef() { return patientRef; }
    public void setPatientRef(String patientRef) { this.patientRef = patientRef; }

    public String getModality() { return modality; }
    public void setModality(String modality) { this.modality = modality; }

    public String getBodyPart() { return bodyPart; }
    public void setBodyPart(String bodyPart) { this.bodyPart = bodyPart; }

    public String getStudyDate() { return studyDate; }
    public void setStudyDate(String studyDate) { this.studyDate = studyDate; }

    public String getAccessionNo() { return accessionNo; }
    public void setAccessionNo(String accessionNo) { this.accessionNo = accessionNo; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getByteSize() { return byteSize; }
    public void setByteSize(long byteSize) { this.byteSize = byteSize; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getHandoffDepartment() { return handoffDepartment; }
    public void setHandoffDepartment(String handoffDepartment) { this.handoffDepartment = handoffDepartment; }

    public String getHandoffNote() { return handoffNote; }
    public void setHandoffNote(String handoffNote) { this.handoffNote = handoffNote; }

    public String getHandoffBy() { return handoffBy; }
    public void setHandoffBy(String handoffBy) { this.handoffBy = handoffBy; }

    public Instant getHandoffAt() { return handoffAt; }
    public void setHandoffAt(Instant handoffAt) { this.handoffAt = handoffAt; }

    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
