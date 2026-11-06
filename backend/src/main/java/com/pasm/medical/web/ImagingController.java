package com.pasm.medical.web;

import com.pasm.medical.domain.ImagingStudy;
import com.pasm.medical.service.ImagingArchiveService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 影像「归档 + 转交」接口。
 *
 * <p><b>定位边界（务必别越界）</b>：系统只把患者上传的影像原样存档、登记元数据，
 * 再让医护转交给科室；<b>不提供任何自动分析 / 读片 / 影像所见 / 印象 / 结论</b>。
 * 这不是「还没做」的功能，而是无三类医疗器械资质时<b>刻意不做</b>的事情
 * （见 {@code docs/COMPLIANCE-GATE.md} §3）。
 *
 * <p>因此：
 * <ul>
 *   <li>响应里没有任何结论类字段，并带 {@code noAnalysis=true} 的显式声明；</li>
 *   <li>写入请求带结论类字段 → <b>400 ANALYSIS_FORBIDDEN</b>（不给旁路）；</li>
 *   <li>原始字节只做整块存取 + SHA-256 完整性核对，服务端不解码图像。</li>
 * </ul>
 *
 * <p>授权沿用系统既有的<strong>两层判定</strong>：URL 级（{@code /api/**} 需认证）
 * + 资源级（患者只能碰自己的 {@code ref}；临床侧可跨患者但每次都落读审计）。
 */
@RestController
@RequestMapping("/api/imaging")
public class ImagingController {

    public static final String NOTICE =
            "本系统仅归档与转交影像，不提供任何自动分析或诊断结论；请由具备资质的医师阅片。";

    private final ImagingArchiveService imaging;

    public ImagingController(ImagingArchiveService imaging) {
        this.imaging = imaging;
    }

    /** 归档一条影像检查。body 里带 {@code contentBase64}（原始字节）。 */
    @PostMapping("/studies")
    public ResponseEntity<?> archive(@RequestBody Map<String, Object> body) {
        try {
            ImagingArchiveService.assertNoAnalysisFields(body);
            return ResponseEntity.ok(view(imaging.archive(body)));
        } catch (ImagingArchiveService.Problem p) {
            return problem(p);
        }
    }

    /** 某患者的归档列表。患者不传 ref 即本人；传别人 → 403。 */
    @GetMapping("/studies")
    public ResponseEntity<?> list(@RequestParam(required = false) String ref) {
        try {
            List<Map<String, Object>> out = imaging.list(ref).stream().map(this::view).toList();
            return ResponseEntity.ok(out);
        } catch (ImagingArchiveService.Problem p) {
            return problem(p);
        }
    }

    /** 单条详情（不含原始字节）。 */
    @GetMapping("/studies/{id}")
    public ResponseEntity<?> detail(@PathVariable long id) {
        try {
            return ResponseEntity.ok(view(imaging.requireReadable(id)));
        } catch (ImagingArchiveService.Problem p) {
            return problem(p);
        }
    }

    /** 下载原始字节：不透明传输，服务端不解码、不推理、不产出结论。 */
    @GetMapping("/studies/{id}/content")
    public ResponseEntity<?> content(@PathVariable long id) {
        try {
            ImagingStudy s = imaging.requireReadable(id);
            byte[] bytes = imaging.content(id);
            String safeName = (s.getFileName() == null ? "study.bin" : s.getFileName())
                    .replaceAll("[\\r\\n\"\\\\/]", "_");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(bytes);
        } catch (ImagingArchiveService.Problem p) {
            return problem(p);
        }
    }

    /** 待转交队列（仅医护侧；按数据范围收窄）。 */
    @GetMapping("/queue")
    public ResponseEntity<?> queue() {
        try {
            List<Map<String, Object>> out = imaging.queue().stream().map(this::view).toList();
            return ResponseEntity.ok(out);
        } catch (ImagingArchiveService.Problem p) {
            return problem(p);
        }
    }

    /** 转交给科室（仅医护侧；已转交 → 409）。 */
    @PostMapping("/studies/{id}/handoff")
    public ResponseEntity<?> handoff(@PathVariable long id,
                                     @RequestBody Map<String, Object> body) {
        try {
            ImagingArchiveService.assertNoAnalysisFields(body);
            Object dept = body.get("department");
            Object note = body.get("note");
            ImagingStudy s = imaging.handoff(id,
                    dept == null ? null : String.valueOf(dept),
                    note == null ? null : String.valueOf(note));
            return ResponseEntity.ok(view(s));
        } catch (ImagingArchiveService.Problem p) {
            return problem(p);
        }
    }

    // ---------------------------------------------------------------- 序列化

    /**
     * 响应视图。★ 字段清单是白名单：即便将来实体加了字段，也不会自动泄漏到接口；
     * 结论类字段更是一个都没有。
     */
    private Map<String, Object> view(ImagingStudy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("patientRef", s.getPatientRef());
        m.put("modality", s.getModality());
        m.put("bodyPart", s.getBodyPart());
        m.put("studyDate", s.getStudyDate() == null ? "" : s.getStudyDate());
        m.put("accessionNo", s.getAccessionNo() == null ? "" : s.getAccessionNo());
        m.put("department", s.getDepartment() == null ? "" : s.getDepartment());
        m.put("fileName", s.getFileName());
        m.put("contentType", s.getContentType());
        m.put("byteSize", s.getByteSize());
        m.put("sha256", s.getSha256());
        m.put("status", s.getStatus());
        m.put("handoffDepartment", s.getHandoffDepartment() == null ? "" : s.getHandoffDepartment());
        m.put("handoffNote", s.getHandoffNote() == null ? "" : s.getHandoffNote());
        m.put("handoffBy", s.getHandoffBy() == null ? "" : s.getHandoffBy());
        m.put("handoffAt", s.getHandoffAt() == null ? "" : s.getHandoffAt().toString());
        m.put("uploadedBy", s.getUploadedBy());
        m.put("createdAt", s.getCreatedAt() == null ? "" : s.getCreatedAt().toString());
        // ★ 显式声明「没有分析」，而不是让前端从「缺字段」去猜
        m.put("noAnalysis", true);
        m.put("notice", NOTICE);
        return m;
    }

    private static ResponseEntity<Map<String, Object>> problem(ImagingArchiveService.Problem p) {
        return ResponseEntity.status(p.status()).body(Map.of(
                "error", p.getMessage() == null ? p.code() : p.getMessage(),
                "code", p.code()));
    }
}
