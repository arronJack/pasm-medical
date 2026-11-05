package com.pasm.medical.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 坐诊会话的 SSE 广播（服务器推送）。
 *
 * <p>★ 为什么用 SSE 而不是轮询：轮询有 1~2s 固定延迟且请求量随在线人数线性增长；
 * SSE 是单向推送（患者/医生发消息走普通 POST），实现简单、无 WebSocket 的握手与
 * 代理粘性问题，浏览器断线会自动重连（前端用 fetch 流自行控制重连）。
 *
 * <p>★ <b>单实例内存广播</b>：emitter 存在本进程内。多主机部署时需要换成
 * Redis Pub/Sub（或消息队列）广播，否则用户连到 A 实例、消息发到 B 实例会收不到。
 * 这是刻意的取舍：当前部署是单实例，先把链路做通，不引入中间件。
 */
@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    /** sessionId → 该会话的所有订阅者（一场会话通常只有患者 + 接诊医生两个）。 */
    private final Map<Long, List<SseEmitter>> bySession = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long sessionId) {
        // 0 = 不设超时（长连接由心跳维持）；超时/完成/错误都要把 emitter 摘掉，否则内存只增不减
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> remove(sessionId, emitter));
        emitter.onTimeout(() -> {
            remove(sessionId, emitter);
            try { emitter.complete(); } catch (Exception ignored) { }
        });
        emitter.onError(t -> remove(sessionId, emitter));
        bySession.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        return emitter;
    }

    /** 推一条新消息。 */
    public void message(Long sessionId, Object body) {
        broadcast(sessionId, "message", body);
    }

    /** 推会话状态变化（接诊 / 结束）。 */
    public void session(Long sessionId, Object body) {
        broadcast(sessionId, "session", body);
    }

    /** 推最后一条状态事件后关闭所有连接（会话已结束）。 */
    public void sessionAndClose(Long sessionId, Object body) {
        session(sessionId, body);
        List<SseEmitter> list = bySession.remove(sessionId);
        if (list == null) {
            return;
        }
        for (SseEmitter e : list) {
            try { e.complete(); } catch (Exception ignored) { }
        }
    }

    private void broadcast(Long sessionId, String name, Object body) {
        List<SseEmitter> list = bySession.get(sessionId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name(name).data(body));
            } catch (IOException | IllegalStateException ex) {
                // 客户端已断开 / emitter 已结束：摘掉即可，不能让一个坏连接影响其他人
                remove(sessionId, e);
                try { e.complete(); } catch (Exception ignored) { }
            }
        }
    }

    /** 心跳：15s 一次注释行，防止中间代理把空闲长连接掐断。 */
    @Scheduled(fixedRate = 15000)
    public void heartbeat() {
        bySession.forEach((sessionId, list) -> {
            for (SseEmitter e : list) {
                try {
                    e.send(SseEmitter.event().comment("ping"));
                } catch (Exception ex) {
                    remove(sessionId, e);
                    try { e.complete(); } catch (Exception ignored) { }
                }
            }
        });
    }

    private void remove(Long sessionId, SseEmitter emitter) {
        List<SseEmitter> list = bySession.get(sessionId);
        if (list == null) {
            return;
        }
        list.remove(emitter);
        if (list.isEmpty()) {
            bySession.remove(sessionId, list);
        }
        log.debug("sse removed session={} remaining={}", sessionId, list.size());
    }
}
