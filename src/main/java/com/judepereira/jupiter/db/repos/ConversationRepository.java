package com.judepereira.jupiter.db.repos;

import com.judepereira.jupiter.db.entities.Conversation;
import com.judepereira.jupiter.db.entities.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByTaskOrderByCreatedAtAsc(Task task);

    void deleteByTask(Task task);
}
