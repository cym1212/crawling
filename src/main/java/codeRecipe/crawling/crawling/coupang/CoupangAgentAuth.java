package codeRecipe.crawling.crawling.coupang;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** RPA 에이전트(담당자 PC) 전용 토큰 검증. 30-we용 내부 토큰과 분리 운영한다. */
@Component
public class CoupangAgentAuth {

    @Value("${coupang.agent.token:}")
    private String agentToken;

    public boolean isAuthorized(String token) {
        return agentToken != null && !agentToken.isBlank() && token != null
                && MessageDigest.isEqual(
                        agentToken.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8));
    }
}
