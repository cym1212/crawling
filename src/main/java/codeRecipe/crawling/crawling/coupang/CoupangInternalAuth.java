package codeRecipe.crawling.crawling.coupang;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 내부 API / 수동 트리거용 X-Internal-Token 검증. 토큰 미설정 시 항상 거부(fail-closed). */
@Component
public class CoupangInternalAuth {

    @Value("${coupang.internal.token:}")
    private String internalToken;

    public boolean isAuthorized(String token) {
        return internalToken != null && !internalToken.isBlank() && token != null
                && MessageDigest.isEqual(
                        internalToken.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8));
    }
}
