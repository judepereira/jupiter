package com.judepereira.jupiter2.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.harness.ToolCallTrace;
import com.judepereira.jupiter2.agent.llm.AgentStreamListener;
import com.judepereira.jupiter2.agent.tools.impl.FileUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Log4j2
@Controller
public class UiController {

    private final CodingAgentHarness harness;
    private final AgentProperties agentProperties;

    private final List<ChatMessage> chat = new CopyOnWriteArrayList<>();
    private final List<ChangedFile> changedFiles = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextFileId = new AtomicInteger(1);
    private final AtomicBoolean reviewPanelOpen = new AtomicBoolean(false);
    private volatile ChangedFile selectedFile = null;
    private final Executor agentExecutor;
    // pending streaming jobs keyed by assistantId -> AgentTurnRequest
    private final ConcurrentMap<String, AgentTurnRequest> pendingStreams = new ConcurrentHashMap<>();

    // Primary constructor used by Spring (agentTaskExecutor bean should be provided).
    @Autowired
    public UiController(CodingAgentHarness harness, AgentProperties agentProperties, @Qualifier("agentTaskExecutor") Executor agentExecutor) {
        this.harness = harness;
        this.agentProperties = agentProperties;
        this.agentExecutor = agentExecutor;
        // seed with a welcome message
        chat.add(new ChatMessage("system", "Welcome to Jupiter", Instant.now().toEpochMilli(), false, UUID.randomUUID().toString()));
    }

    // Jackson mapper for safe JSON serialization of SSE payloads
    private static final ObjectMapper SseJson = new ObjectMapper();

    // Convenience constructor for tests or simple instantiation. Uses a single-thread daemon executor to avoid
    // leaking non-daemon threads when tests finish.
    public UiController(CodingAgentHarness harness, AgentProperties agentProperties) {
        this(harness, agentProperties, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("ui-controller-test-exec");
            return t;
        }));
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("chatMessages", List.copyOf(chat));
        model.addAttribute("changedFiles", List.copyOf(changedFiles));
        model.addAttribute("reviewPanelOpen", reviewPanelOpen.get());
        model.addAttribute("selectedFile", selectedFile);
        model.addAttribute("hasPending", false);
        model.addAttribute("reviewOob", false);
        return "index";
    }

    @PostMapping("/ui/chat/send")
    public String sendMessage(@RequestParam("message") String message, Model model, HttpServletRequest request) {
        // collect only newly created chat messages for append response
        List<ChatMessage> newChatMessages = new ArrayList<>();
        if (message != null && !message.isBlank()) {
            String user = message.trim();
            ChatMessage userMsg = new ChatMessage("user", user, Instant.now().toEpochMilli(), false, UUID.randomUUID().toString());
            chat.add(userMsg);
            newChatMessages.add(userMsg);

            String assistantId = UUID.randomUUID().toString();
            // add pending assistant message
            ChatMessage pendingAssistant = new ChatMessage("assistant", "Thinking…", Instant.now().toEpochMilli(), true, assistantId);
            chat.add(pendingAssistant);
            newChatMessages.add(pendingAssistant);

            // build concise system prompt for coding agent
            String systemPrompt = "You are a concise coding assistant. Use available tools to inspect and modify the workspace when helpful. Prefer tools for file edits and external commands; return a final assistant message when done.";
            // register a pending stream job; the front-end will connect to /ui/chat/stream/{assistantId}
            pendingStreams.put(assistantId, new AgentTurnRequest(systemPrompt, user));
        }

        // keep full chatMessages model for initial page render / other flows
        model.addAttribute("chatMessages", List.copyOf(chat));
        // supply only newly created rows for the append response
        model.addAttribute("newChatMessages", List.copyOf(newChatMessages));
        model.addAttribute("changedFiles", List.copyOf(changedFiles));
        model.addAttribute("reviewPanelOpen", reviewPanelOpen.get());
        model.addAttribute("selectedFile", selectedFile);
        boolean hasPending = chat.stream().anyMatch(m -> m.pending);
        model.addAttribute("hasPending", hasPending);

        // include an OOB update for the review panel when changed files or review open state may have changed
        // We control via a model flag so the review fragment will render with hx-swap-oob when appropriate.
        boolean reviewOob = !hasPending && reviewPanelOpen.get();
        model.addAttribute("reviewOob", reviewOob);

        // return a composite response fragment that renders only the new rows and optional OOB review fragment
        return "fragments/chat-response :: response";
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

    @GetMapping("/ui/chat/stream/{assistantId}")
    public SseEmitter streamChat(@PathVariable("assistantId") String assistantId) {
        AgentTurnRequest req = pendingStreams.remove(assistantId);
        if (req == null) {
            // no such pending job; return closed emitter
            SseEmitter e = new SseEmitter(0L);
            try {
                String json = SseJson.writeValueAsString(Map.of("message", "no_job"));
                e.send(SseEmitter.event().name("error").data(json));
            } catch (Exception ignored) {
            }
            e.complete();
            return e;
        }

        SseEmitter emitter = new SseEmitter(0L); // no timeout

        // locate the pending assistant chat message and start updating it
        Runnable task = () -> {
            AtomicBoolean done = new AtomicBoolean(false);
            StringBuilder accumulated = new StringBuilder();

            AgentStreamListener listener = new AgentStreamListener() {
                @Override
                public void onTextDelta(String delta) {
                    try {
                        if (delta == null) {
                            return;
                        }
                        accumulated.append(delta);
                        try {
                            String json = SseJson.writeValueAsString(Map.of("text", delta));
                            emitter.send(SseEmitter.event().name("delta").data(json));
                        } catch (JsonProcessingException e) {
                            // fallback to raw string if serialization somehow fails
                            emitter.send(SseEmitter.event().name("delta").data(delta));
                        }
                        // update in-memory chat message text
                        synchronized (chat) {
                            for (int i = 0; i < chat.size(); i++) {
                                ChatMessage m = chat.get(i);
                                if (m.id.equals(assistantId)) {
                                    chat.set(i, new ChatMessage("assistant", accumulated.toString(), m.ts, true, assistantId, m.toolCalls));
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        onError(e);
                    }
                }

                @Override
                public void onStatus(String status) {
                    try {
                        try {
                            String json = SseJson.writeValueAsString(Map.of("status", status));
                            emitter.send(SseEmitter.event().name("status").data(json));
                        } catch (JsonProcessingException e) {
                            emitter.send(SseEmitter.event().name("status").data(status));
                        }
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onToolCallTrace(ToolCallTrace trace) {
                    try {
                        ToolCallView v = traceToView(trace);
                        // append to in-memory pending assistant message's toolCalls
                        synchronized (chat) {
                            for (int i = 0; i < chat.size(); i++) {
                                ChatMessage m = chat.get(i);
                                if (m.id.equals(assistantId)) {
                                    List<ToolCallView> existing = m.toolCalls == null ? List.of() : m.toolCalls;
                                    List<ToolCallView> updated = new ArrayList<>(existing.size() + 1);
                                    updated.addAll(existing);
                                    updated.add(v);
                                    chat.set(i, new ChatMessage("assistant", m.text, m.ts, m.pending, assistantId, List.copyOf(updated)));
                                    break;
                                }
                            }
                        }

                        try {
                            String json = SseJson.writeValueAsString(v);
                            emitter.send(SseEmitter.event().name("tool_call").data(json));
                        } catch (JsonProcessingException e) {
                            emitter.send(SseEmitter.event().name("tool_call").data(v.toString()));
                        }
                    } catch (Exception e) {
                        onError(e);
                    }
                }

                @Override
                public void onComplete(AgentTurnResult result) {
                    try {
                        // mark pending false and set final text
                        synchronized (chat) {
                            for (int i = 0; i < chat.size(); i++) {
                                ChatMessage m = chat.get(i);
                                if (m.id.equals(assistantId)) {
                                    // convert traces to views for final message
                                    List<ToolCallView> views = List.of();
                                    if (result.getTraces() != null) {
                                        views = result.getTraces().stream().map(t -> traceToView(t)).toList();
                                    }
                                    chat.set(i, new ChatMessage("assistant", result.getFinalText(), Instant.now().toEpochMilli(), false, assistantId, views));
                                    break;
                                }
                            }
                        }

                        try {
                            String finalText = result.getFinalText() == null ? "" : result.getFinalText();
                            String json = SseJson.writeValueAsString(Map.of("text", finalText));
                            emitter.send(SseEmitter.event().name("done").data(json));
                        } catch (JsonProcessingException e) {
                            emitter.send(SseEmitter.event().name("done").data(result.getFinalText() == null ? "" : result.getFinalText()));
                        }

                        // process changed files same logic as original sendMessage; extract into helper
                        processChangedFiles(result);

                    } catch (Exception e) {
                        onError(e);
                    } finally {
                        done.set(true);
                        emitter.complete();
                    }
                }

                @Override
                public void onError(Exception e) {
                    try {
                        String normalizedMessage = normalizeProviderErrorMessage(e);
                        synchronized (chat) {
                            for (int i = 0; i < chat.size(); i++) {
                                ChatMessage m = chat.get(i);
                                if (m.id.equals(assistantId)) {
                                    chat.set(i, new ChatMessage("assistant", "Agent execution failed: " + normalizedMessage, Instant.now().toEpochMilli(), false, assistantId, m.toolCalls));
                                    log.error("Execution failure!", e);
                                    break;
                                }
                            }
                        }
                        try {
                            String msg = normalizedMessage;
                            String json = SseJson.writeValueAsString(Map.of("message", msg));
                            emitter.send(SseEmitter.event().name("error").data(json));
                        } catch (JsonProcessingException ex) {
                            emitter.send(SseEmitter.event().name("error").data(normalizedMessage));
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.set(true);
                        emitter.completeWithError(e);
                    }
                }
            };

            try {
                AgentTurnResult result = harness.runTurnStreaming(req, listener);
                if (!done.get()) {
                    listener.onComplete(result);
                }
            } catch (Exception e) {
                listener.onError(e);
            }
        };

        if (agentExecutor instanceof ExecutorService es) {
            es.submit(task);
        } else {
            agentExecutor.execute(task);
        }

        return emitter;
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

    private String safeComputeDiff(String relativePath) {
        final int MAX_CHARS = 16_000; // bound diff size
        // validate path against configured workspace root
        Path workspaceRoot = Path.of(agentProperties.getWorkspaceRoot());
        Path resolved;
        try {
            resolved = FileUtils.resolveWorkspacePath(workspaceRoot, relativePath);
        } catch (Exception e) {
            return "(invalid path: " + relativePath + ")";
        }

        // try git diff -- <path>
        try {
            Path relForGit = FileUtils.relativizeWorkspacePath(workspaceRoot, resolved);
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--", relForGit.toString());
            // use configured workspace root as working dir
            pb.directory(new File(workspaceRoot.toAbsolutePath().normalize().toString()));
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                    if (out.length() > MAX_CHARS) {
                        break;
                    }
                }
            }
            p.waitFor();
            String gitDiff = out.toString().trim();
            if (!gitDiff.isBlank()) {
                return gitDiff.length() > MAX_CHARS ? gitDiff.substring(0, MAX_CHARS) : gitDiff;
            }
        } catch (Exception ignored) {
            // fall back
        }

        // fallback: show file contents snippet or note if missing
        try {
            if (!Files.exists(resolved)) {
                return "(no diff available)";
            }
            String content = Files.readString(resolved);
            if (content.length() > MAX_CHARS) {
                content = content.substring(0, MAX_CHARS) + "\n... (truncated)";
            }
            return "+++ " + resolved + "\n" + content;
        } catch (Exception e) {
            return "(error reading file: " + e.getMessage() + ")";
        }
    }

    private void processChangedFiles(AgentTurnResult result) {
        if (result == null) {
            return;
        }
        var mutatingTools = Set.of("write_file", "apply_patch");
        List<ToolCallTrace> traces = result.getTraces();
        Set<String> seen = new HashSet<>();
        String latestPath = null;
        List<String> addedPathsInOrder = new ArrayList<>();
        for (ToolCallTrace t : traces) {
            if (t.getToolName() == null) {
                continue;
            }
            if (!mutatingTools.contains(t.getToolName())) {
                continue;
            }
            if (!t.isSuccess()) {
                continue;
            }
            Object mp = null;
            if (t.getMachineSummary() != null) {
                mp = t.getMachineSummary().get("path");
            }
            String path = null;
            if (mp instanceof String) {
                path = (String) mp;
            }
            if (path == null || path.isBlank()) {
                continue;
            }
            try {
                Path workspaceRoot = Path.of(agentProperties.getWorkspaceRoot());
                Path resolved = FileUtils.resolveWorkspacePath(workspaceRoot, path);
                Path rel = FileUtils.relativizeWorkspacePath(workspaceRoot, resolved);
                String relStr = rel.toString();
                if (!seen.contains(relStr)) {
                    seen.add(relStr);
                    addedPathsInOrder.add(relStr);
                    latestPath = relStr;
                }
            } catch (Exception e) {
            }
        }

        for (String p : addedPathsInOrder) {
            String diff = safeComputeDiff(p);
            int id = nextFileId.getAndIncrement();
            ChangedFile cf = new ChangedFile(id, p, diff);
            changedFiles.add(0, cf);
        }

        if (!addedPathsInOrder.isEmpty()) {
            reviewPanelOpen.set(true);
            String sel = latestPath;
            if (sel != null) {
                ChangedFile found = changedFiles.stream().filter(f -> f.path().equals(sel)).findFirst().orElse(null);
                if (found != null) {
                    selectedFile = found;
                }
            }
        }
    }

    private String normalizeProviderErrorMessage(Exception e) {
        String message = e == null ? null : e.getMessage();
        if (message == null || message.isBlank()) {
            return "error";
        }

        String rawJson = extractJsonObject(message);
        if (rawJson != null) {
            try {
                ProviderErrorPayload payload = SseJson.readValue(rawJson, ProviderErrorPayload.class);
                String errorMessage = payload.error() == null ? null : payload.error().message();
                if (errorMessage != null && !errorMessage.isBlank()) {
                    log.warn("Provider error payload: {}", rawJson);
                    return errorMessage;
                }
            } catch (Exception ignored) {
                // fall back to the original message below
            }
        }

        return message;
    }

    private String extractJsonObject(String message) {
        int start = message.indexOf('{');
        int end = message.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return message.substring(start, end + 1);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderErrorPayload(ProviderError error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderError(String message) {}

    public static record ToolCallView(String toolName, boolean success, String inputPreview, String outputPreview, boolean inputTruncated, boolean outputTruncated) {}

    private static final int TOOL_PREVIEW_MAX = 2000;

    private static String previewAndTruncate(String s, int max, boolean[] truncatedFlag) {
        if (s == null) {
            truncatedFlag[0] = false;
            return "";
        }
        if (s.length() <= max) {
            truncatedFlag[0] = false;
            return s;
        }
        truncatedFlag[0] = true;
        return s.substring(0, max);
    }

    private static ToolCallView traceToView(ToolCallTrace t) {
        // try to serialize args nicely
        String input;
        try {
            input = SseJson.writerWithDefaultPrettyPrinter().writeValueAsString(t.getArgs());
        } catch (Exception e) {
            input = String.valueOf(t.getArgs());
        }
        String output = t.getTextSummary();
        boolean[] inTr = new boolean[1];
        boolean[] outTr = new boolean[1];
        String inPrev = previewAndTruncate(input, TOOL_PREVIEW_MAX, inTr);
        String outPrev = previewAndTruncate(output, TOOL_PREVIEW_MAX, outTr);
        return new ToolCallView(t.getToolName(), t.isSuccess(), inPrev, outPrev, inTr[0], outTr[0]);
    }

    public record ChatMessage(String role, String text, long ts, boolean pending, String id, List<ToolCallView> toolCalls) {
        public ChatMessage(String role, String text, long ts, boolean pending, String id) {
            this(role, text, ts, pending, id, List.of());
        }
    }

    public record ChangedFile(int id, String path, String diff) {
    }
}
