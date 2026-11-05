package com.pasm.medical.service;

import com.pasm.medical.domain.Department;
import com.pasm.medical.repository.ChatSessionRepository;
import com.pasm.medical.repository.DepartmentRepository;
import com.pasm.medical.repository.EncounterRepository;
import com.pasm.medical.repository.KnowledgeDocRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 科室配置（总后台维护，登录/坐诊按它校验与展示）。 */
@Service
public class DepartmentService {

    private final DepartmentRepository repo;
    private final EncounterRepository encounters;
    private final KnowledgeDocRepository kb;
    private final ChatSessionRepository chats;

    public DepartmentService(DepartmentRepository repo, EncounterRepository encounters,
                             KnowledgeDocRepository kb, ChatSessionRepository chats) {
        this.repo = repo;
        this.encounters = encounters;
        this.kb = kb;
        this.chats = chats;
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
     * <p>★ <b>已有历史数据的科室不允许删除</b>：就诊/资料/会话里存的是**科室名称**，
     * 删掉主数据后这些历史会变成无法解释的孤儿字符串（统计口径、按科室授权、审计回溯全部失真）。
     * 正确做法是「停用」—— 停用只影响新选择，历史仍在。
     */
    @Transactional
    public void delete(Long id) {
        Department d = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("科室不存在：" + id));
        if (encounters.existsByDepartment(d.getName())
                || kb.existsByDepartment(d.getName())
                || chats.existsByDepartment(d.getName())) {
            throw new IllegalArgumentException(
                    "科室「" + d.getName() + "」已有历史数据，不能删除；请改用「停用」");
        }
        repo.delete(d);
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
