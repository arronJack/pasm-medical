package com.pasm.medical.service;

import com.pasm.medical.config.Identity;
import com.pasm.medical.config.IdentityContext;
import com.pasm.medical.domain.ChatMessage;
import com.pasm.medical.domain.ChatSession;
import com.pasm.medical.domain.Encounter;
import com.pasm.medical.repository.ChatMessageRepository;
import com.pasm.medical.repository.ChatSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工坐诊：转人工 → 排队 → 接诊 → 双向消息 → 结束落就诊。
 *
 * <p>★ 权限判定集中在这里，而不是散落在 Controller：会话的可见性同时取决于
 * 「患者归属」与「科室归属」两个维度，分散写必然漏一处 —— 而漏判不会报错，
 * 只会静默把别科患者的对话给出去。
 */
@Service
public class ChatService {

    /** 会话不存在 / 当前身份无权访问（Controller 统一翻成 403/404）。 */
    public static class AccessException extends RuntimeException {
        public AccessException(String message) { super(message); }
    }

    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final DepartmentService departments;
    private final EncounterService encounters;
    private final AuditService audit;

    public ChatService(ChatSessionRepository sessions, ChatMessageRepository messages,
                       DepartmentService departments, EncounterService encounters,
                       AuditService audit) {
        this.sessions = sessions;
        this.messages = messages;
        this.departments = departments;
        this.encounters = encounters;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 患者侧

    /**
     * 请求转人工。**幂等**：已有进行中会话时直接返回，不新建
     * （患者连点几次「转人工」不应该刷出一队重复会话）。
     */
    @Transactional
    public ChatSession handoff(String patientRef, String department, String chiefComplaint,
                               String urgency, String patientName, String transcript) {
        String dept = department == null ? "" : department.trim();
        if (dept.isEmpty()) {
            throw new IllegalArgumentException("请选择要转接的科室");
        }
        if (!departments.existsActive(dept)) {
            throw new IllegalArgumentException("科室不存在或已停用：" + dept);
        }
        ChatSession existing = sessions
                .findFirstByPatientRefAndStatusInOrderByCreatedAtDesc(
                        patientRef, List.of(ChatSession.WAITING, ChatSession.HUMAN))
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        ChatSession s = new ChatSession();
        s.setPatientRef(patientRef);
        s.setDepartment(dept);
        s.setStatus(ChatSession.WAITING);
        s.setChiefComplaint(trim(chiefComplaint, 512));
        s.setUrgency(urgency == null || urgency.isBlank() ? "routine" : urgency);
        Instant now = Instant.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        sessions.save(s);

        ChatMessage sys = new ChatMessage();
        sys.setSessionId(s.getId());
        sys.setSender(ChatMessage.SYSTEM);
        sys.setSenderName("系统");
        sys.setBody("已为您转接 " + dept + " 医生，请稍候。接诊后可直接在此对话。");
        sys.setCreatedAt(now);
        messages.save(sys);
        touch(s, sys);

        // ★ 把 AI 预问诊的关键问答一起交给医生 —— 否则医生要重新问一遍，
        //   既浪费患者时间，也可能漏掉已经说过的红旗线索。只带摘要文本，不传原始会话 ID。
        if (transcript != null && !transcript.isBlank()) {
            ChatMessage t = new ChatMessage();
            t.setSessionId(s.getId());
            t.setSender(ChatMessage.SYSTEM);
            t.setSenderName("预问诊记录");
            t.setBody("AI 预问诊记录：" + trim(transcript, 3000));
            t.setCreatedAt(Instant.now());
            messages.save(t);
            touch(s, t);
        }

        audit.recordRead(patientName == null ? patientRef : patientName, patientRef,
                "chat-handoff", "session=" + s.getId() + ";dept=" + dept);
        return s;
    }

    /** 患者当前进行中的会话（没有则 null），用于刷新页面后恢复现场。 */
    public ChatSession myOpenSession(String patientRef) {
        return sessions.findFirstByPatientRefAndStatusInOrderByCreatedAtDesc(
                patientRef, List.of(ChatSession.WAITING, ChatSession.HUMAN)).orElse(null);
    }

    // ---------------------------------------------------------------- 医护侧

    /** 科室队列：WAITING + HUMAN（超管看全院）。 */
    public List<ChatSession> queue() {
        Identity id = IdentityContext.current();
        if (!id.clinicalSide()) {
            throw new AccessException("仅医护侧可查看坐诊队列");
        }
        List<String> open = List.of(ChatSession.WAITING, ChatSession.HUMAN);
        if (id.hospitalWide()) {
            return sessions.findByStatusInOrderByUpdatedAtDesc(open);
        }
        String dept = id.department();
        if (dept == null || dept.isBlank()) {
            return List.of();
        }
        return sessions.findByDepartmentAndStatusInOrderByUpdatedAtDesc(dept, open);
    }

    /** 接诊：WAITING → HUMAN，记录接诊人。重复接诊同一个人是幂等的。 */
    @Transactional
    public ChatSession take(Long id) {
        ChatSession s = require(id);
        Identity id0 = IdentityContext.current();
        requireClinicalDepartment(s, id0);
        if (ChatSession.CLOSED.equals(s.getStatus())) {
            throw new AccessException("会话已结束");
        }
        if (ChatSession.HUMAN.equals(s.getStatus())) {
            if (id0.username().equals(s.getAssignedStaff())) {
                return s;
            }
            throw new AccessException("该会话已被 " + s.getAssignedName() + " 接诊");
        }
        s.setStatus(ChatSession.HUMAN);
        s.setAssignedStaff(id0.username());
        s.setAssignedName(id0.displayName());
        s.setUpdatedAt(Instant.now());
        sessions.save(s);

        ChatMessage sys = new ChatMessage();
        sys.setSessionId(s.getId());
        sys.setSender(ChatMessage.SYSTEM);
        sys.setSenderName("系统");
        sys.setBody(id0.displayName() + " 医生已接诊，请描述您的情况。");
        sys.setCreatedAt(Instant.now());
        messages.save(sys);
        touch(s, sys);

        audit.recordRead(id0.username(), s.getPatientRef(), "chat-take",
                "session=" + s.getId() + ";dept=" + s.getDepartment());
        return s;
    }

    // ---------------------------------------------------------------- 消息

    public List<ChatMessage> messages(Long id, Long afterId) {
        ChatSession s = require(id);
        requireReadable(s);
        return messages.findBySessionIdAndIdGreaterThanOrderByIdAsc(
                id, afterId == null ? 0L : Math.max(0L, afterId));
    }

    /** 供 SSE 端点先做鉴权（不通过就不建立长连接）。 */
    public void requireReadable(Long id) {
        requireReadable(require(id));
    }

    /** 发消息：患者本人，或该会话的接诊医生（超管可代发用于排障）。 */
    @Transactional
    public ChatMessage post(Long id, String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        ChatSession s = require(id);
        if (ChatSession.CLOSED.equals(s.getStatus())) {
            throw new AccessException("会话已结束");
        }
        Identity me = IdentityContext.current();
        String sender;
        String senderName;
        if (me.isPatient()) {
            if (!me.patientRef().equals(s.getPatientRef())) {
                throw new AccessException("无权访问该会话");
            }
            sender = ChatMessage.PATIENT;
            senderName = me.displayName();
            // 患者发言时若还在排队，自动把消息留给接诊医生看（不改变状态）
        } else if (me.clinicalSide()) {
            requireClinicalDepartment(s, me);
            if (!me.username().equals(s.getAssignedStaff()) && !me.hospitalWide()) {
                throw new AccessException("请先接诊再回复");
            }
            sender = ChatMessage.DOCTOR;
            senderName = me.displayName();
        } else {
            throw new AccessException("无权访问该会话");
        }

        ChatMessage m = new ChatMessage();
        m.setSessionId(id);
        m.setSender(sender);
        m.setSenderName(senderName);
        m.setBody(trim(body, 4000));
        m.setCreatedAt(Instant.now());
        messages.save(m);
        touch(s, m);
        return m;
    }

    /**
     * 结束会话并**落一条就诊**（这是坐诊真正的产出）。
     *
     * <p>医师可填「判断 / 处置」—— 这正是 P0 缺口里说的「医师确认后的结论」：
     * 有它，自学习候选才有资格作为机构回答依据；没有就留空，绝不自动编造。
     */
    @Transactional
    public Map<String, Object> close(Long id, String assessment, String plan) {
        ChatSession s = require(id);
        Identity me = IdentityContext.current();
        if (!me.isPatient()) {
            requireClinicalDepartment(s, me);
            if (!me.hospitalWide() && !me.username().equals(s.getAssignedStaff())) {
                throw new AccessException("只有接诊医生可以结束会话");
            }
        }

        Encounter saved = null;
        if (!ChatSession.CLOSED.equals(s.getStatus())) {
            List<ChatMessage> all = messages.findAllBySessionIdOrderByIdAsc(id);
            Encounter e = new Encounter();
            e.setPatientRef(s.getPatientRef());
            e.setOccurredAt(Instant.now());
            e.setDepartment(s.getDepartment());
            e.setChiefComplaint(s.getChiefComplaint() == null || s.getChiefComplaint().isBlank()
                    ? "人工咨询" : s.getChiefComplaint());
            e.setAssessment(blankToNull(assessment));
            e.setPlan(blankToNull(plan));
            e.setTriageUrgency(s.getUrgency() == null ? "routine" : s.getUrgency());
            e.setSummaryText(summaryOf(s, all, assessment, plan));
            saved = encounters.save(e);

            s.setStatus(ChatSession.CLOSED);
            s.setClosedAt(Instant.now());
            s.setUpdatedAt(Instant.now());
            sessions.save(s);
            audit.recordRead(me.username(), s.getPatientRef(), "chat-close",
                    "session=" + id + ";encounter=" + saved.getId());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", s);
        out.put("encounterId", saved == null ? null : saved.getId());
        out.put("alreadyClosed", saved == null);
        return out;
    }

    // ---------------------------------------------------------------- 内部

    private ChatSession require(Long id) {
        return sessions.findById(id).orElseThrow(() -> new AccessException("会话不存在：" + id));
    }

    /** 读权限：患者本人；临床侧限本科室（超管全院）。 */
    private void requireReadable(ChatSession s) {
        Identity me = IdentityContext.current();
        if (me.isPatient()) {
            if (!me.patientRef().equals(s.getPatientRef())) {
                throw new AccessException("无权访问该会话");
            }
            return;
        }
        requireClinicalDepartment(s, me);
    }

    private void requireClinicalDepartment(ChatSession s, Identity me) {
        if (!me.clinicalSide()) {
            throw new AccessException("仅医护侧可访问坐诊会话");
        }
        if (me.hospitalWide()) {
            return;
        }
        if (s.getDepartment() == null || !s.getDepartment().equals(me.department())) {
            throw new AccessException("该会话属于其他科室：" + s.getDepartment());
        }
    }

    private void touch(ChatSession s, ChatMessage m) {
        s.setLastMessage(trim(m.getBody(), 512));
        s.setLastMessageAt(m.getCreatedAt());
        s.setUpdatedAt(m.getCreatedAt());
        sessions.save(s);
    }

    private static String summaryOf(ChatSession s, List<ChatMessage> all,
                                    String assessment, String plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("人工坐诊（").append(s.getDepartment()).append("）：");
        int from = Math.max(0, all.size() - 10);
        for (int i = from; i < all.size(); i++) {
            ChatMessage m = all.get(i);
            sb.append(m.getSenderName() == null ? m.getSender() : m.getSenderName())
              .append("：").append(trim(m.getBody(), 200)).append("；");
        }
        if (assessment != null && !assessment.isBlank()) {
            sb.append("判断：").append(assessment.trim()).append("；");
        }
        if (plan != null && !plan.isBlank()) {
            sb.append("处置：").append(plan.trim()).append("；");
        }
        return trim(sb.toString(), 4000);
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
