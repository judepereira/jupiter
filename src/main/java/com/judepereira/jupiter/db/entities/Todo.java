package com.judepereira.jupiter.db.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "todos", indexes = {
        @Index(columnList = "task_id", name = "idx_todo_task"),
        @Index(columnList = "created_at", name = "idx_todo_created_at")
})
public class Todo {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @NotBlank
    @Size(max = 1024)
    @Column(nullable = false, length = 1024)
    private String text;

    public Todo(Task task, String text) {
        this.task = task;
        this.text = text;
        this.createdAt = Instant.now();
    }
}
