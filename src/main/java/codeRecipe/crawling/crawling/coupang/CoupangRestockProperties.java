package codeRecipe.crawling.crawling.coupang;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 부족 재고 판단 기준. yml(coupang.restock.*)로 조정 가능하며,
 * 설정 파일이 없어도 기동되도록 코드 기본값을 둔다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "coupang.restock")
public class CoupangRestockProperties {

    /** 재고 소진 예상일이 이 일수 이내면 입고 제안 생성 */
    private int thresholdDays = 7;

    /** 제안 수량은 이 일수만큼의 판매량을 채우는 양 */
    private int targetDays = 21;
}
