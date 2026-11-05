package com.pasm.medical.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 人工坐诊会话。
 *
 * <p>状态机刻意只有三态：
 * <ul>
 *   <li>{@link #WAITING} 患者已请求转人工，在科室队列里等医生接诊；</li>
 *   <li>{@link #HUMAN} 医生已接诊，患者与该医生实时对话；</li>
 *   <li>{@link #CLOSED} 已结束（结束会落一条 {@link Encounter}）。</li>
 * </ul>
 *
 * <p>★ 为什么单独建会话、不复用认知侧的 AI 问诊会话：AI 会话的状态在 Python 侧，
 * 人工会话必须能在业务库做**队列、科室归属、接诊人、审计与就诊落库**。两者生命周期
 * 不同（AI 会话可能根本没转人工），硬塞进一张表会让"谁该看到它"变得含糊。
 */
@Entity
@Table(name = "chat_sessions")
public class ChatSession {

    public static final String WAITING = "WAITING";
    public static final String HUMAN = "HUMAN";
    public static final String CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_ref", length = 128, nullable = false)
    private String patientRef;

    /** 目标科室（来自总后台维护的科室表，必填）。 */
    @Column(length = 64, nullable = false)
    private String department;

    @Column(length = 16, nullable = false)
    private String status = WAITING;

    @Column(name = "chief_complaint", length = 512)
    private String chiefComplaint;

    @Column(length = 32)
    private String urgency;

    @Column(name = "assigned_staff", length = 64)
    private String assignedStaff;

    @Column(name = "assigned_name", length = 128)
    private String assignedName;

    @Column(name = "last_message", length = 512)
    private String lastMessage;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPatientRef() { return patientRef; }
    public void setPatientRef(String patientRef) { this.patientRef = patientRef; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getChiefComplaint() { return chiefComplaint; }
    public void setChiefComplaint(String chiefComplaint) { this.chiefComplaint = chiefComplaint; }

    public String getUrgency() { return urgency; }
    public void setUrgency(String urgency) { this.urgency = urgency; }

    public String getAssignedStaff() { return assignedStaff; }
    public void setAssignedStaff(String assignedStaff) { this.assignedStaff = assignedStaff; }

    public String getAssignedName() { return assignedName; }
    public void setAssignedName(String assignedName) { this.assignedName = assignedName; }

    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }

    public Instant getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(Instant lastMessageAt) { this.lastMessageAt = lastMessageAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}
