package com.pasm.medical.web;

import com.pasm.medical.domain.Department;
import com.pasm.medical.service.DepartmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 科室管理 —— **仅超级管理员**（URL 级由 SecurityConfig 限定）。
 *
 * <p>为什么不让科室管理员维护自己的科室：科室是全局主数据，改名/停用会影响
 * 历史就诊归属、资料库授权、统计口径与候选审核范围。这是"全院级"操作，
 * 与"本科室资料库"不是一类权限。
 */
@RestController
@RequestMapping("/api/admin/departments")
public class AdminDepartmentController {

    private final DepartmentService departments;

    public AdminDepartmentController(DepartmentService departments) {
        this.departments = departments;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return departments.listAll().stream().map(this::body).toList();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody UpsertRequest req) {
        try {
            return ResponseEntity.ok(body(departments.create(req.name(), req.sortOrder())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody UpsertRequest req) {
        try {
            return ResponseEntity.ok(body(departments.update(id, req.name(), req.active(), req.sortOrder())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            departments.delete(id);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> body(Department d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("active", d.isActive());
        m.put("sortOrder", d.getSortOrder());
        m.put("createdAt", d.getCreatedAt() == null ? "" : d.getCreatedAt().toString());
        return m;
    }

    public record UpsertRequest(String name, Boolean active, Integer sortOrder) {
    }
}
