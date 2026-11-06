package com.pasm.medical.service;

import com.pasm.medical.config.Identity;
import com.pasm.medical.config.IdentityContext;
import com.pasm.medical.domain.AiAudit;
import com.pasm.medical.domain.ImagingStudy;
import com.pasm.medical.repository.ImagingStudyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 影像归档 + 转交。
 *
 * <p><b>职责止于「字节搬运」</b>：登记检查元数据 → 把原始字节写入归档目录 →
 * 算一个 SHA-256 供完整性核对 → 允许医护把这条记录转交给某科室。
 * 服务端<b>不解析像素、不读 DICOM 标签、不生成影像所见/印象/结论</b>。
 *
 * <p>★ 「不做分析」不是文档里的一句话，而是三道代码约束：
 * <ol>
 *   <li>{@code imaging_studies} 表与实体<b>没有</b>任何结论字段；</li>
 *   <li>入参里出现结论类字段（{@link #FORBIDDEN_KEYS}）直接 <b>400</b>，
 *       不给「顺手塞个模型结果」留旁路；</li>
 *   <li>原始文件按服务端生成的 UUID 落盘，读取时只做整块字节返回，
 *       没有任何解码/推理调用点。</li>
 * </ol>
 *
 * <p>⚠ 当前落本地目录（dev / 单机）。生产需替换为 PACS / MinIO 适配器，
 * 并补加密、留存策略与跨主机共享 —— 见 {@code docs/GUIDE.md}。
 * 这属于部署能力缺口，**不**因为本模块跑通就当已解决。
 */
@Service
public class ImagingArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ImagingArchiveService.class);

    /**
     * 结论类字段黑名单。命中即 400 —— 目的是让「影像分析」无法通过加字段的方式
     * 悄悄混进归档链路（合规边界要靠代码挡，不能靠自觉）。
     */
    public static final Set<String> FORBIDDEN_KEYS = Set.of(
            "analysis", "analysed", "interpretation", "impression", "conclusion",
            "findings", "finding", "diagnosis", "airesult", "report",
            "reporttext", "readresult", "impressiontext");

    public static final String ERR_ANALYSIS = "ANALYSIS_FORBIDDEN";

    private final ImagingStudyRepository repo;
    private final DepartmentService departments;
    private final AuditService audit;
    private final Path root;
    private final long maxBytes;

    public ImagingArchiveService(ImagingStudyRepository repo, DepartmentService departments,
                                 AuditService audit,
                                 @Value("${medical.imaging.dir:.demo/imaging}") String dir,
                                 @Value("${medical.imaging.max-bytes:5242880}") long maxBytes) {
        this.repo = repo;
        this.departments = departments;
        this.audit = audit;
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    /** 入参闸门：任何结论类字段一律拒绝。两个写入口都要调，别只挡上传。 */
    public static void assertNoAnalysisFields(Map<String, Object> body) {
        if (body == null) {
            return;
        }
        for (String key : body.keySet()) {
            String norm = (key == null ? "" : key).toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]", "");
            if (FORBIDDEN_KEYS.contains(norm)) {
                throw new Problem(400, ERR_ANALYSIS,
                        "本系统只归档与转交影像，不接受「" + key + "」等分析/结论字段"
                                + "（自动读片需三类医疗器械资质，不在本系统范围内）");
            }
        }
    }

    /** 归档一条影像检查（患者本人或医护代录）。 */
    public ImagingStudy archive(Map<String, Object> body) {
        Identity me = IdentityContext.current();
        String ref = IdentityContext.scopedRef(text(body, "patientRef"));
        if (ref == null) {
            throw new Problem(403, "FORBIDDEN_PATIENT", "无权为该患者归档影像");
        }
        assertNoAnalysisFields(body);

        String modality = require(body, "modality", 32);
        String bodyPart = require(body, "bodyPart", 128);
        String fileName = require(body, "fileName", 256);
        String contentType = require(body, "contentType", 128);
        String encoded = require(body, "contentBase64", Integer.MAX_VALUE);
        String department = text(body, "department") == null ? "" : text(body, "department").trim();
        if (!department.isEmpty() && !departments.existsActive(department)) {
            throw new Problem(400, "UNKNOWN_DEPARTMENT", "接收科室不存在或已停用：" + department);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            throw new Problem(400, "BAD_BASE64", "contentBase64 不是合法的 Base64");
        }
        if (bytes.length == 0) {
            throw new Problem(400, "EMPTY_FILE", "影像文件为空，不予归档");
        }
        if (bytes.length > maxBytes) {
            throw new Problem(400, "FILE_TOO_LARGE",
                    "单文件上限 " + maxBytes + " 字节，本次 " + bytes.length + " 字节；"
                            + "生产请接 PACS/对象存储，不要用接口搬运大文件");
        }

        String month = DateTimeFormatter.ofPattern("yyyyMM")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        String storageKey = month + "/" + UUID.randomUUID() + ".bin";
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            // storageKey 由服务端生成，这里仍做一次路径逃逸兜底（防将来有人改成用户可控）
            throw new Problem(400, "BAD_STORAGE_KEY", "归档路径非法");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);          // ★ 只写字节，不解码、不推理
        } catch (IOException ex) {
            log.warn("影像归档落盘失败：ref={} key={}", ref, storageKey, ex);
            throw new Problem(500, "STORAGE_FAILED", "归档目录不可写，未生成记录");
        }

        ImagingStudy s = new ImagingStudy();
        s.setPatientRef(ref);
        s.setModality(modality);
        s.setBodyPart(bodyPart);
        s.setStudyDate(clip(text(body, "studyDate"), 32));
        s.setAccessionNo(clip(text(body, "accessionNo"), 128));
        s.setDepartment(department);
        s.setFileName(fileName);
        s.setContentType(contentType);
        s.setByteSize(bytes.length);
        s.setSha256(sha256(bytes));
        s.setStorageKey(storageKey);
        s.setStatus(ImagingStudy.ARCHIVED);
        s.setUploadedBy(me.username() == null ? "" : me.username());
        s.setCreatedAt(Instant.now());
        ImagingStudy saved = repo.save(s);

        recordWrite("imaging-archive", ref,
                "id=" + saved.getId() + ";modality=" + modality + ";bytes=" + bytes.length
                        + ";sha256=" + saved.getSha256() + ";dept=" + department);
        return saved;
    }

    /** 列表：患者只能列本人（scopedRef 收窄）；临床侧按 ref 查任意患者 + 读留痕。 */
    public List<ImagingStudy> list(String requestedRef) {
        String ref = IdentityContext.scopedRef(requestedRef);
        if (ref == null) {
            throw new Problem(403, "FORBIDDEN_PATIENT", "无权查看该患者的影像");
        }
        // ★ 先记再读：即使一条都没有，这次「查看尝试」也已留痕
        audit.recordRead(AuditService.currentActor(null), ref, "imaging-list",
                "ref=" + ref);
        return repo.findByPatientRefOrderByCreatedAtDesc(ref);
    }

    /** 单条读取（含原始字节下载）：归属不符一律 404，不区分「不存在」与「不属于你」。 */
    public ImagingStudy requireReadable(long id) {
        ImagingStudy s = repo.findById(id).orElse(null);
        if (s == null) {
            throw new Problem(404, "NOT_FOUND", "影像记录不存在");
        }
        Identity me = IdentityContext.current();
        if (me.isPatient() && !s.getPatientRef().equals(
                me.patientRef() == null ? "" : me.patientRef())) {
            // 不泄漏「这个 id 存在，但不是你的」—— 否则自增 id 变成存在性预言机
            throw new Problem(404, "NOT_FOUND", "影像记录不存在");
        }
        audit.recordRead(AuditService.currentActor(null), s.getPatientRef(),
                "imaging-read", "id=" + id + ";status=" + s.getStatus());
        return s;
    }

    /** 读原始字节。★ 返回的是不透明字节，服务端不做任何图像解码。 */
    public byte[] content(long id) {
        ImagingStudy s = requireReadable(id);
        Path p = root.resolve(s.getStorageKey()).normalize();
        if (!p.startsWith(root)) {
            throw new Problem(500, "BAD_STORAGE_KEY", "归档路径非法");
        }
        try {
            return Files.readAllBytes(p);
        } catch (IOException ex) {
            throw new Problem(500, "STORAGE_MISSING", "归档文件缺失（记录仍在，需运维核对）");
        }
    }

    /** 待转交队列：超管全院；其它医护侧只看本科室 + 未指定科室的记录。 */
    public List<ImagingStudy> queue() {
        if (!IdentityContext.clinicalSide()) {
            throw new Problem(403, "CLINICAL_ONLY", "转交队列仅医护侧可见");
        }
        String dept = IdentityContext.scopeDepartment();
        List<ImagingStudy> all = new java.util.ArrayList<>(dept == null
                ? repo.findByStatusOrderByCreatedAtDesc(ImagingStudy.ARCHIVED)
                : repo.findByStatusAndDepartmentOrderByCreatedAtDesc(
                        ImagingStudy.ARCHIVED, dept));
        if (dept != null) {
            // 未指定科室的记录对任何在岗科室可见（避免患者没选科室就永远没人接）
            List<ImagingStudy> unassigned = repo.findByStatusAndDepartmentOrderByCreatedAtDesc(
                    ImagingStudy.ARCHIVED, "");
            for (ImagingStudy s : unassigned) {
                if (all.stream().noneMatch(x -> x.getId().equals(s.getId()))) {
                    all.add(s);
                }
            }
        }
        audit.recordRead(AuditService.currentActor(null), "", "imaging-queue",
                "dept=" + (dept == null ? "ALL" : dept));
        return all;
    }

    /** 转交给科室（仅医护侧；已转交的不能重复转，避免责任归属含糊）。 */
    public ImagingStudy handoff(long id, String department, String note) {
        if (!IdentityContext.clinicalSide()) {
            throw new Problem(403, "CLINICAL_ONLY", "影像转交由医护侧执行");
        }
        String dept = department == null ? "" : department.trim();
        if (dept.isEmpty() || !departments.existsActive(dept)) {
            throw new Problem(400, "UNKNOWN_DEPARTMENT", "转交科室必须是已启用的科室");
        }
        ImagingStudy s = repo.findById(id).orElse(null);
        if (s == null) {
            throw new Problem(404, "NOT_FOUND", "影像记录不存在");
        }
        if (ImagingStudy.HANDED_OFF.equals(s.getStatus())) {
            throw new Problem(409, "ALREADY_HANDED_OFF", "该影像已转交，不能重复转交");
        }
        s.setStatus(ImagingStudy.HANDED_OFF);
        s.setHandoffDepartment(dept);
        s.setHandoffNote(clip(note, 1024));
        s.setHandoffBy(IdentityContext.current().username());
        s.setHandoffAt(Instant.now());
        ImagingStudy saved = repo.save(s);
        recordWrite("imaging-handoff", s.getPatientRef(),
                "id=" + id + ";to=" + dept + ";note=" + clip(note, 200));
        return saved;
    }

    // ---------------------------------------------------------------- 工具

    private void recordWrite(String action, String ref, String detail) {
        AiAudit a = new AiAudit();
        a.setPatientRef(ref);
        a.setActor(AuditService.currentActor(null));
        a.setAction(action);
        a.setEvidenceCount(0);
        a.setModelVersion("business-layer");
        a.setInputSnapshot(detail);
        a.setRefused(false);
        audit.record(a);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 缺 SHA-256", ex);
        }
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String require(Map<String, Object> body, String key, int max) {
        String v = text(body, key);
        if (v == null || v.isBlank()) {
            throw new Problem(400, "MISSING_FIELD", "缺少必填字段：" + key);
        }
        return clip(v.trim(), max);
    }

    /** 携带 HTTP 状态码与机器可读码的业务异常（让断言能精确区分失败原因）。 */
    public static class Problem extends RuntimeException {
        private final int status;
        private final String code;

        public Problem(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }
        public String code() { return code; }
    }
}
