package codeRecipe.crawling.util;

import codeRecipe.crawling.crawling.util.BarcodeNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BarcodeNormalizer 단위테스트.
 * Spring 컨텍스트/DB 불필요 — 순수 함수 검증(30-we.com 완성 전 crawling 단독 테스트).
 */
class BarcodeNormalizerTest {

    @Test
    @DisplayName("정상 13자리 EAN-13은 그대로 유지된다")
    void keepsValidEan13() {
        assertEquals("8809983890170", BarcodeNormalizer.normalize("8809983890170"));
    }

    @Test
    @DisplayName("앞뒤 공백은 제거된다")
    void trimsEdgeWhitespace() {
        assertEquals("8809983890170", BarcodeNormalizer.normalize("  8809983890170 "));
        assertEquals("8809983890170", BarcodeNormalizer.normalize("\t8809983890170\n"));
    }

    @Test
    @DisplayName("내부 공백도 제거된다")
    void removesInnerWhitespace() {
        assertEquals("8809983890170", BarcodeNormalizer.normalize("880998 3890170"));
        assertEquals("8809983890170", BarcodeNormalizer.normalize("8809983890170".replace("998", "998 ")));
    }

    @Test
    @DisplayName("NBSP(non-breaking space)도 제거된다")
    void removesNbsp() {
        assertEquals("8809983890170", BarcodeNormalizer.normalize("8809983890170 "));
    }

    @Test
    @DisplayName("null은 null을 반환한다")
    void nullReturnsNull() {
        assertNull(BarcodeNormalizer.normalize(null));
    }

    @Test
    @DisplayName("빈 문자열/공백만 있으면 null을 반환한다")
    void blankReturnsNull() {
        assertNull(BarcodeNormalizer.normalize(""));
        assertNull(BarcodeNormalizer.normalize("   "));
        assertNull(BarcodeNormalizer.normalize("\t \n"));
    }

    @Test
    @DisplayName("숫자 외 문자(ISBN 부가기호 등)는 보존한다 — 과공격적 변형 금지")
    void keepsNonNumericPayload() {
        // 하이픈 포함 코드는 그대로 유지(숫자만 남기기 금지)
        assertEquals("979-11-1234", BarcodeNormalizer.normalize(" 979-11-1234 "));
    }

    @Test
    @DisplayName("isValid: 유효/무효 판별")
    void isValidWorks() {
        assertTrue(BarcodeNormalizer.isValid("8809983890170"));
        assertTrue(BarcodeNormalizer.isValid("  8809983890170  "));
        assertFalse(BarcodeNormalizer.isValid(""));
        assertFalse(BarcodeNormalizer.isValid("   "));
        assertFalse(BarcodeNormalizer.isValid(null));
    }
}
