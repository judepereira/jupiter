package com.judepereira.jupiter.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByTaskOrderByCreatedAtAsc(Task task);

    void deleteByTask(Task task);
}
