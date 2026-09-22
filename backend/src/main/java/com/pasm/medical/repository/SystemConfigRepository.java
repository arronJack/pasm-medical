package com.pasm.medical.repository;

import com.pasm.medical.domain.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/** 机构级对接设置仓储。主键是配置项名，天然幂等。 */
public interface SystemConfigRepository extends JpaRepository<SystemConfig, String> {
}
