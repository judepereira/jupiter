package com.judepereira.jupiter;

import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.AppStateView;
import com.judepereira.jupiter.persistence.Persistence.ChangedFileDraft;
import com.judepereira.jupiter.persistence.Persistence.ReviewSource;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.ui.UiController;
import com.judepereira.jupiter.ui.UiController.ChangedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class UiControllerReviewFileTests {

    @Test
    public void reviewFileSelectionReturnsFullPanelAndKeepsSelectionRequestScoped(@TempDir Path workspaceRoot) throws Exception {
        initGitRepo(workspaceRoot);
        Files.writeString(workspaceRoot.resolve("alpha.txt"), "alpha\n");

        AgentProperties props = new AgentProperties();
        UiController controller = TestAppStateSupport.controller(new com.judepereira.jupiter.agent.harness.CodingAgentHarness(null, null, props, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().renderer()), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().discovery(), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().resolver(), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().injector()), props);
        AppStateService appStateService = appStateService(controller);

        controller.addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        AppStateView initialView = appStateService.loadViewData();
        long sessionId = initialView.activeSession().id();
        appStateService.addChangedFilesToSession(sessionId, List.of(new ChangedFileDraft("session.txt", "session diff")));

        controller.openReviewPanel(new ConcurrentModel());

        Model model = new ConcurrentModel();
        String view = controller.loadFile(ReviewSource.GIT, "git:alpha.txt", false, model);

        assertThat(view).isEqualTo("fragments/review :: panel");
        assertThat(model.getAttribute("reviewPanelOpen")).isEqualTo(true);
        assertThat(model.getAttribute("reviewOob")).isEqualTo(false);
        assertThat(model.getAttribute("selectedFile")).isInstanceOf(ChangedFile.class);
        assertThat(((ChangedFile) model.getAttribute("selectedFile")).key()).isEqualTo("git:alpha.txt");

        Model closedModel = new ConcurrentModel();
        String closedView = controller.loadFile(ReviewSource.GIT, "git:alpha.txt", true, closedModel);

        assertThat(closedView).isEqualTo("fragments/review :: panel");
        assertThat(closedModel.getAttribute("reviewOob")).isEqualTo(false);
        assertThat(closedModel.getAttribute("selectedFile")).isNull();

        AppStateView reloaded = appStateService.loadViewData();
        assertThat(reloaded.activeSessionDetail().reviewSource()).isEqualTo(ReviewSource.GIT);
        assertThat(reloaded.activeSessionDetail().selectedFile()).isNull();
    }

    @Test
    public void closingASelectedSessionFileClearsThePersistedSelection(@TempDir Path workspaceRoot) throws Exception {
        initGitRepo(workspaceRoot);
        Files.writeString(workspaceRoot.resolve("alpha.txt"), "alpha\n");

        AgentProperties props = new AgentProperties();
        UiController controller = TestAppStateSupport.controller(new com.judepereira.jupiter.agent.harness.CodingAgentHarness(null, null, props, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().renderer()), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().discovery(), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().resolver(), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().injector()), props);
        AppStateService appStateService = appStateService(controller);

        controller.addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        AppStateView initialView = appStateService.loadViewData();
        long sessionId = initialView.activeSession().id();
        appStateService.addChangedFilesToSession(sessionId, List.of(new ChangedFileDraft("session.txt", "session diff")));
        String sessionKey = appStateService.loadViewData().activeSessionDetail().changedFiles().getFirst().key();

        controller.openReviewPanel(new ConcurrentModel());

        Model openModel = new ConcurrentModel();
        assertThat(controller.loadFile(ReviewSource.SESSION, sessionKey, false, openModel)).isEqualTo("fragments/review :: panel");
        assertThat(openModel.getAttribute("reviewOob")).isEqualTo(false);
        assertThat(openModel.getAttribute("selectedFile")).isInstanceOf(ChangedFile.class);

        Model closedModel = new ConcurrentModel();
        assertThat(controller.loadFile(ReviewSource.SESSION, sessionKey, true, closedModel)).isEqualTo("fragments/review :: panel");
        assertThat(closedModel.getAttribute("reviewOob")).isEqualTo(false);
        assertThat(closedModel.getAttribute("selectedFile")).isNull();

        AppStateView reloaded = appStateService.loadViewData();
        assertThat(reloaded.activeSessionDetail().reviewSource()).isEqualTo(ReviewSource.SESSION);
        assertThat(reloaded.activeSessionDetail().selectedFile()).isNull();
    }

    private static AppStateService appStateService(UiController controller) throws Exception {
        Field field = UiController.class.getDeclaredField("appStateService");
        field.setAccessible(true);
        return (AppStateService) field.get(controller);
    }

    private static void initGitRepo(Path workspaceRoot) throws Exception {
        run(workspaceRoot, "git", "init");
    }

    private static void run(Path workspaceRoot, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(workspaceRoot.toFile()).start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", command));
        }
    }
}
