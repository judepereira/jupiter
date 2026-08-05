package com.judepereira.jupiter.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UiControllerChatMessageGroupingTests {

    @Test
    void exploratoryToolCallsGroupAcrossNamesAndAggregateSuccess() {
        UiController.ChatMessage message = new UiController.ChatMessage("assistant", "thinking", 1L, false, "assistant-1", List.of(
                toolCall("read-1", "read_file", true),
                toolCall("read-2", "read_file", false),
                toolCall("list-1", "list_files", true),
                toolCall("task-1", "task", true),
                toolCall("list-2", "list_files", true)
        ), null);

        List<UiController.ToolCallGroupView> groups = message.toolCallGroups();

        assertThat(groups).hasSize(3);

        assertThat(groups.get(0).displayLabel()).isEqualTo("read_file (2), list_files");
        assertThat(groups.get(0).count()).isEqualTo(3);
        assertThat(groups.get(0).success()).isFalse();

        assertThat(groups.get(1).displayLabel()).isEqualTo("task");
        assertThat(groups.get(1).count()).isEqualTo(1);
        assertThat(groups.get(1).success()).isTrue();

        assertThat(groups.get(2).displayLabel()).isEqualTo("list_files");
        assertThat(groups.get(2).count()).isEqualTo(1);
        assertThat(groups.get(2).success()).isTrue();
    }

    @Test
    void adjacentNonExploratorySameNameCallsStillGroupAsBefore() {
        UiController.ChatMessage message = new UiController.ChatMessage("assistant", "thinking", 1L, false, "assistant-1", List.of(
                toolCall("write-1", "write_file", true),
                toolCall("write-2", "write_file", true)
        ), null);

        List<UiController.ToolCallGroupView> groups = message.toolCallGroups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).displayLabel()).isEqualTo("write_file (2)");
        assertThat(groups.get(0).count()).isEqualTo(2);
        assertThat(groups.get(0).success()).isTrue();
    }

    private static UiController.ToolCallView toolCall(String id, String toolName, boolean success) {
        return new UiController.ToolCallView(id, toolName, success, "input", "output", false, false, null, null, null);
    }
}
