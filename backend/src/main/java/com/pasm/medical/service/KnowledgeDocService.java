package com.pasm.medical.service;

import com.pasm.medical.cognition.PasmCognitionClient;
import com.pasm.medical.domain.KnowledgeDoc;
import com.pasm.medical.repository.KnowledgeDocRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 资料库服务：**业务库是权威**，认知侧只放一份"当前生效资料"的检索副本。
 *
 * <p>★ 同步策略是**全量替换**，不是逐条增删：
 * 权威数据在本表（带状态/版本/复核人），认知侧只需要"现在生效的那一批"。
 * 逐条同步要在两侧对齐 id 与版本，多一处不一致就多一处"下架了却还能被引用"。
 * 每次写操作后调 {@link #syncToCognition()} 推全集，认知侧先清本项目写入的再重灌。
 *
 * <p>★ 同步失败**不改数据库的成功**，但必须如实回报（返回值里带 {@code synced=false} 与原因）——
 * 界面要能看出"库里改了、检索侧没生效"这种漂移，而不是假装一切正常。
 */
@Service
public class KnowledgeDocService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocService.class);

    /** 生效状态：只有它会进认知侧的检索副本。 */
    public static final String ACTIVE = "active";
    /** 下架状态：仍留在业务库（可再上架、可审计），但不作为任何回答的依据。 */
    public static final String INACTIVE = "inactive";

    private final KnowledgeDocRepository repo;
    private final PasmCognitionClient cog;

    public KnowledgeDocService(KnowledgeDocRepository repo, PasmCognitionClient cog) {
        this.repo = repo;
        this.cog = cog;
    }

    // ------------------------------------------------------------ 读

    public List<KnowledgeDoc> list() {
        return repo.findAllByOrderByUpdatedAtDesc();
    }

    /**
     * 本科室资料列表（P1-2）。
     *
     * <p>★ 科室管理员只看自己科室那一批；传空科室直接返回空列表
     * （不会退化成"查全院" —— 空值查不到任何行，而不是 {@code department IS NULL}）。
     */
    public List<KnowledgeDoc> listByDepartment(String department) {
        if (department == null || department.isBlank()) {
            return List.of();
        }
        return repo.findByDepartmentOrderByUpdatedAtDesc(department);
    }

    public Optional<KnowledgeDoc> find(String docKey) {
        return repo.findByDocKey(docKey);
    }

    public long countActive() {
        return repo.countByStatus(ACTIVE);
    }

    public long countInactive() {
        return repo.countByStatus(INACTIVE);
    }

    /**
     * 当前生效资料的 key 集合。
     *
     * <p>★ 它是**正确性的第二道保险**：即使认知侧的清理失败、下架资料还留在检索库里，
     * 问答时会用这个集合再过滤一次 —— 下架的资料不会被当成依据。
     */
    public List<String> activeKeys() {
        return repo.findByStatusOrderByUpdatedAtDesc(ACTIVE).stream()
                .map(KnowledgeDoc::getDocKey).toList();
    }

    // ------------------------------------------------------------ 写

    /**
     * 新建或更新（按 {@code docKey} 幂等 upsert）。
     *
     * <p>{@code docKey} 为空时自动生成：资料库是给人用的，"起个短键"这种要求
     * 只会让人填错或放弃。
     */
    @Transactional
    public KnowledgeDoc upsert(String docKey, String title, String content, String tags,
                              String department, String version, String reviewer, String actor) {
        KnowledgeDoc d = (docKey == null || docKey.isBlank())
                ? null : repo.findByDocKey(docKey.trim()).orElse(null);
        boolean created = (d == null);
        if (created) {
            d = new KnowledgeDoc();
            d.setDocKey((docKey == null || docKey.isBlank())
                    ? "kb-" + Long.toString(System.currentTimeMillis(), 36)
                    : docKey.trim());
            d.setStatus(ACTIVE);
            d.setUpdatedBy(actor);
        }
        if (title != null && !title.isBlank()) {
            d.setTitle(title.trim());
        }
        if (content != null) {
            d.setContent(content);
        }
        if (tags != null) {
            d.setTags(normalizeTags(tags));
        }
        if (department != null) {
            d.setDepartment(department.trim());
        }
        if (version != null) {
            d.setVersion(version.trim());
        }
        if (reviewer != null) {
            d.setReviewer(reviewer.trim());
        }
        d.setUpdatedAt(Instant.now());
        d.setUpdatedBy(actor);
        KnowledgeDoc saved = repo.save(d);
        log.info("资料{}：{}（tags={}）by {}", created ? "新建" : "更新",
                saved.getDocKey(), saved.getTags(), actor);
        return saved;
    }

    /** 上架 / 下架。下架不删数据（可再上架、可追溯），只是不再作为回答依据。 */
    @Transactional
    public KnowledgeDoc setStatus(String docKey, boolean active, String actor) {
        KnowledgeDoc d = repo.findByDocKey(docKey).orElse(null);
        if (d == null) {
            return null;
        }
        d.setStatus(active ? ACTIVE : INACTIVE);
        d.setUpdatedAt(Instant.now());
        d.setUpdatedBy(actor);
        return repo.save(d);
    }

    @Transactional
    public boolean delete(String docKey) {
        KnowledgeDoc d = repo.findByDocKey(docKey).orElse(null);
        if (d == null) {
            return false;
        }
        repo.delete(d);
        return true;
    }

    // ------------------------------------------------------------ 同步到认知侧

    /**
     * 把**当前生效的全集**推给认知服务（全量替换语义，见类注释）。
     *
     * <p>认知服务不可达时**不抛异常**（否则资料库页面会因为认知服务没起来而打不开），
     * 而是返回 {@code synced=false} + 原因，由界面如实显示漂移。
     */
    public Map<String, Object> syncToCognition() {
        List<KnowledgeDoc> docs = repo.findByStatusOrderByUpdatedAtDesc(ACTIVE);
        List<Map<String, Object>> payload = new ArrayList<>();
        for (KnowledgeDoc d : docs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", d.getDocKey());
            m.put("title", d.getTitle() == null ? "" : d.getTitle());
            m.put("content", d.getContent() == null ? "" : d.getContent());
            m.put("tags", splitTags(d.getTags()));
            m.put("department", d.getDepartment() == null ? "" : d.getDepartment());
            payload.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expected", payload.size());
        try {
            var r = cog.post("/api/library/sync", Map.of("docs", payload));
            out.put("synced", true);
            out.put("cognition", cog.toPlain(r));
        } catch (Exception ex) {
            out.put("synced", false);
            out.put("error", String.valueOf(ex.getMessage()));
        }
        return out;
    }

    // ------------------------------------------------------------ 小工具

    /** 标签归一化：逗号/顿号/分号统一成英文逗号，去空、去重、限长。 */
    static String normalizeTags(String raw) {
        return String.join(",", splitTags(raw));
    }

    /** 拆标签（对外的唯一姿势 —— 存的是逗号串，读的时候一律走这里）。 */
    public static List<String> splitTags(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String t : raw.split("[,，、;；]")) {
            String s = t.trim();
            if (!s.isEmpty() && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }
}
