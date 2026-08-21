package codeRecipe.crawling.crawling.coupang;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

/** 쿠팡 API 호출 실패. HTTP 상태코드를 보존해 429 재시도 판단에 사용한다. */
@Getter
public class CoupangApiException extends RuntimeException {

    private final HttpStatusCode status; // HTTP 외 오류(파싱, 인터럽트 등)는 null

    public CoupangApiException(String message, HttpStatusCode status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public boolean isTooManyRequests() {
        return status != null && status.value() == 429;
    }
}
