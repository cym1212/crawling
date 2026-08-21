package codeRecipe.crawling.crawling.coupang;

import com.fasterxml.jackson.databind.JsonNode;

public final class CoupangJsonUtils {

    private CoupangJsonUtils() {
    }

    /**
     * 텍스트 필드를 null 정규화해서 추출.
     * MissingNode / JSON null / 빈 문자열 / "null" 문자열(NullNode.asText() 함정)을 모두 null로 취급한다.
     * nextToken 페이징 종료 판정에 반드시 이 메서드를 사용할 것.
     */
    public static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        if (text == null || text.isBlank() || "null".equals(text)) {
            return null;
        }
        return text;
    }

    public static Integer intOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asInt();
    }
}
