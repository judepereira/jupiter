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
        assertThat(service.list()).isEmpty();
        String first = service.create(" first ", true, Set.of(), Set.of("list_projects"));
        String second = service.create("second", true, Set.of(), Set.of("list_agents"));
        var firstCredential = service.authenticate(first);
        assertThat(service.authenticate(second).tools()).containsExactly("list_agents");

        service.edit(firstCredential.id(), true, Set.of(), Set.of("list_sessions"));
        assertThat(service.authenticate(first).tools()).containsExactly("list_sessions");
        assertThat(service.authenticate(second).tools()).containsExactly("list_agents");

        assertThat(service.list()).extracting(InboundMcpTokenService.CredentialRecord::name).containsExactly("first",
                "second");
        service.revoke(firstCredential.id());
        assertThat(service.authenticate(first)).isNull();
        assertThat(service.authenticate(second)).isNotNull();
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
        String global = service.create("global", true, Set.of(999L), Set.of("list_projects"));
        String globalId = service.authenticate(global).id();
        service.edit(globalId, true, Set.of(999L), Set.of("list_agents"));
        assertThat(service.authenticate(global).tools()).containsExactly("list_agents");
        assertThat(service.list())
                .anyMatch(record -> record.id().equals(globalId) && record.projectIds().contains(999L));
        String scoped = service.create("scoped", true, Set.of(), Set.of("list_projects"));
        String scopedId = service.authenticate(scoped).id();
        assertThatThrownBy(() -> service.edit(scopedId, false, Set.of(999L), Set.of("list_projects")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
