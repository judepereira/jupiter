package com.judepereira.jupiter.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.security.RuntimeEnvironment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

public class TerminalManagerReplayBufferTests {

    @Test
    public void attachReplaysBufferedOutputToNewSession() throws Exception {
        TerminalManager manager = new TerminalManager(new ObjectMapper(), List.of(), new RuntimeEnvironment(Map.of()));
        TerminalManager.TerminalRuntime runtime = manager.new TerminalRuntime("terminal-1", "Terminal 1", null);

        runtime.appendOutput("hello from replay");

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);

        runtime.attach(session);

        var captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("hello from replay", "\"type\":\"output\"");
    }

}
