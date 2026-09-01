package codeRecipe.crawling.crawling.hottracks;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 교보 내부 API용 X-Internal-Token 검증. 토큰 미설정 시 항상 거부(fail-closed).
 *  공통 토큰 app.internal.token 사용(30-we는 서비스 구분 없이 단일 토큰 사용). */
@Component
public class HottracksInternalAuth {

    // 공통 키 우선, 미설정 시 구(舊) 서비스별 키(hottracks.internal.token) fallback (배포 과도기 안전).
    @Value("${app.internal.token:${hottracks.internal.token:}}")
    private String internalToken;

    public boolean isAuthorized(String token) {
        return internalToken != null && !internalToken.isBlank() && token != null
                && MessageDigest.isEqual(
                        internalToken.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8));
    }
}
