package com.judepereira.jupiter.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.persistence.AppStateRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InboundMcpTokenService {
    public static final String DEFAULT_TOKEN_NAME = "Default";
    public static final Set<String> TOOLS = Set.of("list_projects", "list_workspaces", "list_sessions", "list_agents",
            "create_workspace", "post_message", "summarize_session");
    private final AppStateRepository repository;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();
    private final InboundMcpSessionRegistry sessions = new InboundMcpSessionRegistry(Clock.systemUTC());

    public void bindSession(String sessionId, String credentialId, String fingerprint) {
        sessions.bind(sessionId, credentialId, fingerprint);
    }

    public SessionStatus sessionStatus(String sessionId, String credentialId, String fingerprint) {
        return sessions.status(sessionId, credentialId, fingerprint);
    }

    public void invalidateSession(String sessionId) {
        sessions.invalidate(sessionId);
    }

    public enum SessionStatus {
        UNKNOWN, MISMATCH, VALID
    }

    public InboundMcpTokenService(AppStateRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public String create(String name, boolean allProjects, Set<Long> projectIds, Set<String> tools) {
        validate(name, allProjects, projectIds, tools);
        String token = generateToken();
        repository.insertInboundMcpCredential(UUID.randomUUID().toString(), name.trim(), token, allProjects,
                json(projectIds == null ? Set.of() : projectIds), json(tools), Instant.now());
        return token;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initializeDefaultAtStartup() {
        initializeDefault();
    }

    @Transactional
    public boolean initializeDefault() {
        String token = generateToken();
        return repository.initializeInboundMcpCredential(UUID.randomUUID().toString(), DEFAULT_TOKEN_NAME, token,
                json(Set.of()), json(TOOLS), Instant.now());
    }

    @Transactional(readOnly = true)
    public List<CredentialRecord> list() {
        return repository.listInboundMcpCredentials().stream().map(row -> new CredentialRecord(row.id(), row.name(),
                row.allProjects(), readLongs(row.projectIdsJson()), readStrings(row.allowedToolsJson()), row.revoked()))
                .toList();
    }

    @Transactional(readOnly = true)
    public String reveal(String id) {
        return repository.listInboundMcpCredentials().stream().filter(row -> row.id().equals(id) && !row.revoked())
                .map(row -> row.token()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Credential not found"));
    }

    @Transactional
    public void edit(String id, boolean allProjects, Set<Long> projectIds, Set<String> tools) {
        CredentialRecord credential = list().stream()
                .filter(candidate -> candidate.id().equals(id) && !candidate.revoked()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Credential not found"));
        validate(credential.name(), allProjects, projectIds, tools);
        repository.updateInboundMcpCredential(id, allProjects, json(projectIds == null ? Set.of() : projectIds),
                json(tools));
    }

    @Transactional
    public String rotate(String id) {
        String token = generateToken();
        repository.replaceInboundMcpCredentialToken(id, token, Instant.now());
        return token;
    }

    @Transactional
    public void revoke(String id) {
        repository.revokeInboundMcpCredential(id, Instant.now());
    }

    private String generateToken() {
        return "jup_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret());
    }
    private void validate(String name, boolean allProjects, Set<Long> ids, Set<String> tools) {
        if (name == null || name.isBlank() || name.trim().length() > 100 || tools == null || !TOOLS.containsAll(tools)
                || (ids != null && ids.stream().anyMatch(id -> id == null || id <= 0))) {
            throw new IllegalArgumentException("Provide a valid name and tools");
        }
        if (!allProjects) {
            requireProjects(ids);
        }
    }
    private void requireProjects(Set<Long> ids) {
        Set<Long> existing = Set.copyOf(repository.listVisibleProjectIds());
        if (ids != null && !existing.containsAll(ids)) {
            throw new IllegalArgumentException("Unknown project id");
        }
    }
    public record CredentialRecord(String id, String name, boolean allProjects, Set<Long> projectIds, Set<String> tools,
            boolean revoked) {
    }

    @Transactional(readOnly = true)
    public Credential authenticate(String bearer) {
        return repository
                .findInboundMcpCredentialByToken(bearer).map(row -> new Credential(row.id(), row.name(),
                        row.allProjects(), readLongs(row.projectIdsJson()), readStrings(row.allowedToolsJson())))
                .orElse(null);
    }

    private byte[] secret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return bytes;
    }
    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    private Set<Long> readLongs(String value) {
        try {
            return Set.copyOf(mapper.readValue(value, new TypeReference<List<Long>>() {
            }));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    private Set<String> readStrings(String value) {
        try {
            return Set.copyOf(mapper.readValue(value, new TypeReference<List<String>>() {
            }));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    public record Credential(String id, String name, boolean allProjects, Set<Long> projectIds, Set<String> tools) {
    }
}
