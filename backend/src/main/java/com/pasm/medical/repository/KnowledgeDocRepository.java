package com.pasm.medical.repository;

import com.pasm.medical.domain.KnowledgeDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeDocRepository extends JpaRepository<KnowledgeDoc, Long> {

    /** 资料库列表（新改的排前面）。 */
    List<KnowledgeDoc> findAllByOrderByUpdatedAtDesc();

    /** **只有生效资料**才会被同步到认知侧（下架 = 不进检索副本）。 */
    List<KnowledgeDoc> findByStatusOrderByUpdatedAtDesc(String status);

    /** 本科室资料（按更新时间倒序）。P1-2：资料库按科室授权后，科室管理员只看这一批。 */
    List<KnowledgeDoc> findByDepartmentOrderByUpdatedAtDesc(String department);

    Optional<KnowledgeDoc> findByDocKey(String docKey);

    /** 科室是否已有资料（删除科室前拦住）。 */
    boolean existsByDepartment(String department);

    long countByStatus(String status);

    /** 按版本号查资料（知识池回滚时定位"上一版本引入的资料"）。 */
    List<KnowledgeDoc> findByVersion(String version);

    /** 某版本号的资料条数（知识池版本信息用）。 */
    long countByVersion(String version);
}
