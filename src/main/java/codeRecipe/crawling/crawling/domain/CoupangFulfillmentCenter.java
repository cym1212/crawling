package codeRecipe.crawling.crawling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 쿠팡 물류센터 주소 (의뢰 #8). 로젠 송장 발급 시 수하인 주소로 사용.
 * 운영 데이터로 수동 등록한다 (WING 입고 이력의 FC부터).
 */
@Getter
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "coupang_fulfillment_center",
        uniqueConstraints = @UniqueConstraint(name = "uk_fc_name", columnNames = "fc_name"))
public class CoupangFulfillmentCenter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fc_id")
    private Long fcId;

    @Column(name = "fc_name", nullable = false, length = 50)
    private String fcName;                   // WING 표기 (창원1, 안산3, 고양1 ...)

    @Column(name = "receiver_name", nullable = false, length = 100)
    private String receiverName;             // 로젠 수하인 표기 (예: 쿠팡 창원1센터)

    @Column(name = "address", nullable = false, length = 300)
    private String address;

    @Column(name = "phone", length = 50)
    private String phone;
}
