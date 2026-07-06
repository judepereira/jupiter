package com.judepereira.jupiter2.ui;

import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.harness.ToolCallTrace;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Controller
public class UiController {

    private final CodingAgentHarness harness;
    private final com.judepereira.jupiter2.agent.config.AgentProperties agentProperties;

    private final List<ChatMessage> chat = new CopyOnWriteArrayList<>();
    private final List<ChangedFile> changedFiles = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextFileId = new AtomicInteger(1);
    private final AtomicBoolean reviewPanelOpen = new AtomicBoolean(false);
    private volatile ChangedFile selectedFile = null;
    private final java.util.concurrent.Executor agentExecutor;

    // Primary constructor used by Spring (agentTaskExecutor bean should be provided).
    @org.springframework.beans.factory.annotation.Autowired
    public UiController(CodingAgentHarness harness, com.judepereira.jupiter2.agent.config.AgentProperties agentProperties, @org.springframework.beans.factory.annotation.Qualifier("agentTaskExecutor") java.util.concurrent.Executor agentExecutor) {
        this.harness = harness;
        this.agentProperties = agentProperties;
        this.agentExecutor = agentExecutor;
        // seed with a welcome message
        chat.add(new ChatMessage("system", "Welcome to Jupiter", Instant.now().toEpochMilli(), false, java.util.UUID.randomUUID().toString()));
    }

    // Convenience constructor for tests or simple instantiation. Uses a single-thread daemon executor to avoid
    // leaking non-daemon threads when tests finish.
    public UiController(CodingAgentHarness harness, com.judepereira.jupiter2.agent.config.AgentProperties agentProperties) {
        this(harness, agentProperties, java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
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
        if (message != null && !message.isBlank()) {
            String user = message.trim();
            chat.add(new ChatMessage("user", user, Instant.now().toEpochMilli(), false, java.util.UUID.randomUUID().toString()));

            String assistantId = java.util.UUID.randomUUID().toString();
            // add pending assistant message
            chat.add(new ChatMessage("assistant", "Thinking…", Instant.now().toEpochMilli(), true, assistantId));

            // build concise system prompt for coding agent
            String systemPrompt = "You are a concise coding assistant. Use available tools to inspect and modify the workspace when helpful. Prefer tools for file edits and external commands; return a final assistant message when done.";

            // run in background
            Runnable task = () -> {
                AgentTurnResult result = null;
                Exception ex = null;
                try {
                    result = harness.runTurn(new AgentTurnRequest(systemPrompt, user));
                } catch (Exception e) {
                    ex = e;
                }

                // replace pending message
                synchronized (chat) {
                    for (int i = 0; i < chat.size(); i++) {
                        ChatMessage m = chat.get(i);
                        if (m.id.equals(assistantId)) {
                            if (ex != null) {
                                chat.set(i, new ChatMessage("assistant", "Agent execution failed: " + ex.getMessage(), Instant.now().toEpochMilli(), false, assistantId));
                            } else if (result != null && result.getFinalText() != null) {
                                chat.set(i, new ChatMessage("assistant", result.getFinalText(), Instant.now().toEpochMilli(), false, assistantId));
                            } else {
                                String fallback = result == null ? "(no response)" : (result.getFinalText() == null ? "(no final text)" : result.getFinalText());
                                chat.set(i, new ChatMessage("assistant", fallback, Instant.now().toEpochMilli(), false, assistantId));
                            }
                            break;
                        }
                    }
                }

                // process changed files if any
                if (result != null) {
                    var mutatingTools = Set.of("write_file", "apply_patch");
                    List<ToolCallTrace> traces = result.getTraces();
                    Set<String> seen = new HashSet<>();
                    String latestPath = null;
                    List<String> addedPathsInOrder = new ArrayList<>();
                    for (ToolCallTrace t : traces) {
                        if (!mutatingTools.contains(t.getToolName())) continue;
                        if (!t.isSuccess()) continue;
                        Object mp = null;
                        if (t.getMachineSummary() != null) mp = t.getMachineSummary().get("path");
                        String path = null;
                        if (mp instanceof String) path = (String) mp;
                        if (path == null || path.isBlank()) continue;
                        try {
                            java.nio.file.Path workspaceRoot = java.nio.file.Path.of(agentProperties.getWorkspaceRoot());
                            java.nio.file.Path resolved = com.judepereira.jupiter2.agent.tools.impl.FileUtils.resolveWorkspacePath(workspaceRoot, path);
                            java.nio.file.Path rel = com.judepereira.jupiter2.agent.tools.impl.FileUtils.relativizeWorkspacePath(workspaceRoot, resolved);
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
                            if (found != null) selectedFile = found;
                        }
                    }
                }
            };
            if (agentExecutor instanceof java.util.concurrent.ExecutorService es) {
                es.submit(task);
            } else {
                agentExecutor.execute(task);
            }
        }

        model.addAttribute("chatMessages", List.copyOf(chat));
        model.addAttribute("changedFiles", List.copyOf(changedFiles));
        model.addAttribute("reviewPanelOpen", reviewPanelOpen.get());
        model.addAttribute("selectedFile", selectedFile);
        boolean hasPending = chat.stream().anyMatch(m -> m.pending);
        model.addAttribute("hasPending", hasPending);

        // include an OOB update for the review panel when changed files or review open state may have changed
        // We control via a model flag so the review fragment will render with hx-swap-oob when appropriate.
        boolean reviewOob = hasPending == false && reviewPanelOpen.get();
        model.addAttribute("reviewOob", reviewOob);

        // return a composite response fragment that contains the chat fragment and (optionally) an OOB review fragment
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
        java.nio.file.Path workspaceRoot = java.nio.file.Path.of(agentProperties.getWorkspaceRoot());
        java.nio.file.Path resolved;
        try {
            resolved = com.judepereira.jupiter2.agent.tools.impl.FileUtils.resolveWorkspacePath(workspaceRoot, relativePath);
        } catch (Exception e) {
            return "(invalid path: " + relativePath + ")";
        }

        // try git diff -- <path>
        try {
            java.nio.file.Path relForGit = com.judepereira.jupiter2.agent.tools.impl.FileUtils.relativizeWorkspacePath(workspaceRoot, resolved);
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--", relForGit.toString());
            // use configured workspace root as working dir
            pb.directory(new File(workspaceRoot.toAbsolutePath().normalize().toString()));
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                    if (out.length() > MAX_CHARS) break;
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
            if (!Files.exists(resolved)) return "(no diff available)";
            String content = Files.readString(resolved);
            if (content.length() > MAX_CHARS) content = content.substring(0, MAX_CHARS) + "\n... (truncated)";
            return "+++ " + resolved.toString() + "\n" + content;
        } catch (Exception e) {
            return "(error reading file: " + e.getMessage() + ")";
        }
    }

    // simple records for view models
    public static record ChatMessage(String role, String text, long ts, boolean pending, String id) {}

    public static record ChangedFile(int id, String path, String diff) {}

    @GetMapping("/ui/chat/poll")
    public String pollChat(Model model) {
        model.addAttribute("chatMessages", List.copyOf(chat));
        model.addAttribute("changedFiles", List.copyOf(changedFiles));
        model.addAttribute("reviewPanelOpen", reviewPanelOpen.get());
        model.addAttribute("selectedFile", selectedFile);
        boolean hasPending = chat.stream().anyMatch(m -> m.pending);
        model.addAttribute("hasPending", hasPending);

        // When there is no pending work and the review panel is open, instruct the client
        // to update the review panel out-of-band so it can appear/replace without disturbing
        // the chat container swap. Otherwise no OOB update.
        boolean reviewOob = !hasPending && reviewPanelOpen.get();
        model.addAttribute("reviewOob", reviewOob);

        // return the composite response fragment that contains the chat fragment and (optionally) an OOB review fragment
        return "fragments/chat-response :: response";
    }
}
