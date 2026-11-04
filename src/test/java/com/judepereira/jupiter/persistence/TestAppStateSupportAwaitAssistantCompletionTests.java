package com.judepereira.jupiter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.judepereira.jupiter.ui.ChatPresentationService;
import com.judepereira.jupiter.ui.UiController;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ui.ConcurrentModel;

class TestAppStateSupportAwaitAssistantCompletionTests {

    private static ChatPresentationService.ChatMessage chatMessage(String id, String text) {
        return new ChatPresentationService.ChatMessage("assistant", text, 0, false, id, null, List.of(), null, null);
    }

    @Test
    void returnsFreshMessageAfterStreamCleanup() {
        UiController controller = mock(UiController.class);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            ConcurrentModel model = invocation.getArgument(0);
            switch (calls.incrementAndGet()) {
                case 1 -> model.addAttribute("chatMessages", List.of(chatMessage("assistant-1", "Thinking...")));
                case 2 -> model.addAttribute("activeSession", null);
                case 3 -> model.addAttribute("chatMessages", List.of(chatMessage("assistant-1", "done")));
                default -> throw new AssertionError("Unexpected index call");
            }
            return "index";
        }).when(controller).index(ArgumentMatchers.any(ConcurrentModel.class));

        ChatPresentationService.ChatMessage result = TestAppStateSupport.awaitAssistantCompletion(controller,
                "assistant-1");

        assertThat(result.text()).isEqualTo("done");
        assertThat(calls).hasValue(3);
    }
}
