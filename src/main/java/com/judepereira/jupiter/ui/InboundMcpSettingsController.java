package com.judepereira.jupiter.ui;

import com.judepereira.jupiter.agent.mcp.InboundMcpTokenService;
import com.judepereira.jupiter.config.RequestOrigin;
import com.judepereira.jupiter.persistence.AppStateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/ui/settings/inbound-mcp")
public class InboundMcpSettingsController {
    private static final String PANEL = "fragments/inbound-mcp-settings :: panel";
    private final InboundMcpTokenService tokens;
    private final AppStateService appState;

    public InboundMcpSettingsController(InboundMcpTokenService tokens, AppStateService appState) {
        this.tokens = tokens;
        this.appState = appState;
    }

    @GetMapping
    public String panel(Model model) {
        populate(model);
        return PANEL;
    }

    @PostMapping("/create")
    public String create(@RequestParam String name, @RequestParam(defaultValue = "false") boolean allProjects,
            @RequestParam(required = false) Set<Long> projectIds, @RequestParam(required = false) Set<String> tools,
            HttpServletRequest request, HttpServletResponse response, Model model) {
        protect(request);
        noStore(response);
        try {
            String token = tokens.create(name, allProjects, projectIds, tools == null ? Set.of() : tools);
            populate(model);
            model.addAttribute("issuedToken", token);
        } catch (IllegalArgumentException e) {
            populate(model);
            model.addAttribute("settingsError", "Unable to create credential. Check the name, projects, and tools.");
        }
        return PANEL;
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable String id, @RequestParam(defaultValue = "false") boolean allProjects,
            @RequestParam(required = false) Set<Long> projectIds, @RequestParam(required = false) Set<String> tools,
            HttpServletRequest request, Model model) {
        protect(request);
        try {
            tokens.edit(id, allProjects, projectIds, tools == null ? Set.of() : tools);
        } catch (IllegalArgumentException e) {
            model.addAttribute("settingsError", "Unable to update credential. It may no longer be available.");
        }
        model.addAttribute("openCredential", id);
        populate(model);
        return PANEL;
    }

    @PostMapping("/{id}/reveal")
    public String reveal(@PathVariable String id, HttpServletRequest request, HttpServletResponse response,
            Model model) {
        protect(request);
        noStore(response);
        try {
            model.addAttribute("issuedToken", tokens.reveal(id));
        } catch (IllegalArgumentException e) {
            model.addAttribute("settingsError", "Unable to reveal credential. It may no longer be available.");
        }
        model.addAttribute("openCredential", id);
        populate(model);
        return PANEL;
    }

    @PostMapping("/{id}/rotate")
    public String rotate(@PathVariable String id, HttpServletRequest request, HttpServletResponse response,
            Model model) {
        protect(request);
        noStore(response);
        try {
            model.addAttribute("issuedToken", tokens.rotate(id));
        } catch (IllegalArgumentException e) {
            model.addAttribute("settingsError", "Unable to rotate credential. It may no longer be available.");
        }
        model.addAttribute("openCredential", id);
        populate(model);
        return PANEL;
    }

    @PostMapping("/{id}/revoke")
    public String revoke(@PathVariable String id, HttpServletRequest request, Model model) {
        protect(request);
        try {
            tokens.revoke(id);
        } catch (IllegalArgumentException e) {
            model.addAttribute("settingsError", "Unable to revoke credential. It may no longer be available.");
        }
        model.addAttribute("openCredential", id);
        populate(model);
        return PANEL;
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }
    private void populate(Model model) {
        model.addAttribute("inboundCredentials", tokens.list());
        model.addAttribute("inboundProjects", appState.listVisibleProjects());
        model.addAttribute("inboundTools", InboundMcpTokenService.TOOLS.stream().sorted().toList());
    }
    private void protect(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if ("cross-site".equalsIgnoreCase(site)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String origin = request.getHeader("Origin");
        boolean isOrigin = origin != null;
        if (!isOrigin) {
            origin = request.getHeader("Referer");
        }
        if (origin == null || !sameOrigin(origin, request, isOrigin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
    private boolean sameOrigin(String value, HttpServletRequest request, boolean strictOrigin) {
        try {
            URI uri = URI.create(value);
            return RequestOrigin.matches(uri, request) && uri.getRawUserInfo() == null && (!strictOrigin
                    || uri.getRawPath().isEmpty() && uri.getRawQuery() == null && uri.getRawFragment() == null);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
