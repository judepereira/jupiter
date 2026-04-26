package com.judepereira.jupiter.db.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "conversations", indexes = {@Index(columnList = "task_id, created_at", name = "idx_conversations_task_created_at")})
public class Conversation {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @NotNull
    @ManyToOne
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @NotNull
    @Column(nullable = false)
    private String role;

    @NotNull
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @NotNull
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "tool_name")
    private String toolName;

    @Column(name = "tool_args_payload", columnDefinition = "TEXT")
    private String toolArgsPayload;

    @Column(name = "tool_result_payload", columnDefinition = "TEXT")
    private String toolResultPayload;

    @Column(name = "tool_error_payload", columnDefinition = "TEXT")
    private String toolErrorPayload;

    @Column(name = "tool_started_at")
    private Instant toolStartedAt;

    @Column(name = "tool_duration_millis")
    private Long toolDurationMillis;

    public Conversation(Task task, String role, String content, Instant createdAt) {
        this.task = task;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }

    public Conversation(Task task, String role, String content, Instant createdAt,
                        String toolName, String toolArgsPayload, String toolResultPayload, String toolErrorPayload,
                        Instant toolStartedAt, Long toolDurationMillis) {
        this.task = task;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
        this.toolName = toolName;
        this.toolArgsPayload = toolArgsPayload;
        this.toolResultPayload = toolResultPayload;
        this.toolErrorPayload = toolErrorPayload;
        this.toolStartedAt = toolStartedAt;
        this.toolDurationMillis = toolDurationMillis;
    }
}
