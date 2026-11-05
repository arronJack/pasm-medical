package com.pasm.medical.repository;

import com.pasm.medical.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /** 患者在某个进行中的会话（有则复用，避免反复点「转人工」刷出一堆会话）。 */
    Optional<ChatSession> findFirstByPatientRefAndStatusInOrderByCreatedAtDesc(
            String patientRef, Collection<String> statuses);

    /** 科室队列：WAITING 与本科室 HUMAN。 */
    List<ChatSession> findByDepartmentAndStatusInOrderByUpdatedAtDesc(
            String department, Collection<String> statuses);

    /** 全院队列（超管）。 */
    List<ChatSession> findByStatusInOrderByUpdatedAtDesc(Collection<String> statuses);
}
