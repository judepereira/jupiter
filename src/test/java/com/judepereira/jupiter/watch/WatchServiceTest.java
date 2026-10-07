package com.judepereira.jupiter.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.command.CommandCatalogService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class WatchServiceTest {
    private final WatchRepository repository = mock(WatchRepository.class);
    private final CommandCatalogService commands = mock(CommandCatalogService.class);
    private final AgentDefinitionService agents = mock(AgentDefinitionService.class);
    private final WatchService service = new WatchService(repository, commands, new ObjectMapper(), agents);

    @Test
    void actionableJsonRequiresOneBooleanPropertyAndConsumesEntireInput() {
        assertThat(service.parseActionable("{\"actionable\":true}")).isTrue();
        assertThat(service.actionableValue("{\"actionable\":false}")).isFalse();
        assertThat(service.parseActionable("{\"actionable\":true,\"other\":false}")).isFalse();
        assertThat(service.parseActionable("{\"actionable\":true}{\"actionable\":false}")).isFalse();
        assertThat(service.parseActionable("{\"actionable\":true,\"actionable\":false}")).isFalse();
        assertThatThrownBy(() -> service.actionableValue("{\"actionable\":true} trailing"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.actionableValue("null")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createAcceptsPrimaryAgentsAndPromptCommandsButRejectsInvalidDefinitions() {
        var primary = new AgentDefinition("plan", "Plan", "", "", AgentMode.AGENT, List.of(), ThinkingLevel.LOW, "",
                false, false, List.of());
        when(agents.getRequired("plan")).thenReturn(primary);
        when(commands.list()).thenReturn(List.of(new CommandCatalogService.CommandDefinition("act", "Act", "",
                CommandCatalogService.CommandKind.PROMPT, "body", null, null)));
        when(repository.insert(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(7L);
        var definition = new WatchService.Definition(7, 3, "watch", "look", 60, "plan", "plan", "act", 1);
        when(repository.definition(7)).thenReturn(Optional.of(definition));

        assertThat(service.create(3, "watch", "look", 60, "plan", "plan", "act")).isEqualTo(definition);
        assertThatThrownBy(() -> service.create(3, "watch", "look", 59, "plan", "plan", "act"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(3, "watch", "look", 60, "plan", "plan", "missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enablementRequiresTheSessionProject() {
        var definition = new WatchService.Definition(4, 10, "w", "p", 60, "plan", "plan", "act", 1);
        when(repository.definition(4)).thenReturn(Optional.of(definition));
        when(repository.sessionProjectId(8)).thenReturn(Optional.of(11L));
        assertThatThrownBy(() -> service.enable(4, 8)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same project");
        when(repository.sessionProjectId(9)).thenReturn(Optional.of(10L));
        service.enable(4, 9);
        Mockito.verify(repository).enable(4, 9);
    }
}
