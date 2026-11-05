package com.pasm.medical.web;

import com.pasm.medical.config.Identity;
import com.pasm.medical.config.IdentityContext;
import com.pasm.medical.domain.ChatMessage;
import com.pasm.medical.domain.ChatSession;
import com.pasm.medical.service.ChatService;
import com.pasm.medical.service.ChatStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工坐诊接口。
 *
 * <p>链路：患者 {@code handoff} → 医生 {@code queue} 看到 → {@code take} 接诊
 * → 双方 {@code messages} 收发 → {@code close} 结束并落就诊。
 *
 * <p>★ 实时性采用**增量轮询**（{@code afterId}）而不是 WebSocket：单机 dev 部署下
 * 轮询 1.5~2s 已满足"即时沟通"的体感，且没有长连接在多实例/代理下的粘性问题。
 * 接口形状按增量设计，将来换 SSE/WS 不需要改前端数据模型。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chat;
    private final ChatStreamService streams;

    public ChatController(ChatService chat, ChatStreamService streams) {
        this.chat = chat;
        this.streams = streams;
    }

    /** 患者请求转人工（幂等）。 */
    @PostMapping("/handoff")
    public ResponseEntity<?> handoff(@RequestBody HandoffRequest req) {
        Identity me = IdentityContext.current();
        String ref = IdentityContext.scopedRef(req.patientRef());
        if (ref == null) {
            return ResponseEntity.status(403).body(Map.of("error", "无权为该患者发起会话"));
        }
        if (!me.isPatient()) {
            return ResponseEntity.status(403).body(Map.of("error", "转人工由患者发起"));
        }
        try {
            ChatSession s = chat.handoff(ref, req.department(), req.chiefComplaint(),
                    req.urgency(), me.displayName(), req.transcript());
            return ResponseEntity.ok(sessionBody(s));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (ChatService.AccessException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    /** 患者当前进行中的会话（刷新页面恢复现场）。 */
    @GetMapping("/my")
    public ResponseEntity<?> my() {
        Identity me = IdentityContext.current();
        if (!me.isPatient()) {
            return ResponseEntity.status(403).body(Map.of("error", "仅患者可查询本人会话"));
        }
        if (me.patientRef() == null || me.patientRef().isBlank()) {
            return ResponseEntity.status(403).body(Map.of("error", "账号未绑定患者"));
        }
        ChatSession s = chat.myOpenSession(me.patientRef());
        return s == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(sessionBody(s));
    }

    /** 坐诊队列（科室范围）。 */
    @GetMapping("/queue")
    public ResponseEntity<?> queue() {
        try {
            List<Map<String, Object>> out = chat.queue().stream().map(this::sessionBody).toList();
            return ResponseEntity.ok(out);
        } catch (ChatService.AccessException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    /** 接诊。 */
    @PostMapping("/{id}/take")
    public ResponseEntity<?> take(@PathVariable Long id) {
        try {
            ChatSession s = chat.take(id);
            streams.session(id, sessionBody(s));   // 患者端立刻看到「已被接诊」
            return ResponseEntity.ok(sessionBody(s));
        } catch (ChatService.AccessException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * SSE 长连接：先补发 {@code afterId} 之后的历史，再实时推送新消息/状态。
     *
     * <p>★ 先鉴权再建长连接：否则会先给未授权连接一个 200，再在流里沉默 —— 排查极难。
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id,
                             @RequestParam(defaultValue = "0") Long afterId) {
        try {
            chat.requireReadable(id);
        } catch (ChatService.AccessException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
        SseEmitter emitter = streams.subscribe(id);
        for (ChatMessage m : chat.messages(id, afterId)) {
            try {
                emitter.send(SseEmitter.event().name("message").data(messageBody(m)));
            } catch (IOException e) {
                break;   // 客户端已断开，直接返回，emitter 会被 onError 清理
            }
        }
        return emitter;
    }

    /** 增量拉取消息：afterId 之后的新消息。 */
    @GetMapping("/{id}/messages")
    public ResponseEntity<?> messages(@PathVariable Long id,
                                      @RequestParam(defaultValue = "0") Long afterId) {
        try {
            List<Map<String, Object>> out = chat.messages(id, afterId).stream()
                    .map(this::messageBody).toList();
            return ResponseEntity.ok(out);
        } catch (ChatService.AccessException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    /** 发消息。 */
    @PostMapping("/{id}/messages")
    public ResponseEntity<?> send(@PathVariable Long id, @RequestBody SendRequest req) {
        try {
            ChatMessage m = chat.post(id, req.body());
            streams.message(id, messageBody(m));   // 在线的一方立刻收到（未在线则从 DB 补）
            return ResponseEntity.ok(messageBody(m));
        } catch (ChatService.AccessException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 结束会话并落就诊（可带医师判断/处置）。 */
    @PostMapping("/{id}/close")
    public ResponseEntity<?> close(@PathVariable Long id, @RequestBody(required = false) CloseRequest req) {
        try {
            Map<String, Object> out = chat.close(id,
                    req == null ? null : req.assessment(),
                    req == null ? null : req.plan());
            Object s = out.get("session");
            Map<String, Object> body = new LinkedHashMap<>();
            if (s instanceof ChatSession cs) {
                Map<String, Object> sb = sessionBody(cs);
                body.put("session", sb);
                // 先推「已结束」，再关掉长连接 —— 否则对方会一直停在"正在服务"
                streams.sessionAndClose(id, sb);
            }
            body.put("encounterId", out.get("encounterId"));
            body.put("alreadyClosed", out.get("alreadyClosed"));
            return ResponseEntity.ok(body);
        } catch (ChatService.AccessException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- 序列化

    private Map<String, Object> sessionBody(ChatSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("patientRef", s.getPatientRef());
        m.put("department", s.getDepartment());
        m.put("status", s.getStatus());
        m.put("chiefComplaint", s.getChiefComplaint() == null ? "" : s.getChiefComplaint());
        m.put("urgency", s.getUrgency() == null ? "routine" : s.getUrgency());
        m.put("assignedStaff", s.getAssignedStaff() == null ? "" : s.getAssignedStaff());
        m.put("assignedName", s.getAssignedName() == null ? "" : s.getAssignedName());
        m.put("lastMessage", s.getLastMessage() == null ? "" : s.getLastMessage());
        m.put("createdAt", s.getCreatedAt() == null ? "" : s.getCreatedAt().toString());
        m.put("updatedAt", s.getUpdatedAt() == null ? "" : s.getUpdatedAt().toString());
        m.put("lastMessageAt", s.getLastMessageAt() == null ? "" : s.getLastMessageAt().toString());
        return m;
    }

    private Map<String, Object> messageBody(ChatMessage m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", m.getId());
        o.put("sender", m.getSender());
        o.put("senderName", m.getSenderName() == null ? "" : m.getSenderName());
        o.put("body", m.getBody());
        o.put("createdAt", m.getCreatedAt() == null ? "" : m.getCreatedAt().toString());
        return o;
    }

    public record HandoffRequest(String patientRef, String department,
                                 String chiefComplaint, String urgency, String transcript) {
    }

    public record SendRequest(String body) {
    }

    public record CloseRequest(String assessment, String plan) {
    }
}
