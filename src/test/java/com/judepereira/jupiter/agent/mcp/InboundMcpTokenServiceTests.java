package com.judepereira.jupiter.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InboundMcpTokenServiceTests {
    @Test
    void credentialsAuthenticateIndependentlyAndPolicyEditsAreImmediate() {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        var service = new InboundMcpTokenService(context.repository(), new ObjectMapper());
        String first = service.create(" first ", true, Set.of(), Set.of("list_projects"));
        String second = service.create("second", true, Set.of(), Set.of("list_agents"));
        var firstCredential = service.authenticate(first);
        assertThat(firstCredential.name()).isEqualTo("first");
        assertThat(service.authenticate(second).tools()).containsExactly("list_agents");

        service.edit(firstCredential.id(), "renamed", true, Set.of(), Set.of("list_sessions"));
        assertThat(service.authenticate(first).name()).isEqualTo("renamed");
        assertThat(service.authenticate(first).tools()).containsExactly("list_sessions");
        assertThat(service.authenticate(second).tools()).containsExactly("list_agents");

        String rotated = service.rotate(firstCredential.id());
        assertThat(service.authenticate(first)).isNull();
        assertThat(service.authenticate(rotated).tools()).containsExactly("list_sessions");
        service.revoke(firstCredential.id());
        assertThat(service.authenticate(rotated)).isNull();
        assertThat(service.authenticate(second)).isNotNull();
    }

    @Test
    void initializationDoesNotRecreateCredentialAfterAllAreRevoked() {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        var service = new InboundMcpTokenService(context.repository(), new ObjectMapper());
        assertThat(service.initializeDefault()).isTrue();
        assertThat(service.initializeDefault()).isFalse();
        var initialized = service.list().getFirst();
        assertThat(service.authenticate(service.reveal(initialized.id()))).isNotNull();
        String id = initialized.id();
        service.revoke(id);
        assertThat(service.initializeDefault()).isFalse();
        assertThat(service.list()).anyMatch(record -> record.id().equals(id) && record.revoked());
        assertThat(service.list()).anyMatch(record -> record.revoked());
    }

    @Test
    void rejectsInvalidCredentialsAndAllowsEmptyToolPermissions() {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        var service = new InboundMcpTokenService(context.repository(), new ObjectMapper());
        assertThatThrownBy(() -> service.create(" ", true, Set.of(), Set.of("list_projects")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("valid", false, Set.of(-1L), Set.of("list_projects")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("valid", true, Set.of(), Set.of("unknown")))
                .isInstanceOf(IllegalArgumentException.class);
        String noTools = service.create("valid", true, Set.of(), Set.of());
        assertThat(service.authenticate(noTools).tools()).isEmpty();
    }
}
