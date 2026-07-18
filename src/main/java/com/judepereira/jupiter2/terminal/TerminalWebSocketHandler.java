package com.judepereira.jupiter2.terminal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

@Log4j2
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private final TerminalManager terminalManager;
    private final ObjectMapper objectMapper;

    public TerminalWebSocketHandler(TerminalManager terminalManager, ObjectMapper objectMapper) {
        this.terminalManager = terminalManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String terminalId = terminalId(session);
        if (terminalId == null || !terminalManager.hasTerminal(terminalId)) {
            sendErrorAndClose(session, "Unknown terminal");
            return;
        }
        terminalManager.attach(terminalId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String terminalId = terminalId(session);
        if (terminalId == null || !terminalManager.hasTerminal(terminalId)) {
            sendErrorAndClose(session, "Unknown terminal");
            return;
        }

        TerminalSocketMessage socketMessage;
        try {
            socketMessage = objectMapper.readValue(message.getPayload(), TerminalSocketMessage.class);
        } catch (Exception e) {
            sendErrorAndClose(session, "Invalid terminal message");
            return;
        }
        if (socketMessage.type == null) {
            sendErrorAndClose(session, "Missing message type");
            return;
        }

        try {
            switch (socketMessage.type) {
                case "input" -> terminalManager.write(terminalId, socketMessage.data == null ? "" : socketMessage.data);
                case "resize" -> {
                    if (socketMessage.cols == null || socketMessage.rows == null) {
                        sendErrorAndClose(session, "Missing terminal size");
                        return;
                    }
                    terminalManager.resize(terminalId, socketMessage.cols, socketMessage.rows);
                }
                default -> sendErrorAndClose(session, "Unknown message type");
            }
        } catch (Exception e) {
            log.error("Terminal websocket command failed", e);
            sendErrorAndClose(session, e.getMessage() == null ? "Terminal command failed" : e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String terminalId = terminalId(session);
        if (terminalId != null) {
            terminalManager.detach(terminalId, session);
        }
    }

    private void sendErrorAndClose(WebSocketSession session, String message) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("type", "error", "message", message))));
        } catch (Exception e) {
            log.error("Failed to send websocket error", e);
        }
        try {
            session.close(CloseStatus.BAD_DATA);
        } catch (Exception e) {
            log.error("Failed to close websocket session", e);
        }
    }

    private String terminalId(WebSocketSession session) {
        Object value = session.getAttributes().get("terminalId");
        return value == null ? null : value.toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TerminalSocketMessage(String type, String data, Integer cols, Integer rows) {}
}
