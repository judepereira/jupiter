package com.judepereira.jupiter.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TerminalManagerReplayBufferTests {

    @Test
    public void attachReplaysBufferedOutputToNewSession() throws Exception {
        TerminalManager manager = new TerminalManager(new ObjectMapper(), List.of());
        Object runtime = newRuntime(manager);

        invoke(runtime, "appendOutput", "hello from replay");

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);

        invoke(runtime, "attach", session);

        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("hello from replay", "\"type\":\"output\"");
    }

    private static Object newRuntime(TerminalManager manager) throws Exception {
        Class<?> runtimeClass = null;
        for (Class<?> nested : TerminalManager.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("TerminalRuntime")) {
                runtimeClass = nested;
                break;
            }
        }
        if (runtimeClass == null) {
            throw new IllegalStateException("TerminalRuntime not found");
        }

        for (Constructor<?> constructor : runtimeClass.getDeclaredConstructors()) {
            constructor.setAccessible(true);
            if (constructor.getParameterCount() == 4) {
                return constructor.newInstance(manager, "terminal-1", "Terminal 1", null);
            }
            if (constructor.getParameterCount() == 3) {
                return constructor.newInstance("terminal-1", "Terminal 1", null);
            }
        }

        throw new IllegalStateException("Unsupported TerminalRuntime constructor");
    }

    private static void invoke(Object target, String methodName, Object argument) throws Exception {
        Method method = null;
        for (Method candidate : target.getClass().getDeclaredMethods()) {
            if (!candidate.getName().equals(methodName) || candidate.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameterType = candidate.getParameterTypes()[0];
            if (argument == null || parameterType.isInstance(argument) || parameterType.isAssignableFrom(argument.getClass())) {
                method = candidate;
                break;
            }
        }
        if (method == null) {
            throw new IllegalStateException("Method not found: " + methodName);
        }
        method.setAccessible(true);
        method.invoke(target, argument);
    }
}
