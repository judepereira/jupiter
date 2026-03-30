package com.judepereira.jupiter.db.repos;

import com.judepereira.jupiter.db.entities.Todo;
import com.judepereira.jupiter.db.entities.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TodoRepository extends JpaRepository<Todo, Long> {
    List<Todo> findAllByTaskOrderByCreatedAtAsc(Task task);

    Optional<Todo> findByIdAndTask(Long id, Task task);
}
