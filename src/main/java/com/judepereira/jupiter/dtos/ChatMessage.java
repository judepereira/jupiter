package com.judepereira.jupiter.dtos;

import com.judepereira.jupiter.ui.TaskContext;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.ai.chat.messages.Message;
import java.util.UUID;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ChatMessage {
    @EqualsAndHashCode.Include
    private final String id = UUID.randomUUID().toString();

    private final TaskContext taskContext;

    private Message message;

    private ToolCallTrace toolTrace;

    public ChatMessage(Message message, TaskContext taskContext) {
        this.message = message;
        this.taskContext = taskContext;
    }

    public ChatMessage(ToolCallTrace toolTrace, TaskContext taskContext) {
        this.taskContext = taskContext;
        this.toolTrace = toolTrace;
    }
}
