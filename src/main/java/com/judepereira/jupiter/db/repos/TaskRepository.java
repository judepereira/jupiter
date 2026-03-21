package com.judepereira.jupiter.db.repos;

import com.judepereira.jupiter.db.entities.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {
    Optional<Task> findBySlugIgnoreCase(String slug);

    boolean existsBySlugIgnoreCase(String slug);
}
