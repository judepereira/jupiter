package com.judepereira.jupiter.ui;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Controller
@RequiredArgsConstructor
public class InfoMessageStreamController {

    private final InfoMessageDeliveryService infoMessageDeliveryService;

    @GetMapping("/ui/chat/info/stream")
    public SseEmitter infoMessageStream() {
        return infoMessageDeliveryService.connect();
    }
}
