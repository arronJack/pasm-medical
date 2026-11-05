package com.pasm.medical.repository;

import com.pasm.medical.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 增量拉取：只取 afterId 之后的消息（轮询不重复、不丢消息）。 */
    List<ChatMessage> findBySessionIdAndIdGreaterThanOrderByIdAsc(Long sessionId, Long afterId);

    List<ChatMessage> findAllBySessionIdOrderByIdAsc(Long sessionId);
}
