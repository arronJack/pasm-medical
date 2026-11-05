package com.pasm.medical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 科室（可配置实体）。
 *
 * <p>★ 为什么不是枚举：科室在各医院不同、会被改名/合并/停用。写死成枚举意味着
 * 每次调整都要发版；写死成字符串则拼错不报错，只是"永远匹配不上"（静默失效）。
 *
 * <p>{@code active=false} 的科室不再出现在登录选择与新建会话里，但**历史数据仍保留**
 * —— 停用不是删除，历史就诊/资料仍要能按原科室名查到。
 */
@Entity
@Table(name = "departments")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    /** 展示排序（小的在前），同级按名称。 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
