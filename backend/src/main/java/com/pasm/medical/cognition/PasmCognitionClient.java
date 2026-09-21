package com.pasm.medical.cognition;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 认知服务客户端 —— Spring Boot 侧访问 Python 认知服务（PASM）的**唯一入口**。
 *
 * <p>为什么要包一层，而不是各 Service 自己发 HTTP：
 * <ul>
 *   <li><b>审计</b>：每次认知调用的入参/出参/耗时都要能落审计。收在一个类里才可能做到；</li>
 *   <li><b>韧性</b>：超时、重试、熔断只在这里配一次；</li>
 *   <li><b>越权防护</b>：管理令牌只出现在这个类里，不会被顺手写进别处。</li>
 * </ul>
 *
 * <p><b>令牌是管理令牌</b>：认知接口能写记忆、改人格、触发巩固，比对话敏感得多，
 * 所以服务端全部落在管理作用域。这个令牌<b>绝不能下发到浏览器</b> ——
 * 前端只跟本服务说话。
 *
 * <p>另有一个零依赖的 Java 客户端 {@code pasm-framework/sdks/java/PasmClient.java}
 * （只用 {@code java.net.http}，返回原始 JSON 串）。这里用 Spring 的 {@code RestClient}
 * 是因为本项目已有 Jackson，序列化交给它更自然；非 Spring 环境（Android、批处理）
 * 用那个 SDK 更合适。
 */
@Component
public class PasmCognitionClient {

    private static final Logger log = LoggerFactory.getLogger(PasmCognitionClient.class);

    private final RestClient http;
    private final CognitionProperties props;

    public PasmCognitionClient(CognitionProperties props) {
        this.props = props;
        String base = props.getBaseUrl() == null ? "http://127.0.0.1:8090"
                : props.getBaseUrl().replaceAll("/+$", "");

        // 超时必须有：认知服务不可用时不能把业务线程挂死。
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(3));
        rf.setReadTimeout(Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds())));

        RestClient.Builder b = RestClient.builder().baseUrl(base).requestFactory(rf);
        if (props.getToken() != null && !props.getToken().isBlank()) {
            b = b.defaultHeader("Authorization", "Bearer " + props.getToken());
        }
        this.http = b.build();
    }

    // ------------------------------------------------------------ 健康 / 能力探测

    /**
     * 能力探测。启动自检与运维页用它 —— <b>不要硬编码操作名</b>，
     * 认知服务升级后操作集会变。
     */
    public JsonNode capabilities() {
        return http.get().uri("/api/cog/capabilities").retrieve().body(JsonNode.class);
    }

    public boolean isAvailable() {
        try {
            JsonNode n = capabilities();
            return n != null && n.path("available").asBoolean(false);
        } catch (Exception ex) {
            log.warn("认知服务不可用：{}", ex.toString());
            return false;
        }
    }

    // ------------------------------------------------------------ 只读（注入提示词 / 展示依据）

    /**
     * 记忆检索。<b>返回值要原样带给前端与审计</b> —— 医生需要看到"系统凭哪几条说这话"。
     *
     * @param patientRef 患者引用（业务库主键或假名），<b>不要传真实姓名</b>：
     *                   它会被拼进认知实例的落盘目录名。
     */
    public JsonNode recall(String patientRef, String query, int k) {
        return http.get()
                .uri(uri -> uri.path("/api/cog/recall")
                        .queryParam("agent_id", agentId(patientRef))
                        .queryParam("k", k)
                        .queryParam("query", query)
                        .build())
                .retrieve().body(JsonNode.class);
    }

    /** 取认知上下文（只读）：把"该记得什么、情绪如何"打包给自己这一侧的大模型用。 */
    public JsonNode context(String patientRef, String query, int k) {
        return http.get()
                .uri(uri -> uri.path("/api/cog/context")
                        .queryParam("agent_id", agentId(patientRef))
                        .queryParam("k", k)
                        .queryParam("query", query)
                        .build())
                .retrieve().body(JsonNode.class);
    }

    // ------------------------------------------------------------ 写入

    /** 登记关键事实（过敏史 / 危急事件）。<b>务必用高 salience</b>，否则会被闲聊挤出上下文。 */
    public JsonNode observe(String patientRef, String title, String brief,
                            List<String> tags, int salience) {
        return http.post().uri("/api/cog/observe")
                .body(Map.of("agent_id", agentId(patientRef),
                        "title", title,
                        "brief", brief == null ? "" : brief,
                        "tags", tags == null ? List.of() : tags,
                        "salience", salience))
                .retrieve().body(JsonNode.class);
    }

    /** 记录情绪事件（驱动共情语气 + 情绪状态跟踪）。 */
    public JsonNode feel(String patientRef, String event, double valence) {
        return http.post().uri("/api/cog/feel")
                .body(Map.of("agent_id", agentId(patientRef),
                        "event", event, "valence", valence))
                .retrieve().body(JsonNode.class);
    }

    /**
     * 医生对本次 AI 输出的反馈（采纳 / 修改 / 否决）。
     *
     * <p><b>这是最高质量的学习信号，比病历本身可靠得多</b> —— 病历只记录"结果"，
     * 而"医生把这条 AI 建议否决了"直接标出了系统的错误。务必带上 {@code action}。
     */
    public JsonNode feedback(String patientRef, String kind, String action) {
        var body = new java.util.HashMap<String, Object>();
        body.put("agent_id", agentId(patientRef));
        body.put("kind", kind);
        if (action != null && !action.isBlank()) {
            body.put("action", action);
        }
        return http.post().uri("/api/cog/feedback").body(body)
                .retrieve().body(JsonNode.class);
    }

    /** 触发记忆巩固（睡眠回放）。{@code apply=false} 只给建议 —— 日批任务先跑这个。 */
    public JsonNode consolidate(String patientRef, boolean apply) {
        return http.post().uri("/api/cog/consolidate")
                .body(Map.of("agent_id", agentId(patientRef), "apply", apply))
                .retrieve().body(JsonNode.class);
    }

    // ------------------------------------------------------------ 内部

    /**
     * 患者引用 → 认知层 agent_id。
     *
     * <p>这里只做 URL 编码；<b>真正的命名与隔离规则在 Python 侧</b>
     * （{@code pasm_medical.domain.agent_id_for}）。之所以不在 Java 侧重做一遍：
     * 两处实现必然漂移，而漂移的后果是"某个患者的记忆写到了别人名下"。
     */
    private static String agentId(String patientRef) {
        if (patientRef == null || patientRef.isBlank()) {
            throw new IllegalArgumentException("patientRef 不能为空");
        }
        return patientRef;
    }

    /*
     * ★ 查询参数不要手动 URL 编码。
     *   RestClient 的 UriBuilder.queryParam 会自己编码；再手动 enc() 一次就是
     *   **双重编码** —— 中文会变成 "%25E9%259D%2592..."，网关解码后拿到乱码，
     *   表现为"明明有资料却检索不到"，而且不报错。
     *   （零依赖 SDK PasmClient 里必须手动编码，因为它是自己拼 URL 的 —— 场景不同。）
     */
}
