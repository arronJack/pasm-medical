package com.pasm.medical.web;

import com.pasm.medical.service.DepartmentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 科室只读接口。
 *
 * <p>{@code /public} 供**登录页**加载下拉选项 —— 登录前还没有令牌，所以必须匿名可读；
 * 它只返回启用中的科室名称，不含任何患者/配置信息，泄露面为零。
 */
@RestController
@RequestMapping("/api/departments")
public class DepartmentController {

    private final DepartmentService departments;

    public DepartmentController(DepartmentService departments) {
        this.departments = departments;
    }

    /** 当前登录人可选的科室（启用中）。 */
    @GetMapping
    public List<Map<String, Object>> list() {
        return departments.listActive().stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("name", d.getName());
            m.put("sortOrder", d.getSortOrder());
            return m;
        }).toList();
    }

    /** 登录页专用（匿名）：只给名称数组。 */
    @GetMapping("/public")
    public List<String> publicList() {
        return departments.listActive().stream().map(d -> d.getName()).toList();
    }
}
