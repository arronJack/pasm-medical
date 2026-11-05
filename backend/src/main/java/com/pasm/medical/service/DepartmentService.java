package com.pasm.medical.service;

import com.pasm.medical.domain.Department;
import com.pasm.medical.repository.DepartmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 科室配置（总后台维护，登录/坐诊按它校验与展示）。 */
@Service
public class DepartmentService {

    private final DepartmentRepository repo;

    public DepartmentService(DepartmentRepository repo) {
        this.repo = repo;
    }

    public List<Department> listAll() {
        return repo.findAllByOrderBySortOrderAscNameAsc();
    }

    public List<Department> listActive() {
        return repo.findByActiveTrueOrderBySortOrderAscNameAsc();
    }

    public Optional<Department> findActiveByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return repo.findByNameIgnoreCase(name.trim()).filter(Department::isActive);
    }

    public boolean existsActive(String name) {
        return findActiveByName(name).isPresent();
    }

    @Transactional
    public Department create(String name, Integer sortOrder) {
        String n = requireName(name);
        if (repo.existsByNameIgnoreCase(n)) {
            throw new IllegalArgumentException("科室已存在：" + n);
        }
        Department d = new Department();
        d.setName(n);
        d.setSortOrder(sortOrder == null ? 100 : sortOrder);
        d.setActive(true);
        d.setCreatedAt(Instant.now());
        return repo.save(d);
    }

    @Transactional
    public Department update(Long id, String name, Boolean active, Integer sortOrder) {
        Department d = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("科室不存在：" + id));
        if (name != null && !name.isBlank()) {
            String n = requireName(name);
            repo.findByNameIgnoreCase(n).ifPresent(other -> {
                if (!other.getId().equals(id)) {
                    throw new IllegalArgumentException("科室已存在：" + n);
                }
            });
            d.setName(n);
        }
        if (active != null) {
            d.setActive(active);
        }
        if (sortOrder != null) {
            d.setSortOrder(sortOrder);
        }
        return repo.save(d);
    }

    /**
     * 删除科室。
     *
     * <p>★ 只允许删除**从未被使用**的科室（这里不做跨表引用统计，采用更保守的规则：
     * 停用优先）。真正承载历史数据的科室应停用而不是删除 —— 删掉会让历史就诊/资料
     * 按名称回查不到。调用方（界面）默认提供"停用"，删除只用于配错的脏数据。
     */
    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) {
            throw new IllegalArgumentException("科室不存在：" + id);
        }
        repo.deleteById(id);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("科室名称不能为空");
        }
        String n = name.trim();
        if (n.length() > 64) {
            throw new IllegalArgumentException("科室名称过长（最多 64 字）");
        }
        return n;
    }
}
