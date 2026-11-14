package com.judepereira.jupiter.ui;

import com.judepereira.jupiter.agent.mcp.InboundMcpTokenService;
import com.judepereira.jupiter.config.PublicRequestScheme;
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
    public String edit(@PathVariable String id, @RequestParam String name,
            @RequestParam(defaultValue = "false") boolean allProjects,
            @RequestParam(required = false) Set<Long> projectIds, @RequestParam(required = false) Set<String> tools,
            HttpServletRequest request, Model model) {
        protect(request);
        try {
            tokens.edit(id, name, allProjects, projectIds, tools == null ? Set.of() : tools);
        } catch (IllegalArgumentException e) {
            model.addAttribute("settingsError", "Unable to update credential. It may no longer be available.");
        }
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
        populate(model);
        return PANEL;
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }
    private void populate(Model model) {
        model.addAttribute("inboundCredentials", tokens.list());
        model.addAttribute("inboundProjects", appState.loadViewData().projects());
        model.addAttribute("inboundTools", InboundMcpTokenService.TOOLS.stream().sorted().toList());
    }
    private void protect(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if ("cross-site".equalsIgnoreCase(site)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String origin = request.getHeader("Origin");
        if (origin == null) {
            origin = request.getHeader("Referer");
        }
        if (origin == null || !sameOrigin(origin, request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
    private boolean sameOrigin(String value, HttpServletRequest request) {
        try {
            URI uri = URI.create(value);
            int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
            int requestPort = request.getServerPort();
            if (requestPort == 0) {
                requestPort = "https".equalsIgnoreCase(request.getScheme()) ? 443 : 80;
            }
            return uri.getScheme() != null && uri.getHost() != null
                    && uri.getScheme().equalsIgnoreCase(PublicRequestScheme.isHttps(request) ? "https" : "http")
                    && uri.getHost().equalsIgnoreCase(request.getServerName()) && port == requestPort;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
