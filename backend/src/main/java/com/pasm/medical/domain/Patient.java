package com.pasm.medical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 患者（业务层主键）。
 *
 * <p><b>不要传真实姓名</b>：认知层会把 patient_ref 拼进落盘目录名，
 * 真实标识会出现在日志与备份里。这里存的是业务库主键 / 假名。
 *
 * <p>过敏史 / 慢病是高优先级事实 —— 在认知层用 salience=5 防止被闲聊挤出上下文；
 * 这里同时落结构化字段，便于后台检索与展示。
 */
@Entity
@Table(name = "patients")
public class Patient {

    @Id
    @Column(length = 64, nullable = false)
    private String ref;

    @Column(length = 64)
    private String name;

    @Column(length = 8)
    private String sex;

    @Column
    private Integer age;

    @Column(length = 200)
    private String allergy;

    @Column(length = 200)
    private String chronic;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public String getRef() { return ref; }
    public void setRef(String ref) { this.ref = ref; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSex() { return sex; }
    public void setSex(String sex) { this.sex = sex; }

    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }

    public String getAllergy() { return allergy; }
    public void setAllergy(String allergy) { this.allergy = allergy; }

    public String getChronic() { return chronic; }
    public void setChronic(String chronic) { this.chronic = chronic; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
