package codeRecipe.crawling.crawling.util;

/**
 * 서점 크롤링으로 수집한 바코드(상품코드)를 저장하기 전에 정규화한다.
 *
 * <p>배경: 이 바코드는 30-we.com이 자사 상품(TradeProduct)의 바코드와
 * <b>완전일치(=)</b>로 매칭한다. 앞뒤/중간 공백이나 빈 값이 섞이면 매칭이 조용히
 * 실패해 재고 차감이 누락된다(silent failure). 이를 원천에서 예방한다.
 *
 * <p>실측(2026-08, bsight product 214종): 이미 99.5%가 13자리 순수 숫자(EAN-13)이고
 * 오염은 거의 없다. 따라서 정규화는 <b>공백 제거 + 빈 값 판별</b>까지만 하는 방어적
 * 수준으로 한정한다. "숫자만 남기기" 같은 공격적 변형은 ISBN 부가기호나 비도서 코드를
 * 손상시킬 수 있어 적용하지 않는다.
 */
public final class BarcodeNormalizer {

    private BarcodeNormalizer() {
    }

    /**
     * 바코드를 정규화한다.
     * <ul>
     *   <li>null → null</li>
     *   <li>앞뒤 공백 제거(trim) 후 내부의 모든 공백류(스페이스/탭/개행) 제거</li>
     *   <li>결과가 빈 문자열이면 null (저장 대상 아님)</li>
     * </ul>
     *
     * @param raw 크롤링 원본 바코드 문자열
     * @return 정규화된 바코드, 유효한 값이 없으면 null
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        // 모든 공백류(스페이스, 탭, 개행, NBSP 등) 제거
        String cleaned = raw.replaceAll("[\\s\\u00A0]", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * 저장 가능한(유효한) 바코드인지 여부. normalize 결과가 null이 아니면 유효.
     *
     * @param raw 크롤링 원본 바코드 문자열
     * @return 유효하면 true
     */
    public static boolean isValid(String raw) {
        return normalize(raw) != null;
    }
}
