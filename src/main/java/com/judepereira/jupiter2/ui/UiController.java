package com.judepereira.jupiter2.ui;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Controller
public class UiController {

    private final List<ChatMessage> chat = new CopyOnWriteArrayList<>();
    private final List<ChangedFile> changedFiles = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextFileId = new AtomicInteger(1);
    private final AtomicBoolean reviewPanelOpen = new AtomicBoolean(false);
    private volatile ChangedFile selectedFile = null;

    public UiController() {
        // seed with a welcome message
        chat.add(new ChatMessage("system", "Welcome to Jupiter UI shell", Instant.now().toEpochMilli()));
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("chatMessages", List.copyOf(chat));
        model.addAttribute("changedFiles", List.copyOf(changedFiles));
        model.addAttribute("reviewPanelOpen", reviewPanelOpen.get());
        model.addAttribute("selectedFile", selectedFile);
        return "index";
    }

    @PostMapping("/ui/chat/send")
    public String sendMessage(@RequestParam("message") String message, Model model, HttpServletRequest request) {
        if (message != null && !message.isBlank()) {
            chat.add(new ChatMessage("user", message.trim(), Instant.now().toEpochMilli()));
            // simulate assistant response
            String assistantText = simulateAssistantResponse(message.trim());
            chat.add(new ChatMessage("assistant", assistantText, Instant.now().toEpochMilli()));

            // simulate changed file/update: add a new changed file and set its diff as selected
            int id = nextFileId.getAndIncrement();
            ChangedFile cf = new ChangedFile(id, "src/Main" + id + ".java", "+ line added\n- line removed\n context\n");
            changedFiles.add(0, cf); // newest first
            selectedFile = cf;
            reviewPanelOpen.set(true);
        }

        model.addAttribute("chatMessages", List.copyOf(chat));
        model.addAttribute("changedFiles", List.copyOf(changedFiles));
        model.addAttribute("reviewPanelOpen", reviewPanelOpen.get());
        model.addAttribute("selectedFile", selectedFile);

        // return the chat fragment (HTMX will swap into chat area). Templates/fragments/chat.html expected
        return "fragments/chat :: chat";
    }

    @GetMapping("/ui/review/file/{id}")
    public String loadFile(@PathVariable("id") int id, Model model) {
        ChangedFile found = changedFiles.stream().filter(f -> f.id() == id).findFirst().orElse(null);
        if (found != null) {
            selectedFile = found;
        }
        model.addAttribute("selectedFile", selectedFile);
        model.addAttribute("reviewPanelOpen", reviewPanelOpen.get());
        return "fragments/file-diff :: diff";
    }

    @PostMapping("/ui/review/toggle")
    public String toggleReview(Model model) {
        // AtomicBoolean.updateAndGet may not be available on older compilers/JDKs.
        // Use a CAS loop to toggle the boolean and obtain the new value atomically.
        boolean now;
        boolean prev;
        do {
            prev = reviewPanelOpen.get();
        } while (!reviewPanelOpen.compareAndSet(prev, !prev));
        now = !prev;
        model.addAttribute("reviewPanelOpen", now);
        model.addAttribute("changedFiles", List.copyOf(changedFiles));
        model.addAttribute("selectedFile", selectedFile);
        return "fragments/review :: panel";
    }

    @GetMapping("/ui/panel/{name}")
    public String panelPlaceholder(@PathVariable String name, Model model) {
        model.addAttribute("panelName", name);
        return "fragments/panel :: panel";
    }

    private String simulateAssistantResponse(String prompt) {
        // minimal deterministic simulation
        return "Assistant reply to: '" + prompt + "'";
    }

    // simple records for view models
    public static record ChatMessage(String role, String text, long ts) {}

    public static record ChangedFile(int id, String path, String diff) {}
}
