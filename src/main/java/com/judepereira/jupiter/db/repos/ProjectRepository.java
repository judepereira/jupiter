package com.judepereira.jupiter.db.repos;

import com.judepereira.jupiter.db.project.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    boolean existsByPathIgnoreCase(String path);

    Optional<Project> findByPathIgnoreCase(String path);
}
