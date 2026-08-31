package com.judepereira.jupiter.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UiControllerChatMessageGroupingTests {

    @Test
    void exploratoryToolCallsGroupAcrossNamesAndAggregateSuccess() {
        ChatPresentationService.ChatMessage message = new ChatPresentationService.ChatMessage("assistant", "thinking", 1L, false, "assistant-1", null, List.of(
                toolCall("read-1", "read_file", true),
                toolCall("read-2", "read_file", false),
                toolCall("list-1", "list_files", true),
                toolCall("task-1", "task", true),
                toolCall("list-2", "list_files", true)
        ), null);

        List<ChatPresentationService.ToolCallGroupView> groups = message.toolCallGroups();

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
        ChatPresentationService.ChatMessage message = new ChatPresentationService.ChatMessage("assistant", "thinking", 1L, false, "assistant-1", null, List.of(
                toolCall("write-1", "write_file", true),
                toolCall("write-2", "write_file", true)
        ), null);

        List<ChatPresentationService.ToolCallGroupView> groups = message.toolCallGroups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).displayLabel()).isEqualTo("write_file (2)");
        assertThat(groups.get(0).count()).isEqualTo(2);
        assertThat(groups.get(0).success()).isTrue();
    }

    @Test
    void toolCallBlocksPreserveChronologicalOrderAcrossStandaloneSpecialTools() {
        ChatPresentationService.ChatMessage message = new ChatPresentationService.ChatMessage("assistant", "thinking", 1L, false, "assistant-1", null, List.of(
                toolCall("read-1", "read_file", true),
                toolCall("read-2", "read_file", true),
                toolCall("task-1", "task", true),
                toolCall("read-3", "read_file", true)
        ), null);

        List<ChatPresentationService.ToolCallBlockView> blocks = message.toolCallBlocks();

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).bundle().summaryLabel()).isEqualTo("Used: read_file (2)");
        assertThat(blocks.get(1).group().toolName()).isEqualTo("task");
        assertThat(blocks.get(2).bundle().summaryLabel()).isEqualTo("Used: read_file");
    }

    @Test
    void presentationModelsOwnStableSafeDomIds() {
        ChatPresentationService.ChatMessage message = new ChatPresentationService.ChatMessage("assistant", "", 1L, false,
                "assistant / ü", null, List.of(toolCall("call / ü", "read_file", true)), null);

        assertThat(message.toolCallHostId()).isEqualTo("assistant-tool-calls-assistant-20-2f-20-c3-bc");
        assertThat(message.toolCallBlocks().get(0).bundle().domId(message.id()))
                .isEqualTo("assistant-tool-bundle-assistant-20-2f-20-c3-bc-call-20-2f-20-c3-bc");
        assertThat(message.toolCallBlocks().get(0).bundle().groups().get(0).domId(message.id()))
                .isEqualTo("assistant-tool-group-assistant-20-2f-20-c3-bc-call-20-2f-20-c3-bc");
        assertThat(message.toolCallBlocks().get(0).bundle().groups().get(0).calls().get(0).domId(message.id()))
                .isEqualTo("assistant-tool-call-assistant-20-2f-20-c3-bc-call-20-2f-20-c3-bc");
    }

    @Test
    void emptyAssistantToolCallHostIsStableAndHasNoToolCallChildren() {
        ChatPresentationService.ChatMessage message = new ChatPresentationService.ChatMessage("assistant", "", 1L, false,
                "assistant-1", null, List.of(), null);

        assertThat(message.toolCallHostId()).isEqualTo("assistant-tool-calls-assistant-1");
        assertThat(message.toolCallBlocks()).isEmpty();
    }

    private static ChatPresentationService.ToolCallView toolCall(String id, String toolName, boolean success) {
        return new ChatPresentationService.ToolCallView(id, toolName, success, "input", "output", false, false, null, null, null);
    }
}
