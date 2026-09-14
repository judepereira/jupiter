package com.judepereira.jupiter.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "anthropic.oauth")
public class AnthropicOAuthProperties {
    private String clientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    private String authorizationUrl = "https://claude.com/cai/oauth/authorize";
    private String tokenUrl = "https://platform.claude.com/v1/oauth/token";
    private String redirectUri = "https://platform.claude.com/oauth/code/callback";
    private String codeChallengeMethod = "S256";
    private boolean code = true;
    private String scopes =
            "user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload";
}
