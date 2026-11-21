package com.judepereira.jupiter.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.judepereira.jupiter.agent.mcp.InboundMcpTokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboundMcpHttpAuthRegressionE2ETest extends E2ETestSupport {
    private static final String BASIC_PASSWORD = "test-password";

    @Test
    void mcpChallengesStayBearerAndBasicAuthStillProtectsUi(@TempDir Path tempDir) throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        try (RunningApp app = startApp(home, tempDir.resolve("state.db"),
                Map.of("jupiter.http-auth.username", "jupiter", "jupiter.http-auth.password", BASIC_PASSWORD))) {
            InboundMcpTokenService tokens = app.context().getBean(InboundMcpTokenService.class);
            String token = tokens.create("http regression", true, Set.of(), InboundMcpTokenService.TOOLS);
            tokens.bindSession("foreign-session", "different-credential");
            McpHttpTestClient client = new McpHttpTestClient(app.baseUrl(), token);

            HttpResponse<String> invalid = client.postRaw("Bearer invalid-token", null);
            assertThat(invalid.statusCode()).isEqualTo(401);
            assertThat(invalid.headers().firstValue("WWW-Authenticate")).contains("Bearer");

            HttpResponse<String> unknown = client.postRaw("Bearer " + token, "unknown-session");
            assertThat(unknown.statusCode()).isEqualTo(404);
            assertThat(unknown.headers().firstValue("WWW-Authenticate")).isEmpty();

            HttpResponse<String> foreign = client.postRaw("Bearer " + token, "foreign-session");
            assertThat(foreign.statusCode()).isEqualTo(403);
            assertThat(foreign.headers().firstValue("WWW-Authenticate")).isEmpty();

            HttpResponse<String> valid = client.postRaw("Bearer " + token, null);
            assertThat(valid.statusCode()).isNotEqualTo(401);
            assertThat(valid.headers().firstValue("WWW-Authenticate")).isEmpty();

            HttpResponse<String> ui = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(app.baseUrl() + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(ui.statusCode()).isEqualTo(401);
            assertThat(ui.headers().firstValue("WWW-Authenticate")).contains("Basic realm=\"Jupiter\"");
        }
    }
}
