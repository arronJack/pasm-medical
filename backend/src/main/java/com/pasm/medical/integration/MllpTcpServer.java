package com.pasm.medical.integration;

import com.pasm.medical.integration.hl7.Hl7Ack;
import com.pasm.medical.integration.hl7.Hl7ParseException;
import com.pasm.medical.integration.hl7.MllpFrame;
import com.pasm.medical.service.LisIngestService;
import com.pasm.medical.web.IntegrationTokenGuard;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LIS 直连通道：MLLP over TCP（{@code 0x0B HL7 0x1C 0x0D}）+ HL7 {@code ACK^R01}。
 *
 * <p><b>默认完全不启用</b>（{@code medical.lis.mllp.enabled=false} 时不创建这个 Bean）。
 * 现有部署走 HTTP 投递，由院内集成引擎转发；只有对接方明确要求直连 2575 端口、
 * 且强制要 ACK 报文时才打开。开一个监听端口是很重的决定，不能靠"有配置就顺手起"。
 *
 * <p>★ <b>fail closed 的三道闸</b>：
 * <ol>
 *   <li>开关默认 false；</li>
 *   <li>只绑 {@code 127.0.0.1}（要跨机必须由运维显式改 bind 并自己加防火墙白名单，
 *       本项目不代替院方做网络准入）；</li>
 *   <li>令牌未配置 / 缺失 / 错误 → 回 {@code AR} 拒绝，<b>绝不因为"没配"就放行</b>，
 *       与 HTTP 端点共用 {@link IntegrationTokenGuard} 的常量时间比较。</li>
 * </ol>
 *
 * <p>★ <b>令牌怎么传</b>：TCP 没有 HTTP 头，用 HL7 自定义 {@code ZAT} 段承载
 * （{@code ZAT|<token>}），这是 HL7 v2 允许的 Z 段扩展。<b>在交给业务层之前剥掉该段</b>，
 * 保证共享密钥不会落进任何日志、异常文本或入库内容。消息控制号仍取 MSH-10，
 * 幂等键不受影响。
 *
 * <p>★ <b>业务不变量不重写</b>：解析、机构配置校验、未知患者拒收、幂等落库
 * 全部复用 {@link LisIngestService#ingest(String)}。TCP 层只负责帧、鉴权、ACK，
 * 不另起一套判定 —— 否则两条通道迟早会漂移成"HTTP 拒绝的 MLLP 却收了"。
 *
 * <p>★ 日志<b>不打印消息体和令牌</b>，只打印结果码与消息控制号（控制号也是回执对账依据）。
 */
@Component
@ConditionalOnProperty(prefix = "medical.lis.mllp", name = "enabled", havingValue = "true")
public class MllpTcpServer {

    private static final Logger log = LoggerFactory.getLogger(MllpTcpServer.class);

    private final LisIngestService ingest;
    private final IntegrationTokenGuard guard;
    private final int port;
    private final String bind;
    private final int readTimeoutMs;
    private final int maxConnections;

    private ServerSocket server;
    private ExecutorService pool;
    private Thread acceptor;
    private volatile boolean running = false;
    private final AtomicLong accepted = new AtomicLong();

    public MllpTcpServer(LisIngestService ingest,
                         IntegrationTokenGuard guard,
                         @Value("${medical.lis.mllp.port:2575}") int port,
                         @Value("${medical.lis.mllp.bind:127.0.0.1}") String bind,
                         @Value("${medical.lis.mllp.read-timeout-ms:10000}") int readTimeoutMs,
                         @Value("${medical.lis.mllp.max-connections:8}") int maxConnections) {
        this.ingest = ingest;
        this.guard = guard;
        this.port = port;
        this.bind = bind;
        this.readTimeoutMs = readTimeoutMs;
        this.maxConnections = maxConnections;
    }

    @PostConstruct
    public void start() throws IOException {
        if (guard.check("__probe__") == IntegrationTokenGuard.Verdict.NOT_CONFIGURED) {
            // 不抛异常阻止启动：监听仍开着，但每条消息都回 AR，让 LIS 侧拿到
            // 明确的「令牌未配置」而不是面对一个连不上/没响应的黑盒。
            log.error("MLLP 已启用但未配置 MEDICAL_LIS_TOKEN —— 通道将拒绝所有消息（fail closed）");
        }
        server = new ServerSocket();
        server.bind(new java.net.InetSocketAddress(InetAddress.getByName(bind), port));
        pool = Executors.newFixedThreadPool(maxConnections, r -> {
            Thread t = new Thread(r, "mllp-worker");
            t.setDaemon(true);
            return t;
        });
        running = true;
        acceptor = new Thread(this::acceptLoop, "mllp-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        log.info("MLLP 监听已启动 {}:{}（仅本机、需 ZAT 令牌、回 ACK^R01）", bind, port);
    }

    @PreDestroy
    public void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // 关闭时的异常没有可恢复动作
        }
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("MLLP 监听已停止（累计连接 {}）", accepted.get());
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                accepted.incrementAndGet();
                pool.submit(() -> handle(s));
            } catch (IOException e) {
                if (running) {
                    log.warn("MLLP accept 失败：{}", e.getMessage());
                }
                break;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(readTimeoutMs);
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            // 一条连接可以连续发多帧（LIS 常见做法），每帧立即回一个 ACK；
            // 空闲超时或 EOF 就断开，不维持无限长连接占住线程池。
            while (running) {
                byte[] frame;
                try {
                    frame = readFrame(in);
                } catch (SocketTimeoutException e) {
                    break;
                } catch (MllpFrame.MllpFrameException e) {
                    // ★ 帧层异常发生在 unwrap 之前的 try 外面，必须在这里单独回 ACK：
                    //   「坏帧不回 ACK」会让 LIS 一直重发同一条坏消息把队列堵死。
                    //   回完就断开：流已经不同步，无法确定下一帧边界。
                    String ack = Hl7Ack.build("", Hl7Ack.Code.AE, e.code());
                    out.write(MllpFrame.wrap(ack));
                    out.flush();
                    log.info("MLLP 回执 controlId= code=AE reason={}", e.code());
                    break;
                }
                if (frame == null) {
                    break;
                }
                String controlId;
                Hl7Ack.Code code;
                String reason;
                try {
                    String raw = MllpFrame.unwrap(frame);
                    controlId = Hl7Ack.controlIdOf(raw);
                    String token = MllpAuth.extract(raw);
                    IntegrationTokenGuard.Verdict v = guard.check(token);
                    if (v != IntegrationTokenGuard.Verdict.OK) {
                        code = Hl7Ack.Code.AR;
                        reason = switch (v) {
                            case NOT_CONFIGURED -> "lis_token_not_configured";
                            case MISSING -> "missing_token";
                            default -> "invalid_token";
                        };
                    } else {
                        // ★ 剥掉 ZAT 段再交给业务层：令牌不得进入解析/异常/落库链路。
                        LisIngestService.IngestResult r = ingest.ingest(MllpAuth.strip(raw));
                        code = Hl7Ack.Code.AA;
                        reason = "inserted=" + r.inserted() + ";skippedDuplicates="
                                + r.skippedDuplicates();
                    }
                } catch (MllpFrame.MllpFrameException e) {
                    controlId = "";
                    code = Hl7Ack.Code.AE;
                    reason = e.code();
                } catch (LisDisabledException e) {
                    code = Hl7Ack.Code.AR;   // 配置不允许 → 停止重试，联系管理员
                    reason = "lis_disabled";
                    controlId = Hl7Ack.controlIdOf(safeText(frame));
                } catch (UnknownPatientException e) {
                    code = Hl7Ack.Code.AE;   // 核对主索引后可重发
                    reason = "unknown_patient";
                    controlId = Hl7Ack.controlIdOf(safeText(frame));
                } catch (Hl7ParseException e) {
                    code = Hl7Ack.Code.AE;   // 消息本身有问题，修正后可重发
                    reason = "hl7_parse_error";
                    controlId = Hl7Ack.controlIdOf(safeText(frame));
                } catch (RuntimeException e) {
                    code = Hl7Ack.Code.AE;
                    reason = "ingest_error";
                    controlId = Hl7Ack.controlIdOf(safeText(frame));
                    log.warn("MLLP 处理异常 controlId={} type={}", controlId,
                            e.getClass().getSimpleName());
                }
                out.write(MllpFrame.wrap(Hl7Ack.build(controlId, code, reason)));
                out.flush();
                // 只打控制号与结果码；消息体、令牌、患者 ref 一律不进日志。
                log.info("MLLP 回执 controlId={} code={} reason={}", controlId, code, reason);
            }
        } catch (IOException e) {
            log.debug("MLLP 连接结束：{}", e.getMessage());
        }
    }

    /** 读一帧；EOF 返回 null。逐字节读，遇到 0x1C 后必须紧跟 0x0D 才算结束。 */
    private static byte[] readFrame(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        int b = in.read();
        if (b < 0) {
            return null;
        }
        if (b != (MllpFrame.VT & 0xFF)) {
            throw new MllpFrame.MllpFrameException("bad_start", "缺少 0x0B 起始字节");
        }
        buf.write(b);
        while (true) {
            int c = in.read();
            if (c < 0) {
                throw new MllpFrame.MllpFrameException("bad_end", "连接提前关闭，帧未结束");
            }
            buf.write(c);
            if (c == (MllpFrame.FS & 0xFF)) {
                int cr = in.read();
                if (cr < 0) {
                    throw new MllpFrame.MllpFrameException("bad_end", "连接提前关闭，帧未结束");
                }
                buf.write(cr);
                if (cr != (MllpFrame.CR & 0xFF)) {
                    throw new MllpFrame.MllpFrameException("bad_end", "0x1C 后必须紧跟 0x0D");
                }
                return buf.toByteArray();
            }
            if (buf.size() > MllpFrame.MAX_FRAME_BYTES) {
                throw new MllpFrame.MllpFrameException("frame_too_large", "帧超过 1 MiB 上限");
            }
        }
    }

    /** 帧异常时用于提取控制号的尽力文本（不参与业务，不进日志）。 */
    private static String safeText(byte[] frame) {
        return new String(frame, StandardCharsets.UTF_8);
    }
}
