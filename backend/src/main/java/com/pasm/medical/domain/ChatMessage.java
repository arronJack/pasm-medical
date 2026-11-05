package com.pasm.medical.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 人工坐诊会话中的一条消息（只增不改）。 */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    public static final String PATIENT = "PATIENT";
    public static final String DOCTOR = "DOCTOR";
    public static final String SYSTEM = "SYSTEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(length = 16, nullable = false)
    private String sender;

    @Column(name = "sender_name", length = 128)
    private String senderName;

    @Column(length = 4000, nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
